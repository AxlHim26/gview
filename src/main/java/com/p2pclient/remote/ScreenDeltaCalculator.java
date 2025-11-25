package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Computes dirty-rect regions between consecutive frames to avoid sending full screen
 * updates when only a portion changed. Falls back to full frames periodically
 * to keep the client in sync.
 */
public class ScreenDeltaCalculator {
    private static final Logger logger = LoggerFactory.getLogger(ScreenDeltaCalculator.class);

    private final ScreenCapture screenCapture;
    private final int diffThreshold;
    private final double maxDeltaCoverage;
    private final int padding;
    private final int sampleStride;
    private final int maxDeltaStreak;
    private final long keyFrameIntervalMs;

    private BufferedImage lastFrame;
    private long frameSeq = 0;
    private long lastKeyFrameTs = 0L;
    private int deltaStreak = 0;

    public ScreenDeltaCalculator(ScreenCapture screenCapture) {
        this(screenCapture, 3_000L, 0.40d, 2, 2, 45);
    }

    public ScreenDeltaCalculator(ScreenCapture screenCapture,
                                 long keyFrameIntervalMs,
                                 double maxDeltaCoverage,
                                 int padding,
                                 int sampleStride,
                                 int maxDeltaStreak) {
        this.screenCapture = screenCapture;
        this.keyFrameIntervalMs = keyFrameIntervalMs;
        this.maxDeltaCoverage = maxDeltaCoverage;
        this.padding = Math.max(0, padding);
        this.sampleStride = Math.max(1, sampleStride);
        this.maxDeltaStreak = Math.max(1, maxDeltaStreak);
        this.diffThreshold = 12;
    }

    public DeltaFrame buildFrame(ScreenCaptureResult capture, float quality) {
        if (capture == null || capture.getProcessedImage() == null || capture.getJpegBytes() == null) {
            return null;
        }
        BufferedImage current = capture.getProcessedImage();
        boolean needsKeyFrame = lastFrame == null
            || hasDimensionChange(current, lastFrame)
            || reachedKeyFrameInterval()
            || deltaStreak >= maxDeltaStreak;

        long seq = ++frameSeq;
        if (needsKeyFrame) {
            lastFrame = copyImage(current);
            deltaStreak = 0;
            lastKeyFrameTs = System.currentTimeMillis();
            return DeltaFrame.fullFrame(capture.getJpegBytes(), seq, current, capture);
        }

        DiffBounds diff = findDiffBounds(current, lastFrame);
        lastFrame = copyImage(current);
        if (!diff.changed) {
            return null; // nothing changed - skip send to save bandwidth
        }

        double coverage = diff.coverage(current.getWidth(), current.getHeight());
        if (coverage > maxDeltaCoverage) {
            deltaStreak = 0;
            lastKeyFrameTs = System.currentTimeMillis();
            return DeltaFrame.fullFrame(capture.getJpegBytes(), seq, current, capture);
        }

        BufferedImage regionImage = cropRegion(current, diff);
        long encodeStart = System.nanoTime();
        byte[] regionBytes;
        try {
            regionBytes = screenCapture.encodeJpeg(regionImage, quality);
        } catch (Exception e) {
            logger.warn("Failed to encode delta region, falling back to full frame", e);
            deltaStreak = 0;
            lastKeyFrameTs = System.currentTimeMillis();
            return DeltaFrame.fullFrame(capture.getJpegBytes(), seq, current, capture);
        }
        long encodeMs = (System.nanoTime() - encodeStart) / 1_000_000L;

        deltaStreak++;
        return DeltaFrame.deltaFrame(regionBytes, seq, current, diff, capture, encodeMs);
    }

    private boolean hasDimensionChange(BufferedImage current, BufferedImage previous) {
        return previous.getWidth() != current.getWidth() || previous.getHeight() != current.getHeight();
    }

