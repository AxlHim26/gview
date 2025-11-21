package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenCapture {
    private static final Logger logger = LoggerFactory.getLogger(ScreenCapture.class);
    private static final String CONFIG_FILE = "config.properties";
    private static final float MIN_QUALITY = 0.05f;
    private static final float MAX_QUALITY = 1.0f;
    private static final AtomicInteger CAPTURE_COUNTER = new AtomicInteger();
    
    private final Robot robot;
    private final Rectangle screenRect;
    private final int maxWidth;
    private final int maxHeight;
    private final float defaultQuality;

    public ScreenCapture() throws AWTException {
        this.robot = new Robot();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.screenRect = new Rectangle(screenSize);

        Properties props = loadConfig();
        this.maxWidth = parseInt(props.getProperty("screen.capture.maxWidth"), 1024);
        this.maxHeight = parseInt(props.getProperty("screen.capture.maxHeight"), 576);
        this.defaultQuality = clampQuality(parseFloat(props.getProperty("screen.capture.quality"), 0.25f));

        logger.info("ScreenCapture initialized with max {}x{}, quality {}", maxWidth, maxHeight, defaultQuality);
    }

    /**
     * Capture current screen as BufferedImage
     */
    public BufferedImage captureScreen() {
        try {
            return robot.createScreenCapture(screenRect);
        } catch (Exception e) {
            logger.error("Error capturing screen", e);
            return null;
        }
    }

    /**
     * Capture screen and compress to JPEG bytes using configured scaling & quality
     */
    public byte[] captureScreenAsBytes() {
        return captureScreenAsBytesInternal(null);
    }

    /**
     * Capture screen with custom quality (0.0 - 1.0)
     */
    public byte[] captureScreenAsBytes(float quality) {
        return captureScreenAsBytesInternal(quality, null, null);
    }

    /**
     * Capture screen optimized for relay mode (improved quality for better readability)
     * This produces frames typically 50-80KB JPEG, base64Len ~65-110k, well within 512KB STOMP limits
     * Quality increased to 0.55 for sharper text and less blocky UI
     */
    public byte[] captureScreenForRelay() {
        // Use 960x540 (16:9) with quality 0.55 for improved visual quality
        // Resolution kept at 960x540 as safe first step; can increase later if needed
        return captureScreenAsBytesInternal(0.55f, 960, 540);
    }

    private byte[] captureScreenAsBytesInternal(Float overrideQuality) {
        return captureScreenAsBytesInternal(overrideQuality, null, null);
    }

    private byte[] captureScreenAsBytesInternal(Float overrideQuality, Integer overrideMaxWidth, Integer overrideMaxHeight) {
        try {
            BufferedImage screenCapture = captureScreen();
            if (screenCapture == null) {
                logger.warn("Screen capture returned null");
                return null;
            }

            int srcWidth = screenCapture.getWidth();
            int srcHeight = screenCapture.getHeight();

            // Use override dimensions if provided, otherwise use configured defaults
            int effectiveMaxWidth = overrideMaxWidth != null ? overrideMaxWidth : this.maxWidth;
            int effectiveMaxHeight = overrideMaxHeight != null ? overrideMaxHeight : this.maxHeight;

            double scale = calculateScale(srcWidth, srcHeight, effectiveMaxWidth, effectiveMaxHeight);
            BufferedImage processedImage = screenCapture;

            if (scale < 1.0d) {
                int targetWidth = Math.max(1, (int) Math.round(srcWidth * scale));
                int targetHeight = Math.max(1, (int) Math.round(srcHeight * scale));
                // Safety clamp to ensure we don't exceed effective max dimensions
                if (targetWidth > effectiveMaxWidth) {
                    targetWidth = effectiveMaxWidth;
                }
                if (targetHeight > effectiveMaxHeight) {
                    targetHeight = effectiveMaxHeight;
                }
                processedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = processedImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.drawImage(screenCapture, 0, 0, targetWidth, targetHeight, null);
                g2d.dispose();
            }

            float quality = overrideQuality != null ? clampQuality(overrideQuality) : defaultQuality;
            byte[] jpegBytes = encodeJpeg(processedImage, quality);
            if (jpegBytes == null) {
                return null;
            }

            int count = CAPTURE_COUNTER.incrementAndGet();
            boolean isRelay = overrideMaxWidth != null || overrideMaxHeight != null;
            String mode = isRelay ? "RELAY" : "P2P";
            
            // Calculate base64 length for relay mode logging
            int base64Len = isRelay ? (int) Math.ceil(jpegBytes.length * 4.0 / 3.0) : 0;
            
            logger.debug(
                "ScreenCapture [{}]: original={}x{}, scaled={}x{}, scaleFactor={}, jpegSize={} bytes, quality={}",
                mode,
                srcWidth,
                srcHeight,
                processedImage.getWidth(),
                processedImage.getHeight(),
                String.format("%.3f", scale),
                jpegBytes.length,
                quality
            );
            if (count % 10 == 0) {
                if (isRelay) {
                    logger.info("ScreenCapture [RELAY] stats: frame #{}, jpegSize={} bytes, base64Len≈{}, relayResolution={}x{}",
                        count, jpegBytes.length, base64Len, processedImage.getWidth(), processedImage.getHeight());
                } else {
                    logger.info("ScreenCapture [P2P] stats: frame #{}, jpegSize={} bytes", count, jpegBytes.length);
                }
            }

            return jpegBytes;
        } catch (Exception e) {
            logger.error("Error capturing or compressing screen", e);
            return null;
        }
    }

    private double calculateScale(int width, int height) {
        return calculateScale(width, height, this.maxWidth, this.maxHeight);
    }

    private double calculateScale(int width, int height, int maxW, int maxH) {
        double widthScale = maxW > 0 ? (double) maxW / width : 1.0d;
        double heightScale = maxH > 0 ? (double) maxH / height : 1.0d;
        return Math.min(1.0d, Math.min(widthScale, heightScale));
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            logger.error("No JPEG writers available");
            return null;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
            } else {
                logger.warn("Config file {} not found; using defaults", CONFIG_FILE);
            }
        } catch (IOException e) {
            logger.warn("Failed to load config {}, using defaults", CONFIG_FILE, e);
        }
        return props;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private float parseFloat(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private float clampQuality(float quality) {
        return Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, quality));
    }

    public Robot getRobot() {
        return robot;
    }

    public Rectangle getScreenRect() {
        return screenRect;
    }
}

