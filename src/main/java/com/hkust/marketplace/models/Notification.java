package com.hkust.marketplace.models;

import java.time.LocalDateTime;

public class Notification {
    private String id;
    private String username; // recipient
    private String sender;
    private String title;
    private String message;
    private String category; // Offer, Chat, System, Rating, etc.
    private LocalDateTime timestamp;
    private boolean read;
    private Integer requestId;
    private Integer listingId;
    private String targetUsername;

    public Notification() {
        this.timestamp = LocalDateTime.now();
        this.read = false;
    }

    public Notification(String username, String title, String message, String category) {
        this();
        this.username = username;
        this.title = title;
        this.message = message;
        this.category = category;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public Integer getRequestId() { return requestId; }
    public void setRequestId(Integer requestId) { this.requestId = requestId; }

    public Integer getListingId() { return listingId; }
    public void setListingId(Integer listingId) { this.listingId = listingId; }

    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
}
