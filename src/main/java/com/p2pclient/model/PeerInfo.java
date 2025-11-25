package com.p2pclient.model;

import java.io.Serializable;

public class PeerInfo implements Serializable {
    private String peerId;
    private String ipAddress;
    private Integer port;
    private Boolean online;

    public PeerInfo() {
    }

    public PeerInfo(String peerId, String ipAddress, Integer port, Boolean online) {
        this.peerId = peerId;
        this.ipAddress = ipAddress;
        this.port = port;
        this.online = online;
    }

    public String getPeerId() {
        return peerId;
    }

    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    @Override
    public String toString() {
        return "PeerInfo{" +
                "peerId='" + peerId + '\'' +
                ", ipAddress='" + ipAddress + '\'' +
                ", port=" + port +
                ", online=" + online +
                '}';
    }
}

