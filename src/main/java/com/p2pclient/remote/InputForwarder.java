package com.p2pclient.remote;

import com.p2pclient.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

public class InputForwarder {
    private static final Logger logger = LoggerFactory.getLogger(InputForwarder.class);
    private static final int NORMALIZATION_SCALE = 10000; // Scale factor for normalized coordinates (0.0-1.0 -> 0-10000)
    
    private final Robot robot;
    private volatile int lastRemoteImageWidth = 0;   // Last known remote image dimensions for normalization
    private volatile int lastRemoteImageHeight = 0;
    private Dimension realScreenSize; // Real screen size (used for fallback normalization and controlled side denormalization)

    public InputForwarder() throws AWTException {
        this.robot = new Robot();
        // Get real screen size for fallback normalization (controller) and denormalization (controlled)
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.realScreenSize = screenSize;
        logger.info("InputForwarder initialized with real screen size: {}x{}", screenSize.width, screenSize.height);
    }

    /**
     * Update remote image size for coordinate normalization (controller side)
     * Called when a new remote image is received
     */
    public void updateRemoteImageSize(int width, int height) {
        this.lastRemoteImageWidth = width;
        this.lastRemoteImageHeight = height;
        logger.info("InputForwarder: remote image size updated to {}x{}", width, height);
    }

    /**
     * Create mouse move message with normalized coordinates (controller side)
     * Uses two-phase normalization:
     * - Phase 1 (before first SCREEN): fallback to local screen size to enable controlled relay mode
     * - Phase 2 (after first SCREEN): accurate normalization using remote image dimensions
     * @param imageX X coordinate in image space (0 to remoteImageWidth-1 or realScreenWidth-1)
     * @param imageY Y coordinate in image space (0 to remoteImageHeight-1 or realScreenHeight-1)
     */
    public P2PMessage createMouseMoveMessage(int imageX, int imageY) {
        int imgW = lastRemoteImageWidth;
        int imgH = lastRemoteImageHeight;
        double normX;
        double normY;
        
        if (imgW > 0 && imgH > 0) {
            // Phase 2: accurate normalization based on actual remote image
            normX = (double) imageX / imgW;
            normY = (double) imageY / imgH;
        } else {
            // Phase 1: before first SCREEN, fall back to local real screen size
            // This allows MOUSE to be sent to trigger controlled relay mode
            normX = (double) imageX / realScreenSize.width;
            normY = (double) imageY / realScreenSize.height;
        }
        
        // Clamp to [0,1]
        normX = Math.max(0.0, Math.min(1.0, normX));
        normY = Math.max(0.0, Math.min(1.0, normY));
        
        // Store as scaled integer (0-10000 represents 0.0-1.0)
        int scaledX = (int) Math.round(normX * NORMALIZATION_SCALE);
        int scaledY = (int) Math.round(normY * NORMALIZATION_SCALE);
        
        if (logger.isTraceEnabled()) {
            logger.trace("MOUSE OUT MOVE: raw=({},{}), norm=({},{}) imgSize={}x{}, fallbackScreen={}x{}",
                imageX, imageY, String.format("%.4f", normX), String.format("%.4f", normY), 
                imgW, imgH, realScreenSize.width, realScreenSize.height);
        }
        
        P2PMessage message = new P2PMessage();
        message.setType(P2PMessage.TYPE_MOUSE);
        message.setMouseX(scaledX);
        message.setMouseY(scaledY);
        message.setMouseButton(0); // 0 indicates MOVE (no button)
        message.setMousePressed(false);
        
        return message;
    }

    /**
     * Create mouse click message with normalized coordinates (controller side)
     * Uses two-phase normalization:
     * - Phase 1 (before first SCREEN): fallback to local screen size to enable controlled relay mode
     * - Phase 2 (after first SCREEN): accurate normalization using remote image dimensions
     * @param imageX X coordinate in image space (0 to remoteImageWidth-1 or realScreenWidth-1)
     * @param imageY Y coordinate in image space (0 to remoteImageHeight-1 or realScreenHeight-1)
     */
    public P2PMessage createMouseClickMessage(int imageX, int imageY, int button, boolean pressed) {
        int imgW = lastRemoteImageWidth;
        int imgH = lastRemoteImageHeight;
        double normX;
        double normY;
        
        if (imgW > 0 && imgH > 0) {
            // Phase 2: accurate normalization based on actual remote image
            normX = (double) imageX / imgW;
            normY = (double) imageY / imgH;
        } else {
            // Phase 1: before first SCREEN, fall back to local real screen size
            // This allows MOUSE to be sent to trigger controlled relay mode
            normX = (double) imageX / realScreenSize.width;
            normY = (double) imageY / realScreenSize.height;
        }
        
        // Clamp to [0,1]
        normX = Math.max(0.0, Math.min(1.0, normX));
        normY = Math.max(0.0, Math.min(1.0, normY));
        
        // Store as scaled integer (0-10000 represents 0.0-1.0)
        int scaledX = (int) Math.round(normX * NORMALIZATION_SCALE);
        int scaledY = (int) Math.round(normY * NORMALIZATION_SCALE);
        
        if (logger.isTraceEnabled()) {
            String action = pressed ? "PRESS" : "RELEASE";
            String buttonName = getButtonName(button);
            logger.trace("MOUSE OUT CLICK: raw=({},{}), norm=({},{}) imgSize={}x{}, button={}, action={}",
                imageX, imageY, String.format("%.4f", normX), String.format("%.4f", normY), 
                imgW, imgH, buttonName, action);
        }
        
        P2PMessage message = new P2PMessage();
        message.setType(P2PMessage.TYPE_MOUSE);
        message.setMouseX(scaledX);
        message.setMouseY(scaledY);
        message.setMouseButton(button);
        message.setMousePressed(pressed);
        
        return message;
    }

