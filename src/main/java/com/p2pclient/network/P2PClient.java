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

        try {
            logger.info("Connecting to peer at {}:{}", host, port);
            logger.info("This peer is CONTROLLER - will receive screen from remote peer");
            
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            // High-quality (4K) streaming can have long encode gaps; disable read timeout
            socket.setSoTimeout(0); // 0 = infinite
            socket.setKeepAlive(true);
            
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
            logger.error("Failed to connect to peer", e);
            connected.set(false);
            if (messageListener != null) {
                messageListener.onError("Connection failed: " + e.getMessage());
            }
            return false;
        }
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
                    logger.error("Error deserializing message", e);
                } catch (IOException e) {
                    if (connected.get()) {
                        logger.debug("Connection closed or error reading: {}", e.getMessage());
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
            } catch (IOException e) {
                logger.error("Error sending message", e);
                disconnect();
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
