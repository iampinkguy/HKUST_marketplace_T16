package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;

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

    public static String hashPassword(String password) {
        int logRounds = 12;
        String salt = BCrypt.gensalt(logRounds);
        return BCrypt.hashpw(password, salt);
    }

    public static boolean checkPassword(String password, String storedHash) {
        return BCrypt.checkpw(password, storedHash);
    }
}
