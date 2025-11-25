package com.p2pclient.remote;

import java.awt.image.BufferedImage;

/**
 * Immutable container for a captured screen frame including the scaled image
 * used for encoding. Carries encode timing to feed adaptive throttling.
 */
public class ScreenCaptureResult {
    private final BufferedImage processedImage;
    private final byte[] jpegBytes;
    private final int width;
    private final int height;
    private final float quality;
    private final long captureTimestamp;
    private final long encodeMillis;

    public ScreenCaptureResult(BufferedImage processedImage, byte[] jpegBytes, int width, int height,
                               float quality, long captureTimestamp, long encodeMillis) {
        this.processedImage = processedImage;
        this.jpegBytes = jpegBytes;
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.captureTimestamp = captureTimestamp;
        this.encodeMillis = encodeMillis;
    }

    public BufferedImage getProcessedImage() {
        return processedImage;
    }

    public byte[] getJpegBytes() {
        return jpegBytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float getQuality() {
        return quality;
    }

    public long getCaptureTimestamp() {
        return captureTimestamp;
    }

    public long getEncodeMillis() {
        return encodeMillis;
    }
}
