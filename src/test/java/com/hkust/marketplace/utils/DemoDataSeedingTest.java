package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.controllers.admin.AdminUserService;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class DemoDataSeedingTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    @Before public void createDatabase() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("seed.db"));
        DatabaseManager.initialize();
        SessionManager.logout();
    }
    @After public void restoreEnvironment() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }
    private void login(String username, UserRole role) {
        var user = DataService.authenticate(username, role == UserRole.ADMIN ? "admin123" : "password1", role);
        assertNotNull(username, user);
        SessionManager.login(user);
    }
    @Test public void seedDataMakesTheSuppliedAccountsListingsDealReviewsAndNotificationsAvailable() {
        DataService.seedData();
        assertFalse(SessionManager.isLoggedIn());
        login("admin", UserRole.ADMIN);
        Map<String, Listing> listings = DataService.getAllListings().stream().collect(Collectors.toMap(Listing::getTitle, Function.identity()));
        assertEquals(6, listings.size()); // Five submissions plus the historical listing supporting the sample review.
        assertEquals(4, DataService.getLiveListings().size());
        assertEquals(1, DataService.getPendingListings().size());
        assertEquals("Mini Fridge", DataService.getPendingListings().get(0).getTitle());
        assertEquals(180.0, listings.get("Calculus Textbook (Stewart)").getPrice(), 0.001);
        assertEquals(250.0, listings.get("MacBook Pro Charger 61W").getPrice(), 0.001);
        assertEquals(Integer.valueOf(90), listings.get("Graphing Calculator TI-84").getLendingDays());
        assertEquals(ListingStatus.SOLD, listings.get("Old Chemistry Book").getStatus());
        assertEquals(1, DataService.getNotificationsFor("admin").size());
        var reviews = DataService.getAllReviews();
        assertEquals(1, reviews.size());
        assertEquals("Old Chemistry Book", reviews.get(0).getListingTitle());
        assertEquals("bob", reviews.get(0).getBuyerUsername());
        assertEquals(5, reviews.get(0).getRating());
        login("charlie", UserRole.SELLER);
        assertEquals(4.7, SessionManager.getCurrentUser().getAverageRating(), 0.001);
        assertEquals(12, SessionManager.getCurrentUser().getRatingCount());
        login("diana", UserRole.SELLER);
        assertEquals(4.3, SessionManager.getCurrentUser().getAverageRating(), 0.001);
        assertEquals(8, SessionManager.getCurrentUser().getRatingCount());
        login("alice", UserRole.BUYER);
        var deals = DataService.getBuyerDeals();
        assertEquals(1, deals.size());
        assertEquals("ACCEPTED", deals.get(0).get("status"));
        assertEquals(160.0, ((Number) deals.get(0).get("offer_price")).doubleValue(), 0.001);
        assertNull(deals.get(0).get("completed_at"));
        assertEquals("Offer Accepted", DataService.getNotificationsFor("alice").get(0).getTitle());
    }
    private Map<String, Object> snapshot() {
        Map<String, Object> tables = new LinkedHashMap<>();
        for (String table : List.of("users", "listings", "listing_requests", "reviews", "notifications", "feedbacks")) {
            tables.put(table, DatabaseManager.fetchRows(table, Map.of()));
        }
        return tables;
    }
    @Test public void repeatedSeedingPreservesEditsReviewDecisionsAndReadNotificationsWithoutDuplicates() {
        DataService.seedData();
        login("admin", UserRole.ADMIN);
        var fridge = DataService.getPendingListings().get(0);
        DataService.rejectListing(fridge.getId(), "Needs changes", fridge.getReviewVersion());
        DatabaseManager.updateUserProfile("charlie", "Edited Charlie", null, "Updated biography", "Library", null);
        var notice = DataService.getNotificationsFor("alice").get(0);
        DatabaseManager.markNotificationRead("alice", notice.getId());
        Map<String, Object> before = snapshot();

        DataService.seedData();
        DataService.seedData();

        assertEquals(before, snapshot());
        assertTrue(DataService.getNotificationsFor("alice").get(0).isRead());
        assertEquals(ListingStatus.REJECTED, DataService.getListingForAdmin(fridge.getId()).getStatus());
    }
    @Test public void anInactiveExistingSellerIsNotResetAndDoesNotProducePhantomAcceptedOffers() {
        DatabaseManager.createUser("CHARLIE", "Existing seller", DataService.hashPassword("ExistingPassword123"),
                UserRole.SELLER, null, "Existing bio", "Library");
        DatabaseManager.setUserActive("CHARLIE", false);
        DataService.seedData();
        login("admin", UserRole.ADMIN);
        var account = DatabaseManager.fetchRow("users", "username", "charlie");
        assertEquals("Existing seller", account.get("full_name"));
        assertEquals("Existing bio", account.get("bio"));
        assertEquals(0, ((Number) account.get("active")).intValue());
        assertEquals(3, DataService.getAllListings().size());
        login("alice", UserRole.BUYER);
        assertTrue(DataService.getBuyerDeals().isEmpty());
        assertTrue(DataService.getNotificationsFor("alice").isEmpty());
    }
    @Test public void aLaterStartupDoesNotRestoreAPermanentlyDeletedSeededAccountOrItsListings() {
        DataService.seedData();
        login("admin", UserRole.ADMIN);
        var diana = AdminUserService.getAllUsers(false).stream().filter(user -> user.username().equals("diana")).findFirst().orElseThrow();
        AdminUserService.deleteUser(diana);
        Map<String, Object> before = snapshot();

        DatabaseManager.initialize();
        DataService.seedData();

        assertEquals(before, snapshot());
        assertNull(DataService.authenticate("diana", "password1", UserRole.SELLER));
        assertTrue(AdminUserService.getAllUsers(false).stream().noneMatch(user -> user.username().equals("diana")));
        assertEquals(2, DataService.getLiveListings().size());
        assertTrue(DataService.getPendingListings().isEmpty());
    }
    @Test public void failedSeedingRollsBackEveryRecordAndCanBeRetried() throws Exception {
        try (var connection = DatabaseManager.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TRIGGER fail_seed_notification BEFORE INSERT ON notifications
                    BEGIN SELECT RAISE(ABORT, 'Simulated seed failure'); END
                    """);
        }
        assertThrows(IllegalStateException.class, DataService::seedData);
        for (String table : List.of("users", "listings", "listing_requests", "reviews", "notifications", "demo_data_seed")) {
            assertTrue(table, DatabaseManager.fetchRows(table, Map.of()).isEmpty());
        }
        try (var connection = DatabaseManager.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER fail_seed_notification");
        }
        DataService.seedData();
        login("admin", UserRole.ADMIN);
        assertEquals(6, DataService.getAllListings().size());
        assertEquals(1, DataService.getAllReviews().size());
        assertEquals(4, DatabaseManager.fetchRows("notifications", Map.of()).size());
    }
    @Test public void existingLegacyListingsAreNotDuplicatedOrGivenInventedAcceptedDeals() throws Exception {
        DatabaseManager.createUser("charlie", "Existing Charlie", DataService.hashPassword("ExistingPassword123"),
                UserRole.SELLER, null, "Existing bio", "Library");
        int id = DatabaseManager.createListing("Calculus Textbook (Stewart)", "Textbooks", "Used", "Existing description", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "charlie", "SELL", 999.0, null);
        // Older databases used seller_username; the newer seller column may be empty after migration.
        try (var connection = DatabaseManager.connect(); var update = connection.prepareStatement("UPDATE listings SET seller = '' WHERE listing_id = ?")) {
            update.setInt(1, id);
            update.executeUpdate();
        }
        DataService.seedData();
        login("admin", UserRole.ADMIN);
        assertEquals(6, DataService.getAllListings().size());
        var preserved = DataService.getListingForAdmin(Integer.toString(id));
        assertEquals(ListingStatus.PENDING_REVIEW, preserved.getStatus());
        assertEquals(999.0, preserved.getPrice(), 0.001);
        assertEquals("Existing description", preserved.getDescription());
        login("alice", UserRole.BUYER);
        assertTrue(DataService.getBuyerDeals().isEmpty());
        assertTrue(DataService.getNotificationsFor("alice").isEmpty());
    }
}
