package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClearDatabaseTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;
    private String previousChatHistoryPath;

    @Before
    public void configureDatabase() throws Exception {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        previousChatHistoryPath = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("clear.db"));
        System.setProperty("marketplace.chat.history.path",
                databaseFolder.getRoot().toPath().resolve("chat_history.json").toString());
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "HKUST Admin", "hash", UserRole.ADMIN.name(), null, null, null);
        DatabaseManager.createUser("seller", "Seller", "hash", UserRole.SELLER.name(), null, null, null);
        try (java.sql.Connection connection = DatabaseManager.connect();
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO demo_data_seed (dataset_key, seeded_at) VALUES ('marketplace-demo-v1', '2026-11-20T10:00:00')")) {
            statement.executeUpdate();
        }
        DatabaseManager.createNotification("seller", "buyer", "New offer", "An offer arrived.", "Offer");
        DatabaseManager.appendMessageToChatHistory("1", "buyer", new Message("buyer", "buyer", "Hello"));
    }

    @After
    public void restore() {
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
        if (previousChatHistoryPath == null) System.clearProperty("marketplace.chat.history.path");
        else System.setProperty("marketplace.chat.history.path", previousChatHistoryPath);
    }

    @Test
    public void clearDatabaseRemovesRecordsAndChatButKeepsAdminAndSeedMarker() throws Exception {
        DatabaseManager.clearDatabase();

        assertNotNull(DatabaseManager.fetchRow("users", "username", "admin"));
        assertNull(DatabaseManager.fetchRow("users", "username", "seller"));
        assertTrue(DatabaseManager.fetchRows("notifications", Map.of()).isEmpty());
        assertNotNull(DatabaseManager.fetchRow("demo_data_seed", "dataset_key", "marketplace-demo-v1"));
        String chat = Files.readString(databaseFolder.getRoot().toPath().resolve("chat_history.json"));
        assertEquals("{}", chat.replaceAll("\\s+", ""));
    }
}
