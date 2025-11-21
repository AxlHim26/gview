package com.p2pclient.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class LoginPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(LoginPanel.class);
    
    private JTextField passwordField;
    private JTextField peerIdField;
    private JButton registerButton;
    private JButton connectButton;
    private JLabel statusLabel;
    private JLabel peerIdLabel;

    public interface LoginListener {
        void onRegister(String password);
        void onConnect(String peerId, String password);
    }

    private LoginListener listener;

    public LoginPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Title
        JLabel titleLabel = new JLabel("Welcome to P2P Remote Desktop", JLabel.CENTER);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
        titleLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 30, 0));
        add(titleLabel, BorderLayout.NORTH);

        // Main content panel
        JPanel contentPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Password field for registration
        gbc.gridx = 0;
        gbc.gridy = 0;
        contentPanel.add(new JLabel("Password:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        passwordField = new JPasswordField(20);
        contentPanel.add(passwordField, gbc);

        // Register button
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        registerButton = new JButton("Register New Peer");
        registerButton.addActionListener(e -> {
            String password = passwordField.getText();
            if (password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a password", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (listener != null) {
                listener.onRegister(password);
            }
        });
        contentPanel.add(registerButton, gbc);

        // Separator
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        contentPanel.add(new JSeparator(), gbc);

        // Connect with existing ID section
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        contentPanel.add(new JLabel("Peer ID:"), gbc);
        
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        peerIdField = new JTextField(20);
        peerIdField.setToolTipText("Format: XXX-XXX-XXX");
        contentPanel.add(peerIdField, gbc);

        // Connect button
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        connectButton = new JButton("Connect with Existing ID");
        connectButton.addActionListener(e -> {
            String peerId = peerIdField.getText().trim();
            String password = passwordField.getText();
            
            if (peerId.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a peer ID", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter a password", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (listener != null) {
                listener.onConnect(peerId, password);
            }
        });
        contentPanel.add(connectButton, gbc);

        // Status label
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        statusLabel = new JLabel(" ", JLabel.CENTER);
        statusLabel.setForeground(Color.BLUE);
        contentPanel.add(statusLabel, gbc);

        // Peer ID display (after registration)
        gbc.gridy = 4;
        peerIdLabel = new JLabel(" ", JLabel.CENTER);
        peerIdLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        peerIdLabel.setForeground(Color.GREEN);
        contentPanel.add(peerIdLabel, gbc);

        add(contentPanel, BorderLayout.CENTER);
    }

    public void setListener(LoginListener listener) {
        this.listener = listener;
    }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            statusLabel.setForeground(Color.BLUE);
        });
    }

    public void setError(String error) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(error);
            statusLabel.setForeground(Color.RED);
        });
    }

    public void setPeerId(String peerId) {
        SwingUtilities.invokeLater(() -> {
            peerIdLabel.setText("Your Peer ID: " + peerId);
            peerIdLabel.setForeground(Color.GREEN);
        });
    }

    public void setButtonsEnabled(boolean enabled) {
        SwingUtilities.invokeLater(() -> {
            registerButton.setEnabled(enabled);
            connectButton.setEnabled(enabled);
        });
    }

    public void clearFields() {
        SwingUtilities.invokeLater(() -> {
            passwordField.setText("");
            peerIdField.setText("");
            statusLabel.setText(" ");
            peerIdLabel.setText(" ");
        });
    }
}

