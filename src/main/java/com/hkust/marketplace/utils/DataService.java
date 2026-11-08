package com.hkust.marketplace.utils;

import java.util.List;

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

    public static String hashPassword(String password) {
        int logRounds = 12;
        String salt = BCrypt.gensalt(logRounds);
        return BCrypt.hashpw(password, salt);
    }

    public static boolean checkPassword(String password, String storedHash) {
        return BCrypt.checkpw(password, storedHash);
    }
}
