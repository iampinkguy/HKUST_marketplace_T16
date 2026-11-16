package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.SessionManager;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** Account management confined to the admin module; profiles never expose password hashes. */
public final class AdminUserService {
    private AdminUserService() {}

    public record Account(String username, String fullName, UserRole role, String residenceHall,
                          String bio, String preferredMeetup, boolean active, LocalDateTime createdAt,
                          LocalDateTime deletedAt, long version) {}
    public record Event(String admin, String username, String action, LocalDateTime at) {}

    public static List<Account> getAllUsers(boolean includeDeleted) {
        requireAdmin();
        ensureStorage();
        List<Account> accounts = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("users", Map.of())) {
            if (!"BUYER".equals(row.get("role")) && !"SELLER".equals(row.get("role"))) continue;
            if (!includeDeleted && row.get("deleted_at") != null) continue;
            accounts.add(new Account((String) row.get("username"), (String) row.get("full_name"),
                    UserRole.valueOf((String) row.get("role")), (String) row.get("residence_hall"),
                    (String) row.get("bio"), (String) row.get("preferred_meetup"),
                    ((Number) row.get("active")).intValue() == 1, date(row.get("created_at")),
                    date(row.get("deleted_at")), row.get("user_version") instanceof Number n ? n.longValue() : 0));
        }
        accounts.sort(Comparator.comparing(Account::username, String.CASE_INSENSITIVE_ORDER));
        return accounts;
    }

    public static void updateUser(Account expected, String fullName, String hall, String bio, String meetup) {
        requireAdmin();
        if (expected == null) throw new IllegalArgumentException("Select an account.");
        if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("Full name is required.");
        boolean buyer = expected.role() == UserRole.BUYER;
        hall = buyer ? optional(hall) : null;
        meetup = buyer ? null : optional(meetup);
        if (hall != null && !DataService.RESIDENCES.contains(hall)) throw new IllegalArgumentException("Choose a valid residence hall.");
        if (meetup != null && !DataService.HKUST_LOCATIONS.contains(meetup)) throw new IllegalArgumentException("Choose a valid meetup location.");
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("full_name", fullName.trim());
        changes.put("residence_hall", hall);
        changes.put("bio", buyer ? null : optional(bio));
        changes.put("preferred_meetup", meetup);
        save(expected, "UPDATE_PROFILE", changes);
    }

    public static List<Event> getHistory(String username) {
        requireAdmin();
        ensureStorage();
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required.");
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", username.trim());
        if (account == null) return List.of();
        List<Event> events = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("admin_user_audit", Map.of("target_username", account.get("username")))) {
            events.add(new Event((String) row.get("admin_username"), (String) row.get("target_username"),
                    (String) row.get("action"), date(row.get("occurred_at"))));
        }
        events.sort(Comparator.comparing(Event::at).reversed());
        return events;
    }

    public static void setUserActive(Account expected, boolean active) {
        save(expected, active ? "ACTIVATE" : "DEACTIVATE", Map.of("active", active ? 1 : 0));
    }

    /** Erases credentials/profile but retains the immutable username for linked historical records. */
    public static void deleteUser(Account expected) {
        String admin = requireAdmin();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("full_name", "Deleted user");
        changes.put("password_hash", "!deleted-" + java.util.UUID.randomUUID());
        changes.put("residence_hall", null);
        changes.put("bio", null);
        changes.put("preferred_meetup", null);
        changes.put("active", 0);
        changes.put("deleted_at", LocalDateTime.now().toString());
        changes.put("deleted_by", admin);
        save(expected, "DELETE", changes);
    }

    private static void ensureStorage() {
        try (Connection connection = DatabaseManager.connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                List<String> columns = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery("PRAGMA table_info(users)")) {
                    while (rows.next()) columns.add(rows.getString("name"));
                }
                for (String name : List.of("deleted_at", "deleted_by", "user_version")) {
                    if (!columns.contains(name)) statement.executeUpdate("ALTER TABLE users ADD COLUMN " + name
                            + (name.equals("user_version") ? " INTEGER NOT NULL DEFAULT 0" : " TEXT"));
                }
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS admin_user_audit (
                            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            admin_username TEXT NOT NULL, target_username TEXT NOT NULL,
                            action TEXT NOT NULL, occurred_at TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TRIGGER IF NOT EXISTS admin_user_version
                        AFTER UPDATE OF full_name, password_hash, role, residence_hall, bio, preferred_meetup,
                            active, deleted_at, deleted_by ON users
                        BEGIN UPDATE users SET user_version = OLD.user_version + 1 WHERE username = NEW.username; END
                        """);
                // A completed borrow may try to republish its item. Preserve completion but keep deleted sellers withdrawn.
                statement.executeUpdate("""
                        CREATE TRIGGER IF NOT EXISTS admin_deleted_seller_listing
                        AFTER UPDATE OF listing_status, availability_status ON listings
                        WHEN NEW.listing_status IN ('LIVE', 'PENDING_REVIEW') AND EXISTS (
                            SELECT 1 FROM users WHERE username = COALESCE(NULLIF(NEW.seller, ''), NEW.seller_username)
                                COLLATE NOCASE AND deleted_at IS NOT NULL)
                        BEGIN
                            UPDATE listings SET listing_status = 'REMOVED', availability_status = 'Unavailable',
                                approved = CASE WHEN NEW.listing_status = 'PENDING_REVIEW' THEN 0 ELSE approved END
                            WHERE listing_id = NEW.listing_id;
                        END
                        """);
                connection.commit();
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Could not initialize admin account history.", e); }
    }

    private static void save(Account expected, String action, Map<String, Object> changes) {
        String admin = requireAdmin();
        ensureStorage();
        if (expected == null) throw new IllegalArgumentException("Select an account.");
        if (expected.role() != UserRole.BUYER && expected.role() != UserRole.SELLER) {
            throw new SecurityException("Only buyer and seller accounts can be managed here.");
        }
        if (expected.deletedAt() != null) throw new IllegalStateException("This account was permanently deleted and cannot be changed.");
        String assignments = changes.keySet().stream().map(column -> column + " = ?").collect(Collectors.joining(", "));
        try (Connection connection = DatabaseManager.connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("UPDATE users SET " + assignments + """
                     WHERE username = ? AND user_version = ? AND role = ? AND role IN ('BUYER', 'SELLER')
                         AND deleted_at IS NULL AND EXISTS (SELECT 1 FROM users actor
                             WHERE actor.username = ? AND actor.role = 'ADMIN' AND actor.active = 1 AND actor.deleted_at IS NULL)
                    """)) {
                int index = 1;
                for (Object value : changes.values()) update.setObject(index++, value);
                update.setString(index++, expected.username());
                update.setLong(index++, expected.version());
                update.setString(index++, expected.role().name());
                update.setString(index, admin);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Account changed or is unavailable. Refresh and review it again.");
                if (action.equals("DELETE")) {
                    // Keep the listing and all deal/chat links; withdraw only entries still on the marketplace.
                    try (PreparedStatement withdraw = connection.prepareStatement("""
                            UPDATE listings SET listing_status = 'REMOVED', availability_status = 'Unavailable',
                                approved = CASE WHEN listing_status = 'PENDING_REVIEW' THEN 0 ELSE approved END
                            WHERE COALESCE(NULLIF(seller, ''), seller_username) = ? COLLATE NOCASE
                                AND listing_status IN ('PENDING_REVIEW', 'LIVE')
                            """)) {
                        withdraw.setString(1, expected.username());
                        withdraw.executeUpdate();
                    }
                }
                try (PreparedStatement audit = connection.prepareStatement("""
                        INSERT INTO admin_user_audit (admin_username, target_username, action, occurred_at) VALUES (?, ?, ?, ?)
                        """)) {
                    audit.setString(1, admin);
                    audit.setString(2, expected.username());
                    audit.setString(3, action);
                    audit.setString(4, LocalDateTime.now().toString());
                    audit.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Account change failed. No changes were saved.", e); }
    }

    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String requireAdmin() {
        User actor = SessionManager.getCurrentUser();
        if (actor == null || actor.getRole() != UserRole.ADMIN || !actor.isActive()) {
            throw new SecurityException("An active admin session is required.");
        }
        Map<String, Object> row = DatabaseManager.fetchRow("users", "username", actor.getUsername());
        if (row == null || !"ADMIN".equals(row.get("role")) || row.get("deleted_at") != null
                || ((Number) row.get("active")).intValue() != 1) {
            throw new SecurityException("An active admin session is required.");
        }
        return (String) row.get("username");
    }

    private static LocalDateTime date(Object value) {
        return value == null ? null : LocalDateTime.parse(value.toString());
    }
}
