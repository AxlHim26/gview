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
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenCapture {
    private static final Logger logger = LoggerFactory.getLogger(ScreenCapture.class);
    private static final float MIN_QUALITY = 0.05f;
    private static final float MAX_QUALITY = 1.0f;
    private static final AtomicInteger CAPTURE_COUNTER = new AtomicInteger();
    
    private final Robot robot;
    private final Rectangle screenRect;
    private final ScreenQualityProfile profile;

    public ScreenCapture(ScreenQualityProfile profile) throws AWTException {
        this.profile = profile == null ? ScreenQualityProfile.defaultProfile() : profile;
        this.robot = new Robot();
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        this.screenRect = new Rectangle(screenSize);
        logger.info("ScreenCapture initialized with profile {}", this.profile);
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
        ScreenCaptureResult result = captureFrameInternal(null, null, null);
        return result != null ? result.getJpegBytes() : null;
    }

    /**
     * Capture screen with custom quality (0.0 - 1.0)
     */
    public byte[] captureScreenAsBytes(float quality) {
        ScreenCaptureResult result = captureFrameInternal(quality, null, null);
        return result != null ? result.getJpegBytes() : null;
    }

    /**
     * Capture screen optimized for relay mode using the active quality profile unless overridden.
     */
    public byte[] captureScreenForRelay() {
        return captureScreenForRelay(null);
    }

    public byte[] captureScreenForRelay(ScreenQualityProfile relayProfile) {
        ScreenQualityProfile effective = relayProfile != null ? relayProfile : this.profile;
        // Use profile quality directly - profiles are already tuned for relay use
        // No quality bump needed as profiles are designed for WAN constraints
        ScreenCaptureResult result = captureFrameInternal(
            effective.getJpegQuality(), effective.getMaxWidth(), effective.getMaxHeight());
        return result != null ? result.getJpegBytes() : null;
    }

    /**
     * Capture a screen frame and return the processed image and JPEG bytes.
     * The processed image is already scaled according to the profile/overrides.
     */
    public ScreenCaptureResult captureFrameWithImage() {
        return captureFrameInternal(null, null, null);
    }

    public ScreenCaptureResult captureFrameWithImage(Float overrideQuality, Integer overrideMaxWidth, Integer overrideMaxHeight) {
        return captureFrameInternal(overrideQuality, overrideMaxWidth, overrideMaxHeight);
    }

    private ScreenCaptureResult captureFrameInternal(Float overrideQuality, Integer overrideMaxWidth, Integer overrideMaxHeight) {
        try {
            BufferedImage screenCapture = captureScreen();
            if (screenCapture == null) {
                logger.warn("Screen capture returned null");
                return null;
            }

            int srcWidth = screenCapture.getWidth();
            int srcHeight = screenCapture.getHeight();

            // Use override dimensions if provided, otherwise use configured defaults
            int effectiveMaxWidth = overrideMaxWidth != null ? overrideMaxWidth : profile.getMaxWidth();
            int effectiveMaxHeight = overrideMaxHeight != null ? overrideMaxHeight : profile.getMaxHeight();

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

            float quality = overrideQuality != null ? clampQuality(overrideQuality) : profile.getJpegQuality();
            long encodeStart = System.nanoTime();
            byte[] jpegBytes = encodeJpeg(processedImage, quality);
            long encodeMillis = (System.nanoTime() - encodeStart) / 1_000_000L;
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

            return new ScreenCaptureResult(
                processedImage,
                jpegBytes,
                processedImage.getWidth(),
                processedImage.getHeight(),
                quality,
                System.currentTimeMillis(),
                encodeMillis
            );
        } catch (Exception e) {
            logger.error("Error capturing or compressing screen", e);
            return null;
        }
    }

    private double calculateScale(int width, int height) {
        return calculateScale(width, height, profile.getMaxWidth(), profile.getMaxHeight());
    }

    private double calculateScale(int width, int height, int maxW, int maxH) {
        double widthScale = maxW > 0 ? (double) maxW / width : 1.0d;
        double heightScale = maxH > 0 ? (double) maxH / height : 1.0d;
        return Math.min(1.0d, Math.min(widthScale, heightScale));
    }

    byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
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

    private float clampQuality(float quality) {
        return Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, quality));
    }

    public Robot getRobot() {
        return robot;
    }

    public Rectangle getScreenRect() {
        return screenRect;
    }

    public ScreenQualityProfile getProfile() {
        return profile;
    }
}
