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
import com.p2pclient.util.ConfigLoader;
import com.p2pclient.util.NetworkUtils;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

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

    private static final int FILE_CHUNK_SIZE = 64 * 1024;

    private final ScreenReceiver screenReceiver = new ScreenReceiver();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, ScreenStreamer> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileReceiveSession> incomingFileTransfers = new ConcurrentHashMap<>();
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
                        mainWindowController.showControlView();
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
        closeAllIncomingFileTransfers();
        Platform.runLater(() -> {
            sessionsController.setDisconnectEnabled(false);
            sessionsController.setConnectionMode("Idle");
            sessionsController.updateActiveSession(myPeerId != null ? myPeerId : "-", "Idle", "Disconnected");
            remoteViewController.clearScreen();
            remoteViewController.setController(false);
            mainWindowController.updateConnectionMode("Idle");
            sessionsController.setConnectEnabled(true);
            sessionsController.setBusy(false);
            mainWindowController.showRegistrationView();
            mainWindowController.setIncomingControllerInfo(null, null, null);
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

    public void sendChatMessage(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        P2PMessage chatMessage = new P2PMessage();
        chatMessage.setType(P2PMessage.TYPE_CHAT);
        chatMessage.setChatText(text);
        if (isController) {
            if (p2pClient != null && p2pClient.isConnected()) {
                p2pClient.sendMessage(chatMessage);
            } else {
                sessionsController.appendLog("Không gửi chat được: chưa kết nối");
            }
        } else {
            if (p2pServer != null && activePeerAddress != null && p2pServer.hasConnection(activePeerAddress)) {
                p2pServer.sendMessageToPeer(activePeerAddress, chatMessage);
            } else {
                sessionsController.appendLog("Không gửi chat được: không có controller nào kết nối");
            }
        }
    }

    public void sendFile(File file) {
        sendFileInternal(file, false);
    }

    public void sendFolder(File folder) {
        sendFileInternal(folder, true);
    }

    private void sendFileInternal(File file, boolean treatAsFolder) {
        if (file == null) {
            sessionsController.appendLog("Không có file/folder để gửi");
            return;
        }
        if (!file.exists()) {
            sessionsController.appendLog("Không tìm thấy: " + file.getAbsolutePath());
            return;
        }

        boolean isDirectory = file.isDirectory();
        File payload = file;
        if (treatAsFolder && file.isDirectory()) {
            try {
                payload = zipDirectory(file);
                isDirectory = true;
            } catch (IOException e) {
                sessionsController.appendLog("Nén folder thất bại: " + e.getMessage());
                logger.error("Failed to zip folder {}", file.getAbsolutePath(), e);
                return;
            }
        }

        final File toSend = payload;
        final boolean directoryFlag = isDirectory;
        final long size = Math.max(0, toSend.length());
        final int totalChunks = (int) Math.max(1, Math.ceil(size / (double) FILE_CHUNK_SIZE));
        final String transferId = UUID.randomUUID().toString();
        final File originalFile = file;
        final File tempPayload = payload;
        sessionsController.appendLog("Đang gửi " + (directoryFlag ? "folder" : "file") + ": " + toSend.getName() + " (" + size + " bytes)");

        executorService.submit(() -> {
            if (!isActiveConnection()) {
                sessionsController.appendLog("Không gửi được: chưa kết nối P2P");
                return;
            }
            try (FileInputStream fis = new FileInputStream(toSend)) {
                byte[] buffer = new byte[FILE_CHUNK_SIZE];
                int chunkIndex = 0;
                int read;
                while ((read = fis.read(buffer)) != -1 || (size == 0 && chunkIndex == 0)) {
                    if (read == -1) {
                        read = 0; // zero-byte file case
                    }
                    P2PMessage msg = new P2PMessage();
                    msg.setType(P2PMessage.TYPE_FILE_CHUNK);
                    msg.setFileTransferId(transferId);
                    msg.setFileName(toSend.getName());
                    msg.setFileSize(size);
                    msg.setFileChunkIndex(chunkIndex);
                    msg.setFileChunkTotal(totalChunks);
                    msg.setFileIsDirectory(directoryFlag);
                    msg.setFileTransferComplete(chunkIndex >= totalChunks - 1);
                    if (read > 0) {
                        msg.setData(Arrays.copyOf(buffer, read));
                    } else {
                        msg.setData(new byte[0]);
                    }
                    boolean sent = sendMessageToActivePeer(msg);
                    if (!sent) {
                        sessionsController.appendLog("Dừng gửi: mất kết nối P2P");
                        break;
                    }
                    chunkIndex++;
                    if (chunkIndex >= totalChunks) {
                        break;
                    }
                }
                sessionsController.appendLog("Đã gửi xong: " + toSend.getName());
            } catch (IOException e) {
                sessionsController.appendLog("Lỗi gửi file: " + e.getMessage());
                logger.error("Error sending file {}", toSend.getAbsolutePath(), e);
            } finally {
                if (tempPayload != null && tempPayload != originalFile && tempPayload.exists() && tempPayload.getName().startsWith("gview-folder-")) {
                    // best-effort cleanup for temp zip
                    try {
                        Files.deleteIfExists(tempPayload.toPath());
                    } catch (IOException ignored) { }
                }
            }
        });
    }

    private boolean isActiveConnection() {
        if (isController) {
            return p2pClient != null && p2pClient.isConnected();
        } else {
            return p2pServer != null && activePeerAddress != null && p2pServer.hasConnection(activePeerAddress);
        }
    }

    private boolean sendMessageToActivePeer(P2PMessage message) {
        if (isController) {
            if (p2pClient != null && p2pClient.isConnected()) {
                p2pClient.sendMessage(message);
                return true;
            }
            return false;
        } else {
            if (p2pServer != null && activePeerAddress != null && p2pServer.hasConnection(activePeerAddress)) {
                p2pServer.sendMessageToPeer(activePeerAddress, message);
                return true;
            }
            return false;
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
                mainWindowController.setIncomingControllerInfo(sourcePeerId, ipAddress, port);
                mainWindowController.showRegistrationView();
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
                } else if (P2PMessage.TYPE_CHAT.equals(message.getType())) {
                    handleIncomingChat(message.getChatText());
                } else if (P2PMessage.TYPE_FILE_CHUNK.equals(message.getType())) {
                    handleIncomingFileChunk(message);
                }
            }

            @Override
            public void onPeerConnected(String peerAddress) {
                activePeerAddress = peerAddress;
                if (screenCapture != null) {
                    startScreenStreaming(peerAddress);
                }
                Platform.runLater(() -> {
                    sessionsController.appendLog("Peer connected: " + peerAddress);
                    // When we are the host, show that a controller is attached
                    if (!isController) {
                        String controllerId = activePeerId != null ? activePeerId : peerAddress;
                        String ip = peerAddress;
                        Integer port = null;
                        int colonIdx = peerAddress.lastIndexOf(':');
                        if (colonIdx > 0 && colonIdx < peerAddress.length() - 1) {
                            ip = peerAddress.substring(0, colonIdx);
                            try {
                                port = Integer.parseInt(peerAddress.substring(colonIdx + 1));
                            } catch (NumberFormatException ignored) {
                                // Keep ip-only if port is not parseable
                            }
                        }
                        mainWindowController.setIncomingControllerInfo(controllerId, ip, port);
                        sessionsController.updateActiveSession(controllerId, "Host", "Connected");
                        sessionsController.setConnectionMode("P2P");
                        mainWindowController.updateConnectionMode("P2P");
                    }
                });
            }

            @Override
            public void onPeerDisconnected(String peerAddress) {
                stopStream(peerAddress);
                Platform.runLater(() -> {
                    sessionsController.appendLog("Peer disconnected: " + peerAddress);
                    if (!isController) {
                        mainWindowController.setIncomingControllerInfo(null, null, null);
                        mainWindowController.showRegistrationView();
                    }
                });
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
                } else if (P2PMessage.TYPE_CHAT.equals(message.getType())) {
                    handleIncomingChat(message.getChatText());
                } else if (P2PMessage.TYPE_FILE_CHUNK.equals(message.getType())) {
                    handleIncomingFileChunk(message);
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
                    mainWindowController.showRegistrationView();
                    mainWindowController.setIncomingControllerInfo(null, null, null);
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

    private void handleIncomingChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Platform.runLater(() -> {
            remoteViewController.appendChat("Peer: " + text);
            mainWindowController.appendIncomingChat("Peer: " + text);
        });
    }

    private void handleIncomingFileChunk(P2PMessage message) {
        String transferId = message.getFileTransferId();
        if (transferId == null || transferId.isBlank()) {
            transferId = "transfer-" + message.getFileName() + "-" + message.getTimestamp();
        }
        int totalChunks = message.getFileChunkTotal() > 0 ? message.getFileChunkTotal() : 1;
        FileReceiveSession session = incomingFileTransfers.get(transferId);
        if (session == null) {
            try {
                Path dir = ensureDownloadDir();
                Path target = resolveUniqueFilePath(dir, message.getFileName());
                OutputStream out = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
                session = new FileReceiveSession(target, out, totalChunks, message.getFileSize(), message.isFileIsDirectory());
                incomingFileTransfers.put(transferId, session);
                sessionsController.appendLog("Đang nhận " + (session.isDirectory ? "folder" : "file") + ": " + target.getFileName());
            } catch (IOException e) {
                logger.error("Cannot create receive session", e);
                sessionsController.appendLog("Không thể lưu file: " + e.getMessage());
                return;
            }
        }

        try {
            byte[] data = message.getData();
            if (data != null && data.length > 0) {
                session.out.write(data);
                session.bytesReceived += data.length;
                logReceiveProgress(session);
            }
            session.receivedChunks++;
            boolean done = message.isFileTransferComplete() || session.receivedChunks >= session.totalChunks;
            if (done) {
                session.out.flush();
                session.out.close();
                incomingFileTransfers.remove(transferId);
                if (session.bytesReceived <= 0 && message.getFileSize() == 0) {
                    // Ensure zero-byte files still create a path
                    Files.write(session.path, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                String savedMsg = "Đã nhận " + (session.isDirectory ? "folder (.zip)" : "file") + ": " + session.path.toAbsolutePath();
                String extractedMsg = null;
                if (session.isDirectory) {
                    try {
                        Path extracted = unzipToFolder(session.path);
                        extractedMsg = "Đã giải nén: " + extracted.toAbsolutePath();
                        // giữ lại zip để đối chiếu, giống TV/AnyDesk lưu 1 bản nén
                    } catch (IOException e) {
                        logger.error("Unzip failed for {}", session.path, e);
                        extractedMsg = "Giải nén thất bại: " + e.getMessage();
                    }
                }
                final String finalExtractedMsg = extractedMsg;
                Platform.runLater(() -> {
                    sessionsController.appendLog(savedMsg);
                    if (finalExtractedMsg != null) {
                        sessionsController.appendLog(finalExtractedMsg);
                    }
                    if (remoteViewController != null) {
                        remoteViewController.appendChat(savedMsg);
                        if (finalExtractedMsg != null) {
                            remoteViewController.appendChat(finalExtractedMsg);
                        }
                    }
                    if (mainWindowController != null) {
                        mainWindowController.appendIncomingChat(savedMsg);
                        if (finalExtractedMsg != null) {
                            mainWindowController.appendIncomingChat(finalExtractedMsg);
                        }
                    }
                });
            }
        } catch (IOException e) {
            logger.error("Error writing incoming file chunk", e);
            sessionsController.appendLog("Lỗi lưu file: " + e.getMessage());
            try {
                session.out.close();
            } catch (IOException ignored) {}
            incomingFileTransfers.remove(transferId);
        }
    }

    private Path ensureDownloadDir() throws IOException {
        Path dir = Paths.get(System.getProperty("user.home"), "Downloads", "gview-transfers");
        Files.createDirectories(dir);
        return dir;
    }

    private Path resolveUniqueFilePath(Path dir, String fileName) {
        String safeName = (fileName == null || fileName.isBlank()) ? "received.bin" : fileName;
        Path candidate = dir.resolve(safeName);
        int counter = 1;
        int dot = safeName.lastIndexOf('.');
        String base = dot > 0 ? safeName.substring(0, dot) : safeName;
        String ext = dot > 0 ? safeName.substring(dot) : "";
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + counter + ext);
            counter++;
        }
        return candidate;
    }

    private void logReceiveProgress(FileReceiveSession session) {
        if (session.fileSize <= 0) {
            return; // unknown size, skip %
        }
        long percent = Math.min(100, (session.bytesReceived * 100) / session.fileSize);
        if (percent >= session.lastLoggedPercent + 10 || percent == 100) {
            session.lastLoggedPercent = percent;
            sessionsController.appendLog("Nhận " + session.path.getFileName() + ": " + percent + "%");
        }
    }

    private File zipDirectory(File directory) throws IOException {
        Path temp = Files.createTempFile("gview-folder-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(temp.toFile()))) {
            Path base = directory.toPath();
            Files.walk(base).forEach(path -> {
                File f = path.toFile();
                if (f.isDirectory()) {
                    return;
                }
                String entryName = base.relativize(path).toString();
                try {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) { }
            throw e.getCause();
        }
        return temp.toFile();
    }

    private Path unzipToFolder(Path zipPath) throws IOException {
        String name = zipPath.getFileName().toString();
        String baseName = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        Path targetDir = zipPath.getParent().resolve(baseName);
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target dir");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        return targetDir;
    }

    private void closeAllIncomingFileTransfers() {
        for (FileReceiveSession session : incomingFileTransfers.values()) {
            try {
                session.out.close();
            } catch (IOException ignored) { }
        }
        incomingFileTransfers.clear();
    }

    private static class FileReceiveSession {
        final Path path;
        final OutputStream out;
        final int totalChunks;
        final long fileSize;
        final boolean isDirectory;
        int receivedChunks = 0;
        long bytesReceived = 0;
        long lastLoggedPercent = -1;

        FileReceiveSession(Path path, OutputStream out, int totalChunks, long fileSize, boolean isDirectory) {
            this.path = path;
            this.out = out;
            this.totalChunks = totalChunks;
            this.fileSize = fileSize;
            this.isDirectory = isDirectory;
        }
    }

    private Properties loadConfig() {
        return ConfigLoader.load("config.properties");
    }
}
