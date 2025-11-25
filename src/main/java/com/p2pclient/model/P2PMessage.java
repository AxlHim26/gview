package com.p2pclient.model;

import java.io.Serializable;

public class P2PMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final String TYPE_SCREEN = "SCREEN";
    public static final String TYPE_MOUSE = "MOUSE";
    public static final String TYPE_KEYBOARD = "KEYBOARD";
    public static final String TYPE_DISCONNECT = "DISCONNECT";
    public static final String TYPE_INPUT_ACK = "ACK";
    
    private String type;
    private byte[] data;
    private long timestamp;
    private long frameSeq;
    private int frameWidth;
    private int frameHeight;
    private boolean deltaFrame;
    private int regionX;
    private int regionY;
    private int regionWidth;
    private int regionHeight;
    private long encodeTimeMs;
    private double senderCpuLoad;
    private boolean lowResourceMode;
    private int targetFpsHint;
    private int targetBitrateKbps;
    private int estimatedBitrateKbps;
    private long ackRefTimestamp;
    private String ackForType;
    private boolean keyFrame;
    private int mouseX;
    private int mouseY;
    private int mouseButton;
    private boolean mousePressed;
    private int keyCode;
    private boolean keyPressed;
    private int keyModifiers;

    public P2PMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public P2PMessage(String type, byte[] data) {
        this.type = type;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getFrameSeq() {
        return frameSeq;
    }

    public void setFrameSeq(long frameSeq) {
        this.frameSeq = frameSeq;
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public void setFrameWidth(int frameWidth) {
        this.frameWidth = frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public void setFrameHeight(int frameHeight) {
        this.frameHeight = frameHeight;
    }

    public boolean isDeltaFrame() {
        return deltaFrame;
    }

    public void setDeltaFrame(boolean deltaFrame) {
        this.deltaFrame = deltaFrame;
    }

    public int getRegionX() {
        return regionX;
    }

    public void setRegionX(int regionX) {
        this.regionX = regionX;
    }

    public int getRegionY() {
        return regionY;
    }

    public void setRegionY(int regionY) {
        this.regionY = regionY;
    }

    public int getRegionWidth() {
        return regionWidth;
    }

    public void setRegionWidth(int regionWidth) {
        this.regionWidth = regionWidth;
    }

    public int getRegionHeight() {
        return regionHeight;
    }

    public void setRegionHeight(int regionHeight) {
        this.regionHeight = regionHeight;
    }

    public long getEncodeTimeMs() {
        return encodeTimeMs;
    }

    public void setEncodeTimeMs(long encodeTimeMs) {
        this.encodeTimeMs = encodeTimeMs;
    }

    public double getSenderCpuLoad() {
        return senderCpuLoad;
    }

    public void setSenderCpuLoad(double senderCpuLoad) {
        this.senderCpuLoad = senderCpuLoad;
    }

    public boolean isLowResourceMode() {
        return lowResourceMode;
    }

    public void setLowResourceMode(boolean lowResourceMode) {
        this.lowResourceMode = lowResourceMode;
    }

    public int getTargetFpsHint() {
        return targetFpsHint;
    }

    public void setTargetFpsHint(int targetFpsHint) {
        this.targetFpsHint = targetFpsHint;
    }

    public int getTargetBitrateKbps() {
        return targetBitrateKbps;
    }

    public void setTargetBitrateKbps(int targetBitrateKbps) {
        this.targetBitrateKbps = targetBitrateKbps;
    }

    public int getEstimatedBitrateKbps() {
        return estimatedBitrateKbps;
    }

    public void setEstimatedBitrateKbps(int estimatedBitrateKbps) {
        this.estimatedBitrateKbps = estimatedBitrateKbps;
    }

    public long getAckRefTimestamp() {
        return ackRefTimestamp;
    }

    public void setAckRefTimestamp(long ackRefTimestamp) {
        this.ackRefTimestamp = ackRefTimestamp;
    }

    public String getAckForType() {
        return ackForType;
    }

    public void setAckForType(String ackForType) {
        this.ackForType = ackForType;
    }

    public boolean isKeyFrame() {
        return keyFrame;
    }

    public void setKeyFrame(boolean keyFrame) {
        this.keyFrame = keyFrame;
    }

    public int getMouseX() {
        return mouseX;
    }

    public void setMouseX(int mouseX) {
        this.mouseX = mouseX;
    }

    public int getMouseY() {
        return mouseY;
    }

    public void setMouseY(int mouseY) {
        this.mouseY = mouseY;
    }

    public int getMouseButton() {
        return mouseButton;
    }

    public void setMouseButton(int mouseButton) {
        this.mouseButton = mouseButton;
    }

    public boolean isMousePressed() {
        return mousePressed;
    }

    public void setMousePressed(boolean mousePressed) {
        this.mousePressed = mousePressed;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public boolean isKeyPressed() {
        return keyPressed;
    }

    public void setKeyPressed(boolean keyPressed) {
        this.keyPressed = keyPressed;
    }

    public int getKeyModifiers() {
        return keyModifiers;
    }

    public void setKeyModifiers(int keyModifiers) {
        this.keyModifiers = keyModifiers;
    }
}
