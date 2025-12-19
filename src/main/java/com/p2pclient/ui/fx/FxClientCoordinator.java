package com.p2pclient.ui.fx;

import com.p2pclient.model.P2PMessage;
import com.p2pclient.model.PeerInfo;
import com.p2pclient.network.IdServerClient;
import com.p2pclient.network.P2PClient;
import com.p2pclient.network.P2PServer;
import com.p2pclient.remote.InputForwarder;
import com.p2pclient.remote.ScreenCapture;
import com.p2pclient.remote.ScreenQualityProfile;
import com.p2pclient.remote.ScreenReceiver;
import com.p2pclient.remote.ScreenStreamer;
import com.p2pclient.util.NetworkUtils;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates control-plane (ID server discovery/signaling) with the data-plane (direct peer-to-peer sockets on the tailnet).
 * All media/input flows over P2P transport only; the server is never on the media path.
 */
public class FxClientCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(FxClientCoordinator.class);

    private final MainWindowController mainWindowController;
    private final SessionsController sessionsController;
    private final RemoteViewController remoteViewController;
    private final SettingsController settingsController;

    private final ScreenReceiver screenReceiver = new ScreenReceiver();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, ScreenStreamer> activeStreams = new ConcurrentHashMap<>();
    private final AtomicLong lastStreamLog = new AtomicLong();

    private IdServerClient idServerClient;
    private P2PServer p2pServer;
    private P2PClient p2pClient;
    private ScreenCapture screenCapture;
    private InputForwarder inputForwarder;
    private ScreenQualityProfile qualityProfile = ScreenQualityProfile.defaultProfile();

    private String activePeerId;
    private String activePeerAddress;

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
        // Control plane only; no relay/forwarding paths.
    }

    public void attachStage(Stage stage) {
        this.stage = stage;
        stage.setOnCloseRequest(evt -> {
            shutdown();
            Platform.exit();
        });
    }

    public boolean toggleFullScreen() {
        if (mainWindowController == null) {
            return false;
        }
        return mainWindowController.toggleRemoteOnlyFullscreen();
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
                boolean p2pSuccess = connectP2PClient(targetPeer.getIpAddress(), targetPeer.getPort());
                isController = true;
                activePeerId = targetPeerId;
                activePeerAddress = targetPeer.getIpAddress() + ":" + targetPeer.getPort();
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
                        sessionsController.appendLog("P2P connection failed");
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
        logger.info("disconnect invoked");
        if (p2pClient != null) {
            p2pClient.disconnect();
            p2pClient = null;
        }
        isController = false;
        activePeerId = null;
        activePeerAddress = null;
        stopAllStreams();
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
        activeStreams.values().forEach(stream -> stream.updateProfile(newProfile));
        if (Objects.equals(oldProfile, newProfile)) {
            return;
        }
        if (screenCapture != null) {
            try {
                screenCapture = new ScreenCapture(newProfile);
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
        stopAllStreams();
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
            isController = false;
            activePeerId = sourcePeerId;
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
                activePeerAddress = peerAddress;
                if (!isController && screenCapture != null) {
                    startScreenStreaming(peerAddress);
                }
                Platform.runLater(() -> sessionsController.appendLog("Peer connected: " + peerAddress));
            }

            @Override
            public void onPeerDisconnected(String peerAddress) {
                stopStream(peerAddress);
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
            logger.warn("P2P server not running; rejecting incoming request");
            Platform.runLater(() -> sessionsController.appendLog("Cannot accept: P2P server not running"));
            return;
        }
        boolean p2pConnected = waitForP2PConnection(10);
        if (p2pConnected) {
            Platform.runLater(() -> sessionsController.setConnectionMode("P2P"));
        } else {
            logger.warn("No P2P connection within timeout; rejecting");
            Platform.runLater(() -> sessionsController.appendLog("No P2P connection established"));
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

    private void startScreenStreaming(String peerAddress) {
        if (screenCapture == null || peerAddress == null || p2pServer == null) {
            return;
        }
        if (activeStreams.containsKey(peerAddress)) {
            return;
        }
        ScreenStreamer streamer = new ScreenStreamer(
            screenCapture,
            qualityProfile,
            () -> p2pServer.hasConnection(peerAddress),
            msg -> p2pServer.sendMessageToPeer(peerAddress, msg),
            executorService,
            peerAddress
        );
        activeStreams.put(peerAddress, streamer);
        streamer.start();
        long now = System.currentTimeMillis();
        long last = lastStreamLog.get();
        if (now - last >= 2000 && lastStreamLog.compareAndSet(last, now)) {
            logger.info("Started screen streaming to {}", peerAddress);
        }
    }

    private void stopStream(String peerAddress) {
        ScreenStreamer streamer = activeStreams.remove(peerAddress);
        if (streamer != null) {
            streamer.stop();
            logger.info("Stopped screen streaming to {}", peerAddress);
        }
    }

    private void stopAllStreams() {
        for (String peer : activeStreams.keySet()) {
            stopStream(peer);
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
}
