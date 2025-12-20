package com.p2pclient.network;

import com.p2pclient.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class P2PClient {
    private static final Logger logger = LoggerFactory.getLogger(P2PClient.class);
    
    private final String host;
    private final int port;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private final AtomicBoolean connected;
    private Thread receiverThread;
    private final Object sendLock = new Object();
    private long lastSuccessfulSend = System.currentTimeMillis();
    private int consecutiveSendErrors = 0;

    public interface MessageListener {
        void onMessageReceived(P2PMessage message);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    private MessageListener messageListener;

    public P2PClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.connected = new AtomicBoolean(false);
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    /**
     * Connect to remote peer
     */
    public boolean connect() {
        if (connected.get()) {
            logger.warn("Already connected");
            return true;
        }

        int attempts = 3;
        int connectTimeoutMs = 8000; // allow slower connects over tailnet but fail fast-ish
        for (int i = 1; i <= attempts; i++) {
            try {
                logger.info("Connecting to peer at {}:{} (attempt {}/{})", host, port, i, attempts);
                logger.info("This peer is CONTROLLER - will receive screen from remote peer");

                socket = new Socket();
                // CRITICAL TCP optimizations for stable P2P over overlay network
                socket.setTcpNoDelay(true); // Disable Nagle for low-latency input events
                socket.setKeepAlive(true); // Enable TCP keep-alive
                socket.setSendBufferSize(512 * 1024); // 512KB send buffer (input events burst)
                socket.setReceiveBufferSize(512 * 1024); // 512KB receive buffer (screen frames)
                socket.setReuseAddress(true); // Allow quick reconnection
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                // Disable read timeout - controlled peer may pause encoding temporarily
                socket.setSoTimeout(0); // 0 = infinite, won't timeout on slow frames
                logger.info("Socket configured: sendBuf={}KB, recvBuf={}KB, tcpNoDelay=true, keepAlive=true", 
                    socket.getSendBufferSize() / 1024, socket.getReceiveBufferSize() / 1024);

                // CRITICAL: Initialize streams in correct order
                // ObjectOutputStream must be created first to send header
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush(); // Flush header immediately
                logger.debug("ObjectOutputStream created and flushed");

                in = new ObjectInputStream(socket.getInputStream());
                logger.debug("ObjectInputStream created");

                connected.set(true);
                logger.info("Connected to peer at {}:{}", host, port);

                // Start receiver thread to receive screen data
                receiverThread = new Thread(this::receiveMessages);
                receiverThread.setDaemon(true);
                receiverThread.setName("P2PClient-Receiver");
                receiverThread.start();

                logger.info("Screen receiver thread started - waiting for screen data...");

                if (messageListener != null) {
                    messageListener.onConnected();
                }

                return true;
            } catch (IOException e) {
                logger.error("Failed to connect attempt {}/{} to {}:{} -> {}", i, attempts, host, port, e.getMessage());
                closeQuietly();
                connected.set(false);
                if (i == attempts && messageListener != null) {
                    messageListener.onError("Connection failed: " + e.getMessage());
                }
                if (i < attempts) {
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Receive messages from peer (screen data)
     */
    private void receiveMessages() {
        logger.info("Screen receiver thread running...");
        int frameCount = 0;
        
        try {
            while (connected.get() && !socket.isClosed()) {
                try {
                    P2PMessage message = (P2PMessage) in.readObject();
                    if (message != null) {
                        if (P2PMessage.TYPE_DISCONNECT.equals(message.getType())) {
                            logger.info("Received disconnect message");
                            disconnect();
                            break;
                        }
                        
                        if (P2PMessage.TYPE_SCREEN.equals(message.getType())) {
                            frameCount++;
                            byte[] imageData = message.getData();
                            if (imageData != null) {
                                if (frameCount % 30 == 0) { // Log every 30 frames
                                    logger.info("Received {} frames. Last frame: {} bytes", 
                                        frameCount, imageData.length);
                                } else {
                                    logger.debug("Received screen frame {}: {} bytes", 
                                        frameCount, imageData.length);
                                }
                            } else {
                                logger.warn("Received SCREEN message with null data");
                            }
                        }
                        
                        if (messageListener != null) {
                            messageListener.onMessageReceived(message);
                        }
                    }
                } catch (ClassNotFoundException e) {
                    logger.error("Error deserializing message (class not found), continuing...", e);
                    // Don't break - try to continue reading next message
                } catch (java.io.StreamCorruptedException e) {
                    logger.warn("Stream corrupted (possibly from reset()), attempting recovery...", e);
                    // Try to recover by reading past corruption
                    // This is safe because reset() is rare (every 1000 events / 60s)
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // Continue reading - next message should be clean
                } catch (java.io.OptionalDataException e) {
                    logger.warn("Optional data in stream (reset token?), skipping...", e);
                    // This can happen when reset() is called - skip and continue
                } catch (java.io.EOFException e) {
                    logger.info("Peer closed connection cleanly");
                    break;
                } catch (java.net.SocketException e) {
                    if (connected.get()) {
                        logger.error("Socket error: {}", e.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    if (connected.get()) {
                        logger.error("Connection error while reading: {} - {}", 
                            e.getClass().getSimpleName(), e.getMessage());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Error in receiver thread", e);
        } finally {
            logger.info("Screen receiver thread stopped. Total frames received: {}", frameCount);
            disconnect();
        }
    }

    /**
     * Send message to peer (mouse/keyboard events)
     * CRITICAL: NO reset() EVER for input events
     * - Input events are small (< 1KB) and don't cause significant memory leak
     * - reset() causes StreamCorruptedException on receiver → disconnect
     * - Stability and responsiveness >> memory efficiency
     */
    public void sendMessage(P2PMessage message) {
        if (!connected.get() || out == null) {
            logger.warn("Not connected, cannot send message");
            return;
        }

        synchronized (sendLock) {
            try {
                out.writeObject(message);
                out.flush();
                // NO reset() - stability is paramount for input events
                
                lastSuccessfulSend = System.currentTimeMillis();
                if (consecutiveSendErrors > 0) {
                    logger.info("Send recovered after {} errors", consecutiveSendErrors);
                    consecutiveSendErrors = 0;
                }
            } catch (IOException e) {
                consecutiveSendErrors++;
                long timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulSend;
                
                logger.error("Error sending {} event (error #{}, time since last success={}ms, socket alive={}): {}", 
                    message.getType(), consecutiveSendErrors, timeSinceLastSuccess,
                    socket.isConnected(), e.getMessage());
                
                // Disconnect only if:
                // 1. Socket is confirmed dead, OR
                // 2. 10+ consecutive errors, OR
                // 3. No successful send for 10+ seconds
                if (!socket.isConnected() || socket.isClosed() || 
                    consecutiveSendErrors >= 10 ||
                    timeSinceLastSuccess >= 10000) {
                    logger.error("Connection to peer dead, disconnecting (errors={}, staleTime={}ms)", 
                        consecutiveSendErrors, timeSinceLastSuccess);
                disconnect();
                } else {
                    // Transient error - continue
                    logger.warn("Send error #{} but connection likely alive, retrying...", consecutiveSendErrors);
                }
            }
        }
    }

    /**
     * Disconnect from peer
     */
    public void disconnect() {
        if (!connected.get()) {
            return;
        }

        connected.set(false);
        
        try {
            // Send disconnect message
            if (out != null) {
                P2PMessage disconnectMsg = new P2PMessage(P2PMessage.TYPE_DISCONNECT, null);
                out.writeObject(disconnectMsg);
                out.flush();
            }
        } catch (Exception e) {
            logger.debug("Error sending disconnect message", e);
        }

        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing connection", e);
        }

        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        logger.info("Disconnected from peer");
        
        if (messageListener != null) {
            messageListener.onDisconnected();
        }
    }

    private void closeQuietly() {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {}
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return connected.get() && socket != null && !socket.isClosed();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
