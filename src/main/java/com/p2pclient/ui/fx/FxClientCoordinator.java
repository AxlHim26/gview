package com.p2pclient.ui.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2pclient.model.P2PMessage;
import com.p2pclient.model.PeerInfo;
import com.p2pclient.model.RelayMessage;
import com.p2pclient.network.IdServerClient;
import com.p2pclient.network.P2PClient;
import com.p2pclient.network.P2PServer;
import com.p2pclient.remote.InputForwarder;
import com.p2pclient.remote.ScreenCapture;
import com.p2pclient.remote.ScreenQualityProfile;
import com.p2pclient.remote.ScreenReceiver;
import com.p2pclient.util.NetworkUtils;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Coordinator that wires the existing networking/service logic into the new JavaFX UI.
 * The non-UI classes (network, remote, model) remain unchanged; this class replaces the Swing glue code.
 */
public class FxClientCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(FxClientCoordinator.class);

    private final MainWindowController mainWindowController;
    private final SessionsController sessionsController;
    private final RemoteViewController remoteViewController;
    private final SettingsController settingsController;

    private final ScreenReceiver screenReceiver = new ScreenReceiver();
    private final ObjectMapper relayMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, ScheduledExecutorService> relayMonitors = new ConcurrentHashMap<>();
    private final AtomicLong relayFrameSequence = new AtomicLong();
    private final AtomicLong lastRenderedRelayFrame = new AtomicLong();
    private final BlockingQueue<RelayMessage> relayFrameQueue = new LinkedBlockingDeque<>(1);
    private final LongAdder relayLatencySum = new LongAdder();
    private final LongAdder relayLatencyCount = new LongAdder();
    private final LongAccumulator relayLatencyMax = new LongAccumulator(Long::max, Long.MIN_VALUE);
    private final LongAccumulator relayLatencyMin = new LongAccumulator(Long::min, Long.MAX_VALUE);
    private final AtomicLong lastRelayLatencyLog = new AtomicLong();

    private IdServerClient idServerClient;
    private P2PServer p2pServer;
    private P2PClient p2pClient;
    private ScreenCapture screenCapture;
    private InputForwarder inputForwarder;
    private ScreenQualityProfile qualityProfile = ScreenQualityProfile.defaultProfile();

    private boolean controllerRelayMode = false;
    private volatile boolean controlledRelayMode = false;
    private String currentControllerPeerId;
    private String relayTargetPeerId;
    private RelayScreenSender relayScreenSender;
    private Thread relayScreenThread;
    private Thread relayFrameWorker;

    private String myPeerId;
    private String myPassword;
    private int myP2PPort;
    private String myIpAddress;
    private boolean isController;
    private Stage stage;

    public FxClientCoordinator(MainWindowController mainWindowController,
                               SessionsController sessionsController,
                               RemoteViewController remoteViewController,
                               SettingsController settingsController) {
        this.mainWindowController = mainWindowController;
        this.sessionsController = sessionsController;
        this.remoteViewController = remoteViewController;
        this.settingsController = settingsController;

        screenReceiver.setListener((image, metadata) ->
            Platform.runLater(() -> remoteViewController.updateRemoteScreen(image, metadata)));
    }

    public void bootstrap() {
        try {
            inputForwarder = new InputForwarder();
            remoteViewController.setInputForwarder(inputForwarder);
        } catch (AWTException e) {
            logger.error("Failed to initialize input forwarder", e);
            sessionsController.appendLog("Input forwarding disabled: " + e.getMessage());
        }
        initializeIdServerClient();
        // Relay disabled per request; no relay worker needed.
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        stage.setOnCloseRequest(evt -> {
            shutdown();
            Platform.exit();
        });
    }

    public void registerNewPeer(String password) {
        sessionsController.showInlineStatus("Registering...", false);
        executorService.submit(() -> {
            try {
                String peerId = idServerClient.registerPeer(password);
                myPeerId = peerId;
                myPassword = password;
                setupNetworkBindings();
                startP2PServer();
                idServerClient.connectWebSocket(peerId, password, myIpAddress, myP2PPort);
                Platform.runLater(() -> {
                    sessionsController.setPeerId(peerId);
                    sessionsController.showInlineStatus("Registration successful", false);
                    sessionsController.updateActiveSession(peerId, "Idle", "Ready");
                    mainWindowController.updateStatus("Registered as " + peerId);
                    sessionsController.setBusy(false);
                });
            } catch (Exception e) {
                logger.error("Registration failed", e);
                Platform.runLater(() -> {
                    sessionsController.showInlineStatus("Registration failed: " + e.getMessage(), true);
                    sessionsController.setBusy(false);
                });
            }
        });
    }

    public void connectExistingPeer(String peerId, String password) {
        if (!NetworkUtils.isValidPeerId(peerId)) {
            sessionsController.showInlineStatus("Invalid peer id format", true);
            sessionsController.setBusy(false);
            return;
        }
        sessionsController.showInlineStatus("Connecting...", false);
        executorService.submit(() -> {
            try {
                myPeerId = peerId;
                myPassword = password;
                idServerClient.lookupPeer(peerId, password); // validate credentials
                setupNetworkBindings();
                startP2PServer();
                idServerClient.connectWebSocket(peerId, password, myIpAddress, myP2PPort);
                Platform.runLater(() -> {
                    sessionsController.setPeerId(peerId);
                    sessionsController.showInlineStatus("Connected as existing peer", false);
                    sessionsController.updateActiveSession(peerId, "Idle", "Online");
                    mainWindowController.updateStatus("Connected to ID server");
                    sessionsController.setBusy(false);
                });
            } catch (Exception e) {
                logger.error("Failed to connect existing peer", e);
                Platform.runLater(() -> {
                    sessionsController.showInlineStatus("Connection failed: " + e.getMessage(), true);
                    sessionsController.setBusy(false);
                });
            }
        });
    }

    public void connectToPeer(String targetPeerId, String password) {
        if (!NetworkUtils.isValidPeerId(targetPeerId)) {
            sessionsController.appendLog("Invalid peer id format");
            sessionsController.setConnectEnabled(true);
            return;
        }
        if (myPeerId == null) {
            sessionsController.appendLog("Register or connect your own ID first");
            sessionsController.setConnectEnabled(true);
            return;
        }
        try {
            if (p2pServer == null) {
                setupNetworkBindings();
                startP2PServer();
            }
        } catch (Exception e) {
            sessionsController.appendLog("Failed to prepare P2P server: " + e.getMessage());
            sessionsController.setConnectEnabled(true);
            return;
        }
        sessionsController.appendLog("Looking up peer " + targetPeerId);
        executorService.submit(() -> {
            try {
                PeerInfo targetPeer = idServerClient.lookupPeer(targetPeerId, password);
                if (!targetPeer.getOnline()) {
                    throw new IOException("Peer is offline");
                }
                relayTargetPeerId = targetPeerId;
                boolean p2pSuccess = connectP2PClient(targetPeer.getIpAddress(), targetPeer.getPort());
                isController = true;
                if (p2pServer != null) {
                    p2pServer.setController(true);
                }
                if (p2pSuccess) {
                    Platform.runLater(() -> {
                        remoteViewController.setController(true);
                        sessionsController.setConnectionMode("P2P");
                        sessionsController.setDisconnectEnabled(true);
                        sessionsController.updateActiveSession(targetPeerId, "Controller", "Connected");
                        mainWindowController.updateConnectionMode("P2P");
                    });
                } else {
                    Platform.runLater(() -> {
                        sessionsController.appendLog("P2P failed; relay disabled");
                        sessionsController.setDisconnectEnabled(false);
                        sessionsController.setConnectionMode("Idle");
                        mainWindowController.updateConnectionMode("Idle");
                    });
                }
            } catch (Exception e) {
                logger.error("Connection error", e);
                Platform.runLater(() -> sessionsController.appendLog("Connection failed: " + e.getMessage()));
            } finally {
                sessionsController.setConnectEnabled(true);
            }
        });
    }

    public void disconnect() {
        logger.info("disconnect invoked: controllerRelayMode={} controlledRelayMode={}", controllerRelayMode, controlledRelayMode);
        if (p2pClient != null) {
            p2pClient.disconnect();
            p2pClient = null;
        }
        if (p2pServer != null) {
            p2pServer.setController(false);
        }
        isController = false;
        controllerRelayMode = false;
        controlledRelayMode = false;
        currentControllerPeerId = null;
        relayTargetPeerId = null;
        stopRelayScreenSender();
        stopAllRelayMonitors();
        Platform.runLater(() -> {
            sessionsController.setDisconnectEnabled(false);
            sessionsController.setConnectionMode("Idle");
            sessionsController.updateActiveSession(myPeerId != null ? myPeerId : "-", "Idle", "Disconnected");
            remoteViewController.clearScreen();
            remoteViewController.setController(false);
            mainWindowController.updateConnectionMode("Idle");
            sessionsController.setConnectEnabled(true);
            sessionsController.setBusy(false);
        });
    }

    public void handleMouseEvent(P2PMessage message) {
        if (p2pClient != null && p2pClient.isConnected()) {
            p2pClient.sendMessage(message);
        }
    }

    public void handleKeyboardEvent(P2PMessage message) {
        if (p2pClient != null && p2pClient.isConnected()) {
            p2pClient.sendMessage(message);
        }
    }

    public void onQualityProfileChanged(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        ScreenQualityProfile newProfile = ScreenQualityProfile.fromCliArg(profileName.trim());
        ScreenQualityProfile oldProfile = qualityProfile;
        qualityProfile = newProfile;
        if (relayScreenSender != null) {
            relayScreenSender.updateProfile(newProfile);
        }
        if (Objects.equals(oldProfile, newProfile)) {
            return;
        }
        if (screenCapture != null) {
            try {
                screenCapture = new ScreenCapture(newProfile);
                if (p2pServer != null) {
                    p2pServer.setScreenCapture(screenCapture);
                }
            } catch (AWTException e) {
                logger.error("Failed to recreate screen capture", e);
            }
        }
        Platform.runLater(() -> {
            sessionsController.setSelectedProfile(newProfile);
            sessionsController.appendLog("Quality profile set to " + newProfile.name());
        });
    }

    public void shutdown() {
        disconnect();
        if (p2pServer != null) {
            try {
                p2pServer.stop();
            } catch (Exception e) {
                logger.warn("Error stopping p2pServer", e);
            }
        }
        if (idServerClient != null) {
            try {
                idServerClient.close();
            } catch (IOException e) {
                logger.warn("Error closing idServerClient", e);
            }
        }
        if (relayFrameWorker != null) {
            relayFrameWorker.interrupt();
        }
        relayFrameQueue.clear();
        executorService.shutdownNow();
    }

    private void initializeIdServerClient() {
        idServerClient = new IdServerClient();
        idServerClient.setConnectionListener(new IdServerClient.ConnectionListener() {
            @Override
            public void onConnectionRequest(String sourcePeerId, String ipAddress, Integer port) {
                sessionsController.appendLog("Incoming request from " + sourcePeerId);
                sessionsController.confirmIncomingConnection(sourcePeerId, ipAddress, port)
                    .thenAccept(accepted -> {
                        if (accepted) {
                            handleIncomingApproval(sourcePeerId, ipAddress, port);
                        } else {
                            logger.info("User rejected connection from {}", sourcePeerId);
                        }
                    });
            }

            @Override
            public void onConnected() {
                Platform.runLater(() -> {
                    sessionsController.appendLog("Connected to ID server");
                    mainWindowController.updateStatus("Online");
                });
            }

            @Override
            public void onDisconnected() {
                Platform.runLater(() -> {
                    sessionsController.appendLog("ID server disconnected");
                    mainWindowController.updateStatus("Offline");
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> sessionsController.appendLog("Server error: " + error));
            }
        });
    }

    private void handleIncomingApproval(String sourcePeerId, String ipAddress, Integer port) {
        executorService.submit(() -> {
            try {
                initializeScreenCapture();
            } catch (AWTException e) {
                logger.error("Failed to initialize screen capture", e);
                Platform.runLater(() -> sessionsController.appendLog("Screen capture failed: " + e.getMessage()));
                return;
            }
            if (p2pServer == null) {
                try {
                    startP2PServer();
                } catch (IOException e) {
                    logger.error("Failed to start P2P server for incoming connection", e);
                    Platform.runLater(() -> sessionsController.appendLog("Cannot start server: " + e.getMessage()));
                }
            }
            if (p2pServer != null) {
                p2pServer.setController(false);
            }
            relayTargetPeerId = sourcePeerId;
            executorService.submit(() -> handleIncomingConnectionWithTimeout(sourcePeerId, ipAddress, port));
            Platform.runLater(() -> {
                remoteViewController.setController(false);
                mainWindowController.updateConnectionMode("P2P");
                sessionsController.setConnectionMode("P2P");
            });
        });
    }

    private void setupNetworkBindings() throws IOException, AWTException {
        Properties props = loadConfig();
        int startPort = Integer.parseInt(props.getProperty("p2p.port.range.start", "50000"));
        int endPort = Integer.parseInt(props.getProperty("p2p.port.range.end", "60000"));
        myP2PPort = NetworkUtils.findAvailablePort(startPort, endPort);
        myIpAddress = NetworkUtils.getLocalIPAddress();
        initializeScreenCapture();
    }

    private synchronized void initializeScreenCapture() throws AWTException {
        if (screenCapture == null) {
            screenCapture = new ScreenCapture(qualityProfile);
        }
    }

    private void startP2PServer() throws IOException {
        if (p2pServer != null) {
            p2pServer.stop();
        }
        p2pServer = new P2PServer(myP2PPort);
        if (screenCapture != null) {
            p2pServer.setScreenCapture(screenCapture);
        }
        p2pServer.setMessageListener(new P2PServer.MessageListener() {
            @Override
            public void onMessageReceived(P2PMessage message, String peerAddress) {
                if (P2PMessage.TYPE_SCREEN.equals(message.getType())) {
                    screenReceiver.receiveScreenMessage(message);
                } else if (P2PMessage.TYPE_MOUSE.equals(message.getType())) {
                    if (inputForwarder != null) {
                        if (message.getMouseButton() != 0) {
                            inputForwarder.executeMouseClick(message);
                        } else {
                            inputForwarder.executeMouseMove(message);
                        }
                    }
                } else if (P2PMessage.TYPE_KEYBOARD.equals(message.getType())) {
                    if (inputForwarder != null) {
                        inputForwarder.executeKeyboard(message);
                    }
                }
            }

            @Override
            public void onPeerConnected(String peerAddress) {
                Platform.runLater(() -> sessionsController.appendLog("Peer connected: " + peerAddress));
            }

            @Override
            public void onPeerDisconnected(String peerAddress) {
                Platform.runLater(() -> sessionsController.appendLog("Peer disconnected: " + peerAddress));
            }
        });
        p2pServer.start();
    }

    private boolean connectP2PClient(String host, int port) {
        if (p2pClient != null) {
            p2pClient.disconnect();
        }
        p2pClient = new P2PClient(host, port);
        p2pClient.setMessageListener(new P2PClient.MessageListener() {
            @Override
            public void onMessageReceived(P2PMessage message) {
                if (P2PMessage.TYPE_SCREEN.equals(message.getType())) {
                    screenReceiver.receiveScreenMessage(message);
                } else if (P2PMessage.TYPE_INPUT_ACK.equals(message.getType())) {
                    logger.debug("Input ack received for {}", message.getAckForType());
                }
            }

            @Override
            public void onConnected() {
                Platform.runLater(() -> sessionsController.appendLog("P2P client connected"));
            }

            @Override
            public void onDisconnected() {
                Platform.runLater(() -> {
                    sessionsController.appendLog("P2P client disconnected");
                    remoteViewController.clearScreen();
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> sessionsController.appendLog("P2P error: " + error));
            }
        });
        boolean connected = p2pClient.connect();
        if (!connected) {
            p2pClient = null;
        }
        return connected;
    }

    private void handleIncomingConnectionWithTimeout(String sourcePeerId, String ipAddress, Integer port) {
        if (p2pServer == null || p2pServer.getPort() == 0) {
            logger.warn("P2P server not running; relay disabled so rejecting");
            Platform.runLater(() -> sessionsController.appendLog("Cannot accept: P2P server not running"));
            return;
        }
        boolean p2pConnected = waitForP2PConnection(10);
        if (p2pConnected) {
            Platform.runLater(() -> sessionsController.setConnectionMode("P2P"));
        } else {
            logger.warn("No P2P connection within timeout; relay disabled so rejecting");
            Platform.runLater(() -> sessionsController.appendLog("No P2P connection; relay disabled"));
        }
        if (!idServerClient.isWebSocketConnected()) {
            Platform.runLater(() -> sessionsController.appendLog("WebSocket disconnected during connect"));
        }
    }

    private boolean waitForP2PConnection(int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (p2pServer != null && p2pServer.getConnectionCount() > 0) {
                return true;
            }
            if (!idServerClient.isWebSocketConnected()) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // Relay paths removed per request; stubs retained for compatibility
    private void enableControllerRelayMode(String targetPeerId) { }
    private void enableControlledRelayMode(String targetPeerId) { }

    private void handleRelayMessage(RelayMessage message) {
        if (message == null) {
            return;
        }
        if (message.getTargetPeerId() == null || myPeerId == null || !myPeerId.equals(message.getTargetPeerId())) {
            return;
        }
        switch (message.getDataType()) {
            case "SCREEN" -> enqueueRelayFrame(message);
            case "MOUSE", "KEYBOARD" -> {
                autoEnableControlledRelayModeIfNeeded(message);
                if (isController) {
                    return;
                }
                if (message.getBase64Data() == null || message.getBase64Data().isEmpty()) {
                    return;
                }
                P2PMessage controlMessage = decodeControlMessage(message.getBase64Data(), message.getDataType());
                if (controlMessage == null) {
                    return;
                }
                if (P2PMessage.TYPE_MOUSE.equals(message.getDataType())) {
                    if (inputForwarder != null) {
                        if (controlMessage.getMouseButton() != 0) {
                            inputForwarder.executeMouseClick(controlMessage);
                        } else {
                            inputForwarder.executeMouseMove(controlMessage);
                        }
                    }
                } else if (P2PMessage.TYPE_KEYBOARD.equals(message.getDataType())) {
                    if (inputForwarder != null) {
                        inputForwarder.executeKeyboard(controlMessage);
                    }
                }
            }
            default -> logger.warn("Unknown relay message type: {}", message.getDataType());
        }
    }

    private void processRelayFrame(RelayMessage message) {
        if (message == null || message.getBase64Data() == null) {
            return;
        }
        long seq = message.getFrameSeq();
        long lastSeq = lastRenderedRelayFrame.get();
        if (seq > 0 && seq <= lastSeq) {
            return;
        }
        try {
            byte[] imageBytes = Base64.getDecoder().decode(message.getBase64Data());
            BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return;
            }
            if (seq > 0) {
                lastRenderedRelayFrame.set(seq);
            }
            long latency = message.getSendTimestampMs() > 0 ? System.currentTimeMillis() - message.getSendTimestampMs() : -1L;
            recordRelayLatency(latency);
            Platform.runLater(() -> remoteViewController.updateRemoteScreen(image, null));
        } catch (Exception e) {
            logger.error("Failed to process relay frame", e);
        }
    }

    private void enqueueRelayFrame(RelayMessage message) {
        if (message == null || message.getBase64Data() == null || message.getBase64Data().isEmpty()) {
            return;
        }
        if (!relayFrameQueue.offer(message)) {
            relayFrameQueue.poll();
            relayFrameQueue.offer(message);
        }
    }

    private void startRelayFrameWorker() {
        relayFrameWorker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    RelayMessage frame = relayFrameQueue.take();
                    processRelayFrame(frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Relay frame worker error", e);
                }
            }
        }, "RelayFrameWorker");
        relayFrameWorker.setDaemon(true);
        relayFrameWorker.start();
    }

    private void autoEnableControlledRelayModeIfNeeded(RelayMessage message) {
        // Relay disabled; no auto-enable
    }

    private void sendRelayControlMessage(P2PMessage message, String dataType) {
        if (relayTargetPeerId == null) {
            return;
        }
        try {
            message.setType(dataType);
            String json = relayMapper.writeValueAsString(message);
            String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            RelayMessage relayMessage = new RelayMessage(myPeerId, relayTargetPeerId, dataType, base64, System.currentTimeMillis());
            idServerClient.sendRelayData(relayMessage);
        } catch (Exception e) {
            logger.error("Failed to send relay control message", e);
        }
    }

    private void startRelayScreenSender(String targetPeerId) {
        stopRelayScreenSender();
        if (targetPeerId == null || screenCapture == null) {
            return;
        }
        relayScreenSender = new RelayScreenSender(targetPeerId);
        relayScreenThread = new Thread(relayScreenSender, "RelayScreenSender");
        relayScreenThread.setDaemon(true);
        relayScreenThread.start();
        Platform.runLater(() -> sessionsController.appendLog("Relay screen sender started for " + targetPeerId));
    }

    private void stopRelayScreenSender() {
        if (relayScreenSender != null) {
            relayScreenSender.stop();
            relayScreenSender = null;
        }
        if (relayScreenThread != null) {
            relayScreenThread.interrupt();
            relayScreenThread = null;
        }
    }

    private void keepConnectionAliveForRelay(String targetPeerId) {
        ScheduledExecutorService existing = relayMonitors.remove(targetPeerId);
        if (existing != null) {
            existing.shutdown();
        }
        ScheduledExecutorService monitor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Relay-Monitor-" + targetPeerId);
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(() -> {
            try {
                if (!idServerClient.isConnected()) {
                    idServerClient.reconnectWebSocket(myPeerId, myPassword, myIpAddress, myP2PPort);
                }
            } catch (Exception e) {
                logger.error("Relay keep-alive error", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        relayMonitors.put(targetPeerId, monitor);
    }

    private void stopAllRelayMonitors() {
        for (ScheduledExecutorService monitor : relayMonitors.values()) {
            if (monitor != null) {
                monitor.shutdown();
            }
        }
        relayMonitors.clear();
    }

    private P2PMessage decodeControlMessage(String base64Data, String dataType) {
        if (base64Data == null) {
            return null;
        }
        try {
            byte[] jsonBytes = Base64.getDecoder().decode(base64Data);
            P2PMessage message = relayMapper.readValue(new String(jsonBytes, StandardCharsets.UTF_8), P2PMessage.class);
            message.setType(dataType);
            return message;
        } catch (Exception e) {
            logger.error("Failed to decode relay payload", e);
            return null;
        }
    }

    private void recordRelayLatency(long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        relayLatencySum.add(latencyMs);
        relayLatencyCount.increment();
        relayLatencyMax.accumulate(latencyMs);
        relayLatencyMin.accumulate(latencyMs);
        long now = System.currentTimeMillis();
        long lastLog = lastRelayLatencyLog.get();
        if (now - lastLog >= 2000 && lastRelayLatencyLog.compareAndSet(lastLog, now)) {
            long count = relayLatencyCount.sumThenReset();
            long total = relayLatencySum.sumThenReset();
            long max = relayLatencyMax.getThenReset();
            long min = relayLatencyMin.getThenReset();
            if (count > 0) {
                long avg = total / count;
                logger.info("Relay latency: avg={}ms, min={}ms, max={}ms over {} frames", avg, min, max, count);
            }
        }
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            logger.warn("Could not load config.properties", e);
        }
        return props;
    }

    private class RelayScreenSender implements Runnable {
        private final String targetPeerId;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile ScreenQualityProfile currentProfile;

        RelayScreenSender(String targetPeerId) {
            this.targetPeerId = targetPeerId;
            this.currentProfile = screenCapture != null ? screenCapture.getProfile() : qualityProfile;
        }

        void stop() {
            running.set(false);
        }

        void updateProfile(ScreenQualityProfile newProfile) {
            this.currentProfile = newProfile;
        }

        @Override
        public void run() {
            if (screenCapture == null) {
                return;
            }
            final AdaptiveAverager frameSizeAvg = new AdaptiveAverager(20);
            final AdaptiveAverager captureAvg = new AdaptiveAverager(20);
            final AdaptiveAverager sendAvg = new AdaptiveAverager(20);
            final AdaptiveAverager totalAvg = new AdaptiveAverager(20);
            long fpsWindowStart = System.currentTimeMillis();
            int framesInWindow = 0;
            long lastStatsLog = System.currentTimeMillis();
            while (running.get()) {
                ScreenQualityProfile profile = currentProfile;
                double maxBytesPerSec = profile.getTargetBitrateBitsPerSec() / 8.0;
                long loopStart = System.currentTimeMillis();
                long frameSeq = relayFrameSequence.incrementAndGet();
                byte[] imageBytes = screenCapture.captureScreenForRelay(profile);
                long afterCapture = System.currentTimeMillis();
                long captureMs = afterCapture - loopStart;
                if (imageBytes == null || imageBytes.length == 0) {
                    sleepQuietly(20);
                    continue;
                }
                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                RelayMessage relayMessage = new RelayMessage(
                    myPeerId,
                    targetPeerId,
                    "SCREEN",
                    base64,
                    System.currentTimeMillis()
                );
                relayMessage.setFrameSeq(frameSeq);
                relayMessage.setSendTimestampMs(System.currentTimeMillis());
                long sendStart = System.currentTimeMillis();
                idServerClient.sendRelayData(relayMessage);
                long afterSend = System.currentTimeMillis();
                long sendMs = afterSend - sendStart;
                long totalMs = afterSend - loopStart;
                captureAvg.addSample(captureMs);
                sendAvg.addSample(sendMs);
                totalAvg.addSample(totalMs);
                frameSizeAvg.addSample(imageBytes.length);
                framesInWindow++;
                long now = System.currentTimeMillis();
                if (now - lastStatsLog >= 2000) {
                    double fps = framesInWindow * 1000.0 / Math.max(1, now - fpsWindowStart);
                    double avgBytes = frameSizeAvg.getAverage();
                    double adaptiveFps = Math.min(profile.getTargetFps(), maxBytesPerSec / Math.max(1.0, avgBytes));
                    adaptiveFps = Math.max(1.0, adaptiveFps);
                    double estMbps = (avgBytes * 8.0 * fps) / 1_000_000.0;
                    double targetMbps = profile.getTargetBitrateBitsPerSec() / 1_000_000.0;
                    logger.info("Relay stats: fps={} adaptiveFps={} captureAvg={}ms sendAvg={}ms totalAvg={}ms avgFrameKB={} targetMbps={} estMbps={}",
                        String.format("%.1f", fps), String.format("%.1f", adaptiveFps),
                        String.format("%.1f", captureAvg.getAverage()), String.format("%.1f", sendAvg.getAverage()),
                        String.format("%.1f", totalAvg.getAverage()), String.format("%.1f", avgBytes / 1024.0),
                        String.format("%.2f", targetMbps), String.format("%.2f", estMbps));
                    lastStatsLog = now;
                    fpsWindowStart = now;
                    framesInWindow = 0;
                }
                double avgBytes = frameSizeAvg.getAverage();
                if (avgBytes <= 0) {
                    avgBytes = imageBytes.length;
                }
                double adaptiveFps = Math.min(profile.getTargetFps(), maxBytesPerSec / Math.max(1.0, avgBytes));
                adaptiveFps = Math.max(2.0, adaptiveFps);
                long dynamicIntervalMs = Math.max(1L, (long) (1000.0 / adaptiveFps));
                long sleepMs = dynamicIntervalMs - totalMs;
                if (sleepMs > 0) {
                    sleepQuietly(sleepMs);
                }
            }
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(Math.max(1, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class AdaptiveAverager {
        private final int window;
        private final long[] samples;
        private int index = 0;
        private int count = 0;
        private long sum = 0;

        AdaptiveAverager(int window) {
            this.window = Math.max(1, window);
            this.samples = new long[this.window];
        }

        synchronized void addSample(long value) {
            if (count < window) {
                samples[index] = value;
                sum += value;
                count++;
            } else {
                sum -= samples[index];
                samples[index] = value;
                sum += value;
            }
            index = (index + 1) % window;
        }

        synchronized double getAverage() {
            return count == 0 ? 0d : (double) sum / count;
        }
    }
}
