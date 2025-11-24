package com.p2pclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2pclient.gui.*;
import com.p2pclient.model.P2PMessage;
import com.p2pclient.model.PeerInfo;
import com.p2pclient.model.RelayMessage;
import com.p2pclient.network.IdServerClient;
import com.p2pclient.network.P2PClient;
import com.p2pclient.network.P2PServer;
import com.p2pclient.remote.ScreenCapture;
import com.p2pclient.remote.ScreenQualityProfile;
import com.p2pclient.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.AWTException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

public class P2PClientApp {
    private static final Logger logger = LoggerFactory.getLogger(P2PClientApp.class);
    
    private MainFrame mainFrame;
    private IdServerClient idServerClient;
    private P2PServer p2pServer;
    private P2PClient p2pClient;
    private ScreenCapture screenCapture;
    private boolean controllerRelayMode = false;
    private volatile boolean controlledRelayMode = false;
    private String currentControllerPeerId;
    private String relayTargetPeerId;
    private RelayScreenSender relayScreenSender;
    private Thread relayScreenThread;
    private final ObjectMapper relayMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ScheduledExecutorService> relayMonitors = new ConcurrentHashMap<>();
    private final AtomicLong relayFrameSequence = new AtomicLong();
    private final AtomicLong lastRenderedRelayFrame = new AtomicLong();
    private final BlockingQueue<RelayMessage> relayFrameQueue = new LinkedBlockingDeque<>(1);
    private final LongAdder relayLatencySum = new LongAdder();
    private final LongAdder relayLatencyCount = new LongAdder();
    private final LongAccumulator relayLatencyMax = new LongAccumulator(Long::max, Long.MIN_VALUE);
    private final LongAccumulator relayLatencyMin = new LongAccumulator(Long::min, Long.MAX_VALUE);
    private final AtomicLong lastRelayLatencyLog = new AtomicLong();
    private Thread relayFrameWorker;
    
    private String myPeerId;
    private String myPassword;
    private int myP2PPort;
    private String myIpAddress;
    
    private boolean isController = false; // true if we initiated the connection
    private final ExecutorService executorService;
    private volatile ScreenQualityProfile qualityProfile; // Can be changed via GUI

    public P2PClientApp() {
        this(null);
    }

    public P2PClientApp(ScreenQualityProfile profile) {
        this.qualityProfile = profile == null ? ScreenQualityProfile.defaultProfile() : profile;
        logger.info("Starting client with screen quality profile {}", this.qualityProfile);
        executorService = Executors.newCachedThreadPool();
        initializeGUI();
        initializeIdServerClient();
        startRelayFrameWorker();
    }

