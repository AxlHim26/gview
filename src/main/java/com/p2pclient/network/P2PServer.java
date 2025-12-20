package com.p2pclient.network;

import com.p2pclient.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
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
                    
                    // Configure socket before starting handler
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setKeepAlive(true);
                    clientSocket.setReuseAddress(true);
                    clientSocket.setSendBufferSize(512 * 1024);
                    clientSocket.setReceiveBufferSize(512 * 1024);
                    
                    PeerConnectionHandler handler = new PeerConnectionHandler(
                        clientSocket, peerAddress, this);
                    connections.put(peerAddress, handler);
                    
                    executorService.submit(handler);
                    
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
     * Check if a specific peer connection is active
     */
    public boolean hasConnection(String peerAddress) {
        return connections.containsKey(peerAddress);
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
        private final Object outLock = new Object();
        private int screenFramesSent = 0; // Track screen frames for periodic reset
        private long lastResetTime = System.currentTimeMillis();
        private long lastSuccessfulSend = System.currentTimeMillis();
        private int consecutiveSendErrors = 0;

        public PeerConnectionHandler(Socket socket, String peerAddress, P2PServer server) {
            this.socket = socket;
            this.peerAddress = peerAddress;
            this.server = server;
            this.running = new AtomicBoolean(true);
        }

        @Override
        public void run() {
            try {
                // Socket already configured in accept() - just set timeout
                socket.setSoTimeout(0); // No read timeout - controller may pause input temporarily
                logger.info("Handler started for {}: sendBuf={}KB, recvBuf={}KB", 
                    peerAddress, socket.getSendBufferSize() / 1024, socket.getReceiveBufferSize() / 1024);

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
                        logger.error("Error deserializing message from {}, continuing...", peerAddress, e);
                        // Don't break - try to continue reading
                    } catch (java.io.StreamCorruptedException e) {
                        logger.warn("Stream corrupted from {} (possibly from reset()), attempting recovery...", peerAddress, e);
                        // Try to recover - wait and continue reading
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Continue - next message should be clean
                    } catch (java.io.OptionalDataException e) {
                        logger.warn("Optional data in stream from {} (reset token?), skipping...", peerAddress, e);
                        // Skip and continue - this can happen during reset()
                    } catch (java.io.EOFException e) {
                        logger.info("Peer {} closed connection cleanly", peerAddress);
                        break;
                    } catch (java.net.SocketException e) {
                        if (running.get()) {
                            logger.error("Socket error from {}: {}", peerAddress, e.getMessage());
                        }
                        break;
                    } catch (IOException e) {
                        if (running.get()) {
                            logger.error("Connection error from {}: {} - {}", peerAddress, 
                                e.getClass().getSimpleName(), e.getMessage());
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
                synchronized (outLock) {
                    try {
                        out.writeObject(message);
                        out.flush();
                        
                        // CRITICAL: Extremely conservative reset strategy
                        // Only reset for SCREEN frames (large, cause memory leak)
                        // Reset every 500 frames OR every 60 seconds (whichever comes first)
                        // At 30 FPS: 500 frames = 16.7 seconds
                        // At 15 FPS: 500 frames = 33 seconds
                        // This minimizes StreamCorruptedException while preventing leak
                        boolean isScreenFrame = P2PMessage.TYPE_SCREEN.equals(message.getType());
                        long now = System.currentTimeMillis();
                        
                        if (isScreenFrame) {
                            screenFramesSent++;
                            if (screenFramesSent >= 500 || (now - lastResetTime) >= 60000) {
                                out.reset();
                                logger.debug("Stream reset to {} after {} screen frames / {}s", 
                                    peerAddress, screenFramesSent, (now - lastResetTime) / 1000);
                                screenFramesSent = 0;
                                lastResetTime = now;
                            }
                        }
                        // NEVER reset for input events - too frequent, causes corruption
                        
                        lastSuccessfulSend = now;
                        consecutiveSendErrors = 0; // Reset error count on success
                    } catch (IOException e) {
                        consecutiveSendErrors++;
                        logger.error("Error sending {} to {} (error #{}, socket alive={}): {}", 
                            message.getType(), peerAddress, consecutiveSendErrors, 
                            socket.isConnected(), e.getMessage());
                        
                        // Only close after multiple consecutive errors AND socket is dead
                        if (consecutiveSendErrors >= 5 || !socket.isConnected() || socket.isClosed()) {
                            logger.error("Multiple send failures or socket dead, closing connection to {} (errors={})", 
                                peerAddress, consecutiveSendErrors);
                        close();
                        } else {
                            // Log but continue - might be transient
                            logger.warn("Send error #{} to {} but retrying...", consecutiveSendErrors, peerAddress);
                            try {
                                Thread.sleep(10); // Brief pause
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                }
            }
        }

        public void close() {
            running.set(false);
            try {
                if (in != null) in.close();
                // Avoid flushing/IO on close if socket already closed
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
            // DISABLED: Input acks are not critical for mouse/keyboard
            // Sending acks for every input event causes excessive reset() calls
            // which can corrupt the stream and cause disconnects
            // Keep method for compatibility but don't send
                return;
        }
    }
}
