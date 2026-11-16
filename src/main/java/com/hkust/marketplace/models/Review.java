package com.hkust.marketplace.models;

import java.time.LocalDateTime;

public class Review {
    private String id;
    private String sellerUsername;
    private String buyerUsername;
    private String listingTitle;
    private double rating; // Buyer's original score, from 0.0 to 5.0.
    private String comment;
    private String sellerReply;
    private boolean hidden; // admin can hide
    private LocalDateTime createdAt;

    public Review() {
        this.createdAt = LocalDateTime.now();
        this.hidden = false;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSellerUsername() { return sellerUsername; }
    public void setSellerUsername(String sellerUsername) { this.sellerUsername = sellerUsername; }

    public String getBuyerUsername() { return buyerUsername; }
    public void setBuyerUsername(String buyerUsername) { this.buyerUsername = buyerUsername; }

    public String getListingTitle() { return listingTitle; }
    public void setListingTitle(String listingTitle) { this.listingTitle = listingTitle; }

    /** Whole-star view retained for existing seller screens. */
    public int getRating() { return (int) Math.round(rating); }
    public void setRating(int rating) { this.rating = rating; }
    public double getScore() { return rating; }
    public void setScore(double rating) { this.rating = rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getSellerReply() { return sellerReply; }
    public void setSellerReply(String sellerReply) { this.sellerReply = sellerReply; }

    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