    private void initializeGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warn("Could not set system look and feel", e);
        }

        SwingUtilities.invokeLater(() -> {
            mainFrame = new MainFrame();
            // CRITICAL FIX: Register shutdown handler - only called when user explicitly closes window
            mainFrame.setShutdownHandler(() -> {
                logger.info("Shutdown requested by user (window close) - calling System.exit(0)");
                shutdown();
                System.exit(0);
            });
            setupLoginPanel();
            setupDashboardPanel();
            setupRemoteControlPanel();
            mainFrame.setVisible(true);
        });
    }

    private void initializeIdServerClient() {
        idServerClient = new IdServerClient();
        idServerClient.setConnectionListener(new IdServerClient.ConnectionListener() {
            @Override
            public void onConnectionRequest(String sourcePeerId, String ipAddress, Integer port) {
                logger.info("Received connection request from: {} - WebSocket connected: {}", 
                    sourcePeerId, idServerClient.isWebSocketConnected());
                
                SwingUtilities.invokeLater(() -> {
                    int response = JOptionPane.showConfirmDialog(
                        mainFrame,
                        "Connection request from peer: " + sourcePeerId + "\n" +
                        "IP: " + ipAddress + ":" + port + "\n\n" +
                        "Accept connection?",
                        "Incoming Connection Request",
                        JOptionPane.YES_NO_OPTION
                    );
                    
                    if (response == JOptionPane.YES_OPTION) {
                        logger.info("User accepted connection request from {}", sourcePeerId);
                        mainFrame.getDashboardPanel().addLog(
                            "Accepted connection request from " + sourcePeerId);
                        isController = false; // We are being controlled
                        
                        // Enable screen capture for controlled peer
                        try {
                            initializeScreenCapture();
                            logger.info("ScreenCapture initialized for controlled peer");
                        } catch (AWTException e) {
                            logger.error("Failed to create screen capture", e);
                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(mainFrame,
                                    "Failed to initialize screen capture",
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            });
                            return;
                        }
                        p2pServer.setScreenCapture(screenCapture);
                        p2pServer.setController(false); // We are controlled, so we send screen
                        
                        relayTargetPeerId = sourcePeerId;
                        
                        // CRITICAL: Wait for P2P connection with timeout, then fallback to relay
                        executorService.submit(() -> {
                            handleIncomingConnectionWithTimeout(sourcePeerId, ipAddress, port);
                        });
                        
                        mainFrame.showRemoteControlPanel();
                        mainFrame.getRemoteControlPanel().setController(false);
                    } else {
                        logger.info("User rejected connection request from {}", sourcePeerId);
                    }
                });
            }

            @Override
            public void onConnected() {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().setStatus("Online", java.awt.Color.GREEN);
                    mainFrame.getDashboardPanel().addLog("Connected to ID Server");
                });
            }

            @Override
            public void onDisconnected() {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().setStatus("Offline", java.awt.Color.RED);
                    mainFrame.getDashboardPanel().addLog("Disconnected from ID Server");
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(mainFrame, error, 
                        "Connection Error", JOptionPane.ERROR_MESSAGE);
                    mainFrame.getDashboardPanel().addLog("Error: " + error);
                });
            }
        });
    }

    private synchronized void initializeScreenCapture() throws AWTException {
        if (screenCapture == null) {
            screenCapture = new ScreenCapture(qualityProfile);
            logger.info("ScreenCapture initialized with profile {}", qualityProfile);
        }
    }

    private void startRelayFrameWorker() {
        relayFrameWorker = new Thread(this::drainRelayFrames, "RelayFrameWorker");
        relayFrameWorker.setDaemon(true);
        relayFrameWorker.start();
    }

    private void drainRelayFrames() {
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
        logger.info("Relay frame worker stopped");
    }

    private void setupLoginPanel() {
        LoginPanel loginPanel = mainFrame.getLoginPanel();
        loginPanel.setListener(new LoginPanel.LoginListener() {
            @Override
            public void onRegister(String password) {
                loginPanel.setButtonsEnabled(false);
                loginPanel.setStatus("Registering...");
                
                executorService.submit(() -> {
                    try {
                        String peerId = idServerClient.registerPeer(password);
                        myPeerId = peerId;
                        myPassword = password;
                        
                        // Find available port and start P2P server
                        Properties props = loadConfig();
                        int startPort = Integer.parseInt(props.getProperty("p2p.port.range.start", "50000"));
                        int endPort = Integer.parseInt(props.getProperty("p2p.port.range.end", "60000"));
                        myP2PPort = NetworkUtils.findAvailablePort(startPort, endPort);
                        myIpAddress = NetworkUtils.getLocalIPAddress();
                        
                        // CRITICAL: Initialize screen capture BEFORE starting P2P server
                        // This is needed for when we become controlled peer
                        try {
                            initializeScreenCapture();
                            logger.info("ScreenCapture initialized during registration");
                        } catch (AWTException e) {
                            logger.error("Failed to create ScreenCapture", e);
                            SwingUtilities.invokeLater(() -> {
                                loginPanel.setError("Failed to initialize screen capture: " + e.getMessage());
                                loginPanel.setButtonsEnabled(true);
                                JOptionPane.showMessageDialog(mainFrame,
                                    "Failed to initialize screen capture",
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            });
                            return;
                        }
                        
                        // Start P2P server
                        startP2PServer();
                        
                        // Connect to ID Server via WebSocket
                        idServerClient.connectWebSocket(peerId, password, myIpAddress, myP2PPort);
                        idServerClient.subscribeToRelay(peerId, P2PClientApp.this::handleRelayMessage);
                        
                        SwingUtilities.invokeLater(() -> {
                            loginPanel.setPeerId(peerId);
                            loginPanel.setStatus("Registration successful!");
                            mainFrame.showDashboardPanel();
                            mainFrame.getDashboardPanel().setPeerId(peerId);
                        });
                    } catch (Exception e) {
                        logger.error("Registration failed", e);
                        SwingUtilities.invokeLater(() -> {
                            loginPanel.setError("Registration failed: " + e.getMessage());
                            loginPanel.setButtonsEnabled(true);
                            JOptionPane.showMessageDialog(mainFrame, 
                                "Registration failed: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                });
            }

            @Override
            public void onConnect(String peerId, String password) {
                if (!NetworkUtils.isValidPeerId(peerId)) {
                    JOptionPane.showMessageDialog(mainFrame, 
                        "Invalid peer ID format. Expected: XXX-XXX-XXX",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                loginPanel.setButtonsEnabled(false);
                loginPanel.setStatus("Connecting...");
                
                executorService.submit(() -> {
                    try {
                        myPeerId = peerId;
                        myPassword = password;
                        
                        // Lookup peer
                        PeerInfo peerInfo = idServerClient.lookupPeer(peerId, password);
                        
                        if (!peerInfo.getOnline()) {
                            throw new IOException("Peer is offline");
                        }
                        
                        // Find available port and start P2P server
                        Properties props = loadConfig();
                        int startPort = Integer.parseInt(props.getProperty("p2p.port.range.start", "50000"));
                        int endPort = Integer.parseInt(props.getProperty("p2p.port.range.end", "60000"));
                        myP2PPort = NetworkUtils.findAvailablePort(startPort, endPort);
                        myIpAddress = NetworkUtils.getLocalIPAddress();
                        
                        // CRITICAL: Initialize screen capture BEFORE starting P2P server
                        try {
                            initializeScreenCapture();
                            logger.info("ScreenCapture initialized during connect");
                        } catch (AWTException e) {
                            logger.error("Failed to create ScreenCapture", e);
                            SwingUtilities.invokeLater(() -> {
                                loginPanel.setError("Failed to initialize screen capture: " + e.getMessage());
                                loginPanel.setButtonsEnabled(true);
                                JOptionPane.showMessageDialog(mainFrame,
                                    "Failed to initialize screen capture",
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            });
                            return;
                        }
                        
                        // Start P2P server
                        startP2PServer();
                        
                        // Connect to ID Server via WebSocket
                        idServerClient.connectWebSocket(peerId, password, myIpAddress, myP2PPort);
                        idServerClient.subscribeToRelay(peerId, P2PClientApp.this::handleRelayMessage);
                        
                        SwingUtilities.invokeLater(() -> {
                            loginPanel.setStatus("Connected successfully!");
                            mainFrame.showDashboardPanel();
                            mainFrame.getDashboardPanel().setPeerId(peerId);
                        });
                    } catch (Exception e) {
                        logger.error("Connection failed", e);
                        SwingUtilities.invokeLater(() -> {
                            loginPanel.setError("Connection failed: " + e.getMessage());
                            loginPanel.setButtonsEnabled(true);
                            JOptionPane.showMessageDialog(mainFrame, 
                                "Connection failed: " + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                });
            }
        });
    }

    private void setupDashboardPanel() {
        DashboardPanel dashboardPanel = mainFrame.getDashboardPanel();
        dashboardPanel.setListener(new DashboardPanel.DashboardListener() {
            @Override
            public void onConnectToPeer(String targetPeerId, String password) {
                if (!NetworkUtils.isValidPeerId(targetPeerId)) {
                    JOptionPane.showMessageDialog(mainFrame, 
                        "Invalid peer ID format. Expected: XXX-XXX-XXX",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                dashboardPanel.setConnectButtonEnabled(false);
                dashboardPanel.addLog("Looking up peer: " + targetPeerId);
                
                executorService.submit(() -> {
                    try {
                        logger.info(">>> ENTERING: Connection thread started for peer: {}", targetPeerId);
                        
                        // Lookup target peer
                        PeerInfo targetPeer = idServerClient.lookupPeer(targetPeerId, password);
                        logger.info("Peer lookup successful: {}:{}", targetPeer.getIpAddress(), targetPeer.getPort());
                        
                        if (!targetPeer.getOnline()) {
                            throw new IOException("Target peer is offline");
                        }
                        
                        dashboardPanel.addLog("Found peer at " + targetPeer.getIpAddress() + 
                            ":" + targetPeer.getPort());
                        
                        relayTargetPeerId = targetPeerId;
                        
                        // Connect P2P client
                        boolean p2pSuccess = connectP2PClient(targetPeer.getIpAddress(), targetPeer.getPort());
                        
                        // We are the controller
                        isController = true;
                        p2pServer.setController(true);
                        
                        if (p2pSuccess) {
                            logger.info("P2P connection successful to {}", targetPeerId);
                            controllerRelayMode = false;
                            SwingUtilities.invokeLater(() -> {
                                dashboardPanel.addLog("P2P connection established");
                                dashboardPanel.setConnectionMode("P2P");
                                dashboardPanel.setDisconnectButtonEnabled(true);
                                mainFrame.showRemoteControlPanel();
                                mainFrame.getRemoteControlPanel().setController(true);
                            });
                        } else {
                            logger.warn("P2P connection to {} failed; falling back to relay", targetPeerId);
                            enableControllerRelayMode(targetPeerId);
                            
                            // CRITICAL: Keep-alive loop for relay mode
                            logger.info("Starting relay mode keep-alive for peer: {}", targetPeerId);
                            keepConnectionAliveForRelay(targetPeerId);
                            
                            SwingUtilities.invokeLater(() -> {
                                dashboardPanel.addLog("P2P failed, using relay mode");
                                dashboardPanel.setDisconnectButtonEnabled(true);
                                mainFrame.showRemoteControlPanel();
                                mainFrame.getRemoteControlPanel().setController(true);
                            });
                        }
                    } catch (Exception e) {
                        logger.error("Connection handler error", e);
                        SwingUtilities.invokeLater(() -> {
                            dashboardPanel.addLog("Connection failed: " + e.getMessage());
                            dashboardPanel.setConnectButtonEnabled(true);
                            JOptionPane.showMessageDialog(mainFrame, 
                                "Failed to connect: " + e.getMessage(),
                                "Connection Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                });
            }

            @Override
            public void onDisconnect() {
                disconnectP2P();
                SwingUtilities.invokeLater(() -> {
                    dashboardPanel.setDisconnectButtonEnabled(false);
                    dashboardPanel.setConnectButtonEnabled(true);
                    dashboardPanel.addLog("Disconnected from peer");
                    mainFrame.showDashboardPanel();
                });
            }

            @Override
            public void onQualityProfileChanged(String profileName) {
                try {
                    ScreenQualityProfile newProfile = ScreenQualityProfile.fromCliArg(profileName);
                    ScreenQualityProfile oldProfile = qualityProfile;
                    if (newProfile != oldProfile) {
                        qualityProfile = newProfile; // Update global profile
                        logger.info("Quality profile changed via GUI: {} -> {}", oldProfile, newProfile);
                        
                        // Update active RelayScreenSender if running
                        if (relayScreenSender != null) {
                            relayScreenSender.updateProfile(newProfile);
                            logger.info("Updated active RelayScreenSender to profile {}", newProfile);
                        }
                        
                        // Recreate ScreenCapture with new profile if it exists
                        if (screenCapture != null) {
                            try {
                                screenCapture = new ScreenCapture(newProfile);
                                logger.info("Recreated ScreenCapture with profile {}", newProfile);
                            } catch (AWTException e) {
                                logger.error("Failed to recreate ScreenCapture", e);
                            }
                        }
                        
                        SwingUtilities.invokeLater(() -> {
                            dashboardPanel.addLog("Screen quality set to: " + newProfile.name() + 
                                " (" + newProfile.getMaxWidth() + "x" + newProfile.getMaxHeight() + 
                                ", " + newProfile.getTargetFps() + " FPS target)");
                        });
                    }
                } catch (Exception e) {
                    logger.error("Failed to change quality profile", e);
                    SwingUtilities.invokeLater(() -> {
                        dashboardPanel.addLog("Failed to change quality profile: " + e.getMessage());
                    });
                }
            }
        });
    }

    private void setupRemoteControlPanel() {
        RemoteControlPanel remotePanel = mainFrame.getRemoteControlPanel();
        remotePanel.setListener(new RemoteControlPanel.RemoteControlListener() {
            @Override
            public void onMouseEvent(P2PMessage message) {
                // CRITICAL: Log when MOUSE event arrives from RemoteControlPanel
                logger.debug("RemoteControlPanel.onMouseEvent: controllerRelayMode={}, p2pClient={}", 
                    controllerRelayMode, (p2pClient != null && p2pClient.isConnected()));
                
                if (controllerRelayMode) {
                    sendRelayControlMessage(message, P2PMessage.TYPE_MOUSE);
                } else if (p2pClient != null && p2pClient.isConnected()) {
                    p2pClient.sendMessage(message);
                } else {
                    logger.warn("MOUSE event dropped: controllerRelayMode=false and p2pClient not connected");
                }
            }

            @Override
            public void onKeyboardEvent(P2PMessage message) {
                if (controllerRelayMode) {
                    sendRelayControlMessage(message, P2PMessage.TYPE_KEYBOARD);
                } else if (p2pClient != null && p2pClient.isConnected()) {
                    p2pClient.sendMessage(message);
                }
            }

            @Override
            public void onDisconnect() {
                disconnectP2P();
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().setDisconnectButtonEnabled(false);
                    mainFrame.getDashboardPanel().setConnectButtonEnabled(true);
                    mainFrame.getDashboardPanel().addLog("Disconnected from peer");
                    mainFrame.showDashboardPanel();
                });
            }
        });
    }

    private void startP2PServer() throws IOException {
        if (p2pServer != null) {
            p2pServer.stop();
        }
        
        p2pServer = new P2PServer(myP2PPort);
        logger.info("P2P Server configured with screen quality profile {}", qualityProfile);
        
        // CRITICAL: Set screen capture so controlled peer can send screen
        if (screenCapture != null) {
            p2pServer.setScreenCapture(screenCapture);
            logger.info("ScreenCapture set for P2PServer");
        } else {
            logger.warn("ScreenCapture is null when starting P2PServer!");
        }
        
        p2pServer.setMessageListener(new P2PServer.MessageListener() {
            @Override
            public void onMessageReceived(P2PMessage message, String peerAddress) {
                if (P2PMessage.TYPE_SCREEN.equals(message.getType())) {
                    // Received screen data
                    byte[] imageData = message.getData();
                    if (imageData != null) {
                        logger.debug("Received screen: {} bytes from {}", imageData.length, peerAddress);
                        // CRITICAL: Update GUI on EDT
                        SwingUtilities.invokeLater(() -> {
                            mainFrame.getRemoteControlPanel().displayScreen(imageData);
                        });
                    } else {
                        logger.warn("Received SCREEN message with null data");
                    }
                } else if (P2PMessage.TYPE_MOUSE.equals(message.getType())) {
                    // Execute mouse event on local machine
                    if (mainFrame.getRemoteControlPanel().getInputForwarder() != null) {
                        if (message.isMousePressed()) {
                            mainFrame.getRemoteControlPanel().getInputForwarder()
                                .executeMouseClick(message);
                        } else {
                            mainFrame.getRemoteControlPanel().getInputForwarder()
                                .executeMouseMove(message);
                        }
                    }
                } else if (P2PMessage.TYPE_KEYBOARD.equals(message.getType())) {
                    // Execute keyboard event on local machine
                    if (mainFrame.getRemoteControlPanel().getInputForwarder() != null) {
                        mainFrame.getRemoteControlPanel().getInputForwarder()
                            .executeKeyboard(message);
                    }
                }
            }

            @Override
            public void onPeerConnected(String peerAddress) {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().addLog("Peer connected: " + peerAddress);
                });
            }

            @Override
            public void onPeerDisconnected(String peerAddress) {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().addLog("Peer disconnected: " + peerAddress);
                });
            }
        });
        
        p2pServer.start();
        logger.info("P2P Server started on port {}", myP2PPort);
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
                    // Received screen data
                    byte[] imageData = message.getData();
                    if (imageData != null) {
                        logger.debug("Received screen: {} bytes via P2P client", imageData.length);
                        // CRITICAL: Update GUI on EDT
                        SwingUtilities.invokeLater(() -> {
                            mainFrame.getRemoteControlPanel().displayScreen(imageData);
                        });
                    } else {
                        logger.warn("Received SCREEN message with null data");
                    }
                }
            }

            @Override
            public void onConnected() {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().addLog("P2P client connected");
                });
            }

            @Override
            public void onDisconnected() {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().addLog("P2P client disconnected");
                    mainFrame.getRemoteControlPanel().clearScreen();
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    mainFrame.getDashboardPanel().addLog("P2P error: " + error);
                });
            }
        });
        
        boolean connected = p2pClient.connect();
        if (!connected) {
            p2pClient = null;
        }
        return connected;
    }

    private void disconnectP2P() {
        // DIAGNOSTIC: Log disconnect reason
        String reason = "unknown";
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack.length > 2) {
                StackTraceElement caller = stack[2];
                reason = caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
            }
        } catch (Exception e) {
            // Ignore
        }
        
        logger.info("disconnectP2P called: reason={}, isController={}, controllerRelayMode={}, controlledRelayMode={}",
            reason, isController, controllerRelayMode, controlledRelayMode);
        
        // CRITICAL FIX: disconnectP2P should ONLY disconnect P2P/relay connections and update UI
        // It should NOT trigger window close or System.exit
        // The app should remain running so user can reconnect or use other features
        
        if (p2pClient != null) {
            p2pClient.disconnect();
            p2pClient = null;
        }
        
        isController = false;
        if (p2pServer != null) {
            p2pServer.setController(false);
        }
        controllerRelayMode = false;
        controlledRelayMode = false;
        currentControllerPeerId = null;
        relayTargetPeerId = null;
        stopRelayScreenSender();
        
        // Stop all relay monitors
        stopAllRelayMonitors();
        
        if (idServerClient != null) {
            idServerClient.unsubscribeRelay();
        }
        mainFrame.getDashboardPanel().setConnectionMode("P2P");
        
        SwingUtilities.invokeLater(() -> {
            mainFrame.getRemoteControlPanel().clearScreen();
            // CRITICAL: Return to dashboard, but keep app running
            mainFrame.showDashboardPanel();
        });
        
        logger.info("disconnectP2P completed: P2P/relay disconnected, app remains running");
    }

    private void enableControllerRelayMode(String targetPeerId) {
        logger.info("=== ENABLING CONTROLLER RELAY MODE ===");
        logger.info("Target peer: {}", targetPeerId);
        logger.info("My peer ID: {}", myPeerId);
        logger.info("WebSocket connected: {}", idServerClient.isWebSocketConnected());
        
        if (controllerRelayMode) {
            logger.warn("Relay mode already enabled, ensuring monitoring is active");
            idServerClient.startWebSocketMonitoring();
            return;
        }
        
        if (!idServerClient.isWebSocketConnected()) {
            logger.error("CRITICAL: Cannot enable relay mode - WebSocket not connected!");
            SwingUtilities.invokeLater(() -> {
                mainFrame.getDashboardPanel().addLog("ERROR: WebSocket disconnected, cannot use relay mode");
            });
            return;
        }

        if (screenCapture == null) {
            logger.error("Cannot enable controlled relay mode: screenCapture is null");
            return;
        }
        
        // CRITICAL: Set controllerRelayMode FIRST
        controllerRelayMode = true;
        relayTargetPeerId = targetPeerId;
        logger.info("Set controllerRelayMode = true, relayTargetPeerId = {}", targetPeerId);
        
        // CRITICAL: Subscription was already created in login flow (line 279)
        // Don't create duplicate subscription - just ensure monitoring is active
        logger.info("Relay subscription should already exist from login flow");
        idServerClient.startWebSocketMonitoring();
        logger.info("WebSocket monitoring activated");
        
        SwingUtilities.invokeLater(() -> {
            mainFrame.getDashboardPanel().setConnectionMode("Relay");
            mainFrame.getDashboardPanel().addLog("Relay mode enabled for peer " + targetPeerId);
        });
        
        logger.info("=== CONTROLLER RELAY MODE ENABLED ===");
        logger.info("controllerRelayMode: {}", controllerRelayMode);
        logger.info("WebSocket connected: {}", idServerClient.isWebSocketConnected());
    }

    /**
     * handleRelayMessage is invoked on the STOMP receiver thread whenever the ID server
     * pushes a relay payload. It validates routing, then dispatches SCREEN vs control data.
     * UI updates are marshalled onto the EDT.
     */
    private void handleRelayMessage(RelayMessage message) {
        logger.info("handleRelayMessage called! Message: type={}, from={}, to={}, controllerRelayMode={}", 
            message != null ? message.getDataType() : "null",
            message != null ? message.getSourcePeerId() : "null",
            message != null ? message.getTargetPeerId() : "null",
            controllerRelayMode);
        
        if (message == null) {
            logger.error("Received null relay message");
            return;
        }
        
        if (message.getTargetPeerId() == null || myPeerId == null) {
            logger.warn("Relay message missing target peer ID or my peer ID: target={}, myPeerId={}", 
                message.getTargetPeerId(), myPeerId);
            return;
        }
        
        if (!myPeerId.equals(message.getTargetPeerId())) {
            logger.debug("Relay message not for this peer: target={}, myPeerId={}", 
                message.getTargetPeerId(), myPeerId);
            return;
        }

        logger.info("Processing relay message: type={}, from={}, to={}, controllerRelayMode={}", 
            message.getDataType(), message.getSourcePeerId(), message.getTargetPeerId(), controllerRelayMode);

        try {
            switch (message.getDataType()) {
                case "SCREEN": {
                    int base64Length = message.getBase64Data() != null ? message.getBase64Data().length() : -1;
                    long latencyHint = message.getSendTimestampMs() > 0
                        ? System.currentTimeMillis() - message.getSendTimestampMs()
                        : -1L;
                    logger.debug("SCREEN relay message received: frameSeq={}, base64Len={}, latencyHint={}ms",
                        message.getFrameSeq(),
                        base64Length,
                        latencyHint >= 0 ? latencyHint : -1);
                    enqueueRelayFrame(message);
                    break;
                }
                case "MOUSE":
                case "KEYBOARD": {
                    autoEnableControlledRelayModeIfNeeded(message);
                    if (isController) {
                        logger.debug("Received control message but this peer is controller");
                        return;
                    }
                    if (message.getBase64Data() == null || message.getBase64Data().isEmpty()) {
                        logger.warn("Received {} message with empty base64Data", message.getDataType());
                        return;
                    }
                    P2PMessage controlMessage = decodeControlMessage(message.getBase64Data(), message.getDataType());
                    if (controlMessage == null) {
                        logger.warn("Failed to decode control message");
                        return;
                    }
                    if (P2PMessage.TYPE_MOUSE.equals(message.getDataType())) {
                        if (mainFrame.getRemoteControlPanel().getInputForwarder() != null) {
                            // CRITICAL FIX: Distinguish MOVE from CLICK based on mouseButton
                            // mouseButton == 0 means MOVE (no button pressed)
                            // mouseButton != 0 means CLICK (button press or release)
                            int button = controlMessage.getMouseButton();
                            if (button != 0) {
                                // This is a click event (press or release)
                                mainFrame.getRemoteControlPanel().getInputForwarder().executeMouseClick(controlMessage);
                            } else {
                                // This is a move event (no button)
                                mainFrame.getRemoteControlPanel().getInputForwarder().executeMouseMove(controlMessage);
                            }
                        }
                    } else if (P2PMessage.TYPE_KEYBOARD.equals(message.getDataType())) {
                        if (mainFrame.getRemoteControlPanel().getInputForwarder() != null) {
                            mainFrame.getRemoteControlPanel().getInputForwarder().executeKeyboard(controlMessage);
                        }
                    }
                    break;
                }
                default:
                    logger.warn("Unknown relay message data type: {}", message.getDataType());
                    break;
            }
        } catch (Exception e) {
            logger.error("Failed to handle relay message: type={}, error={}", 
                message.getDataType(), e.getMessage(), e);
        }
    }

    private void processRelayFrame(RelayMessage message) {
        if (message == null || message.getBase64Data() == null) {
            return;
        }
        long seq = message.getFrameSeq();
        long lastSeq = lastRenderedRelayFrame.get();
        if (seq > 0 && seq <= lastSeq) {
            logger.debug("Skipping stale relay frame {} (lastRendered={})", seq, lastSeq);
            return;
        }

        try {
            byte[] imageBytes = Base64.getDecoder().decode(message.getBase64Data());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                logger.warn("Decoded relay frame {} produced null image", seq);
                return;
            }

            if (seq > 0) {
                lastRenderedRelayFrame.set(seq);
            }

            long latency = message.getSendTimestampMs() > 0
                ? System.currentTimeMillis() - message.getSendTimestampMs()
                : -1L;
            recordRelayLatency(latency);

            SwingUtilities.invokeLater(() ->
                mainFrame.getRemoteControlPanel().updateRemoteScreen(image)
            );
        } catch (Exception e) {
            logger.error("Failed to process relay frame {}", seq, e);
        }
    }

    private void enqueueRelayFrame(RelayMessage message) {
        if (message == null || message.getBase64Data() == null || message.getBase64Data().isEmpty()) {
            logger.warn("Skipping relay frame with empty payload");
            return;
        }
        if (!relayFrameQueue.offer(message)) {
            relayFrameQueue.poll();
            relayFrameQueue.offer(message);
            logger.debug("Dropped oldest relay frame to keep queue fresh (seq={})", message.getFrameSeq());
        }
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
            logger.error("Failed to decode control relay payload", e);
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
                logger.info("Relay latency: avg={}ms, min={}ms, max={}ms over {} frames",
                    avg,
                    min == Long.MAX_VALUE ? "n/a" : min,
                    max == Long.MIN_VALUE ? "n/a" : max,
                    count);
            }
        }
    }

    private void autoEnableControlledRelayModeIfNeeded(RelayMessage message) {
        if (message == null) {
            return;
        }
        String sourcePeerId = message.getSourcePeerId();
        String targetPeerId = message.getTargetPeerId();

        if (myPeerId == null || targetPeerId == null || !myPeerId.equals(targetPeerId)) {
            logger.debug("autoEnableControlledRelayModeIfNeeded: this peer ({}) is not the target ({}), skipping",
                myPeerId, targetPeerId);
            return;
        }

        if (controllerRelayMode) {
            logger.debug("autoEnableControlledRelayModeIfNeeded: controllerRelayMode=true, not enabling controlled relay");
            return;
        }

        if (controlledRelayMode && sourcePeerId != null && sourcePeerId.equals(currentControllerPeerId)) {
            return;
        }

        logger.info("AUTO-ENABLING CONTROLLED RELAY MODE: controllerPeerId={}, thisPeerId={}",
            sourcePeerId, myPeerId);
        enableControlledRelayMode(sourcePeerId);
    }

    private void sendRelayControlMessage(P2PMessage message, String dataType) {
        if (relayTargetPeerId == null) {
            logger.warn("Cannot send relay control message: target peer not set");
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

    /**
     * Handle incoming connection with P2P timeout and relay fallback
     * CRITICAL: This ensures WebSocket stays connected even if P2P fails
     */
    private void handleIncomingConnectionWithTimeout(String sourcePeerId, String ipAddress, Integer port) {
        logger.info("Handling incoming connection from {} - WebSocket connected: {}", 
            sourcePeerId, idServerClient.isWebSocketConnected());
        
        // Check if P2P server is running
        if (p2pServer == null || p2pServer.getPort() == 0) {
            logger.warn("P2P server not running, using relay mode from start");
            enableControlledRelayMode(sourcePeerId);
            return;
        }
        
        // Wait for P2P connection with 10 second timeout
        logger.info("Waiting for P2P connection from {} (timeout: 10 seconds)...", sourcePeerId);
        boolean p2pConnected = waitForP2PConnection(10);
        
        if (p2pConnected) {
            logger.info("P2P connection established from {}", sourcePeerId);
            SwingUtilities.invokeLater(() -> {
                mainFrame.getDashboardPanel().setConnectionMode("P2P");
                mainFrame.getDashboardPanel().addLog("P2P connection established");
            });
            // P2P connection will be handled by P2PServer's PeerConnectionHandler
        } else {
            logger.warn("No P2P connection received within timeout, switching to relay mode");
            enableControlledRelayMode(sourcePeerId);
        }
        
        // CRITICAL: Verify WebSocket is still connected
        if (!idServerClient.isWebSocketConnected()) {
            logger.error("CRITICAL: WebSocket disconnected during connection handling!");
            SwingUtilities.invokeLater(() -> {
                mainFrame.getDashboardPanel().addLog("ERROR: WebSocket disconnected!");
            });
        } else {
            logger.info("WebSocket still connected after connection handling");
        }
    }

    /**
     * Wait for P2P connection with timeout
     * Returns true if P2P connection is received, false otherwise
     */
    private boolean waitForP2PConnection(int timeoutSeconds) {
        if (p2pServer == null) {
            logger.warn("P2P server is null, cannot wait for connection");
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;
        
        // Check if P2P connection was established
        // P2PServer will add connection to its connections map when accepted
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check if any P2P connection exists
            if (p2pServer.getConnectionCount() > 0) {
                logger.info("P2P connection detected!");
                return true;
            }
            
            // Check WebSocket health
            if (!idServerClient.isWebSocketConnected()) {
                logger.error("WebSocket disconnected while waiting for P2P connection!");
                return false;
            }
            
            try {
                Thread.sleep(500); // Check every 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("Interrupted while waiting for P2P connection");
                return false;
            }
        }
        
        logger.warn("P2P connection timeout after {} seconds", timeoutSeconds);
        return false;
    }

    /**
     * Enable relay mode for controlled peer
     * CRITICAL: Subscribe to relay BEFORE starting screen capture
     */
    private synchronized void enableControlledRelayMode(String targetPeerId) {
        logger.info("=== ENABLING CONTROLLED RELAY MODE REQUEST === targetPeerId={}, wsConnected={}",
            targetPeerId, idServerClient.isWebSocketConnected());
        if (targetPeerId == null || targetPeerId.isEmpty()) {
            logger.error("Cannot enable controlled relay mode: controller peer ID is null/empty");
            return;
        }
        if (controlledRelayMode && targetPeerId.equals(currentControllerPeerId)) {
            logger.info("Controlled relay mode already active for controller {}", currentControllerPeerId);
            return;
        }
        
        if (!idServerClient.isWebSocketConnected()) {
            logger.error("CRITICAL: Cannot enable relay mode - WebSocket not connected!");
            SwingUtilities.invokeLater(() -> {
                mainFrame.getDashboardPanel().addLog("ERROR: WebSocket disconnected, cannot use relay mode");
            });
            return;
        }
        
        controlledRelayMode = true;
        currentControllerPeerId = targetPeerId;
        relayTargetPeerId = targetPeerId;
        
        // CRITICAL: Subscribe to relay messages FIRST (for receiving control commands)
        logger.info("Subscribing to relay topic for peer: {} (BEFORE starting screen capture)", myPeerId);
        idServerClient.subscribeToRelay(myPeerId, this::handleRelayMessage);
        
        // Wait a bit to ensure subscription is active
        try {
            Thread.sleep(100); // 100ms delay to ensure subscription is registered
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for relay subscription");
        }
        
        // Start sending screen via relay AFTER subscription is confirmed
        logger.info("CONTROLLED: relay mode enabled, controllerPeerId={}, myPeerId={}", targetPeerId, myPeerId);
        logger.info("Starting relay screen sender for controller {}", targetPeerId);
        startRelayScreenSender(targetPeerId);
        logger.info("CONTROLLED: relay screen sender start invoked (thread active? {})", relayScreenThread != null);
        
        SwingUtilities.invokeLater(() -> {
            mainFrame.getDashboardPanel().setConnectionMode("Relay");
            mainFrame.getDashboardPanel().addLog("Relay mode active for controller " + targetPeerId);
        });
        
        logger.info("Controlled relay mode enabled - WebSocket connected: {}", 
            idServerClient.isWebSocketConnected());
    }

    private void startRelayScreenSender(String targetPeerId) {
        stopRelayScreenSender();
        if (targetPeerId == null) {
            logger.warn("Cannot start relay screen sender without target peer");
            return;
        }
        if (screenCapture == null) {
            logger.warn("ScreenCapture not initialized for relay sender");
            return;
        }
        logger.info("Starting relay screen sender for {}", targetPeerId);
        relayScreenSender = new RelayScreenSender(targetPeerId);
        relayScreenThread = new Thread(relayScreenSender, "RelayScreenSender");
        relayScreenThread.setDaemon(true);
        relayScreenThread.start();
        SwingUtilities.invokeLater(() -> {
            mainFrame.getDashboardPanel().addLog("Relay screen sender started for " + targetPeerId);
        });
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

    /**
     * RelayScreenSender lifecycle:
     * - Constructed when a controlled peer falls back to relay mode.
     * - Runs on a dedicated daemon thread until stop() is called or thread interrupted.
     * - Captures/compresses screen frames and forwards them via IdServerClient over WebSocket.
     */
    private class RelayScreenSender implements Runnable {
        private final String targetPeerId;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile ScreenQualityProfile currentProfile;

        RelayScreenSender(String targetPeerId) {
            this.targetPeerId = targetPeerId;
            this.currentProfile = screenCapture != null ? screenCapture.getProfile() : qualityProfile;
            logger.info("RelayScreenSender configured with profile {}", currentProfile);
        }

        void stop() {
            running.set(false);
        }

        /**
         * Update the active profile for this relay sender.
         * Used when user changes quality via GUI during an active session.
         */
        void updateProfile(ScreenQualityProfile newProfile) {
            ScreenQualityProfile oldProfile = this.currentProfile;
            this.currentProfile = newProfile;
            logger.info("RelayScreenSender profile updated: {} -> {}", oldProfile, newProfile);
        }

        @Override
        public void run() {
            if (screenCapture == null) {
                logger.warn("ScreenCapture is null, stopping relay sender");
                return;
            }
            final AdaptiveAverager frameSizeAvg = new AdaptiveAverager(20);
            final AdaptiveAverager captureAvg = new AdaptiveAverager(20);
            final AdaptiveAverager sendAvg = new AdaptiveAverager(20);
            final AdaptiveAverager totalAvg = new AdaptiveAverager(20);

            logger.info("RelayScreenSender started for targetPeerId={}, targetFps={}, targetBitrate={}bps",
                targetPeerId, currentProfile.getTargetFps(), currentProfile.getTargetBitrateBitsPerSec());
            
            long fpsWindowStart = System.currentTimeMillis();
            int framesInWindow = 0;
            long lastStatsLog = System.currentTimeMillis();
            long lastDowngradeCheck = System.currentTimeMillis();
            long downgradeWindowStart = System.currentTimeMillis();
            int downgradeFramesInWindow = 0;

            while (running.get()) {
                ScreenQualityProfile profile = currentProfile; // Read once per loop
                double maxBytesPerSec = profile.getTargetBitrateBitsPerSec() / 8.0;
                
                long loopStart = System.currentTimeMillis();
                long frameSeq = relayFrameSequence.incrementAndGet();
                byte[] imageBytes = screenCapture.captureScreenForRelay(profile);
                long afterCapture = System.currentTimeMillis();
                long captureMs = afterCapture - loopStart;

                if (imageBytes == null || imageBytes.length == 0) {
                    logger.warn("RelayScreenSender: capture returned null/empty frame");
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
                downgradeFramesInWindow++;

                // Periodic stats logging (every 2 seconds)
                long now = System.currentTimeMillis();
                if (now - lastStatsLog >= 2000) {
                    double fps = framesInWindow * 1000.0 / Math.max(1, now - fpsWindowStart);
                    double avgBytes = frameSizeAvg.getAverage();
                    double adaptiveFps = Math.min(profile.getTargetFps(), maxBytesPerSec / Math.max(1.0, avgBytes));
                    adaptiveFps = Math.max(1.0, adaptiveFps);
                    double estMbps = (avgBytes * 8.0 * fps) / 1_000_000.0;
                    double targetMbps = profile.getTargetBitrateBitsPerSec() / 1_000_000.0;
                    
                    logger.info("Relay stats: fps={}, adaptiveFps={}, captureAvg={}ms, sendAvg={}ms, totalAvg={}ms, avgFrameKB={}, profile={}, targetMbps={}, estMbps={}",
                        String.format("%.1f", fps), String.format("%.1f", adaptiveFps),
                        String.format("%.1f", captureAvg.getAverage()), String.format("%.1f", sendAvg.getAverage()),
                        String.format("%.1f", totalAvg.getAverage()), String.format("%.1f", avgBytes / 1024.0),
                        profile.name(), String.format("%.2f", targetMbps), String.format("%.2f", estMbps));
                    
                    lastStatsLog = now;
                    fpsWindowStart = now;
                    framesInWindow = 0;
                }

                // Auto-downgrade check (every 3-5 seconds, allows multiple downgrades)
                if (now - lastDowngradeCheck >= 4000) { // Check every 4 seconds
                    double recentFps = downgradeFramesInWindow * 1000.0 / Math.max(1, now - downgradeWindowStart);
                    double recentTotalAvg = totalAvg.getAverage();
                    double avgFrameKB = frameSizeAvg.getAverage() / 1024.0;
                    
                    ScreenQualityProfile newProfile = null;
                    String reason = null;
                    
                    // LAN_HIGH -> WAN_SAFE: if FPS < 8 or avg frame time > 250ms
                    if (profile == ScreenQualityProfile.LAN_HIGH) {
                        if (recentFps < 8.0 || recentTotalAvg > 250.0) {
                            newProfile = ScreenQualityProfile.WAN_SAFE;
                            reason = String.format("fps=%.1f < 8 OR totalAvg=%.1fms > 250ms", recentFps, recentTotalAvg);
                        }
                    }
                    // WAN_SAFE -> WAN_ULTRA: if FPS < 4 or avg frame time > 400ms
                    else if (profile == ScreenQualityProfile.WAN_SAFE) {
                        if (recentFps < 4.0 || recentTotalAvg > 400.0) {
                            newProfile = ScreenQualityProfile.WAN_ULTRA;
                            reason = String.format("fps=%.1f < 4 OR totalAvg=%.1fms > 400ms", recentFps, recentTotalAvg);
                        }
                    }
                    
                    if (newProfile != null) {
                        logger.warn("Auto-downgrade: {} -> {} ({}, avgFrameKB={})",
                            profile.name(), newProfile.name(), reason, String.format("%.1f", avgFrameKB));
                        currentProfile = newProfile;
                        logger.info("RelayScreenSender profile downgraded to {} (will use for next frames)", newProfile.name());
                    }
                    
                    lastDowngradeCheck = now;
                    downgradeWindowStart = now;
                    downgradeFramesInWindow = 0;
                }

                // Adaptive FPS calculation
                // Clamp adaptive FPS between 2.0 (minimum usable) and profile targetFps
                double avgBytes = frameSizeAvg.getAverage();
                if (avgBytes <= 0) {
                    avgBytes = imageBytes.length;
                }
                double adaptiveFps = Math.min(profile.getTargetFps(), maxBytesPerSec / Math.max(1.0, avgBytes));
                adaptiveFps = Math.max(2.0, adaptiveFps); // Minimum 2 FPS for usability
                long dynamicIntervalMs = Math.max(1L, (long) (1000.0 / adaptiveFps));

                if (totalMs > dynamicIntervalMs + 10) {
                    logger.debug("Relay pipeline slower than target: total={}ms target={}ms (frameSeq={})",
                        totalMs, dynamicIntervalMs, frameSeq);
                }

                long sleepMs = dynamicIntervalMs - totalMs;
                if (sleepMs > 0) {
                    sleepQuietly(sleepMs);
                }
            }
            logger.info("Relay screen sender stopped for {}", targetPeerId);
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

    /**
     * Keep connection alive for relay mode
     * Monitors WebSocket health and auto-reconnects if needed
     */
    private void keepConnectionAliveForRelay(String targetPeerId) {
        logger.info("=== RELAY KEEP-ALIVE STARTED ===");
        
        // Stop existing monitor for this peer if any
        ScheduledExecutorService existingMonitor = relayMonitors.remove(targetPeerId);
        if (existingMonitor != null) {
            existingMonitor.shutdown();
            logger.info("Stopped existing relay monitor for {}", targetPeerId);
        }
        
        // Create new monitor
        ScheduledExecutorService relayMonitor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Relay-Monitor-" + targetPeerId);
            t.setDaemon(true);
            return t;
        });
        
        relayMonitor.scheduleAtFixedRate(() -> {
            try {
                // Check if WebSocket still connected
                if (!idServerClient.isConnected()) {
                    logger.error("CRITICAL: WebSocket disconnected in relay mode!");
                    logger.info("Attempting to restore WebSocket...");
                    
                    // Try to reconnect
                    idServerClient.reconnectWebSocket(myPeerId, myPassword, myIpAddress, myP2PPort);
                    
                    logger.info("WebSocket reconnection attempted");
                } else {
                    logger.debug("Relay keep-alive: WebSocket OK");
                }
            } catch (Exception e) {
                logger.error("Relay keep-alive error: {}", e.getMessage(), e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        
        // Store monitor for cleanup
        relayMonitors.put(targetPeerId, relayMonitor);
        logger.info("=== RELAY KEEP-ALIVE RUNNING (5s check interval) ===");
    }

    /**
     * Stop all relay monitors
     */
    private void stopAllRelayMonitors() {
        logger.info("Stopping all relay monitors (count: {})", relayMonitors.size());
        for (ScheduledExecutorService monitor : relayMonitors.values()) {
            if (monitor != null) {
                monitor.shutdown();
            }
        }
        relayMonitors.clear();
        logger.info("All relay monitors stopped");
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

    public void shutdown() {
        // DIAGNOSTIC: Log shutdown reason and current state
        String reason = "unknown";
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack.length > 2) {
                StackTraceElement caller = stack[2];
                reason = caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
            }
        } catch (Exception e) {
            // Ignore
        }
        
        logger.info("SHUTDOWN TRIGGERED: reason={}, isController={}, controllerRelayMode={}, controlledRelayMode={}, relayTargetPeerId={}",
            reason, isController, controllerRelayMode, controlledRelayMode, relayTargetPeerId);
        logger.info("Shutting down application");
        
        if (p2pClient != null) {
            p2pClient.disconnect();
        }
        
        if (p2pServer != null) {
            p2pServer.stop();
        }
        
        // Stop all relay monitors
        stopAllRelayMonitors();

        if (relayFrameWorker != null) {
            relayFrameWorker.interrupt();
        }
        relayFrameQueue.clear();
        
        if (idServerClient != null) {
            try {
                idServerClient.close();
            } catch (IOException e) {
                logger.error("Error closing ID Server client", e);
            }
        }
        
        executorService.shutdown();
    }

    public static void main(String[] args) {
        Logger rootLogger = LoggerFactory.getLogger(P2PClientApp.class);
        // DIAGNOSTIC: Set global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            rootLogger.error("UNCAUGHT EXCEPTION on thread {}: {}", thread.getName(), exception.getMessage(), exception);
            // Don't call System.exit here - let the exception propagate or be handled
        });
        
        // DIAGNOSTIC: Set AWT exception handler
        System.setProperty("sun.awt.exception.handler", P2PClientApp.class.getName());
        
        ScreenQualityProfile profile = resolveProfileFromArgs(args);
        rootLogger.info("CLI selected screen quality profile: {}", profile);
        P2PClientApp app = new P2PClientApp(profile);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
    }
    
    private static ScreenQualityProfile resolveProfileFromArgs(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--quality=")) {
                    String value = arg.substring("--quality=".length());
                    return ScreenQualityProfile.fromCliArg(value);
                }
            }
        }
        return ScreenQualityProfile.defaultProfile();
    }
}