    private boolean reachedKeyFrameInterval() {
        if (keyFrameIntervalMs <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        return now - lastKeyFrameTs >= keyFrameIntervalMs;
    }

    private DiffBounds findDiffBounds(BufferedImage current, BufferedImage previous) {
        int width = Math.min(current.getWidth(), previous.getWidth());
        int height = Math.min(current.getHeight(), previous.getHeight());
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y += sampleStride) {
            for (int x = 0; x < width; x += sampleStride) {
                int c1 = current.getRGB(x, y);
                int c2 = previous.getRGB(x, y);
                if (colorDiff(c1, c2) > diffThreshold) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            return DiffBounds.noChange();
        }

        // Expand with padding to avoid tearing
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(width - 1, maxX + padding);
        maxY = Math.min(height - 1, maxY + padding);
        return new DiffBounds(minX, minY, (maxX - minX) + 1, (maxY - minY) + 1);
    }

    private int colorDiff(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF;
        int g1 = (rgb1 >> 8) & 0xFF;
        int b1 = rgb1 & 0xFF;

        int r2 = (rgb2 >> 16) & 0xFF;
        int g2 = (rgb2 >> 8) & 0xFF;
        int b2 = rgb2 & 0xFF;

        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
    }

    private BufferedImage cropRegion(BufferedImage source, DiffBounds diff) {
        BufferedImage target = new BufferedImage(diff.width, diff.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = target.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(source,
            0, 0, diff.width, diff.height,
            diff.x, diff.y, diff.x + diff.width, diff.y + diff.height,
            null);
        g2d.dispose();
        return target;
    }

    private BufferedImage copyImage(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = copy.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
        g2d.dispose();
        return copy;
    }

    public static class DeltaFrame {
        private final byte[] payload;
        private final boolean delta;
        private final int regionX;
        private final int regionY;
        private final int regionWidth;
        private final int regionHeight;
        private final long frameSeq;
        private final int fullWidth;
        private final int fullHeight;
        private final long captureTimestamp;
        private final long encodeMillis;

        private DeltaFrame(byte[] payload, boolean delta, int regionX, int regionY, int regionWidth,
                           int regionHeight, long frameSeq, int fullWidth, int fullHeight,
                           long captureTimestamp, long encodeMillis) {
            this.payload = payload;
            this.delta = delta;
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionWidth = regionWidth;
            this.regionHeight = regionHeight;
            this.frameSeq = frameSeq;
            this.fullWidth = fullWidth;
            this.fullHeight = fullHeight;
            this.captureTimestamp = captureTimestamp;
            this.encodeMillis = encodeMillis;
        }

        public static DeltaFrame fullFrame(byte[] payload, long seq, BufferedImage image, ScreenCaptureResult capture) {
            return new DeltaFrame(
                payload,
                false,
                0,
                0,
                image.getWidth(),
                image.getHeight(),
                seq,
                image.getWidth(),
                image.getHeight(),
                capture.getCaptureTimestamp(),
                capture.getEncodeMillis()
            );
        }

        public static DeltaFrame deltaFrame(byte[] payload, long seq, BufferedImage image, DiffBounds diff,
                                            ScreenCaptureResult capture, long encodeMillis) {
            return new DeltaFrame(
                payload,
                true,
                diff.x,
                diff.y,
                diff.width,
                diff.height,
                seq,
                image.getWidth(),
                image.getHeight(),
                capture.getCaptureTimestamp(),
                capture.getEncodeMillis() + encodeMillis
            );
        }

        public byte[] getPayload() {
            return payload;
        }

        public boolean isDelta() {
            return delta;
        }

        public int getRegionX() {
            return regionX;
        }

        public int getRegionY() {
            return regionY;
        }

        public int getRegionWidth() {
            return regionWidth;
        }

        public int getRegionHeight() {
            return regionHeight;
        }

        public long getFrameSeq() {
            return frameSeq;
        }

        public int getFullWidth() {
            return fullWidth;
        }

        public int getFullHeight() {
            return fullHeight;
        }

        public long getCaptureTimestamp() {
            return captureTimestamp;
        }

        public long getEncodeMillis() {
            return encodeMillis;
        }
    }

    private static class DiffBounds {
        final int x;
        final int y;
        final int width;
        final int height;
        final boolean changed;

        DiffBounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.changed = true;
        }

        static DiffBounds noChange() {
            return new DiffBounds(0, 0, 0, 0, false);
        }

        double coverage(int fullWidth, int fullHeight) {
            if (!changed || fullWidth <= 0 || fullHeight <= 0) {
                return 0.0d;
            }
            return (width * height) / (double) (fullWidth * fullHeight);
        }

        private DiffBounds(int x, int y, int width, int height, boolean changed) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.changed = changed;
        }
    }
}
