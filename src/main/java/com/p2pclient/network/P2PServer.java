package com.p2pclient.network;

import com.p2pclient.model.P2PMessage;
import com.p2pclient.remote.ScreenCapture;
import com.p2pclient.remote.ScreenCaptureResult;
import com.p2pclient.remote.ScreenDeltaCalculator;
import com.p2pclient.remote.ScreenQualityProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.management.OperatingSystemMXBean;

public class P2PServer {
    private static final Logger logger = LoggerFactory.getLogger(P2PServer.class);
    
    private final int port;
    private ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, PeerConnectionHandler> connections;
    private final AtomicBoolean running;
    private ScreenCapture screenCapture;
    private boolean isController; // true if this peer is controlling the remote screen

    public interface MessageListener {
        void onMessageReceived(P2PMessage message, String peerAddress);
        void onPeerConnected(String peerAddress);
        void onPeerDisconnected(String peerAddress);
    }

    private MessageListener messageListener;

    public P2PServer(int port) {
        this.port = port;
        this.executorService = Executors.newCachedThreadPool();
        this.connections = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.isController = false;
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public void setScreenCapture(ScreenCapture screenCapture) {
        this.screenCapture = screenCapture;
        logger.info("ScreenCapture set for P2PServer");
    }

    public void setController(boolean isController) {
        this.isController = isController;
        logger.info("P2PServer role set to: {}", isController ? "CONTROLLER" : "CONTROLLED");
    }

    /**
     * Start the P2P server
     */
    public void start() throws IOException {
        if (running.get()) {
            logger.warn("P2P Server already running");
            return;
        }

        serverSocket = new ServerSocket(port);
        running.set(true);
        logger.info("P2P Server started on port {}", port);
        logger.info("Waiting for P2P connections...");

        // Start accepting connections in a separate thread
        executorService.submit(() -> {
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String peerAddress = clientSocket.getInetAddress().getHostAddress() + 
                                        ":" + clientSocket.getPort();
                    
                    logger.info("Incoming P2P connection from: {}", peerAddress);
                    logger.info("This peer is CONTROLLED - will send screen to {}", peerAddress);
                    
                    PeerConnectionHandler handler = new PeerConnectionHandler(
                        clientSocket, peerAddress, this);
                    connections.put(peerAddress, handler);
                    
                    executorService.submit(handler);
                    
                    // CRITICAL: When controlled peer receives connection, start screen capture
                    if (!isController && screenCapture != null) {
                        logger.info("Starting screen capture for controlled peer");
                        startScreenCaptureLoop(handler);
                    } else if (!isController && screenCapture == null) {
                        logger.error("ScreenCapture is null! Cannot send screen to peer");
                    }
                    
                    if (messageListener != null) {
                        messageListener.onPeerConnected(peerAddress);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        logger.error("Error accepting connection", e);
                    }
                }
            }
        });
    }

    /**
     * Start screen capture loop to send screens to connected peer
     */
    private void startScreenCaptureLoop(PeerConnectionHandler handler) {
        executorService.submit(() -> {
            try {
                ScreenQualityProfile profile = screenCapture.getProfile();
                ScreenDeltaCalculator deltaCalculator = new ScreenDeltaCalculator(screenCapture);
                logger.info("Screen capture loop started with profile {}", profile);
                Deque<Integer> frameWindow = new ArrayDeque<>();
                long windowSum = 0;
                final int windowSize = 20;
                double maxBytesPerSec = profile.getTargetBitrateBitsPerSec() / 8.0;
                boolean lowResourceMode = false;
                int lowResourceScore = 0;
                int frameCount = 0;
                int deltaFrames = 0;
                int fullFrames = 0;
                
                while (running.get() && handler.isRunning() && !connections.isEmpty()) {
                    try {
                        long startTime = System.currentTimeMillis();
                        long frameIntervalMillis = Math.max(1L, Math.round(1000.0 / Math.max(1, profile.getTargetFps())));

                        float qualityOverride = profile.getJpegQuality();
                        int maxWidth = profile.getMaxWidth();
                        int maxHeight = profile.getMaxHeight();
                        int profileFps = profile.getTargetFps();
                        if (lowResourceMode) {
                            qualityOverride = Math.max(0.20f, profile.getJpegQuality() - 0.10f);
                            maxWidth = (int) Math.round(profile.getMaxWidth() * 0.75);
                            maxHeight = (int) Math.round(profile.getMaxHeight() * 0.75);
                            profileFps = Math.max(5, profileFps - 8);
                        }

                        ScreenCaptureResult capture = screenCapture.captureFrameWithImage(qualityOverride, maxWidth, maxHeight);
                        if (capture == null || capture.getJpegBytes() == null) {
                            logger.warn("Screen capture returned null data");
                            Thread.sleep(frameIntervalMillis);
                            continue;
                        }

                        ScreenDeltaCalculator.DeltaFrame frame = deltaCalculator.buildFrame(capture, qualityOverride);
                        if (frame == null) {
                            // No visual change - still sleep to avoid busy loop
                            Thread.sleep(Math.max(5L, frameIntervalMillis));
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
                        double fpsMax = maxBytesPerSec / Math.max(1.0, avgFrameBytes);
                        double targetFps = Math.min(profileFps, fpsMax);
                        targetFps = Math.max(1.0, targetFps);
                        frameIntervalMillis = Math.max(1L, (long) (1000.0 / targetFps));

                        double estimatedBitrateKbps = avgFrameBytes * targetFps * 8.0 / 1000.0;
                        double cpuLoad = getProcessCpuLoad();
                        long encodeMs = frame.getEncodeMillis();

                        boolean cpuStressed = cpuLoad >= 0 && cpuLoad > 0.85;
                        boolean encodeStressed = encodeMs > frameIntervalMillis * 0.8;
                        boolean bitrateStressed = estimatedBitrateKbps > (profile.getTargetBitrateBitsPerSec() / 1000.0) * 1.15;

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
                        message.setTargetBitrateKbps(profile.getTargetBitrateBitsPerSec() / 1000);
                        message.setEstimatedBitrateKbps((int) Math.round(estimatedBitrateKbps));
                        message.setKeyFrame(!isDelta);
                        message.setTimestamp(frame.getCaptureTimestamp());

                        handler.sendMessage(message);

                        if (frameCount % 30 == 0) {
                            logger.info("Sent {} frames (delta={} full={}). Last payload: {} bytes | avg={} bytes | fps≈{} | bitrate≈{}kbps | lowResource={}",
                                frameCount, deltaFrames, fullFrames, payload.length,
                                Math.round(avgFrameBytes), String.format("%.1f", targetFps),
                                Math.round(estimatedBitrateKbps), lowResourceMode);
                        } else {
                            logger.debug("Sent screen frame {} (delta={}): {} bytes", frameCount, isDelta, payload.length);
                        }

                        long elapsed = System.currentTimeMillis() - startTime;
                        long sleepTime = frameIntervalMillis - elapsed;
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.info("Screen capture loop interrupted");
                        break;
                    } catch (Exception e) {
                        logger.error("Error in screen capture loop", e);
                        // Continue trying
                    }
                }
                logger.info("Screen capture loop stopped. Total frames sent: {} (delta={}, full={})",
                    frameCount, deltaFrames, fullFrames);
            } catch (Exception e) {
                logger.error("Screen capture loop error", e);
            }
        });
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

    /**
     * Broadcast message to all connected peers
     */
    public void broadcastMessage(P2PMessage message) {
        for (PeerConnectionHandler handler : connections.values()) {
            try {
                handler.sendMessage(message);
            } catch (Exception e) {
                logger.error("Error broadcasting message to {}", handler.getPeerAddress(), e);
            }
        }
    }

    /**
     * Send message to specific peer
     */
    public void sendMessageToPeer(String peerAddress, P2PMessage message) {
        PeerConnectionHandler handler = connections.get(peerAddress);
        if (handler != null) {
            handler.sendMessage(message);
        } else {
            logger.warn("Peer not found: {}", peerAddress);
        }
    }

    /**
     * Remove peer connection
     */
    public void removeConnection(String peerAddress) {
        PeerConnectionHandler handler = connections.remove(peerAddress);
        if (handler != null) {
            logger.info("Removed peer connection: {}", peerAddress);
            if (messageListener != null) {
                messageListener.onPeerDisconnected(peerAddress);
            }
        }
    }

    /**
     * Get number of connected peers
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * Get the port number this server is listening on
     */
    public int getPort() {
        return port;
    }

    /**
     * Stop the server
     */
    public void stop() {
        running.set(false);
        
        // Close all connections
        for (PeerConnectionHandler handler : connections.values()) {
            handler.close();
        }
        connections.clear();
        
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error("Error closing server socket", e);
            }
        }
        
        executorService.shutdown();
        logger.info("P2P Server stopped");
    }

    // Inner class for handling individual peer connections
    static class PeerConnectionHandler implements Runnable {
        private final Socket socket;
        private final String peerAddress;
        private final P2PServer server;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private final AtomicBoolean running;

        public PeerConnectionHandler(Socket socket, String peerAddress, P2PServer server) {
            this.socket = socket;
            this.peerAddress = peerAddress;
            this.server = server;
            this.running = new AtomicBoolean(true);
        }

        @Override
        public void run() {
            try {
                // CRITICAL: Initialize streams in correct order
                // ObjectOutputStream must be created first to send header
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush(); // Flush header immediately
                logger.debug("ObjectOutputStream created and flushed for {}", peerAddress);
                
                in = new ObjectInputStream(socket.getInputStream());
                logger.debug("ObjectInputStream created for {}", peerAddress);
                
                logger.info("Started handling peer: {}", peerAddress);
                
                // Read messages from peer (mouse/keyboard events)
                while (running.get() && !socket.isClosed()) {
                    try {
                        P2PMessage message = (P2PMessage) in.readObject();
                        if (message != null) {
                            if (P2PMessage.TYPE_DISCONNECT.equals(message.getType())) {
                                logger.info("Received disconnect from {}", peerAddress);
                                break;
                            }

                            if (P2PMessage.TYPE_MOUSE.equals(message.getType()) ||
                                P2PMessage.TYPE_KEYBOARD.equals(message.getType())) {
                                sendInputAck(message);
                            }
                            
                            if (server.messageListener != null) {
                                server.messageListener.onMessageReceived(message, peerAddress);
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        logger.error("Error deserializing message", e);
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.debug("Connection closed or error reading: {}", e.getMessage());
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                logger.error("Error setting up connection handler for {}", peerAddress, e);
            } finally {
                close();
                server.removeConnection(peerAddress);
            }
        }

        public void sendMessage(P2PMessage message) {
            if (out != null && running.get()) {
                try {
                    out.writeObject(message);
                    out.flush();
                    // CRITICAL: Reset to prevent memory leak when sending same object multiple times
                    out.reset();
                } catch (IOException e) {
                    logger.error("Error sending message to {}", peerAddress, e);
                    close();
                }
            }
        }

        public void close() {
            running.set(false);
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logger.error("Error closing connection", e);
            }
        }

        public String getPeerAddress() {
            return peerAddress;
        }

        public boolean isRunning() {
            return running.get();
        }

        private void sendInputAck(P2PMessage original) {
            if (out == null || original == null || P2PMessage.TYPE_INPUT_ACK.equals(original.getType())) {
                return;
            }
            try {
                P2PMessage ack = new P2PMessage(P2PMessage.TYPE_INPUT_ACK, null);
                ack.setAckRefTimestamp(original.getTimestamp());
                ack.setAckForType(original.getType());
                ack.setFrameSeq(original.getFrameSeq());
                ack.setTimestamp(System.currentTimeMillis());
                out.writeObject(ack);
                out.flush();
                out.reset();
            } catch (IOException e) {
                logger.debug("Failed to send input ack to {}: {}", peerAddress, e.getMessage());
            }
        }
    }
}
