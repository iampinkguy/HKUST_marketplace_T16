package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Edits the signed-in admin's own profile without changing their username. */
public final class AdminProfileService {
    private AdminProfileService() {}

    /** Passwords follow buyer registration: 8+ characters, uppercase, lowercase and a digit; symbols optional. */
    public static void updateOwnProfile(String fullName, String newPassword, String confirmPassword) {
        User user = SessionManager.getCurrentUser();
        if (user == null || user.getRole() != UserRole.ADMIN || !user.isActive()) {
            throw new SecurityException("An active admin session is required.");
        }
        String name = fullName == null ? "" : fullName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Full name cannot be empty.");
        boolean changePassword = newPassword != null && !newPassword.isEmpty();
        if (changePassword && !"OK".equals(DataService.checkPasswordStrength(newPassword))) {
            throw new IllegalArgumentException("The password is too weak to be used.");
        }
        if ((changePassword && !newPassword.equals(confirmPassword))
                || (!changePassword && confirmPassword != null && !confirmPassword.isEmpty())) {
            throw new DataService.BuyerInputException("The confirm-password was incorrect.", true);
        }
        String passwordHash = changePassword ? DataService.hashPassword(newPassword) : null;
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE users SET full_name = ?, password_hash = COALESCE(?, password_hash)
                     WHERE username = ? AND role = 'ADMIN' AND active = 1
                     """)) {
            update.setString(1, name);
            update.setString(2, passwordHash);
            update.setString(3, user.getUsername());
            if (update.executeUpdate() != 1) throw new SecurityException("An active admin session is required.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Profile update failed. Please try again.", ex);
        }
        user.setFullName(name);
        if (passwordHash != null) user.setPasswordHash(passwordHash);
    }
}
