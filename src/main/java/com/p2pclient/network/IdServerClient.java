package com.p2pclient.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2pclient.model.PeerInfo;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.lang.reflect.InvocationTargetException;

public class IdServerClient {
    private static final Logger logger = LoggerFactory.getLogger(IdServerClient.class);
    private static final long WEBSOCKET_CHECK_INTERVAL = 10000; // 10 seconds
    
    private final String baseUrl;
    private final String wsUrl;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;
    private WebSocketStompClient stompClient;
    private StompSession stompSession;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledExecutorService wsMonitorExecutor;
    private ThreadPoolTaskScheduler taskScheduler;
    private String currentPeerId;
    private String currentPassword;
    private String currentIpAddress;
    private int currentPort;
    private boolean shouldMaintainConnection = false;
    private ConnectionListener connectionListener;

    public interface ConnectionListener {
        void onConnectionRequest(String sourcePeerId, String ipAddress, Integer port);
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public IdServerClient() {
        Properties props = loadConfig();
        this.baseUrl = props.getProperty("idserver.url", "http://localhost:8080");
        this.wsUrl = props.getProperty("idserver.ws.url", "ws://localhost:8080/ws");
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClients.createDefault();
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            logger.warn("Could not load config.properties, using defaults", e);
        }
        return props;
    }

    /**
     * Register a new peer with ID Server
     */
    public String registerPeer(String password) throws IOException {
        String url = baseUrl + "/api/peer/register";
        logger.info("Registering peer at {}", url);

        try {
            HttpPost request = new HttpPost(url);
            String jsonBody = "{\"password\":\"" + password + "\"}";
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            return httpClient.execute(request, HttpClientContext.create(), (HttpClientResponseHandler<String>) response -> {
                int statusCode = response.getCode();
                String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "";

                if (statusCode == 200) {
                    RegisterResponse registerResponse = objectMapper.readValue(responseBody, RegisterResponse.class);
                    logger.info("Peer registered successfully: {}", registerResponse.getPeerId());
                    return registerResponse.getPeerId();
                } else {
                    logger.error("Registration failed with status {}: {}", statusCode, responseBody);
                    throw new IOException("Registration failed: " + statusCode);
                }
            });
        } catch (Exception e) {
            logger.error("Error registering peer", e);
            throw new IOException("Failed to register peer", e);
        }
    }

    /**
     * Lookup peer information
     */
    public PeerInfo lookupPeer(String peerId, String password) throws IOException {
        String url = baseUrl + "/api/peer/lookup/" + peerId + "?password=" + password;
        logger.info("Looking up peer: {}", peerId);

        try {
            HttpGet request = new HttpGet(url);

            return httpClient.execute(request, HttpClientContext.create(), (HttpClientResponseHandler<PeerInfo>) response -> {
                int statusCode = response.getCode();
                String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "";

                if (statusCode == 200) {
                    LookupResponse lookupResponse = objectMapper.readValue(responseBody, LookupResponse.class);
                    PeerInfo peerInfo = new PeerInfo(peerId, 
                        lookupResponse.getIpAddress(), 
                        lookupResponse.getPort(), 
                        lookupResponse.getOnline());
                    logger.info("Peer lookup successful: {}", peerInfo);
                    return peerInfo;
                } else if (statusCode == 401) {
                    logger.error("Unauthorized: Invalid password");
                    throw new IOException("Invalid password");
                } else if (statusCode == 404) {
                    logger.error("Peer not found: {}", peerId);
                    throw new IOException("Peer not found");
                } else {
                    logger.error("Lookup failed with status {}: {}", statusCode, responseBody);
                    throw new IOException("Lookup failed: " + statusCode);
                }
            });
        } catch (Exception e) {
            logger.error("Error looking up peer", e);
            throw new IOException("Failed to lookup peer", e);
        }
    }

