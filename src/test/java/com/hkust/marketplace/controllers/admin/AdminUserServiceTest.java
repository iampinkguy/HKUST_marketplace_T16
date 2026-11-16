package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import static org.junit.Assert.*;

public class AdminUserServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static String passwordHash;
    private String oldUrl;

    @BeforeClass public static void preparePassword() { passwordHash = DataService.hashPassword("OriginalPassword123"); }
    @Before public void setUp() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("test.db"));
        DatabaseManager.initialize();
        create("admin", UserRole.ADMIN);
        create("buyer", UserRole.BUYER);
        create("seller", UserRole.SELLER);
        SessionManager.login(DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN));
    }
    @After public void tearDown() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }
    private void create(String username, UserRole role) {
        DatabaseManager.createUser(username, "Test " + username, passwordHash, role,
                role == UserRole.BUYER ? "Hall 3" : null,
                role == UserRole.SELLER ? "Seller bio" : null,
                role == UserRole.SELLER ? "Library" : null);
    }
    private AdminUserService.Account account(String username) {
        return AdminUserService.getAllUsers(false).stream().filter(user -> user.username().equals(username)).findFirst().orElseThrow();
    }
    @Test public void adminCanViewBuyerAndSellerProfilesFromTheDatabase() {
        var users = AdminUserService.getAllUsers(false);
        assertEquals(List.of("buyer", "seller"), users.stream().map(AdminUserService.Account::username).toList());
        assertEquals("Test buyer", users.get(0).fullName());
        assertEquals("Hall 3", users.get(0).residenceHall());
        assertEquals(UserRole.SELLER, users.get(1).role());
        assertEquals("Seller bio", users.get(1).bio());
        assertEquals("Library", users.get(1).preferredMeetup());
    }
    @Test public void profileEditingPreservesIdentityAndPasswordAndRecordsTheHandlingAdmin() {
        AdminUserService.updateUser(account("buyer"), " Updated Buyer ", "Hall 5", null, null);
        assertEquals("Updated Buyer", account("buyer").fullName());
        assertEquals("Hall 5", account("buyer").residenceHall());
        assertEquals(UserRole.BUYER, account("buyer").role());
        assertNotNull(DataService.authenticate("buyer", "OriginalPassword123", UserRole.BUYER));
        var history = AdminUserService.getHistory("buyer");
        assertEquals(1, history.size());
        assertEquals("UPDATE_PROFILE", history.get(0).action());
        assertEquals("admin", history.get(0).admin());
    }
    @Test public void deactivationBlocksLoginAndReactivationRetainsProfileAndHistory() {
        AdminUserService.setUserActive(account("seller"), false);
        assertFalse(account("seller").active());
        assertNull(DataService.authenticate("seller", "OriginalPassword123", UserRole.SELLER));
        assertEquals("Seller bio", account("seller").bio());
        AdminUserService.setUserActive(account("seller"), true);
        assertNotNull(DataService.authenticate("seller", "OriginalPassword123", UserRole.SELLER));
        assertEquals(List.of("ACTIVATE", "DEACTIVATE"), AdminUserService.getHistory("seller").stream().map(AdminUserService.Event::action).toList());
    }
    @Test public void permanentDeletionErasesProfileAndCredentialsAndCannotBeReversed() {
        AdminUserService.deleteUser(account("seller"));
        assertEquals(List.of("buyer"), AdminUserService.getAllUsers(false).stream().map(AdminUserService.Account::username).toList());
        var deleted = AdminUserService.getAllUsers(true).stream().filter(user -> user.username().equals("seller")).findFirst().orElseThrow();
        assertEquals("Deleted user", deleted.fullName());
        assertNull(deleted.bio());
        assertNull(deleted.preferredMeetup());
        assertFalse(deleted.active());
        assertNotNull(deleted.deletedAt());
        assertNull(DataService.authenticate("seller", "OriginalPassword123", UserRole.SELLER));
        assertThrows(IllegalStateException.class, () -> AdminUserService.setUserActive(deleted, true));
        assertThrows(IllegalStateException.class, () -> AdminUserService.updateUser(deleted, "Restored", null, null, null));
        assertEquals("DELETE", AdminUserService.getHistory("seller").get(0).action());
        assertEquals("admin", AdminUserService.getHistory("seller").get(0).admin());
    }
    private String listing(String title) {
        return Integer.toString(DatabaseManager.createListing(title, "Others", "Used", "Retained description", 0,
                null, null, null, null, null, null, "Library", "2026-11-15", "Available", "seller", "SELL", 50.0, null));
    }
    @Test public void deletingSellerWithdrawsListingsButRetainsBuyerDealsAndBothChatHistories() {
        String oldChat = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.chat.history.path", folder.getRoot().toPath().resolve("chat.json").toString());
        try {
            String live = listing("Published item");
            DataService.approveListing(live, DataService.getListingForAdmin(live).getReviewVersion());
            String pending = listing("Pending item");
            var admin = SessionManager.getCurrentUser();
            var buyer = DataService.authenticate("buyer", "OriginalPassword123", UserRole.BUYER);
            SessionManager.login(buyer);
            int request = DataService.submitBuyerRequest(live, "OFFER", 40.0, null);
            DataService.sendRequestMessage(request, "Retained offer message");
            DataService.sendBuyerListingChatMessage(live, "Retained listing message");
            SessionManager.login(admin);

            AdminUserService.deleteUser(account("seller"));

            assertTrue(DataService.getLiveListings().isEmpty());
            assertEquals(ListingStatus.REMOVED, DataService.getListingForAdmin(live).getStatus());
            assertEquals(ListingStatus.REMOVED, DataService.getListingForAdmin(pending).getStatus());
            assertTrue(DataService.getPendingListings().isEmpty());
            assertEquals("Retained description", DataService.getListingForAdmin(live).getDescription());
            SessionManager.login(buyer);
            assertEquals(request, ((Number) DataService.getBuyerDeals().get(0).get("request_id")).intValue());
            assertEquals("Retained offer message", DataService.getRequestMessages(request).get(0).get("content"));
            assertEquals("Retained listing message", DataService.getBuyerListingChat(live).getMessages().get(0).getContent());
            assertThrows(IllegalStateException.class, () -> DataService.submitBuyerRequest(live, "OFFER", 42.0, null));
        } finally {
            if (oldChat == null) System.clearProperty("marketplace.chat.history.path");
            else System.setProperty("marketplace.chat.history.path", oldChat);
        }
    }
    @Test public void existingBorrowCanFinishAfterSellerDeletionWithoutRepublishingTheItem() {
        String id = Integer.toString(DatabaseManager.createListing("Loan item", "Others", "Used", "Retained loan", 0,
                null, null, null, null, null, null, "Library", "2026-11-15", "Available", "seller", "LEND", null, 7));
        DataService.approveListing(id, DataService.getListingForAdmin(id).getReviewVersion());
        var admin = SessionManager.getCurrentUser();
        var buyer = DataService.authenticate("buyer", "OriginalPassword123", UserRole.BUYER);
        SessionManager.login(buyer);
        int request = DataService.submitBuyerRequest(id, "BORROW", null, 7);
        SessionManager.login(DataService.authenticate("seller", "OriginalPassword123", UserRole.SELLER));
        DataService.resolveSellerRequest(request, "ACCEPT", null);
        SessionManager.login(admin);
        AdminUserService.deleteUser(account("seller"));
        SessionManager.login(buyer);
        DataService.selfReturnBorrow(request);
        assertNotNull(DataService.getBuyerDeals().get(0).get("returned_at"));
        assertEquals(ListingStatus.REMOVED, DataService.peekBuyerListing(id).getStatus());
        assertTrue(DataService.getLiveListings().isEmpty());
    }
    @Test public void staleEditsStatusChangesAndDeletionCannotOverwriteANewerProfile() {
        var old = account("buyer");
        AdminUserService.updateUser(old, "Latest Buyer", "Hall 5", null, null);
        assertThrows(IllegalStateException.class, () -> AdminUserService.updateUser(old, "Stale Buyer", null, null, null));
        assertThrows(IllegalStateException.class, () -> AdminUserService.setUserActive(old, false));
        assertThrows(IllegalStateException.class, () -> AdminUserService.deleteUser(old));
        assertEquals("Latest Buyer", account("buyer").fullName());
        assertTrue(account("buyer").active());
        assertEquals(1, AdminUserService.getHistory("buyer").size());
    }
    @Test public void anonymousNonAdminAndRevokedAdminSessionsCannotManageUsers() {
        var target = account("buyer");
        var admin = SessionManager.getCurrentUser();
        SessionManager.logout();
        assertDenied(target);
        SessionManager.login(DataService.authenticate("buyer", "OriginalPassword123", UserRole.BUYER));
        assertDenied(target);
        SessionManager.login(admin);
        DatabaseManager.setUserActive("admin", false);
        assertDenied(target);
        DatabaseManager.setUserActive("admin", true);
        assertEquals("Test buyer", account("buyer").fullName());
        assertTrue(AdminUserService.getHistory("buyer").isEmpty());
        var fakeAdminTarget = new AdminUserService.Account("admin", "Admin", UserRole.ADMIN,
                null, null, null, true, null, null, 0);
        assertThrows(SecurityException.class, () -> AdminUserService.deleteUser(fakeAdminTarget));
        assertThrows(SecurityException.class, () -> AdminUserService.setUserActive(fakeAdminTarget, false));
    }
    private void assertDenied(AdminUserService.Account target) {
        assertThrows(SecurityException.class, () -> AdminUserService.getAllUsers(true));
        assertThrows(SecurityException.class, () -> AdminUserService.getHistory(target.username()));
        assertThrows(SecurityException.class, () -> AdminUserService.updateUser(target, "Unauthorized", null, null, null));
        assertThrows(SecurityException.class, () -> AdminUserService.setUserActive(target, false));
        assertThrows(SecurityException.class, () -> AdminUserService.deleteUser(target));
    }
    @Test public void auditFailureRollsBackAccountAndListingChangesTogether() throws Exception {
        String id = listing("Keep this live");
        DataService.approveListing(id, DataService.getListingForAdmin(id).getReviewVersion());
        var original = account("seller");
        // Failure injection at the storage boundary; observations stay at public service interfaces.
        try (var connection = DatabaseManager.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER fail_admin_audit BEFORE INSERT ON admin_user_audit
                    BEGIN SELECT RAISE(ABORT, 'Simulated audit failure'); END
                    """);
        }
        assertThrows(IllegalStateException.class, () -> AdminUserService.deleteUser(original));
        assertThrows(IllegalStateException.class, () -> AdminUserService.setUserActive(original, false));
        assertThrows(IllegalStateException.class, () -> AdminUserService.updateUser(original, "Must not save", null, null, null));
        assertEquals("Test seller", account("seller").fullName());
        assertTrue(account("seller").active());
        assertEquals(original.version(), account("seller").version());
        assertTrue(AdminUserService.getHistory("seller").isEmpty());
        assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(id).getStatus());
        assertNotNull(DataService.authenticate("seller", "OriginalPassword123", UserRole.SELLER));
    }
}
