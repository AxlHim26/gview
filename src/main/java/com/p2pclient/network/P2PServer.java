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
                    clientSocket.setTcpNoDelay(true);
                    clientSocket.setKeepAlive(true);
                    
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

        public PeerConnectionHandler(Socket socket, String peerAddress, P2PServer server) {
            this.socket = socket;
            this.peerAddress = peerAddress;
            this.server = server;
            this.running = new AtomicBoolean(true);
        }

        @Override
        public void run() {
            try {
                // Keep connection alive for long 4K frames (no read timeout)
                socket.setSoTimeout(0);
                socket.setKeepAlive(true);

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
                synchronized (outLock) {
                    try {
                        out.writeObject(message);
                        out.flush();
                        // CRITICAL: Reset to prevent memory leak when sending frames repeatedly
                        out.reset();
                    } catch (IOException e) {
                        logger.error("Error sending message to {}", peerAddress, e);
                        close();
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