    /**
     * Connect to ID Server (update peer info)
     */
    public void connectToServer(String peerId, String password, String ipAddress, int port) throws IOException {
        String url = baseUrl + "/api/peer/connect";
        logger.info("Connecting to server: peerId={}, ip={}, port={}", peerId, ipAddress, port);

        try {
            HttpPost request = new HttpPost(url);
            String jsonBody = String.format(
                "{\"peerId\":\"%s\",\"password\":\"%s\",\"ipAddress\":\"%s\",\"port\":%d}",
                peerId, password, ipAddress, port
            );
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            httpClient.execute(request, HttpClientContext.create(), (HttpClientResponseHandler<Void>) response -> {
                int statusCode = response.getCode();
                String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "";

                if (statusCode == 200) {
                    logger.info("Connected to server successfully");
                } else if (statusCode == 401) {
                    logger.error("Unauthorized: Invalid credentials");
                    throw new IOException("Invalid credentials");
                } else {
                    logger.error("Connect failed with status {}: {}", statusCode, responseBody);
                    throw new IOException("Connect failed: " + statusCode);
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("Error connecting to server", e);
            throw new IOException("Failed to connect to server", e);
        }
    }

    /**
     * Connect WebSocket to ID Server with retry logic
     */
    public void connectWebSocket(String peerId, String password, String ipAddress, int port) {
        this.currentPeerId = peerId;
        this.currentPassword = password;
        this.currentIpAddress = ipAddress;
        this.currentPort = port;
        shouldMaintainConnection = true;
        connectWithRetry(peerId, password, ipAddress, port);
        // Start monitoring after successful connection
        if (stompSession != null && stompSession.isConnected()) {
            startWebSocketMonitoring();
        }
    }

    /**
     * Connect WebSocket with retry mechanism
     */
    private void connectWithRetry(String peerId, String password, String ipAddress, int port) {
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries && !Thread.currentThread().isInterrupted()) {
            try {
                // First connect via REST
                connectToServer(peerId, password, ipAddress, port);
                logger.info("REST connection successful (attempt {}/{})", retryCount + 1, maxRetries);
            } catch (IOException e) {
                logger.error("Failed to connect via REST before WebSocket (attempt {}/{}): {}", 
                    retryCount + 1, maxRetries, e.getMessage());
                if (retryCount == maxRetries - 1) {
                    if (connectionListener != null) {
                        connectionListener.onError("Failed to connect: " + e.getMessage());
                    }
                    return;
                }
                retryCount++;
                try {
                    Thread.sleep(2000); // Wait 2 seconds before retry
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            try {
                logger.info("Attempting WebSocket connection (attempt {}/{})", retryCount + 1, maxRetries);
                
                // Setup TaskScheduler for heartbeat (must be done before creating StompClient)
                setupTaskScheduler();
                
                // CRITICAL: Configure WebSocketContainer with larger buffer sizes for STOMP text frames
                // This prevents Tyrus from closing connections when handling large base64-encoded SCREEN payloads
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.setDefaultMaxTextMessageBufferSize(512 * 1024); // 512 KB text buffer
                container.setDefaultMaxBinaryMessageBufferSize(512 * 1024); // 512 KB binary buffer
                logger.info("Configured WebSocketContainer with textBuffer={} bytes, binaryBuffer={} bytes",
                    container.getDefaultMaxTextMessageBufferSize(),
                    container.getDefaultMaxBinaryMessageBufferSize());
                
                StandardWebSocketClient webSocketClient = new StandardWebSocketClient(container);
                stompClient = new WebSocketStompClient(webSocketClient);
                
                // Allow larger STOMP frames (up to 512 KB)
                stompClient.setInboundMessageSizeLimit(512 * 1024);
                try {
                    stompClient.getClass()
                        .getMethod("setOutboundMessageSizeLimit", int.class)
                        .invoke(stompClient, 512 * 1024);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    logger.warn("OutboundMessageSizeLimit not supported on this Spring version, skipping", e);
                } catch (InvocationTargetException e) {
                    logger.warn("Failed to invoke setOutboundMessageSizeLimit: {}", e.getMessage());
                } catch (NoSuchMethodError e) {
                    logger.warn("OutboundMessageSizeLimit method not present on this Spring version");
                }
                
                // CRITICAL: Set TaskScheduler BEFORE setting heartbeat
                stompClient.setTaskScheduler(taskScheduler);
                
                // CRITICAL: Set long heartbeat to keep connection alive (requires TaskScheduler)
                stompClient.setDefaultHeartbeat(new long[]{30000, 30000}); // 30 seconds
                
                // CRITICAL FIX: Use MappingJackson2MessageConverter for JSON payloads
                MappingJackson2MessageConverter messageConverter = new MappingJackson2MessageConverter();
                messageConverter.setObjectMapper(objectMapper);
                stompClient.setMessageConverter(messageConverter);

                StompSessionHandler sessionHandler = new MyStompSessionHandler(peerId, password, ipAddress, port);
                
                CompletableFuture<StompSession> future = stompClient.connectAsync(wsUrl, sessionHandler);
                stompSession = future.get(10, TimeUnit.SECONDS);
                
                logger.info("WebSocket connected successfully. Session ID: {}", 
                    stompSession.getSessionId());
                logger.info("WebSocket connected: {}", isWebSocketConnected());
                
                // Start heartbeat
                startHeartbeat(peerId);
                
                if (connectionListener != null) {
                    connectionListener.onConnected();
                }
                return; // Success - exit retry loop
            } catch (Exception e) {
                retryCount++;
                logger.warn("WebSocket connection failed (attempt {}/{}): {}", 
                    retryCount, maxRetries, e.getMessage());
                
                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(2000); // Wait 2 seconds before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    logger.error("Failed to connect WebSocket after {} attempts", maxRetries);
                    if (connectionListener != null) {
                        connectionListener.onError("WebSocket connection failed: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Check if WebSocket is connected
     */
    public boolean isWebSocketConnected() {
        boolean connected = stompSession != null && stompSession.isConnected();
        logger.debug("WebSocket connection state: {}", connected);
        return connected;
    }

    /**
     * Check if WebSocket is connected (alias for consistency)
     */
    public boolean isConnected() {
        return isWebSocketConnected();
    }

    /**
     * Start sending periodic heartbeat
     */
    private void startHeartbeat(String peerId) {
        Properties props = loadConfig();
        int interval = Integer.parseInt(props.getProperty("heartbeat.interval.seconds", "30"));
        
        logger.info("Starting heartbeat with interval: {} seconds", interval);
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (stompSession != null && stompSession.isConnected()) {
                try {
                    // CRITICAL FIX: Send JSON as string manually
                    String heartbeatJson = String.format("{\"peerId\":\"%s\"}", peerId);
                    stompSession.send("/app/peer.heartbeat", heartbeatJson);
                    logger.debug("Heartbeat sent for peer: {} - WebSocket connected: {}", 
                        peerId, isWebSocketConnected());
                } catch (Exception e) {
                    logger.error("Error sending heartbeat - WebSocket connected: {} - Error: {}", 
                        isWebSocketConnected(), e.getMessage());
                    // Don't disconnect on heartbeat error - connection might still be alive
                }
            } else {
                logger.warn("Cannot send heartbeat - WebSocket not connected. Session: {}, Connected: {}", 
                    stompSession != null, 
                    stompSession != null && stompSession.isConnected());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Setup TaskScheduler for WebSocket heartbeat
     */
    private void setupTaskScheduler() {
        if (taskScheduler == null) {
            taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("websocket-heartbeat-");
            taskScheduler.initialize();
            logger.info("TaskScheduler initialized for WebSocket heartbeat");
        }
    }

    /**
     * Disconnect WebSocket
     */
    public void disconnectWebSocket() {
        shouldMaintainConnection = false;
        stopWebSocketMonitoring();
        
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        
        if (stompSession != null && stompSession.isConnected()) {
            try {
                stompSession.disconnect();
            } catch (Exception e) {
                logger.error("Error disconnecting WebSocket", e);
            }
        }
        
        // Shutdown TaskScheduler
        if (taskScheduler != null) {
            taskScheduler.shutdown();
            taskScheduler = null;
            logger.info("TaskScheduler shut down");
        }
        
        if (connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }

    /**
     * Start WebSocket connection monitoring
     */
    public void startWebSocketMonitoring() {
        if (wsMonitorExecutor != null) {
            wsMonitorExecutor.shutdown();
        }
        
        wsMonitorExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "WS-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        wsMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                boolean isConnected = stompSession != null && stompSession.isConnected();
                
                if (!isConnected && shouldMaintainConnection) {
                    logger.warn("WebSocket disconnected! Attempting reconnection...");
                    reconnectWebSocket();
                } else if (isConnected) {
                    logger.trace("WebSocket monitoring: Connection OK");
                }
            } catch (Exception e) {
                logger.error("WebSocket monitoring error", e);
            }
        }, WEBSOCKET_CHECK_INTERVAL, WEBSOCKET_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        
        logger.info("WebSocket monitoring started (check every {}ms)", WEBSOCKET_CHECK_INTERVAL);
    }

    /**
     * Stop WebSocket connection monitoring
     */
    public void stopWebSocketMonitoring() {
        shouldMaintainConnection = false;
        if (wsMonitorExecutor != null) {
            wsMonitorExecutor.shutdown();
            wsMonitorExecutor = null;
            logger.info("WebSocket monitoring stopped");
        }
    }

    /**
     * Reconnect WebSocket
     */
    public void reconnectWebSocket() {
        if (currentPeerId == null || currentPassword == null || currentIpAddress == null) {
            logger.error("Cannot reconnect: Missing connection parameters");
            return;
        }
        
        try {
            logger.info("Attempting WebSocket reconnection for peer: {}", currentPeerId);
            
            // Disconnect existing session if connected (but keep monitoring flag)
            boolean keepMonitoring = shouldMaintainConnection;
            if (stompSession != null && stompSession.isConnected()) {
                try {
                    // Disconnect without stopping monitoring
                    if (heartbeatExecutor != null) {
                        heartbeatExecutor.shutdown();
                    }
                    if (stompSession != null) {
                        stompSession.disconnect();
                    }
                    Thread.sleep(1000); // Wait 1 second before reconnecting
                } catch (Exception e) {
                    logger.warn("Error disconnecting existing session", e);
                }
            }
            
            // Reset connection state
            stompSession = null;
            stompClient = null;
            
            // Reconnect using stored parameters
            shouldMaintainConnection = keepMonitoring;
            connectWithRetry(currentPeerId, currentPassword, currentIpAddress, currentPort);
            
            logger.info("WebSocket reconnection attempt completed");
        } catch (Exception e) {
            logger.error("WebSocket reconnection failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Reconnect WebSocket with explicit parameters
     */
    public void reconnectWebSocket(String peerId, String password, String ipAddress, int port) {
        this.currentPeerId = peerId;
        this.currentPassword = password;
        this.currentIpAddress = ipAddress;
        this.currentPort = port;
        shouldMaintainConnection = true;
        reconnectWebSocket();
    }


    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void close() throws IOException {
        disconnectWebSocket();
        httpClient.close();
    }

    // Inner class for STOMP session handler
    private class MyStompSessionHandler extends StompSessionHandlerAdapter {
        private final String peerId;
        private final String password;
        private final String ipAddress;
        private final int port;

        public MyStompSessionHandler(String peerId, String password, String ipAddress, int port) {
            this.peerId = peerId;
            this.password = password;
            this.ipAddress = ipAddress;
            this.port = port;
        }

        @Override
        public void handleException(StompSession session, StompCommand command, 
                                   StompHeaders headers, byte[] payload, Throwable exception) {
            logger.error("STOMP exception - WebSocket connected: {}", 
                session.isConnected(), exception);
            // CRITICAL: Don't disconnect on exception - connection might still be alive
            if (connectionListener != null) {
                connectionListener.onError("WebSocket error: " + exception.getMessage());
            }
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            logger.error("STOMP transport error - WebSocket may disconnect. Error: {}", 
                exception.getMessage(), exception);
            // CRITICAL: Don't disconnect WebSocket on transport error - try to reconnect
            // The heartbeat will detect disconnection and we can reconnect if needed
            if (connectionListener != null) {
                connectionListener.onError("Transport error: " + exception.getMessage());
            }
        }

        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            logger.info("STOMP session connected - Session ID: {}", session.getSessionId());
            logger.info("WebSocket connected: {}", session.isConnected());
            
            // Subscribe to connection requests
            session.subscribe("/queue/connection-request", new StompFrameHandler() {
                @Override
                public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        String json = (String) payload;
                        ConnectionRequestNotification notification = objectMapper.readValue(
                            json, ConnectionRequestNotification.class);
                        
                        logger.info("Received connection request from: {} - WebSocket connected: {}", 
                            notification.getSourcePeerId(), session.isConnected());
                        
                        if (connectionListener != null) {
                            connectionListener.onConnectionRequest(
                                notification.getSourcePeerId(),
                                notification.getIpAddress(),
                                notification.getPort()
                            );
                        }
                    } catch (Exception e) {
                        logger.error("Error handling connection request - WebSocket connected: {}", 
                            session.isConnected(), e);
                    }
                }
            });

            // Send connect message
            try {
                // CRITICAL FIX: Build JSON string manually and send as String
                String connectJson = String.format(
                    "{\"peerId\":\"%s\",\"password\":\"%s\",\"ipAddress\":\"%s\",\"port\":%d}",
                    peerId, password, ipAddress, port
                );
                
                logger.info("Sending connect message JSON: {} - WebSocket connected: {}", 
                    connectJson, session.isConnected());
                session.send("/app/peer.connect", connectJson);
                logger.info("Sent peer.connect message via WebSocket");
            } catch (Exception e) {
                logger.error("Error sending peer.connect - WebSocket connected: {}", 
                    session.isConnected(), e);
            }
        }
    }

    // DTO classes for JSON parsing
    private static class RegisterResponse {
        private String peerId;

        public String getPeerId() {
            return peerId;
        }

        public void setPeerId(String peerId) {
            this.peerId = peerId;
        }
    }

    private static class LookupResponse {
        private String ipAddress;
        private Integer port;
        private Boolean online;

        public String getIpAddress() {
            return ipAddress;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public Boolean getOnline() {
            return online;
        }

        public void setOnline(Boolean online) {
            this.online = online;
        }
    }

    private static class ConnectionRequestNotification {
        private String sourcePeerId;
        private String ipAddress;
        private Integer port;

        public String getSourcePeerId() {
            return sourcePeerId;
        }

        public void setSourcePeerId(String sourcePeerId) {
            this.sourcePeerId = sourcePeerId;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }
    }
}

