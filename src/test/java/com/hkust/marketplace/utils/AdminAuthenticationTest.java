package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class AdminAuthenticationTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private static String passwordHash;
    private String previousDatabaseUrl;

    @BeforeClass
    public static void hashFixturePassword() {
        passwordHash = DataService.hashPassword("ChangedAdmin123");
    }

    @Before
    public void initializeDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("test.db"));
        DatabaseManager.initialize();
        SessionManager.logout();
    }

    @After
    public void restoreDatabaseUrl() {
        if (previousDatabaseUrl == null) {
            System.clearProperty("marketplace.database.url");
        } else {
            System.setProperty("marketplace.database.url", previousDatabaseUrl);
        }
        SessionManager.logout();
    }

    private void createAccount(String username, UserRole role) {
        DatabaseManager.createUser(username, "Test User", passwordHash, role.name(),
                "Hall 3", "Test bio", "Library");
    }

    @Test
    public void seededAdminCanLoginWithDocumentedPassword() {
        DataService.seedUserIfMissing();

        User admin = DataService.authenticate("admin", "admin123", UserRole.ADMIN);

        assertNotNull(admin);
        assertEquals(UserRole.ADMIN, admin.getRole());
        assertTrue(admin.isActive());
        assertNotEquals("admin123", admin.getPasswordHash());
        assertEquals("HKUST Marketplace Admin", admin.getFullName());
    }

    @Test
    public void loginAcceptsMixedCaseUsernameAndSurroundingWhitespace() {
        createAccount("admin", UserRole.ADMIN);

        User admin = DataService.authenticate("  AdMiN  ", "ChangedAdmin123", UserRole.ADMIN);

        assertNotNull(admin);
        assertEquals("admin", admin.getUsername());
    }

    @Test
    public void incorrectPasswordAndUnknownAccountAreRejected() {
        createAccount("admin", UserRole.ADMIN);

        assertNull(DataService.authenticate("admin", "WrongPassword123", UserRole.ADMIN));
        assertNull(DataService.authenticate("missing", "ChangedAdmin123", UserRole.ADMIN));
    }

    @Test
    public void buyerAndSellerCannotLoginThroughAdminPortal() {
        createAccount("buyer", UserRole.BUYER);
        createAccount("seller", UserRole.SELLER);

        assertNull(DataService.authenticate("buyer", "ChangedAdmin123", UserRole.ADMIN));
        assertNull(DataService.authenticate("seller", "ChangedAdmin123", UserRole.ADMIN));
    }

    @Test
    public void inactiveAdminIsRejected() {
        createAccount("admin", UserRole.ADMIN);
        DatabaseManager.editData("users", "admin", "active", false);

        assertNull(DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN));
    }

    @Test
    public void blankOrMissingInputsAreRejected() {
        assertNull(DataService.authenticate(null, "password", UserRole.ADMIN));
        assertNull(DataService.authenticate("  ", "password", UserRole.ADMIN));
        assertNull(DataService.authenticate("admin", null, UserRole.ADMIN));
        assertNull(DataService.authenticate("admin", "", UserRole.ADMIN));
        assertNull(DataService.authenticate("admin", "password", null));
    }

    @Test
    public void legacyPlaintextAndMalformedHashesAreRejected() {
        createAccount("admin", UserRole.ADMIN);
        DatabaseManager.editData("users", "admin", "password_hash", "legacy-password");
        assertNull(DataService.authenticate("admin", "legacy-password", UserRole.ADMIN));

        DatabaseManager.editData("users", "admin", "password_hash", "$2a$12$broken");
        assertNull(DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN));
    }

    @Test
    public void seedingDoesNotResetExistingAdminPasswordProfileOrActivation() {
        createAccount("admin", UserRole.ADMIN);
        DatabaseManager.editData("users", "admin", "active", false);

        DataService.seedUserIfMissing();
        // Also exercise the guarded INSERT independently of the service precheck.
        DatabaseManager.createUserIfMissing("admin", "Replacement", passwordHash,
                "ADMIN", null, null, null);

        var admin = DatabaseManager.fetchRow("users", "username", "admin");
        assertEquals(passwordHash, admin.get("password_hash"));
        assertEquals("Test User", admin.get("full_name"));
        assertEquals(0, ((Number) admin.get("active")).intValue());
        assertEquals(5, DatabaseManager.fetchRows("users", java.util.Map.of()).size());
    }

    @Test
    public void seedingDoesNotPromoteExistingCaseVariantAccount() {
        createAccount("ADMIN", UserRole.BUYER);

        DataService.seedUserIfMissing();
        DatabaseManager.createUserIfMissing("admin", "Replacement", passwordHash,
                "ADMIN", null, null, null);

        assertEquals("BUYER", DatabaseManager.fetchRow("users", "username", "admin").get("role"));
        assertNull(DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN));
        assertEquals(5, DatabaseManager.fetchRows("users", java.util.Map.of()).size());
    }

    @Test
    public void allReadmeDemoAccountsAreSeededWithHashedPasswordsAndCorrectRoles() {
        DataService.seedUserIfMissing();

        assertEquals(5, DatabaseManager.fetchRows("users", java.util.Map.of()).size());
        for (String username : java.util.List.of("alice", "bob", "charlie", "diana")) {
            UserRole role = username.equals("alice") || username.equals("bob")
                    ? UserRole.BUYER : UserRole.SELLER;
            User user = DataService.authenticate(username, "password1", role);
            assertNotNull(username, user);
            assertNotEquals("password1", user.getPasswordHash());
            assertNull(DataService.authenticate(username, "password1", UserRole.ADMIN));
        }
        assertEquals("Hall 3", DatabaseManager.fetchRow("users", "username", "alice").get("residence_hall"));
        assertEquals("Library", DatabaseManager.fetchRow("users", "username", "charlie").get("preferred_meetup"));
    }

    @Test
    public void repeatedSeedingPreservesAllExistingRows() {
        DataService.seedUserIfMissing();
        var before = DatabaseManager.fetchRows("users", java.util.Map.of());

        DataService.seedUserIfMissing();

        assertEquals(before, DatabaseManager.fetchRows("users", java.util.Map.of()));
    }

    @Test
    public void seedingPreservesExistingBuyerAndSellerAccountsIgnoringCase() {
        createAccount("ALICE", UserRole.BUYER);
        createAccount("CHARLIE", UserRole.SELLER);
        DatabaseManager.editData("users", "CHARLIE", "active", false);

        DataService.seedUserIfMissing();
        DatabaseManager.createUserIfMissing("alice", "Replacement", "replacement-hash",
                "ADMIN", null, null, null);

        var buyer = DatabaseManager.fetchRow("users", "username", "alice");
        assertEquals("ALICE", buyer.get("username"));
        assertEquals("Test User", buyer.get("full_name"));
        assertEquals(passwordHash, buyer.get("password_hash"));
        assertEquals("BUYER", buyer.get("role"));
        var seller = DatabaseManager.fetchRow("users", "username", "charlie");
        assertEquals(passwordHash, seller.get("password_hash"));
        assertEquals(0, ((Number) seller.get("active")).intValue());
        assertEquals(5, DatabaseManager.fetchRows("users", java.util.Map.of()).size());
    }

    @Test
    public void databaseUserMappingPreservesStoredProfileAndMetadata() {
        createAccount("admin", UserRole.ADMIN);
        DatabaseManager.editData("users", "admin", "average_rating", 4.5);
        DatabaseManager.editData("users", "admin", "rating_count", 2);

        User admin = DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN);

        assertNotNull(admin);
        assertEquals("Test User", admin.getFullName());
        assertEquals("Hall 3", admin.getResidenceHall());
        assertEquals("Test bio", admin.getBio());
        assertEquals("Library", admin.getPreferredMeetup());
        assertEquals(4.5, admin.getAverageRating(), 0.001);
        assertEquals(2, admin.getRatingCount());
        assertNotNull(admin.getCreatedAt());
    }

    @Test
    public void fetchRowKeepsOtherTextColumnsCaseSensitive() {
        createAccount("admin", UserRole.ADMIN);

        assertNull(DatabaseManager.fetchRow("users", "full_name", "test user"));
        assertNotNull(DatabaseManager.fetchRow("users", "full_name", "Test User"));
    }

    @Test
    public void usernameIsBoundAsDataRatherThanSql() {
        createAccount("admin", UserRole.ADMIN);

        assertNull(DataService.authenticate("admin' OR 1=1 --", "ChangedAdmin123", UserRole.ADMIN));
    }

    @Test
    public void passwordWhitespaceIsNotTrimmed() {
        createAccount("admin", UserRole.ADMIN);

        assertNull(DataService.authenticate("admin", " ChangedAdmin123 ", UserRole.ADMIN));
    }

    @Test
    public void authenticationLeavesSessionCreationToController() {
        createAccount("admin", UserRole.ADMIN);

        assertNotNull(DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN));
        assertFalse(SessionManager.isLoggedIn());
    }

    @Test(expected = IllegalStateException.class)
    public void databaseFailureIsNotReportedAsInvalidCredentials() {
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + databaseFolder.getRoot());

        DataService.authenticate("admin", "ChangedAdmin123", UserRole.ADMIN);
    }
}
