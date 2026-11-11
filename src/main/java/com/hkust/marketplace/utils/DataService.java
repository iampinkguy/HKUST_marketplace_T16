package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

public class DataService {

    public static final List<String> HKUST_LOCATIONS = List.of(
        "Academic Building (LG1)",
        "Library",
        "Student Centre",
        "Hall 1 Lobby",
        "Hall 2 Lobby",
        "Hall 3 Lobby",
        "Hall 4 Lobby",
        "Hall 5 Lobby",
        "Hall 6 Lobby",
        "Hall 7 Lobby",
        "Hall 8 Lobby",
        "Hall 9 Lobby",
        "UG Hall 1",
        "UG Hall 2",
        "North Gate",
        "South Gate",
        "Shaw Auditorium",
        "Coffee Shop (LG5)",
        "Sports Hall"
    );

    public static final List<String> CATEGORIES = List.of(
        "Textbooks", "Electronics", "Furniture", "Clothing",
        "Stationery", "Sports Equipment", "Kitchenware", "Others"
    );

    public static final List<String> CONDITIONS = List.of(
        "New", "Like New", "Used", "For Parts"
    );

    public static final List<String> RESIDENCES = List.of(
        "Hall 1", "Hall 2", "Hall 3", "Hall 4", "Hall 5",
        "Hall 6", "Hall 7", "Hall 8", "Hall 9",
        "UG Hall 1", "UG Hall 2", "Off-campus"
    );

