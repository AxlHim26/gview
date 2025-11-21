package com.p2pclient.network;

import com.p2pclient.model.P2PMessage;
import com.p2pclient.remote.ScreenCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
                Properties props = new Properties();
                try (InputStream is = getClass().getClassLoader()
                        .getResourceAsStream("config.properties")) {
                    if (is != null) {
                        props.load(is);
                    }
                }
                int fps = Integer.parseInt(props.getProperty("screen.capture.fps", "10"));
                long delay = 1000 / fps; // milliseconds per frame
                
                logger.info("Screen capture loop started: {} FPS ({}ms delay)", fps, delay);
                int frameCount = 0;
                
                while (running.get() && handler.isRunning() && !connections.isEmpty()) {
                    try {
                        long startTime = System.currentTimeMillis();
                        byte[] screenData = screenCapture.captureScreenAsBytes();
                        if (screenData != null) {
                            P2PMessage message = new P2PMessage(P2PMessage.TYPE_SCREEN, screenData);
                            handler.sendMessage(message);
                            
                            frameCount++;
                            if (frameCount % 30 == 0) { // Log every 30 frames (3 seconds at 10 FPS)
                                logger.info("Sent {} frames. Last frame: {} bytes to {} peer(s)", 
                                    frameCount, screenData.length, connections.size());
                            } else {
                                logger.debug("Sent screen frame {}: {} bytes", frameCount, screenData.length);
                            }
                        } else {
                            logger.warn("Screen capture returned null data");
                        }
                        
                        // Maintain FPS
                        long elapsed = System.currentTimeMillis() - startTime;
                        long sleepTime = delay - elapsed;
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
                logger.info("Screen capture loop stopped. Total frames sent: {}", frameCount);
            } catch (Exception e) {
                logger.error("Screen capture loop error", e);
            }
        });
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
    }
}
