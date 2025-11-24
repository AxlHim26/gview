package com.p2pclient.gui;

import com.p2pclient.model.P2PMessage;
import com.p2pclient.remote.InputForwarder;
import com.p2pclient.remote.ScreenReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;

public class RemoteControlPanel extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(RemoteControlPanel.class);
    
    // CRITICAL: Store image as instance variable with synchronization
    private BufferedImage remoteScreenImage;
    private final Object imageLock = new Object();
    
    private ScreenReceiver screenReceiver;
    private InputForwarder inputForwarder;
    private Dimension remoteScreenSize;
    private int remoteImageWidth;  // Actual image dimensions for coordinate normalization
    private int remoteImageHeight;
    private boolean isController; // true if controlling remote screen
    
    // FPS measurement
    private long renderFpsWindowStart = System.currentTimeMillis();
    private int renderFramesInWindow = 0;
    private long lastRenderFpsLog = System.currentTimeMillis();

    public interface RemoteControlListener {
        void onMouseEvent(P2PMessage message);
        void onKeyboardEvent(P2PMessage message);
        void onDisconnect();
    }

    private RemoteControlListener listener;

    public RemoteControlPanel() {
        // CRITICAL: Set preferred size and background
        setPreferredSize(new Dimension(800, 600));
        setBackground(Color.BLACK);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top panel with controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton disconnectButton = new JButton("Disconnect");
        disconnectButton.addActionListener(e -> {
            if (listener != null) {
                listener.onDisconnect();
            }
        });
        controlPanel.add(disconnectButton);
        
        JLabel infoLabel = new JLabel("Remote Desktop Session Active");
        infoLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        controlPanel.add(infoLabel);
        
        add(controlPanel, BorderLayout.NORTH);

        // CRITICAL: Use custom JPanel for screen display instead of JLabel
        ScreenDisplayPanel screenDisplayPanel = new ScreenDisplayPanel();
        JScrollPane scrollPane = new JScrollPane(screenDisplayPanel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        add(scrollPane, BorderLayout.CENTER);

        // Initialize screen receiver
        screenReceiver = new ScreenReceiver();
        screenReceiver.setListener(image -> {
            // CRITICAL: Update image on EDT
            updateRemoteScreen(image);
        });

        // Setup input forwarding
        try {
            inputForwarder = new InputForwarder();
        } catch (AWTException e) {
            logger.error("Failed to create InputForwarder", e);
            JOptionPane.showMessageDialog(this, "Failed to initialize input forwarding", 
                "Error", JOptionPane.ERROR_MESSAGE);
        }

        // Setup mouse and keyboard listeners on screen display panel
        setupInputListeners(screenDisplayPanel);
    }

    /**
     * CRITICAL: Update remote screen image - must be called on EDT
     */
    public void updateRemoteScreen(BufferedImage newImage) {
        synchronized (imageLock) {
            boolean previousWasNull = (this.remoteScreenImage == null);
            this.remoteScreenImage = newImage;
            if (newImage != null) {
                remoteScreenSize = new Dimension(newImage.getWidth(), newImage.getHeight());
                remoteImageWidth = newImage.getWidth();
                remoteImageHeight = newImage.getHeight();
                logger.debug("updateRemoteScreen: rendering {}x{} (previous image null? {})",
                    newImage.getWidth(), newImage.getHeight(), previousWasNull);
                
                // Update input forwarder with actual image dimensions for coordinate normalization
                if (inputForwarder != null) {
                    SwingUtilities.invokeLater(() -> {
                        inputForwarder.updateRemoteImageSize(remoteImageWidth, remoteImageHeight);
                    });
                }
                
                // FPS measurement - only count when we have a valid image
                long now = System.currentTimeMillis();
                renderFramesInWindow++;
                
                // Log FPS every 2 seconds
                if (now - lastRenderFpsLog >= 2000) {
                    long windowDuration = now - renderFpsWindowStart;
                    if (windowDuration > 0 && renderFramesInWindow > 0) {
                        double fps = renderFramesInWindow * 1000.0 / windowDuration;
                        logger.info("Rendered FPS ≈ {}", String.format("%.1f", fps));
                    } else {
                        logger.debug("Rendered FPS: no frames in window (duration={}ms, count={})", 
                            windowDuration, renderFramesInWindow);
                    }
                    // Reset window
                    renderFpsWindowStart = now;
                    renderFramesInWindow = 0;
                    lastRenderFpsLog = now;
                }
            } else {
                remoteImageWidth = 0;
                remoteImageHeight = 0;
            }
        }
        
        // CRITICAL: Must call repaint() on EDT
        SwingUtilities.invokeLater(() -> repaint());
    }
    
    /**
     * Get actual remote image width for coordinate normalization
     */
    public int getRemoteImageWidth() {
        synchronized (imageLock) {
            return remoteImageWidth;
        }
    }
    
    /**
     * Get actual remote image height for coordinate normalization
     */
    public int getRemoteImageHeight() {
        synchronized (imageLock) {
            return remoteImageHeight;
        }
    }

    public void setListener(RemoteControlListener listener) {
        this.listener = listener;
    }

    public void setController(boolean isController) {
        this.isController = isController;
    }

    public ScreenReceiver getScreenReceiver() {
        return screenReceiver;
    }

    public InputForwarder getInputForwarder() {
        return inputForwarder;
    }

    /**
     * Convert panel coordinates to image coordinates
     * Accounts for image scaling and centering in the panel
     * This method uses the same scaling logic as paintComponent
     */
    private Point panelToImageCoordinates(int panelX, int panelY, Component panel) {
        synchronized (imageLock) {
            if (remoteScreenImage == null || remoteImageWidth <= 0 || remoteImageHeight <= 0) {
                return null; // No valid image
            }
            
            if (panel == null) {
                return null;
            }
            
            int panelWidth = panel.getWidth();
            int panelHeight = panel.getHeight();
            
            // Calculate scaling (same logic as paintComponent)
            double scaleX = (double) panelWidth / remoteImageWidth;
            double scaleY = (double) panelHeight / remoteImageHeight;
            double scale = Math.min(scaleX, scaleY);
            
            int scaledWidth = (int) (remoteImageWidth * scale);
            int scaledHeight = (int) (remoteImageHeight * scale);
            
            // Center offset
            int offsetX = (panelWidth - scaledWidth) / 2;
            int offsetY = (panelHeight - scaledHeight) / 2;
            
            // Convert panel coordinates to image coordinates
            int imageX = (int) Math.round((panelX - offsetX) / scale);
            int imageY = (int) Math.round((panelY - offsetY) / scale);
            
            // Clamp to image bounds
            imageX = Math.max(0, Math.min(remoteImageWidth - 1, imageX));
            imageY = Math.max(0, Math.min(remoteImageHeight - 1, imageY));
            
            return new Point(imageX, imageY);
        }
    }

    private void setupInputListeners(JPanel screenPanel) {
        // Mouse listeners
        screenPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                logger.debug("mousePressed: isController={}, inputForwarder={}, listener={}", 
                    isController, (inputForwarder != null), (listener != null));
                if (isController && inputForwarder != null && listener != null) {
                    // CRITICAL FIX: Allow MOUSE events even when no image exists yet
                    // This enables controlled relay mode to start before first SCREEN arrives
                    Point imageCoords = panelToImageCoordinates(e.getX(), e.getY(), e.getComponent());
                    int mouseX, mouseY;
                    
                    if (imageCoords != null) {
                        // Phase 2: Use actual image coordinates when image is available
                        mouseX = imageCoords.x;
                        mouseY = imageCoords.y;
                    } else {
                        // Phase 1: Before first SCREEN, use panel coordinates directly
                        // InputForwarder will use fallback normalization based on realScreenSize
                        mouseX = e.getX();
                        mouseY = e.getY();
                        logger.debug("MOUSE OUT (no image yet): using panel coordinates ({},{})", mouseX, mouseY);
                    }
                    
                    int button = e.getButton();
                    P2PMessage message = inputForwarder.createMouseClickMessage(mouseX, mouseY, button, true);
                    if (message != null) {
                        listener.onMouseEvent(message);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (isController && inputForwarder != null && listener != null) {
                    // CRITICAL FIX: Allow MOUSE events even when no image exists yet
                    Point imageCoords = panelToImageCoordinates(e.getX(), e.getY(), e.getComponent());
                    int mouseX, mouseY;
                    
                    if (imageCoords != null) {
                        mouseX = imageCoords.x;
                        mouseY = imageCoords.y;
                    } else {
                        mouseX = e.getX();
                        mouseY = e.getY();
                        logger.debug("MOUSE OUT (no image yet): using panel coordinates ({},{})", mouseX, mouseY);
                    }
                    
                    int button = e.getButton();
                    P2PMessage message = inputForwarder.createMouseClickMessage(mouseX, mouseY, button, false);
                    if (message != null) {
                        listener.onMouseEvent(message);
                    }
                }
            }
        });

        screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (isController && inputForwarder != null && listener != null) {
                    // CRITICAL FIX: Allow MOUSE events even when no image exists yet
                    Point imageCoords = panelToImageCoordinates(e.getX(), e.getY(), e.getComponent());
                    int mouseX, mouseY;
                    
                    if (imageCoords != null) {
                        mouseX = imageCoords.x;
                        mouseY = imageCoords.y;
                    } else {
                        mouseX = e.getX();
                        mouseY = e.getY();
                        logger.debug("MOUSE OUT (no image yet): using panel coordinates ({},{})", mouseX, mouseY);
                    }
                    
                    P2PMessage message = inputForwarder.createMouseMoveMessage(mouseX, mouseY);
                    if (message != null) {
                        listener.onMouseEvent(message);
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (isController && inputForwarder != null && listener != null) {
                    // CRITICAL FIX: Allow MOUSE events even when no image exists yet
                    Point imageCoords = panelToImageCoordinates(e.getX(), e.getY(), e.getComponent());
                    int mouseX, mouseY;
                    
                    if (imageCoords != null) {
                        mouseX = imageCoords.x;
                        mouseY = imageCoords.y;
                    } else {
                        mouseX = e.getX();
                        mouseY = e.getY();
                        logger.debug("MOUSE OUT (no image yet): using panel coordinates ({},{})", mouseX, mouseY);
                    }
                    
                    P2PMessage message = inputForwarder.createMouseMoveMessage(mouseX, mouseY);
                    if (message != null) {
                        listener.onMouseEvent(message);
                    }
                }
            }
        });

        // Keyboard listeners
        screenPanel.setFocusable(true);
        screenPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (isController && inputForwarder != null && listener != null) {
                    P2PMessage message = inputForwarder.createKeyboardMessage(
                        e.getKeyCode(), true, e.getModifiersEx());
                    listener.onKeyboardEvent(message);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (isController && inputForwarder != null && listener != null) {
                    P2PMessage message = inputForwarder.createKeyboardMessage(
                        e.getKeyCode(), false, e.getModifiersEx());
                    listener.onKeyboardEvent(message);
                }
            }
        });
    }

    /**
     * CRITICAL: Custom JPanel with proper paintComponent override
     */
    private class ScreenDisplayPanel extends JPanel {
        public ScreenDisplayPanel() {
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(800, 600));
        }

        @Override
        protected void paintComponent(Graphics g) {
            // CRITICAL: Must call super first!
            super.paintComponent(g);
            
            synchronized (imageLock) {
                if (remoteScreenImage != null) {
                    // Draw image scaled to panel size
                    int panelWidth = getWidth();
                    int panelHeight = getHeight();
                    int imageWidth = remoteScreenImage.getWidth();
                    int imageHeight = remoteScreenImage.getHeight();
                    
                    // Calculate scaling to fit while maintaining aspect ratio
                    double scaleX = (double) panelWidth / imageWidth;
                    double scaleY = (double) panelHeight / imageHeight;
                    double scale = Math.min(scaleX, scaleY);
                    
                    int scaledWidth = (int) (imageWidth * scale);
                    int scaledHeight = (int) (imageHeight * scale);
                    
                    // Center the image
                    int x = (panelWidth - scaledWidth) / 2;
                    int y = (panelHeight - scaledHeight) / 2;
                    
                    // CRITICAL: Use ImageObserver parameter (this)
                    g.drawImage(remoteScreenImage, 
                               x, y, 
                               scaledWidth, scaledHeight,
                               this); // ImageObserver parameter
                    
                    logger.trace("Painted remote screen: {}x{} scaled to {}x{} at ({},{})", 
                        imageWidth, imageHeight, scaledWidth, scaledHeight, x, y);
                } else {
                    // Show "Waiting for connection..." text
                    g.setColor(Color.WHITE);
                    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
                    g.setFont(font);
                    FontMetrics fm = g.getFontMetrics();
                    String text = "Waiting for remote screen...";
                    int textWidth = fm.stringWidth(text);
                    int textHeight = fm.getHeight();
                    g.drawString(text, 
                               (getWidth() - textWidth) / 2, 
                               (getHeight() + textHeight) / 2);
                }
            }
        }
    }

    public void displayScreen(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            logger.warn("Received empty screen data");
            return;
        }
        logger.debug("Received screen data: {} bytes", imageData.length);
        screenReceiver.receiveScreenData(imageData);
    }

    public void clearScreen() {
        synchronized (imageLock) {
            remoteScreenImage = null;
            remoteScreenSize = null;
            remoteImageWidth = 0;
            remoteImageHeight = 0;
            // Reset FPS counters
            renderFpsWindowStart = System.currentTimeMillis();
            renderFramesInWindow = 0;
            lastRenderFpsLog = System.currentTimeMillis();
        }
        SwingUtilities.invokeLater(() -> {
            repaint();
        });
    }
}
