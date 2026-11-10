package com.hkust.marketplace.utils;

import java.time.LocalDateTime;
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
