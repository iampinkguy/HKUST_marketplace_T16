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
    private String itemName;
    private String description;
    private String category;
    private ListingType type;
    private ListingStatus status;
    private String condition;          // New / Like New / Used / For Parts
    private Double price;              // Only for SELL
    private Integer lendingDays;       // Only for LEND
    private String meetupLocation;
    private String sellerUsername;
    private LocalDateTime postedAt;
    // private LocalDateTime reviewedAt;  // fetch from `feedback` table
    // private String rejectionReason;   // fetch from `feedback` table
    private String availabilityStatus; // Available / Unavailable
    private LocalDateTime submittedAt;
    private long reviewVersion;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private int viewCount;
    private List<String> photoPaths = new ArrayList<>(); // At most 6 photos
    private boolean isApproved; // Whether the listing has been approved by admin

    public Listing() {
        this.postedAt = LocalDateTime.now();
        this.submittedAt = this.postedAt;
        this.status = ListingStatus.PENDING_REVIEW;
        this.viewCount = 0;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

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

    public String getSeller() { return sellerUsername; }
    public void setSeller(String sellerUsername) { this.sellerUsername = sellerUsername; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String availabilityStatus) { this.availabilityStatus = availabilityStatus; }

    public long getReviewVersion() { return reviewVersion; }
    public void setReviewVersion(long reviewVersion) { this.reviewVersion = reviewVersion; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public boolean isApproved() { return isApproved; }
    public void setIsApproved(boolean isApproved) { this.isApproved = isApproved; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public List<String> getPhotoPaths() { return photoPaths; }
    public void addPhotoPath(String photoPath) {
        if (photoPaths.size() < 6) {
            photoPaths.add(photoPath);
        } else {
            throw new IllegalStateException("Cannot add more than 6 photos.");
        }
    }

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
