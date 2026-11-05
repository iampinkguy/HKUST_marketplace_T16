package com.hkust.marketplace.models;

import java.time.LocalDateTime;

/**
 * Simple in-memory user model (no persistence).
 * TODO: Replace with real entity + repository when backend is added.
 */
public class User {
    private String username;
    private String fullName;
    private String passwordHash; // In real app: never store plaintext
    private UserRole role;
    private String residenceHall;   // Buyer optional
    private String bio;             // Seller optional
    private String preferredMeetup; // Seller optional
    private boolean active = true;
    private LocalDateTime createdAt;
    private double averageRating;   // For sellers
    private int ratingCount;

    public User() {
        this.createdAt = LocalDateTime.now();
    }

    public User(String username, String fullName, String password, UserRole role) {
        this();
        this.username = username;
        this.fullName = fullName;
        this.passwordHash = password; // Demo only – in production hash it!
        this.role = role;
    }

    // Getters & Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public String getResidenceHall() { return residenceHall; }
    public void setResidenceHall(String residenceHall) { this.residenceHall = residenceHall; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getPreferredMeetup() { return preferredMeetup; }
    public void setPreferredMeetup(String preferredMeetup) { this.preferredMeetup = preferredMeetup; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public double getAverageRating() { return averageRating; }
    public void setAverageRating(double averageRating) { this.averageRating = averageRating; }

    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }

    @Override
    public String toString() {
        return fullName + " (@" + username + ")";
    }
}
