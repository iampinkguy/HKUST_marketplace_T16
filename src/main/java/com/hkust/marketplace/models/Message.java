package com.hkust.marketplace.models;

import java.time.LocalDateTime;

public class Message {
    private LocalDateTime timestamp;
    private String sender_id;
    private String sender_role;
    private String content;

    public Message() {
        this.timestamp = LocalDateTime.now();
    }

    public Message(String sender_id, String sender_role, String content) {
        this();
        this.sender_id = sender_id;
        this.sender_role = sender_role;
        this.content = content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSender_id() {
        return sender_id;
    }

    public void setSender_id(String sender_id) {
        this.sender_id = sender_id;
    }

    public String getSender_role() {
        return sender_role;
    }

    public void setSender_role(String sender_role) {
        this.sender_role = sender_role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
