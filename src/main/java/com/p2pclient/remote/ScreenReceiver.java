package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import com.p2pclient.model.P2PMessage;

public class ScreenReceiver {
    private static final Logger logger = LoggerFactory.getLogger(ScreenReceiver.class);

    public interface ScreenUpdateListener {
        void onScreenUpdate(BufferedImage image, P2PMessage metadata);
    }

    private ScreenUpdateListener listener;
    private BufferedImage lastFrame;

    public ScreenReceiver() {
    }

    public void setListener(ScreenUpdateListener listener) {
        this.listener = listener;
    }

    /**
     * Process received screen data and notify listener
     */
    public void receiveScreenData(byte[] imageData) {
        P2PMessage fallback = new P2PMessage(P2PMessage.TYPE_SCREEN, imageData);
        receiveScreenMessage(fallback);
    }

    public void receiveScreenMessage(P2PMessage message) {
        if (message == null) {
            logger.warn("Received null screen message");
            return;
        }
        byte[] imageData = message.getData();
        if (imageData == null || imageData.length == 0) {
            logger.warn("Received empty screen data");
            return;
        }

        logger.debug("Processing screen data: {} bytes", imageData.length);

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
            BufferedImage image = ImageIO.read(bais);
            bais.close();

            if (image != null) {
                logger.debug("Decoded image: {}x{}", image.getWidth(), image.getHeight());
                BufferedImage composed = mergeFrame(image, message);
                if (listener != null) {
                    // CRITICAL: Listener will handle EDT update
                    listener.onScreenUpdate(composed, message);
                }
            } else {
                logger.warn("Failed to decode image from bytes");
            }
        } catch (IOException e) {
            logger.error("Error processing screen data", e);
        }
    }

    private BufferedImage mergeFrame(BufferedImage decoded, P2PMessage message) {
        if (!message.isDeltaFrame()) {
            lastFrame = decoded;
            return decoded;
        }

        int fullWidth = message.getFrameWidth() > 0 ? message.getFrameWidth() : decoded.getWidth();
        int fullHeight = message.getFrameHeight() > 0 ? message.getFrameHeight() : decoded.getHeight();

        BufferedImage baseFrame = lastFrame;
        if (baseFrame == null || baseFrame.getWidth() != fullWidth || baseFrame.getHeight() != fullHeight) {
            baseFrame = new BufferedImage(fullWidth, fullHeight, BufferedImage.TYPE_INT_RGB);
        } else {
            baseFrame = copyImage(baseFrame);
        }

        Graphics2D g2d = baseFrame.createGraphics();
        g2d.drawImage(decoded, message.getRegionX(), message.getRegionY(), null);
        g2d.dispose();

        lastFrame = baseFrame;
        return baseFrame;
    }

    private BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = copy.createGraphics();
        g2d.drawImage(src, 0, 0, null);
        g2d.dispose();
        return copy;
    }
}
