package com.p2pclient.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class DashboardPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(DashboardPanel.class);
    
    private JLabel peerIdLabel;
    private JTextField targetPeerIdField;
    private JPasswordField passwordField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JLabel connectionModeLabel;
    private JButton copyPeerIdButton;
    private JComboBox<String> qualityComboBox;

    public interface DashboardListener {
        void onConnectToPeer(String targetPeerId, String password);
        void onDisconnect();
        void onQualityProfileChanged(String profileName);
    }

    private DashboardListener listener;

    public DashboardPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Top panel - Own peer ID
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new TitledBorder("Your Peer Information"));
        
        JPanel peerIdPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        peerIdPanel.add(new JLabel("Peer ID:"));
        peerIdLabel = new JLabel("XXX-XXX-XXX");
        peerIdLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        peerIdLabel.setForeground(Color.BLUE);
        peerIdPanel.add(peerIdLabel);
        
        copyPeerIdButton = new JButton("Copy");
        copyPeerIdButton.addActionListener(e -> {
            String peerId = peerIdLabel.getText();
            StringSelection selection = new StringSelection(peerId);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, null);
            JOptionPane.showMessageDialog(this, "Peer ID copied to clipboard!", 
                "Success", JOptionPane.INFORMATION_MESSAGE);
        });
        peerIdPanel.add(copyPeerIdButton);
        
        topPanel.add(peerIdPanel, BorderLayout.CENTER);
        
        JPanel statusPanel = new JPanel(new GridLayout(2, 1));
        statusLabel = new JLabel("Status: Online", JLabel.CENTER);
        statusLabel.setForeground(Color.GREEN);
        statusPanel.add(statusLabel);
        connectionModeLabel = new JLabel("Connection Mode: P2P", JLabel.CENTER);
        connectionModeLabel.setForeground(Color.BLUE);
        statusPanel.add(connectionModeLabel);
        topPanel.add(statusPanel, BorderLayout.SOUTH);
        
        add(topPanel, BorderLayout.NORTH);

        // Center panel - Connect to peer
        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setBorder(new TitledBorder("Connect to Another Peer"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        centerPanel.add(new JLabel("Target Peer ID:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        targetPeerIdField = new JTextField(20);
        targetPeerIdField.setToolTipText("Format: XXX-XXX-XXX");
        centerPanel.add(targetPeerIdField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        centerPanel.add(new JLabel("Password:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        passwordField = new JPasswordField(20);
        centerPanel.add(passwordField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        centerPanel.add(new JLabel("Screen Quality:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        qualityComboBox = new JComboBox<>(new String[]{"WAN_SAFE (Default)", "WAN_ULTRA (Slow Network)", "LAN_HIGH (LAN Only)"});
        qualityComboBox.setSelectedIndex(0); // Default to WAN_SAFE
        qualityComboBox.setToolTipText("Select screen quality profile. WAN_SAFE is recommended for internet connections.");
        qualityComboBox.addActionListener(e -> {
            String selected = (String) qualityComboBox.getSelectedItem();
            if (selected != null && listener != null) {
                String profileName = selected.split(" ")[0]; // Extract "WAN_SAFE" from "WAN_SAFE (Default)"
                listener.onQualityProfileChanged(profileName);
            }
        });
        centerPanel.add(qualityComboBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel buttonPanel = new JPanel(new FlowLayout());
        connectButton = new JButton("Connect to Peer");
        connectButton.addActionListener(e -> {
            String targetPeerId = targetPeerIdField.getText().trim();
            String password = new String(passwordField.getPassword());
            
            if (targetPeerId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter target peer ID", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter password", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (listener != null) {
                listener.onConnectToPeer(targetPeerId, password);
            }
        });
        buttonPanel.add(connectButton);
        
        disconnectButton = new JButton("Disconnect");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> {
            if (listener != null) {
                listener.onDisconnect();
            }
        });
        buttonPanel.add(disconnectButton);
        
        centerPanel.add(buttonPanel, gbc);
        
        add(centerPanel, BorderLayout.CENTER);

        // Bottom panel - Connection log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Connection Log"));
        
        logArea = new JTextArea(10, 40);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        
        logPanel.add(scrollPane, BorderLayout.CENTER);
        
        add(logPanel, BorderLayout.SOUTH);
    }

    public void setListener(DashboardListener listener) {
        this.listener = listener;
    }

    public void setPeerId(String peerId) {
        SwingUtilities.invokeLater(() -> {
            peerIdLabel.setText(peerId);
        });
    }

    public void setStatus(String status, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + status);
            statusLabel.setForeground(color);
        });
    }

    public void addLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + java.time.LocalDateTime.now().toString() + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public void setConnectionMode(String mode) {
        SwingUtilities.invokeLater(() -> {
            connectionModeLabel.setText("Connection Mode: " + mode);
            connectionModeLabel.setForeground("Relay".equalsIgnoreCase(mode) ? Color.ORANGE : Color.BLUE);
        });
    }

    public void setConnectButtonEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(enabled);
        });
    }

    public void setDisconnectButtonEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            disconnectButton.setEnabled(enabled);
        });
    }

    public void clearFields() {
        SwingUtilities.invokeLater(() -> {
            targetPeerIdField.setText("");
            passwordField.setText("");
        });
    }
}

