package com.hkust.marketplace.utils;

import org.mindrot.jbcrypt.BCrypt;

public class DataService {

    private static final DataService INSTANCE = new DataService();

    public static String hashPassword(String password) {
        int logRounds = 12;
        String salt = BCrypt.gensalt(logRounds);
        return BCrypt.hashpw(password, salt);
    }

    public static boolean checkPassword(String password, String storedHash) {
        return BCrypt.checkpw(password, storedHash);
    }
}
