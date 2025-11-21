package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class ScreenReceiver {
    private static final Logger logger = LoggerFactory.getLogger(ScreenReceiver.class);

    public interface ScreenUpdateListener {
        void onScreenUpdate(BufferedImage image);
    }

    private ScreenUpdateListener listener;

    public ScreenReceiver() {
    }

    public void setListener(ScreenUpdateListener listener) {
        this.listener = listener;
    }

    /**
     * Process received screen data and notify listener
     */
    public void receiveScreenData(byte[] imageData) {
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
                if (listener != null) {
                    // CRITICAL: Listener will handle EDT update
                    listener.onScreenUpdate(image);
                } else {
                    logger.warn("No listener set for screen updates");
                }
            } else {
                logger.warn("Failed to decode image from bytes");
            }
        } catch (IOException e) {
            logger.error("Error processing screen data", e);
        }
    }
}

