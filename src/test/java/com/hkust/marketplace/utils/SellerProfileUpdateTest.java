package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class SellerProfileUpdateTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;

    @Before
    public void initializeDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("test.db"));
        DatabaseManager.initialize();
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
    public void profileDetailsAndOptionalPasswordArePersisted() {
        String oldPasswordHash = DataService.hashPassword("OriginalPass123");
        DatabaseManager.createUser("seller", "Old Name", oldPasswordHash, UserRole.SELLER,
                null, "Old bio", "Library");

        DatabaseManager.updateUserProfile("seller", "New Name", null,
                "Updated bio", "North Gate", null);

        var seller = DatabaseManager.fetchRow("users", "username", "seller");
        assertEquals("New Name", seller.get("full_name"));
        assertEquals("Updated bio", seller.get("bio"));
        assertEquals("North Gate", seller.get("preferred_meetup"));
        assertTrue(DataService.checkPassword("OriginalPass123",
                (String) seller.get("password_hash")));

        String newPasswordHash = DataService.hashPassword("ChangedPass123");
        DatabaseManager.updateUserProfile("seller", "New Name", null,
                "Updated bio", "North Gate", newPasswordHash);
        assertNotNull(DataService.authenticate("seller", "ChangedPass123", UserRole.SELLER));
        assertNull(DataService.authenticate("seller", "OriginalPass123", UserRole.SELLER));
    }
}
