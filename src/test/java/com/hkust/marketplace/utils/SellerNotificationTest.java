package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Notification;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.*;

public class SellerNotificationTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;

    @Before
    public void configureDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("test.db"));
    }

    @After
    public void restoreDatabaseUrl() {
        if (previousDatabaseUrl == null) {
            System.clearProperty("marketplace.database.url");
        } else {
            System.setProperty("marketplace.database.url", previousDatabaseUrl);
        }
    }

    @Test
    public void notificationsAreNewestFirstAndSelectedNotificationIsMarkedRead() {
        DatabaseManager.initialize();
        DatabaseManager.createNotification("seller", "buyer", "Older", "Older message", "Offer");
        DatabaseManager.createNotification("seller", "buyer", "Newer", "Newer message", "Deal");
        setTimestamp(1, "2026-11-10T10:00:00");
        setTimestamp(2, "2026-11-11T10:00:00");

        List<Notification> notifications = DatabaseManager.fetchNotifications("seller");
        assertEquals(2, notifications.size());
        assertEquals("Newer", notifications.get(0).getTitle());
        assertTrue(DatabaseManager.fetchRows("notifications", java.util.Map.of()).stream()
                .allMatch(row -> "buyer".equals(row.get("sender"))));
        assertFalse(notifications.get(0).isRead());
        assertFalse(notifications.get(1).isRead());

        DatabaseManager.markNotificationRead("seller", notifications.get(0).getId());

        notifications = DatabaseManager.fetchNotifications("seller");
        assertTrue(notifications.get(0).isRead());
        assertFalse(notifications.get(1).isRead());
        assertTrue(DatabaseManager.fetchNotifications("another-seller").isEmpty());
    }

    @Test
    public void legacyNotificationsAreMigratedAndPreserved() throws Exception {
        try (Connection connection = DatabaseManager.connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        title TEXT NOT NULL,
                        message TEXT NOT NULL,
                        category TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        read INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO notifications
                        (username, sender, title, message, category, timestamp, read)
                    VALUES ('seller', 'buyer', 'Old notification', 'Kept after migration',
                        'Offer', '2026-11-10T10:00:00', 1)
                    """);
        }

        DatabaseManager.initialize();

        List<Notification> notifications = DatabaseManager.fetchNotifications("seller");
        assertEquals(1, notifications.size());
        assertEquals("Old notification", notifications.get(0).getTitle());
        assertEquals("Kept after migration", notifications.get(0).getMessage());
        assertTrue(notifications.get(0).isRead());
        assertEquals("buyer", DatabaseManager.fetchRows("notifications", java.util.Map.of())
                .get(0).get("sender"));
    }

    private void setTimestamp(int id, String timestamp) {
        try (Connection connection = DatabaseManager.connect();
             var statement = connection.prepareStatement(
                     "UPDATE notifications SET timestamp = ? WHERE notification_id = ?")) {
            statement.setString(1, timestamp);
            statement.setInt(2, id);
            assertEquals(1, statement.executeUpdate());
        } catch (SQLException e) {
            throw new AssertionError("Could not set notification test timestamp", e);
        }
    }
}
