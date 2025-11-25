package com.p2pclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelayMessage {

    @JsonProperty("sourcePeerId")
    private String sourcePeerId;

    @JsonProperty("targetPeerId")
    private String targetPeerId;

    @JsonProperty("dataType")
    private String dataType;

    @JsonProperty("base64Data")
    private String base64Data;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("frameSeq")
    private long frameSeq;

    @JsonProperty("sendTimestampMs")
    private long sendTimestampMs;

    public RelayMessage() {
    }

    public RelayMessage(String sourcePeerId, String targetPeerId, String dataType, String base64Data, long timestamp) {
        this.sourcePeerId = sourcePeerId;
        this.targetPeerId = targetPeerId;
        this.dataType = dataType;
        this.base64Data = base64Data;
        this.timestamp = timestamp;
    }

    public String getSourcePeerId() {
        return sourcePeerId;
    }

    public void setSourcePeerId(String sourcePeerId) {
        this.sourcePeerId = sourcePeerId;
    }

    public String getTargetPeerId() {
        return targetPeerId;
    }

    public void setTargetPeerId(String targetPeerId) {
        this.targetPeerId = targetPeerId;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getBase64Data() {
        return base64Data;
    }

    public void setBase64Data(String base64Data) {
        this.base64Data = base64Data;
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

    public long getSendTimestampMs() {
        return sendTimestampMs;
    }

    public void setSendTimestampMs(long sendTimestampMs) {
        this.sendTimestampMs = sendTimestampMs;
    }
}

