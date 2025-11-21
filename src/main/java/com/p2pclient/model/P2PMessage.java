package com.p2pclient.model;

import java.io.Serializable;

public class P2PMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public static final String TYPE_SCREEN = "SCREEN";
    public static final String TYPE_MOUSE = "MOUSE";
    public static final String TYPE_KEYBOARD = "KEYBOARD";
    public static final String TYPE_DISCONNECT = "DISCONNECT";
    
    private String type;
    private byte[] data;
    private long timestamp;
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

