package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AdminProfileServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static String passwordHash;
    private String oldUrl;

    @BeforeClass public static void preparePassword() {
        passwordHash = DataService.hashPassword("OriginalPassword123");
    }
    @Before public void setUp() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("profile.db"));
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Original Admin", passwordHash, UserRole.ADMIN, null, null, null);
        SessionManager.login(DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN));
    }
    @After public void tearDown() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }

    @Test public void savingNamePersistsAfterLoginAndKeepsTheCurrentPassword() {
        AdminProfileService.updateOwnProfile("  Updated Admin  ", "", "");
        assertEquals("Updated Admin", SessionManager.getCurrentUser().getFullName());
        SessionManager.logout();
        var saved = DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN);
        assertNotNull(saved);
        assertEquals("Updated Admin", saved.getFullName());
        assertEquals("admin", saved.getUsername());
    }

    @Test public void changingPasswordAllowsOnlyTheNewPasswordAfterLogin() {
        AdminProfileService.updateOwnProfile("Updated Admin", "NewPassword456", "NewPassword456");
        assertTrue(DataService.checkPassword("NewPassword456", SessionManager.getCurrentUser().getPasswordHash()));
        SessionManager.logout();
        assertNull(DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN));
        assertNotNull(DataService.authenticate("admin", "NewPassword456", UserRole.ADMIN));
    }

    @Test public void blankFullNamesUseTheRegistrationErrorAndSaveNothing() {
        for (String name : new String[]{null, "", "  \t "}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> AdminProfileService.updateOwnProfile(name, "NewPassword456", "NewPassword456"));
            assertEquals("Full name cannot be empty.", error.getMessage());
        }
        assertEquals("Original Admin", SessionManager.getCurrentUser().getFullName());
        assertEquals("Original Admin", DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN).getFullName());
    }

    @Test public void weakPasswordsUseTheRegistrationRulesAndSaveNothing() {
        for (String password : new String[]{"Aa1", "lowercase123", "UPPERCASE123", "NoDigitsHere", "        "}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> AdminProfileService.updateOwnProfile("Unsaved Admin", password, password));
            assertEquals("The password is too weak to be used.", error.getMessage());
        }
        assertEquals("Original Admin", SessionManager.getCurrentUser().getFullName());
        assertEquals("Original Admin", DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN).getFullName());
        // Eight characters with the required character mix is valid; symbols are optional.
        AdminProfileService.updateOwnProfile("Valid Admin", "Abcdef12", "Abcdef12");
        assertNotNull(DataService.authenticate("admin", "Abcdef12", UserRole.ADMIN));
    }

    @Test public void mismatchedConfirmationUsesRegistrationFeedbackAndSavesNothing() {
        for (String confirm : new String[]{null, "", "Different123"}) {
            var error = assertThrows(DataService.BuyerInputException.class,
                    () -> AdminProfileService.updateOwnProfile("Unsaved Admin", "NewPassword456", confirm));
            assertEquals("The confirm-password was incorrect.", error.getMessage());
            assertTrue(error.isClearConfirmPassword());
        }
        var error = assertThrows(DataService.BuyerInputException.class,
                () -> AdminProfileService.updateOwnProfile("Unsaved Admin", "", "NewPassword456"));
        assertEquals("The confirm-password was incorrect.", error.getMessage());
        assertEquals("Original Admin", SessionManager.getCurrentUser().getFullName());
        assertEquals("Original Admin", DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN).getFullName());
    }

    @Test public void onlyAnAuthenticatedActiveAdminCanUpdateTheirOwnProfile() {
        var admin = SessionManager.getCurrentUser();
        SessionManager.logout();
        assertThrows(SecurityException.class, () -> AdminProfileService.updateOwnProfile("Blocked", "", ""));
        for (UserRole role : new UserRole[]{UserRole.BUYER, UserRole.SELLER}) {
            String username = role.name().toLowerCase();
            DatabaseManager.createUser(username, "Original " + username, passwordHash, role, null, null, null);
            SessionManager.login(DataService.authenticate(username, "OriginalPassword123", role));
            assertThrows(SecurityException.class, () -> AdminProfileService.updateOwnProfile("Blocked", "", ""));
            assertEquals("Original " + username, DataService.authenticate(username, "OriginalPassword123", role).getFullName());
        }
        SessionManager.login(admin);
        admin.setActive(false);
        assertThrows(SecurityException.class, () -> AdminProfileService.updateOwnProfile("Blocked", "", ""));
        assertEquals("Original Admin", DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN).getFullName());
    }

    @Test public void savingRechecksTheStoredAdminAccountAndDoesNotReportMissingAccountsAsSaved() throws Exception {
        for (String change : new String[]{"UPDATE users SET active = 0 WHERE username = 'admin'",
                "UPDATE users SET role = 'BUYER' WHERE username = 'admin'", "DELETE FROM users WHERE username = 'admin'"}) {
            try (var connection = DatabaseManager.connect(); var statement = connection.createStatement()) {
                statement.executeUpdate(change);
            }
            assertThrows(SecurityException.class, () -> AdminProfileService.updateOwnProfile("Blocked", "", ""));
            assertEquals("Original Admin", SessionManager.getCurrentUser().getFullName());
            try (var connection = DatabaseManager.connect(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE users SET active = 1, role = 'ADMIN' WHERE username = 'admin'");
            }
        }
    }
}