    /** Returns the active user on success, or null for invalid credentials or role. */
    public static User authenticate(String username, String password, UserRole expectedRole) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || expectedRole == null) {
            return null;
        }

        Map<String, Object> row = DatabaseManager.fetchRow("users", "username", username.trim());
        if (row == null || ((Number) row.get("active")).intValue() == 0
                || !expectedRole.name().equals(row.get("role"))) {
            return null;
        }

        String storedHash = (String) row.get("password_hash");
        if (storedHash == null || storedHash.length() != 60) {
            return null;
        }

        try {
            if (!checkPassword(password, storedHash)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            // Legacy plaintext or malformed hashes must not grant access.
            return null;
        }

        User user = new User();
        user.setUsername((String) row.get("username"));
        user.setFullName((String) row.get("full_name"));
        user.setPasswordHash(storedHash);
        user.setRole(expectedRole);
        user.setResidenceHall((String) row.get("residence_hall"));
        user.setBio((String) row.get("bio"));
        user.setPreferredMeetup((String) row.get("preferred_meetup"));
        user.setActive(((Number) row.get("active")).intValue() != 0);
        user.setCreatedAt(LocalDateTime.parse((String) row.get("created_at")));
        user.setAverageRating(((Number) row.get("average_rating")).doubleValue());
        user.setRatingCount(((Number) row.get("rating_count")).intValue());
        return user;
    }

    /** Seeds the README demo accounts without changing accounts that already exist. */
    public static void seedUserIfMissing() {
        record DemoUser(String username, String fullName, String password, UserRole role,
                        String residenceHall, String bio, String preferredMeetup) {}

        List<DemoUser> demoUsers = List.of(
                new DemoUser("admin", "HKUST Marketplace Admin", "admin123", UserRole.ADMIN,
                        null, null, null),
                new DemoUser("alice", "Alice Chan", "password1", UserRole.BUYER,
                        "Hall 3", null, null),
                new DemoUser("bob", "Bob Wong", "password1", UserRole.BUYER,
                        "Off-campus", null, null),
                new DemoUser("charlie", "Charlie Lee", "password1", UserRole.SELLER,
                        null, "Final year CSE student selling textbooks and gadgets.", "Library"),
                new DemoUser("diana", "Diana Ng", "password1", UserRole.SELLER,
                        null, "Selling furniture and kitchen items before graduation.", "Hall 5 Lobby")
        );

        for (DemoUser user : demoUsers) {
            if (!DatabaseManager.usernameExists(user.username())) {
                DatabaseManager.createUserIfMissing(user.username(), user.fullName(),
                        hashPassword(user.password()), user.role().name(), user.residenceHall(),
                        user.bio(), user.preferredMeetup());
            }
        }
    }

    /** Loads SQLite pending listings; no dummy listings are imported or seeded. */
    public static List<Listing> getPendingListings() {
        requireAdmin();
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings", Map.of("approved", -1))) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getSubmittedAt).thenComparing(Listing::getId));
        return listings;
    }

    /** Checks the queue snapshot again when opening a submission. */
    public static Listing getListingForReview(String listingId, long expectedVersion) {
        Listing listing = getListingForAdmin(listingId);
        if (listing == null || listing.getStatus() != ListingStatus.PENDING_REVIEW
                || listing.getReviewVersion() != expectedVersion) {
            throw new IllegalStateException("Listing is unavailable or has changed since it was displayed. Please review it again.");
        }
        return listing;
    }

    /** Also used to show the current state after an unavailable/stale submission. */
    public static Listing getListingForAdmin(String listingId) {
        requireAdmin();
        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id", parseListingId(listingId));
        return row == null ? null : toListing(row);
    }

    /** The expected version must come from the submission actually displayed to the admin. */
    public static void approveListing(String listingId, long expectedVersion) {
        String adminUsername = requireAdmin();
        DatabaseManager.reviewListing(parseListingId(listingId), adminUsername, true, null, expectedVersion);
    }

    public static void rejectListing(String listingId, String reason, long expectedVersion) {
        String adminUsername = requireAdmin();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required.");
        }
        DatabaseManager.reviewListing(parseListingId(listingId), adminUsername, false, reason.trim(), expectedVersion);
    }

    /** Shared read for the buyer team's database integration; approval publishes into this result. */
    public static List<Listing> getLiveListings() {
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings", Map.of("approved", 1))) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getPostedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Listing::getId));
        return listings;
    }

    /** Shared read for the seller team's notification integration. */
    public static List<Notification> getNotificationsFor(String username) {
        User user = SessionManager.getCurrentUser();
        if (user == null || username == null || (!SessionManager.isAdmin()
                && !username.equalsIgnoreCase(user.getUsername()))) {
            throw new SecurityException("You cannot access another user's notifications.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", user.getUsername());
        if (!user.isActive() || account == null || ((Number) account.get("active")).intValue() != 1
                || !user.getRole().name().equals(account.get("role"))) {
            throw new SecurityException("An active authenticated account is required.");
        }
        Map<String, Object> recipient = DatabaseManager.fetchRow("users", "username", username);
        if (recipient == null) return List.of();
        List<Notification> notifications = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("notifications", Map.of("username", recipient.get("username")))) {
            Notification notification = new Notification((String) row.get("username"), (String) row.get("title"),
                    (String) row.get("message"), (String) row.get("category"));
            notification.setId(row.get("notification_id").toString());
            notification.setTimestamp(parseDate((String) row.get("timestamp")));
            notification.setRead(((Number) row.get("is_read")).intValue() != 0);
            notifications.add(notification);
        }
        notifications.sort(Comparator.comparing(Notification::getTimestamp).reversed());
        return notifications;
    }

    private static String requireAdmin() {
        User currentUser = SessionManager.getCurrentUser();
        if (currentUser == null || currentUser.getRole() != UserRole.ADMIN || !currentUser.isActive()) {
            throw new SecurityException("An active admin account is required.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", currentUser.getUsername());
        if (account == null || !"ADMIN".equals(account.get("role"))
                || ((Number) account.get("active")).intValue() != 1) {
            throw new SecurityException("An active admin account is required.");
        }
        return (String) account.get("username");
    }

    private static int parseListingId(String listingId) {
        try {
            int id = Integer.parseInt(listingId);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Listing IDs in SQLite are positive integers, unlike the dummy IDs.
        }
        throw new IllegalArgumentException("A valid listing ID is required.");
    }

    /** Maps database fields to the existing model, including separate submission/publication dates. */
    private static Listing toListing(Map<String, Object> row) {
        Listing listing = new Listing();
        listing.setId(row.get("listing_id").toString());
        listing.setTitle((String) row.get("item_name"));
        listing.setDescription((String) row.get("description"));
        listing.setCategory((String) row.get("category"));
        listing.setCondition((String) row.get("item_condition"));
        listing.setMeetupLocation((String) row.get("meetup_location"));
        listing.setStatus(switch (((Number) row.get("approved")).intValue()) {
            case -1 -> ListingStatus.PENDING_REVIEW;
            case 0 -> ListingStatus.REJECTED;
            case 1 -> ListingStatus.LIVE;
            default -> throw new IllegalStateException("Unknown listing review status.");
        });
        String type = (String) row.get("listing_type");
        listing.setType(type == null ? null : ListingType.valueOf(type));
        Number price = (Number) row.get("price");
        listing.setPrice(price == null ? null : price.doubleValue());
        Number days = (Number) row.get("lending_days");
        listing.setLendingDays(days == null ? null : days.intValue());
        listing.setAvailabilityStatus((String) row.get("availability_status"));
        listing.setReviewVersion(((Number) row.get("review_version")).longValue());
        listing.setViewCount(((Number) row.get("views")).intValue());
        listing.setSellerUsername((String) row.get("seller_username"));
        if (listing.getSellerUsername() != null) {
            Map<String, Object> seller = DatabaseManager.fetchRow("users", "username", listing.getSellerUsername());
            listing.setSellerFullName(seller == null ? listing.getSellerUsername() : (String) seller.get("full_name"));
        }
        try {
            listing.setPostedAt(parseDate((String) row.get("posted_date")));
            listing.setSubmittedAt(parseDate((String) row.get("submitted_at")));
            listing.setReviewedAt(parseDate((String) row.get("reviewed_at")));
            listing.setRejectionReason((String) row.get("rejection_reason"));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Listing " + listing.getId() + " has an invalid date.", e);
        }
        List<String> photos = new ArrayList<>();
        for (int i = 1; i <= Math.min(6, ((Number) row.get("numPhotos")).intValue()); i++) {
            String photo = (String) row.get("photo" + i);
            if (photo != null && !photo.isBlank()) photos.add(photo);
        }
        listing.setPhotoPaths(photos);
        return listing;
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() == 10 ? LocalDate.parse(value).atStartOfDay()
                : LocalDateTime.parse(value.replace(' ', 'T'));
    }

    public static String hashPassword(String password) {
        int logRounds = 12;
        String salt = BCrypt.gensalt(logRounds);
        return BCrypt.hashpw(password, salt);
    }

    public static boolean checkPassword(String password, String storedHash) {
        return BCrypt.checkpw(password, storedHash);
    }

    public static String checkPasswordStrength(String password) {
        if (password == null || password.isEmpty()) return "Password cannot be empty.";
        if (password.length() < 8) return "Password must be at least 8 characters.";
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasUpper || !hasLower || !hasDigit) {
            return "Password should contain uppercase, lowercase and a digit.";
        }
        return "OK";
    }

    public static User toUser(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            if (!map.containsKey("role")) {
                throw new IllegalArgumentException("Map does not contain 'role' key.");
            }
            UserRole role;
            try {
                role = UserRole.valueOf((String) map.get("role"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid role value in map: " + map.get("role"));
            }
            String username = (String) map.get("username");
            String fullName = (String) map.get("full_name");
            String passwordHash = (String) map.get("password_hash");
            String residenceHall = (String) map.get("residence_hall");
            String bio = (String) map.get("bio");
            String preferredMeetup = (String) map.get("preferred_meetup");
            boolean active = ((Number) map.get("active")).intValue() == 1;
            LocalDateTime createdAt = LocalDateTime.parse((String) map.get("created_at"));
            double averageRating = ((Number) map.get("average_rating")).doubleValue();
            int ratingCount = ((Number) map.get("rating_count")).intValue();

            User user = new User(username, fullName, passwordHash, role);
            user.setResidenceHall(residenceHall);
            user.setBio(bio);
            user.setPreferredMeetup(preferredMeetup);
            user.setActive(active);
            user.setCreatedAt(createdAt);
            user.setAverageRating(averageRating);
            user.setRatingCount(ratingCount);

            return user;

        } else {
            throw new IllegalArgumentException("Object is not a valid Map representation of a User.");
        }
    }

    public static Listing toListing(Object obj) {
        // Implementation for converting an object to a Listing model
        // This method can be implemented similarly to toUser, depending on the structure of the Listing model
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Object is not a valid Map representation of a Listing.");
        }

        Listing listing = new Listing();
        listing.setId((String) map.get("listing_id"));
        listing.setItemName((String) map.get("item_name"));
        listing.setCategory((String) map.get("category"));
        listing.setCondition((String) map.get("item_condition"));
        listing.setDescription((String) map.get("description"));
        
        for (int i = 1; i <= ((Number) map.get("photo_count")).intValue(); i++) {
            String photoPathKey = "photo" + i;
            if (map.containsKey(photoPathKey))
                listing.getPhotoPaths().add((String) map.get(photoPathKey));
        }
        
        listing.setMeetupLocation((String) map.get("meetup_location"));
        listing.setPostedAt(LocalDateTime.parse((String) map.get("posted_at")));
        listing.setAvailabilityStatus((String) map.get("availability_status"));
        listing.setViewCount(((Number) map.get("views")).intValue());
        listing.setIsApproved(((Boolean) map.get("approved")).booleanValue() == true);

        listing.setType(ListingType.valueOf((String) map.get("listing_type")));
        listing.setStatus(ListingStatus.valueOf((String) map.get("listing_status")));
        listing.setPrice(map.get("price") != null ? ((Number) map.get("price")).doubleValue() : null);
        listing.setLendingDays(map.get("lending_days") != null ? ((Number) map.get("lending_days")).intValue() : null);
        listing.setSeller((String) map.get("seller"));

        // These shall be accessed in `feedbacks` table in data.db
        // listing.setReviewedAt(map.get("reviewed_at") != null ? LocalDateTime.parse((String) map.get("reviewed_at")) : null);
        // listing.setRejectionReason((String) map.get("rejection_reason"));
        return listing;
    }
}
