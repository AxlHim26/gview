package com.p2pclient.gui;

import com.p2pclient.model.ConnectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

public class MainFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger(MainFrame.class);
    
    private final CardLayout cardLayout;
    private final JPanel mainPanel;
    private final JLabel statusLabel;
    private LoginPanel loginPanel;
    private DashboardPanel dashboardPanel;
    private RemoteControlPanel remoteControlPanel;
    
    // Shutdown handler - only called when user explicitly closes window
    private ShutdownHandler shutdownHandler;
    
    public interface ShutdownHandler {
        void onShutdown();
    }
    
    public void setShutdownHandler(ShutdownHandler handler) {
        this.shutdownHandler = handler;
    }

    public MainFrame() {
        setTitle("P2P Remote Desktop Client");
        // CRITICAL FIX: Use DO_NOTHING_ON_CLOSE and handle shutdown explicitly
        // This prevents network errors or disconnectP2P from accidentally closing the window
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);
        setResizable(true);
        
        // DIAGNOSTIC: Add window listener to handle explicit user window close
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                logger.info("WINDOW CLOSING EVENT: User explicitly closing window - this is the ONLY valid shutdown path");
                // Only shutdown when user explicitly closes the window
                if (shutdownHandler != null) {
                    shutdownHandler.onShutdown();
                }
            }
            
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                logger.info("WINDOW CLOSED EVENT: windowClosed called");
            }
        });

        // Menu bar
        createMenuBar();

        // Main panel with CardLayout
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        // Create panels
        loginPanel = new LoginPanel();
        dashboardPanel = new DashboardPanel();
        remoteControlPanel = new RemoteControlPanel();

        mainPanel.add(loginPanel, "LOGIN");
        mainPanel.add(dashboardPanel, "DASHBOARD");
        mainPanel.add(remoteControlPanel, "REMOTE_CONTROL");

        add(mainPanel, BorderLayout.CENTER);

        // Status bar
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusLabel, BorderLayout.SOUTH);

        // Show login panel initially
        showLoginPanel();

        // Keyboard shortcut
        setupKeyboardShortcuts();
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        
        JMenuItem exitItem = new JMenuItem("Exit", KeyEvent.VK_X);
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        exitItem.addActionListener(e -> {
            logger.info("Exit menu item clicked - shutting down");
            if (shutdownHandler != null) {
                shutdownHandler.onShutdown();
            }
            System.exit(0);
        });
        
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);
        
        setJMenuBar(menuBar);
    }

    private void setupKeyboardShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()), "quit");
        getRootPane().getActionMap().put("quit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logger.info("Quit keyboard shortcut pressed - shutting down");
                if (shutdownHandler != null) {
                    shutdownHandler.onShutdown();
                }
                System.exit(0);
            }
        });
    }

    public void showLoginPanel() {
        cardLayout.show(mainPanel, "LOGIN");
        updateStatus("Ready - Please register or connect");
    }

    public void showDashboardPanel() {
        cardLayout.show(mainPanel, "DASHBOARD");
        updateStatus("Connected to ID Server");
    }

    public void showRemoteControlPanel() {
        cardLayout.show(mainPanel, "REMOTE_CONTROL");
        updateStatus("P2P Connection Active");
    }

    public void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            logger.debug("Status updated: {}", status);
        });
    }

    public void updateStatus(ConnectionStatus status) {
        String statusText;
        switch (status) {
            case CONNECTED:
                statusText = "Connected";
                break;
            case CONNECTING:
                statusText = "Connecting...";
                break;
            case DISCONNECTED:
                statusText = "Disconnected";
                break;
            case ERROR:
                statusText = "Error";
                break;
            default:
                statusText = "Unknown";
        }
        updateStatus(statusText);
    }

    public LoginPanel getLoginPanel() {
        return loginPanel;
    }

    public DashboardPanel getDashboardPanel() {
        return dashboardPanel;
    }

    public RemoteControlPanel getRemoteControlPanel() {
        return remoteControlPanel;
    }
}

