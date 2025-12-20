package com.p2pclient.remote;

import com.p2pclient.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.sun.management.OperatingSystemMXBean;

/**
 * Sends screen frames over an already-established transport.
 * This is transport-agnostic; callers provide a sender callback that writes to the P2P socket.
 * The mesh overlay is treated as a trusted, LAN-like network (e.g., Tailscale 100.x).
 */
public class ScreenStreamer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ScreenStreamer.class);

    private final ScreenCapture screenCapture;
    private final Supplier<Boolean> sessionAlive;
    private final Consumer<P2PMessage> sender;
    private final ExecutorService executor;
    private final String peerLabel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScreenQualityProfile profile;

    public ScreenStreamer(ScreenCapture screenCapture,
                          ScreenQualityProfile profile,
                          Supplier<Boolean> sessionAlive,
                          Consumer<P2PMessage> sender,
                          ExecutorService executor,
                          String peerLabel) {
        this.screenCapture = screenCapture;
        this.profile = profile;
        this.sessionAlive = sessionAlive;
        this.sender = sender;
        this.executor = executor;
        this.peerLabel = peerLabel;
    }

    public void updateProfile(ScreenQualityProfile profile) {
        this.profile = profile;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this);
        }
    }

    public void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        try {
            ScreenDeltaCalculator deltaCalculator = new ScreenDeltaCalculator(screenCapture);
            Deque<Integer> frameWindow = new ArrayDeque<>();
            long windowSum = 0;
            final int windowSize = 20;
            int frameCount = 0;
            int deltaFrames = 0;
            int fullFrames = 0;
            boolean lowResourceMode = false;
            int lowResourceScore = 0;
            long lastKeyframeForceTs = System.currentTimeMillis();

            while (running.get() && sessionAlive.get()) {
                ScreenQualityProfile currentProfile = profile != null ? profile : screenCapture.getProfile();
                long startTime = System.currentTimeMillis();
                long frameIntervalMillis = Math.max(1L, Math.round(1000.0 / Math.max(1, currentProfile.getTargetFps())));

                float qualityOverride = currentProfile.getJpegQuality();
                int maxWidth = currentProfile.getMaxWidth();
                int maxHeight = currentProfile.getMaxHeight();
                int profileFps = currentProfile.getTargetFps();
                if (lowResourceMode) {
                    qualityOverride = Math.max(0.20f, currentProfile.getJpegQuality() - 0.10f);
                    maxWidth = (int) Math.round(currentProfile.getMaxWidth() * 0.75);
                    maxHeight = (int) Math.round(currentProfile.getMaxHeight() * 0.75);
                    profileFps = Math.max(5, profileFps - 8);
                }

                ScreenCaptureResult capture = screenCapture.captureFrameWithImage(qualityOverride, maxWidth, maxHeight);
                if (capture == null || capture.getJpegBytes() == null) {
                    logger.warn("Screen capture returned null data");
                    sleep(frameIntervalMillis);
                    continue;
                }

                // Force a keyframe if too much time passed (avoid drift) even if delta calculator didn't request yet
                if (System.currentTimeMillis() - lastKeyframeForceTs > 3000) {
                    deltaCalculator = new ScreenDeltaCalculator(screenCapture); // reset delta state to force full frame
                    lastKeyframeForceTs = System.currentTimeMillis();
                }

                ScreenDeltaCalculator.DeltaFrame frame = deltaCalculator.buildFrame(capture, qualityOverride);
                if (frame == null) {
                    sleep(Math.max(5L, frameIntervalMillis));
                    continue;
                }

                boolean isDelta = frame.isDelta();
                if (isDelta) {
                    deltaFrames++;
                } else {
                    fullFrames++;
                }
                frameCount++;

                byte[] payload = frame.getPayload();
                frameWindow.addLast(payload.length);
                windowSum += payload.length;
                if (frameWindow.size() > windowSize) {
                    windowSum -= frameWindow.removeFirst();
                }

                double avgFrameBytes = frameWindow.isEmpty()
                    ? payload.length
                    : (double) windowSum / frameWindow.size();
                double maxBytesPerSec = currentProfile.getTargetBitrateBitsPerSec() / 8.0;
                double fpsMax = maxBytesPerSec / Math.max(1.0, avgFrameBytes);
                double targetFps = Math.min(profileFps, fpsMax);
                targetFps = Math.max(1.0, targetFps);
                frameIntervalMillis = Math.max(1L, (long) (1000.0 / targetFps));

                double estimatedBitrateKbps = avgFrameBytes * targetFps * 8.0 / 1000.0;
                double cpuLoad = getProcessCpuLoad();
                long encodeMs = frame.getEncodeMillis();

                boolean cpuStressed = cpuLoad >= 0 && cpuLoad > 0.85;
                boolean encodeStressed = encodeMs > frameIntervalMillis * 0.8;
                boolean bitrateStressed = estimatedBitrateKbps > (currentProfile.getTargetBitrateBitsPerSec() / 1000.0) * 1.15;

                if (cpuStressed || encodeStressed || bitrateStressed) {
                    lowResourceScore = Math.min(lowResourceScore + 1, 6);
                } else if (lowResourceScore > 0) {
                    lowResourceScore--;
                }
                if (!lowResourceMode && lowResourceScore >= 3) {
                    lowResourceMode = true;
                    logger.warn("Entering low-resource mode: cpuLoad={}, encodeMs={}, estBitrate={}kbps",
                        String.format("%.2f", cpuLoad), encodeMs, Math.round(estimatedBitrateKbps));
                } else if (lowResourceMode && lowResourceScore == 0) {
                    lowResourceMode = false;
                    logger.info("Exiting low-resource mode after stable window");
                }

                P2PMessage message = new P2PMessage(P2PMessage.TYPE_SCREEN, payload);
                message.setFrameSeq(frame.getFrameSeq());
                message.setFrameWidth(frame.getFullWidth());
                message.setFrameHeight(frame.getFullHeight());
                message.setDeltaFrame(isDelta);
                message.setRegionX(frame.getRegionX());
                message.setRegionY(frame.getRegionY());
                message.setRegionWidth(frame.getRegionWidth());
                message.setRegionHeight(frame.getRegionHeight());
                message.setEncodeTimeMs(frame.getEncodeMillis());
                message.setSenderCpuLoad(cpuLoad);
                message.setLowResourceMode(lowResourceMode);
                message.setTargetFpsHint((int) Math.round(targetFps));
                message.setTargetBitrateKbps(currentProfile.getTargetBitrateBitsPerSec() / 1000);
                message.setEstimatedBitrateKbps((int) Math.round(estimatedBitrateKbps));
                message.setKeyFrame(!isDelta);
                message.setTimestamp(frame.getCaptureTimestamp());

                long sendStart = System.nanoTime();
                try {
                    sender.accept(message);
                } catch (Exception e) {
                    logger.error("Failed to send screen frame to {}", peerLabel, e);
                    break;
                }
                long sendMs = (System.nanoTime() - sendStart) / 1_000_000L;
                boolean sendStressed = sendMs > frameIntervalMillis * 0.8;
                if (sendStressed) {
                    lowResourceScore = Math.min(lowResourceScore + 1, 6);
                    // Drop next frame to keep input responsive when send is congested
                    sleep(Math.max(2L, frameIntervalMillis / 2));
                    continue;
                }

                if (frameCount % 30 == 0) {
                    logger.info("Sent {} frames to {} (delta={} full={}). Last payload: {} bytes | avg={} bytes | fps≈{} | bitrate≈{}kbps | send={}ms | lowResource={}",
                        frameCount, peerLabel, deltaFrames, fullFrames, payload.length,
                        Math.round(avgFrameBytes), String.format("%.1f", targetFps),
                        Math.round(estimatedBitrateKbps), sendMs, lowResourceMode);
                } else {
                    logger.debug("Sent screen frame {} to {} (delta={}): {} bytes",
                        frameCount, peerLabel, isDelta, payload.length);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                long sleepTime = frameIntervalMillis - elapsed;
                if (sleepTime > 0) {
                    sleep(sleepTime);
                } else {
                    // We're behind schedule; increment stress and skip sleeping to catch up
                    lowResourceScore = Math.min(lowResourceScore + 1, 6);
                }
            }

            logger.info("Screen stream to {} stopped. Frames sent: {} (delta={}, full={})",
                peerLabel, frameCount, deltaFrames, fullFrames);
        } catch (Exception e) {
            logger.error("Screen stream loop error for {}", peerLabel, e);
        } finally {
            running.set(false);
        }
    }

    private double getProcessCpuLoad() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            if (osBean != null) {
                double load = osBean.getProcessCpuLoad();
                return load < 0 ? -1.0d : load;
            }
        } catch (Exception e) {
            logger.debug("Unable to read process CPU load: {}", e.getMessage());
        }
        return -1.0d;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