    /**
     * Create keyboard message
     */
    public P2PMessage createKeyboardMessage(int keyCode, boolean pressed, int modifiers) {
        P2PMessage message = new P2PMessage();
        message.setType(P2PMessage.TYPE_KEYBOARD);
        message.setKeyCode(keyCode);
        message.setKeyPressed(pressed);
        message.setKeyModifiers(modifiers);
        
        return message;
    }

    /**
     * Execute mouse move on remote machine (controlled side)
     * Denormalizes coordinates from normalized (0-10000) to real screen coordinates
     */
    public void executeMouseMove(P2PMessage message) {
        if (!P2PMessage.TYPE_MOUSE.equals(message.getType())) {
            return;
        }

        try {
            // Denormalize: convert scaled int (0-10000) back to normalized (0.0-1.0), then scale to real screen
            double normX = message.getMouseX() / (double) NORMALIZATION_SCALE;
            double normY = message.getMouseY() / (double) NORMALIZATION_SCALE;
            
            // Clamp to [0,1] to avoid any rounding issues
            normX = Math.max(0.0, Math.min(1.0, normX));
            normY = Math.max(0.0, Math.min(1.0, normY));
            
            // Convert to real screen coordinates
            int targetX = (int) Math.round(normX * realScreenSize.width);
            int targetY = (int) Math.round(normY * realScreenSize.height);
            
            if (logger.isTraceEnabled()) {
                logger.trace("MOUSE IN MOVE: norm=({},{}) -> target=({},{}) screen={}x{}",
                    String.format("%.4f", normX), String.format("%.4f", normY), targetX, targetY, realScreenSize.width, realScreenSize.height);
            }
            
            robot.mouseMove(targetX, targetY);
        } catch (Exception e) {
            logger.error("Error executing mouse move", e);
        }
    }

    /**
     * Execute mouse click on remote machine (controlled side)
     * Denormalizes coordinates from normalized (0-10000) to real screen coordinates
     */
    public void executeMouseClick(P2PMessage message) {
        if (!P2PMessage.TYPE_MOUSE.equals(message.getType())) {
            return;
        }

        try {
            // Denormalize: convert scaled int (0-10000) back to normalized (0.0-1.0), then scale to real screen
            double normX = message.getMouseX() / (double) NORMALIZATION_SCALE;
            double normY = message.getMouseY() / (double) NORMALIZATION_SCALE;
            
            // Clamp to [0,1] to avoid any rounding issues
            normX = Math.max(0.0, Math.min(1.0, normX));
            normY = Math.max(0.0, Math.min(1.0, normY));
            
            // Convert to real screen coordinates
            int targetX = (int) Math.round(normX * realScreenSize.width);
            int targetY = (int) Math.round(normY * realScreenSize.height);
            
            int button = message.getMouseButton();
            
            if (logger.isTraceEnabled()) {
                String buttonName = getButtonName(button);
                String action = message.isMousePressed() ? "PRESS" : "RELEASE";
                logger.trace("MOUSE IN CLICK: button={}, action={}, target=({},{}), screen={}x{}",
                    buttonName, action, targetX, targetY, realScreenSize.width, realScreenSize.height);
            }
            
            // Always move to target first
            robot.mouseMove(targetX, targetY);
            
            int buttonMask = getButtonMask(button);
            
            if (message.isMousePressed()) {
                robot.mousePress(buttonMask);
            } else {
                robot.mouseRelease(buttonMask);
            }
        } catch (Exception e) {
            logger.error("Error executing mouse click", e);
        }
    }

    /**
     * Execute keyboard event on remote machine
     */
    public void executeKeyboard(P2PMessage message) {
        if (!P2PMessage.TYPE_KEYBOARD.equals(message.getType())) {
            return;
        }

        try {
            // Handle modifiers first
            if ((message.getKeyModifiers() & InputEvent.SHIFT_DOWN_MASK) != 0) {
                if (message.isKeyPressed()) {
                    robot.keyPress(KeyEvent.VK_SHIFT);
                } else {
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                }
            }
            if ((message.getKeyModifiers() & InputEvent.CTRL_DOWN_MASK) != 0) {
                if (message.isKeyPressed()) {
                    robot.keyPress(KeyEvent.VK_CONTROL);
                } else {
                    robot.keyRelease(KeyEvent.VK_CONTROL);
                }
            }
            if ((message.getKeyModifiers() & InputEvent.ALT_DOWN_MASK) != 0) {
                if (message.isKeyPressed()) {
                    robot.keyPress(KeyEvent.VK_ALT);
                } else {
                    robot.keyRelease(KeyEvent.VK_ALT);
                }
            }

            // Handle main key
            if (message.isKeyPressed()) {
                robot.keyPress(message.getKeyCode());
            } else {
                robot.keyRelease(message.getKeyCode());
            }
        } catch (Exception e) {
            logger.error("Error executing keyboard event", e);
        }
    }


    /**
     * Convert button number to AWT button mask
     */
    private int getButtonMask(int button) {
        switch (button) {
            case 1:
                return InputEvent.BUTTON1_DOWN_MASK; // LEFT
            case 2:
                return InputEvent.BUTTON2_DOWN_MASK; // MIDDLE
            case 3:
                return InputEvent.BUTTON3_DOWN_MASK; // RIGHT
            default:
                return InputEvent.BUTTON1_DOWN_MASK; // Default to LEFT
        }
    }
    
    /**
     * Get button name for logging
     */
    private String getButtonName(int button) {
        switch (button) {
            case 1:
                return "LEFT";
            case 2:
                return "MIDDLE";
            case 3:
                return "RIGHT";
            case 0:
                return "NONE";
            default:
                return "UNKNOWN(" + button + ")";
        }
    }
}
