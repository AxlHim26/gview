package com.p2pclient.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines fixed quality profiles for screen capture/encode behaviour.
 * 
 * Profile selection guide:
 * - LAN_HIGH: Use only on local network (LAN). High quality, high FPS, high bitrate.
 * - WAN_SAFE: Default for general internet use. Balanced quality/performance for typical WAN (1-3 Mbps).
 * - WAN_ULTRA: For very slow or unstable networks. Lowest quality but most stable.
 * - LOW/MEDIUM/HIGH: Legacy profiles, kept for compatibility.
 */
public enum ScreenQualityProfile {
    LOW(960, 540, 0.30f, 15, 3_000_000),
    MEDIUM(1280, 720, 0.35f, 20, 6_400_000),
    HIGH(1280, 720, 0.35f, 25, 8_000_000),
    /**
     * WAN_SAFE: Optimized for typical internet connections (1-3 Mbps upload).
     * Balanced quality and performance. Target: 4-7 FPS, ~25-30 KB JPEG, ~200-400ms latency.
     * Quality 0.30 provides readable text while keeping frame size manageable.
     */
    WAN_SAFE(960, 540, 0.30f, 6, 1_200_000),
    /**
     * WAN_ULTRA: For very slow or unstable networks (<1 Mbps).
     * Minimal quality but maximum stability. Target: 3-5 FPS, ~18-22 KB JPEG.
     * Quality 0.25 still readable for simple tasks (buttons, menus, basic text).
     */
    WAN_ULTRA(800, 450, 0.25f, 5, 800_000),
    /**
     * LAN_HIGH: For local network only. High quality, high FPS.
     * Will auto-downgrade to WAN_SAFE if network cannot sustain it.
     */
    // Ultra/highest profile for LAN or fast tailnet. 4K@30fps, higher JPEG quality and bitrate.
    LAN_HIGH(3840, 2160, 0.75f, 30, 28_000_000);

    private static final Logger logger = LoggerFactory.getLogger(ScreenQualityProfile.class);

    private final int maxWidth;
    private final int maxHeight;
    private final float jpegQuality;
    private final int targetFps;
    private final int targetBitrateBitsPerSec;

    ScreenQualityProfile(int maxWidth, int maxHeight, float jpegQuality, int targetFps, int targetBitrateBitsPerSec) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.jpegQuality = jpegQuality;
        this.targetFps = targetFps;
        this.targetBitrateBitsPerSec = targetBitrateBitsPerSec;
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public int getTargetFps() {
        return targetFps;
    }

    public int getTargetBitrateBitsPerSec() {
        return targetBitrateBitsPerSec;
    }

    public static ScreenQualityProfile defaultProfile() {
        // Single high-quality mode per user request
        return LAN_HIGH;
    }

    public static ScreenQualityProfile fromCliArg(String arg) {
        if (arg == null || arg.isBlank()) {
            return defaultProfile();
        }
        // Normalize: remove spaces, convert to lowercase, replace hyphens with underscores
        String normalized = arg.trim().toLowerCase().replace("-", "_");
        
        ScreenQualityProfile result;
        
        // Map aliases to actual enum values (handle both hyphen and underscore variants)
        switch (normalized) {
            case "wan_safe":
            case "wan":
                result = WAN_SAFE;
                break;
            case "wan_ultra":
            case "ultra":
                result = WAN_ULTRA;
                break;
            case "lan_high":
            case "lan":
                result = LAN_HIGH;
                break;
            case "low":
                result = LOW;
                break;
            case "medium":
                result = MEDIUM;
                break;
            case "high":
                result = HIGH;
                break;
            default:
                // Try direct enum value match
                try {
                    result = ScreenQualityProfile.valueOf(normalized.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    logger.warn("Unknown quality profile '{}', falling back to {}", arg, defaultProfile());
                    return defaultProfile();
                }
        }
        
        // Log friendly mapping message if input was different from canonical name
        if (!arg.trim().equalsIgnoreCase(result.name())) {
            logger.info("CLI quality '{}' mapped to {}", arg, result);
        }
        
        return result;
    }

    @Override
    public String toString() {
        return String.format("%s(%dx%d, quality=%.2f, targetFps=%d, targetBitrate=%d bps)",
            name(), maxWidth, maxHeight, jpegQuality, targetFps, targetBitrateBitsPerSec);
    }
}
