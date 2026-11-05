package com.hkust.marketplace.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory listing model.
 * TODO: Persist to database and add photo storage when backend is ready.
 */
public class Listing {
    private String id;
    private String title;
    private String description;
    private String category;
    private ListingType type;
    private ListingStatus status;
    private String condition;          // New / Like New / Used / For Parts
    private Double price;              // Only for SELL
    private Integer lendingDays;       // Only for LEND
    private String meetupLocation;
    private String sellerUsername;
    private String sellerFullName;
    private LocalDateTime postedAt;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private int viewCount;
    private List<String> photoPaths = new ArrayList<>(); // Demo: placeholder paths

    public Listing() {
        this.postedAt = LocalDateTime.now();
        this.status = ListingStatus.PENDING_REVIEW;
        this.viewCount = 0;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public ListingType getType() { return type; }
    public void setType(ListingType type) { this.type = type; }

    public ListingStatus getStatus() { return status; }
    public void setStatus(ListingStatus status) { this.status = status; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Integer getLendingDays() { return lendingDays; }
    public void setLendingDays(Integer lendingDays) { this.lendingDays = lendingDays; }

    public String getMeetupLocation() { return meetupLocation; }
    public void setMeetupLocation(String meetupLocation) { this.meetupLocation = meetupLocation; }

    public String getSellerUsername() { return sellerUsername; }
    public void setSellerUsername(String sellerUsername) { this.sellerUsername = sellerUsername; }

    public String getSellerFullName() { return sellerFullName; }
    public void setSellerFullName(String sellerFullName) { this.sellerFullName = sellerFullName; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public List<String> getPhotoPaths() { return photoPaths; }
    public void setPhotoPaths(List<String> photoPaths) { this.photoPaths = photoPaths; }

    public String getPriceDisplay() {
        if (type == ListingType.SELL && price != null) {
            return String.format("HK$ %.0f", price);
        } else if (type == ListingType.LEND) {
            return "Borrow";
        } else {
            return "Free";
        }
    }

    public String getTypeDisplay() {
        return switch (type) {
            case SELL -> "For Sale";
            case LEND -> "For Lending";
            case GIVEAWAY -> "Giveaway";
        };
    }
}
