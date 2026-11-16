package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.Assert.*;

public class AdminNotificationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    @Before public void setup() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("notifications.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, "unused", role, null, null, null);
        }
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
    }
    @After public void cleanup() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }

    @Test public void boardLoadsOnlyCurrentAdminsNotificationsWithCategoriesAndExactTimestamps() throws Exception {
        DatabaseManager.createNotification("admin", null, "Maintenance", "Service resumes at noon.", "Announcement");
        DatabaseManager.createNotification("buyer", null, "Private", "Buyer only.", "System");
        DatabaseManager.createNotification("admin", null, "Submission", "Review this listing.", "Listing Review", null, 42);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE notifications SET timestamp = '2026-11-16T09:30:00'");
        }
        var notices = DataService.getAdminNotifications();
        assertEquals(List.of("Submission", "Maintenance"), notices.stream().map(Notification::getTitle).toList());
        assertEquals("Listing Review", notices.get(0).getCategory());
        assertEquals(Integer.valueOf(42), notices.get(0).getListingId());
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 30), notices.get(0).getTimestamp());
        assertFalse(notices.get(0).isRead());
    }
    @Test public void submittedListingNotifiesAdminWithItsExactReviewTarget() {
        Listing listing = new Listing();
        listing.setTitle("Same title");
        listing.setDescription("A clean textbook.");
        listing.setCategory("Textbooks");
        listing.setCondition("Used");
        listing.setType(ListingType.SELL);
        listing.setPrice(80.0);
        listing.setMeetupLocation("Library");
        SessionManager.login(new User("seller", "Seller", "unused", UserRole.SELLER));
        DataService.submitListing(listing, List.of());
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        var notice = DataService.getAdminNotifications().get(0);
        assertEquals(Integer.valueOf(listing.getId()), notice.getListingId());
        assertEquals("Listing Review", notice.getCategory());
        assertNotNull(notice.getTimestamp());
        assertEquals("Same title", DataService.getListingForAdmin(notice.getListingId().toString()).getTitle());
    }

    @Test public void readingIsPersistedAndCannotMarkAnotherRecipientsNotice() {
        DatabaseManager.createNotification("admin", null, "Maintenance", "Notice", "Announcement");
        DatabaseManager.createNotification("buyer", null, "Private", "Notice", "System");
        String id = DataService.getAdminNotifications().get(0).getId();
        DataService.markAdminNotificationRead(id);
        assertTrue(DataService.getAdminNotifications().get(0).isRead());
        String buyerId = DataService.getNotificationsFor("buyer").get(0).getId();
        assertThrows(IllegalArgumentException.class, () -> DataService.markAdminNotificationRead(buyerId));
        assertFalse(DataService.getNotificationsFor("buyer").get(0).isRead());
        assertThrows(IllegalArgumentException.class, () -> DataService.markAdminNotificationRead("invalid"));
        for (UserRole role : List.of(UserRole.BUYER, UserRole.SELLER)) {
            SessionManager.login(new User(role.name().toLowerCase(), "User", "unused", role));
            assertThrows(SecurityException.class, DataService::getAdminNotifications);
            assertThrows(SecurityException.class, () -> DataService.markAdminNotificationRead(id));
        }
        SessionManager.logout();
        assertThrows(SecurityException.class, DataService::getAdminNotifications);
        assertThrows(SecurityException.class, () -> DataService.markAdminNotificationRead(id));
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        DatabaseManager.setUserActive("admin", false);
        assertThrows(SecurityException.class, DataService::getAdminNotifications);
        assertThrows(SecurityException.class, () -> DataService.markAdminNotificationRead(id));
    }

    @Test public void editingAListingCreatesANewLinkedReviewNotification() {
        int id = DatabaseManager.createListing("Book", "Textbooks", "Used", "Clean pages", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 80.0, null);
        var listing = DataService.getListingForAdmin(Integer.toString(id));
        SessionManager.login(new User("seller", "Seller", "unused", UserRole.SELLER));
        listing.setTitle("Revised book");
        DataService.updateSellerListing(listing, List.of(), false);
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        var notice = DataService.getAdminNotifications().get(0);
        assertEquals(Integer.valueOf(id), notice.getListingId());
        assertEquals("Listing Review", notice.getCategory());
        assertTrue(notice.getMessage().contains("Revised book"));
    }

    @Test public void buyerReportCreatesOneModerationNotificationPerAdminWithExactListing() {
        int id = DatabaseManager.createListing("Reported book", "Textbooks", "Used", "Clean pages", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 80.0, null);
        var listing = DataService.getListingForAdmin(Integer.toString(id));
        DataService.approveListing(listing.getId(), listing.getReviewVersion());
        SessionManager.login(new User("buyer", "Buyer", "unused", UserRole.BUYER));
        DataService.reportBuyerListing(Integer.toString(id));
        DataService.reportBuyerListing(Integer.toString(id));
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        var reports = DataService.getAdminNotifications().stream().filter(n -> "Listing Report".equals(n.getCategory())).toList();
        assertEquals(1, reports.size());
        assertEquals(Integer.valueOf(id), reports.get(0).getListingId());
        assertTrue(reports.get(0).getMessage().contains("buyer"));
        assertNotNull(reports.get(0).getTimestamp());
    }
    @Test public void accountUpdatesAndDeactivationNotifyAdminsWithoutLeakingCredentials() {
        DatabaseManager.updateUserProfile("buyer", "Updated Buyer", "Hall 3", null, null, "secret-hash");
        var notice = DataService.getAdminNotifications().get(0);
        assertEquals("Account Update", notice.getCategory());
        assertEquals("buyer", notice.getTargetUsername());
        assertNotNull(notice.getTimestamp());
        assertFalse(notice.getMessage().contains("secret-hash"));
        DatabaseManager.setUserActive("seller", false);
        var deactivated = DataService.getAdminNotifications().get(0);
        assertEquals("seller", deactivated.getTargetUsername());
        assertTrue(deactivated.getMessage().contains("deactivated"));
        int count = DataService.getAdminNotifications().size();
        DatabaseManager.setUserActive("seller", false);
        assertEquals("Unchanged values must not create duplicate updates", count, DataService.getAdminNotifications().size());
    }
    @Test public void startupAddsLinkedBacklogNotificationsOnceAndKeepsReadStateAndLegacyAnnouncements() {
        int id = DatabaseManager.createListing("Existing pending book", "Textbooks", "Used", "Clean pages", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 80.0, null);
        DatabaseManager.createNotification("admin", null, "Old announcement", "Keep this message.", "System");
        DatabaseManager.initialize();
        var pending = DataService.getAdminNotifications().stream().filter(n -> "Listing Review".equals(n.getCategory())).toList();
        assertEquals(1, pending.size());
        assertEquals(Integer.valueOf(id), pending.get(0).getListingId());
        DataService.markAdminNotificationRead(pending.get(0).getId());
        DatabaseManager.initialize();
        assertEquals(2, DataService.getAdminNotifications().size());
        assertTrue(DataService.getAdminNotifications().stream().filter(n -> n.getId().equals(pending.get(0).getId())).findFirst().orElseThrow().isRead());
        assertTrue(DataService.getAdminNotifications().stream().anyMatch(n -> "Keep this message.".equals(n.getMessage())));
    }
    @Test public void freshSampleSubmissionIsClickableOnFirstStartup() {
        DataService.seedData();
        var fridge = DataService.getPendingListings().stream().filter(l -> "Mini Fridge".equals(l.getTitle())).findFirst().orElseThrow();
        var notice = DataService.getAdminNotifications().stream().filter(n -> "New Listing Submitted".equals(n.getTitle())).findFirst().orElseThrow();
        assertEquals(Integer.valueOf(fridge.getId()), notice.getListingId());
        assertEquals("Listing Review", notice.getCategory());
    }
    @Test public void existingReportsGainLinksOnceAtStartup() throws Exception {
        int id = DatabaseManager.createListing("Existing reported book", "Textbooks", "Used", "Clean pages", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 80.0, null);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) { s.executeUpdate("DROP TRIGGER notify_admin_listing_report"); }
        DatabaseManager.reportListing("buyer", id);
        DatabaseManager.initialize();
        DatabaseManager.initialize();
        var reports = DataService.getAdminNotifications().stream().filter(n -> "Listing Report".equals(n.getCategory())).toList();
        assertEquals(1, reports.size());
        assertEquals(Integer.valueOf(id), reports.get(0).getListingId());
    }
    @Test public void irreversibleDeletionIsDescribedAsDeletionRatherThanReversibleDeactivation() {
        var seller = com.hkust.marketplace.controllers.admin.AdminUserService.getAllUsers(false).stream()
                .filter(u -> u.username().equals("seller")).findFirst().orElseThrow();
        com.hkust.marketplace.controllers.admin.AdminUserService.deleteUser(seller);
        var notice = DataService.getAdminNotifications().get(0);
        assertEquals("seller", notice.getTargetUsername());
        assertTrue(notice.getMessage().contains("permanently deleted"));
        assertEquals("Account Update", notice.getCategory());
    }
}
