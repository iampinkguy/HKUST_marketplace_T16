package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AdminListingReviewTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();
    private String previousDatabaseUrl;

    @Before
    public void initializeDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("reviews.db"));
        DatabaseManager.initialize();
        createAccount("admin", UserRole.ADMIN);
        createAccount("seller", UserRole.SELLER);
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
    }

    @After
    public void restoreDatabaseUrl() {
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
        SessionManager.logout();
    }

    private void createAccount(String username, UserRole role) {
        DatabaseManager.createUser(username, "Test " + username, "unused", role.name(), null, null, null);
    }

    private int createListing(String seller, String postedDate) {
        return DatabaseManager.createListing("Seller's textbook", "Textbooks", "Used", "Clean pages",
                2, "photo-one.jpg", "photo-two.jpg", null, null, null, null,
                "Library", postedDate, "Available", seller, "SELL", 125.50, null);
    }

    private int createListing() {
        return createListing("seller", "2026-11-09");
    }

    private Map<String, Object> listing(int id) {
        return DatabaseManager.fetchRow("listings", "listing_id", id);
    }

    private long version(int id) {
        return ((Number) listing(id).get("review_version")).longValue();
    }

    private void approve(int id) {
        DataService.approveListing(Integer.toString(id), version(id));
    }

    private void reject(int id, String reason) {
        DataService.rejectListing(Integer.toString(id), reason, version(id));
    }

    private List<Map<String, Object>> notifications() {
        return DatabaseManager.fetchRows("notifications", Map.of());
    }

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = DatabaseManager.connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private void assertUnreviewed(int id) {
        assertEquals(-1, ((Number) listing(id).get("approved")).intValue());
        assertNull(listing(id).get("reviewed_at"));
        assertNull(listing(id).get("reviewed_by"));
        assertNull(listing(id).get("rejection_reason"));
        assertNull(listing(id).get("posted_date"));
        assertTrue(notifications().isEmpty());
        assertTrue(DatabaseManager.fetchRows("feedbacks", Map.of()).isEmpty());
    }

    @Test
    public void pendingQueueLoadsStoredDetailsInSubmissionOrderAndExcludesDecidedListings() {
        int later = createListing("SELLER", "2026-11-09T14:30:00");
        int earlier = createListing("seller", "2026-11-08");
        int approved = createListing();
        int rejected = createListing();
        approve(approved);
        reject(rejected, "Wrong category");

        List<Listing> pending = DataService.getPendingListings();

        assertEquals(2, pending.size());
        assertEquals(Integer.toString(earlier), pending.get(0).getId());
        Listing result = pending.get(1);
        assertEquals(Integer.toString(later), result.getId());
        assertEquals("Seller's textbook", result.getTitle());
        assertEquals("Clean pages", result.getDescription());
        assertEquals("Textbooks", result.getCategory());
        assertEquals("Used", result.getCondition());
        assertEquals("Library", result.getMeetupLocation());
        assertEquals("SELLER", result.getSellerUsername());
        assertEquals("Test seller", result.getSellerFullName());
        assertEquals(LocalDateTime.of(2026, 11, 9, 14, 30), result.getSubmittedAt());
        assertNull(result.getPostedAt());
        assertEquals(com.hkust.marketplace.models.ListingType.SELL, result.getType());
        assertEquals(125.50, result.getPrice(), 0.001);
        assertEquals("Available", result.getAvailabilityStatus());
        assertEquals(ListingStatus.PENDING_REVIEW, result.getStatus());
        assertEquals(List.of("photo-one.jpg", "photo-two.jpg"), result.getPhotoPaths());
    }

    @Test
    public void approvalPersistsReviewAndExactlyOneSellerNotificationAcrossInitialization() {
        int id = createListing();
        LocalDateTime before = LocalDateTime.now();
        approve(id);
        DatabaseManager.initialize();

        Map<String, Object> row = listing(id);
        assertEquals(1, ((Number) row.get("approved")).intValue());
        assertEquals("admin", row.get("reviewed_by"));
        assertEquals(row.get("reviewed_at"), row.get("posted_date"));
        assertEquals("2026-11-09", row.get("submitted_at"));
        assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(Integer.toString(id)).getStatus());
        assertEquals(Integer.toString(id), DataService.getLiveListings().get(0).getId());
        assertFalse(LocalDateTime.parse((String) row.get("reviewed_at")).isBefore(before));
        assertNull(row.get("rejection_reason"));
        assertEquals("Available", row.get("availability_status"));
        assertEquals("Clean pages", row.get("description"));
        assertEquals("photo-one.jpg", row.get("photo1"));
        assertTrue(DataService.getPendingListings().isEmpty());
        assertTrue(DatabaseManager.fetchRows("feedbacks", Map.of()).isEmpty());
        assertEquals(1, notifications().size());
        Map<String, Object> notification = notifications().get(0);
        assertEquals("seller", notification.get("username"));
        assertEquals("Listing Approved", notification.get("title"));
        assertTrue(notification.get("message").toString().contains("Seller's textbook"));
        assertEquals(row.get("reviewed_at"), notification.get("timestamp"));
        assertEquals(0, ((Number) notification.get("is_read")).intValue());
    }

    @Test
    public void rejectionPersistsTrimmedReasonFeedbackAndNotificationWithoutInterpretingSql() {
        int id = createListing();
        String reason = "Wrong category'; DROP TABLE users; --";
        reject(id, "  " + reason + "  ");
        DatabaseManager.initialize();

        Map<String, Object> row = listing(id);
        assertEquals(0, ((Number) row.get("approved")).intValue());
        assertEquals(reason, row.get("rejection_reason"));
        assertNull(row.get("posted_date"));
        assertEquals(ListingStatus.REJECTED, DataService.getListingForAdmin(Integer.toString(id)).getStatus());
        assertTrue(DataService.getLiveListings().isEmpty());
        assertEquals("admin", row.get("reviewed_by"));
        assertNotNull(row.get("reviewed_at"));
        assertTrue(DataService.getPendingListings().isEmpty());
        List<Map<String, Object>> feedbacks = DatabaseManager.fetchRows("feedbacks", Map.of("listing_id", id));
        assertEquals(1, feedbacks.size());
        assertEquals("admin", feedbacks.get(0).get("admin_username"));
        assertEquals(reason, feedbacks.get(0).get("reason"));
        assertEquals(1, notifications().size());
        assertEquals("seller", notifications().get(0).get("username"));
        assertEquals("Listing Rejected", notifications().get(0).get("title"));
        assertTrue(notifications().get(0).get("message").toString().endsWith(reason));
        assertNotNull(DatabaseManager.fetchRow("users", "username", "admin"));
    }

    @Test
    public void repeatedOrConflictingDecisionsCannotOverwriteReviewOrDuplicateNotification() {
        int approved = createListing();
        approve(approved);
        Map<String, Object> savedApproval = listing(approved);
        assertThrows(IllegalStateException.class, () -> approve(approved));
        assertThrows(IllegalStateException.class, () -> reject(approved, "Changed mind"));
        assertEquals(savedApproval, listing(approved));
        int rejected = createListing();
        reject(rejected, "Incorrect description");
        Map<String, Object> savedRejection = listing(rejected);
        assertThrows(IllegalStateException.class, () -> approve(rejected));
        assertThrows(IllegalStateException.class, () -> reject(rejected, "Changed mind"));
        assertEquals(savedRejection, listing(rejected));
        assertEquals(2, notifications().size());
        assertEquals(1, DatabaseManager.fetchRows("feedbacks", Map.of()).size());
    }

    @Test
    public void invalidInputsAndMissingListingsDoNotWriteAnything() {
        int id = createListing();
        for (String badId : new String[]{null, "", "L1", "0", "-1", "1 OR 1=1", "2147483648"}) {
            assertThrows(IllegalArgumentException.class, () -> DataService.approveListing(badId, 0));
            assertThrows(IllegalArgumentException.class, () -> DataService.rejectListing(badId, "Reason", 0));
        }
        for (String badReason : new String[]{null, "", "  \n "}) {
            assertThrows(IllegalArgumentException.class, () -> reject(id, badReason));
        }
        assertThrows(IllegalStateException.class, () -> DataService.approveListing("999999", 0));
        assertThrows(IllegalStateException.class, () -> DataService.rejectListing("999999", "Reason", 0));
        assertUnreviewed(id);
    }

    private void assertAllHelpersDenyAccess(int id) {
        assertThrows(SecurityException.class, DataService::getPendingListings);
        assertThrows(SecurityException.class, () -> approve(id));
        assertThrows(SecurityException.class, () -> reject(id, "Reason"));
        assertThrows(SecurityException.class, () -> DataService.getListingForAdmin(Integer.toString(id)));
        assertThrows(SecurityException.class, () -> DataService.getListingForReview(Integer.toString(id), version(id)));
        assertUnreviewed(id);
    }

    @Test
    public void anonymousBuyerAndSellerSessionsCannotReadOrReviewTheQueue() {
        int id = createListing();
        SessionManager.logout();
        assertAllHelpersDenyAccess(id);
        for (UserRole role : List.of(UserRole.BUYER, UserRole.SELLER)) {
            createAccount("test" + role.name(), role);
            SessionManager.login(new User("test" + role.name(), "Other user", "unused", role));
            assertAllHelpersDenyAccess(id);
        }
    }

    @Test
    public void databaseDeactivationOrDemotionRevokesAnExistingAdminSession() {
        int id = createListing();
        DatabaseManager.editData("users", "admin", "active", false);
        assertAllHelpersDenyAccess(id);
        DatabaseManager.editData("users", "admin", "active", true);
        DatabaseManager.editData("users", "admin", "role", "BUYER");
        assertAllHelpersDenyAccess(id);
        DatabaseManager.deleteData("users", "admin");
        assertAllHelpersDenyAccess(id);
    }

    @Test
    public void sessionMustAlsoBeActiveAndRepresentAnExistingAccount() {
        int id = createListing();
        SessionManager.getCurrentUser().setActive(false);
        assertAllHelpersDenyAccess(id);
        SessionManager.login(new User("missing", "Missing admin", "unused", UserRole.ADMIN));
        assertAllHelpersDenyAccess(id);
    }

    @Test
    public void legacyCreationSignatureWorksButUnownedListingsCannotBeReviewed() {
        int id = DatabaseManager.createListing("Legacy item", "Others", "Used", null,
                0, null, null, null, null, null, null, "Library", "2026-11-09", "Available");
        assertNull(DataService.getPendingListings().get(0).getSellerUsername());
        assertThrows(IllegalStateException.class, () -> approve(id));
        assertThrows(IllegalStateException.class, () -> reject(id, "Reason"));
        assertUnreviewed(id);
    }

    @Test
    public void nonexistentOrNonSellerOwnersCannotReceiveReviewNotifications() {
        for (String owner : List.of("missing", "admin")) {
            int id = createListing(owner, "2026-11-09");
            assertThrows(IllegalStateException.class, () -> approve(id));
            assertThrows(IllegalStateException.class, () -> reject(id, "Reason"));
            assertUnreviewed(id);
        }
    }

    @Test
    public void notificationFailureRollsBackApprovalAndRejectionIncludingFeedback() throws SQLException {
        int id = createListing();
        executeSql("""
                CREATE TRIGGER fail_notification BEFORE INSERT ON notifications
                BEGIN SELECT RAISE(ABORT, 'Simulated notification failure'); END
                """);
        assertThrows(IllegalStateException.class, () -> approve(id));
        assertUnreviewed(id);
        assertThrows(IllegalStateException.class, () -> reject(id, "Reason"));
        assertUnreviewed(id);
        executeSql("DROP TRIGGER fail_notification");
        reject(id, "Reason");
        assertEquals(1, notifications().size());
    }

    @Test
    public void feedbackFailureRollsBackRejectionAndLeavesQueueUnchanged() throws SQLException {
        int id = createListing();
        executeSql("""
                CREATE TRIGGER fail_feedback BEFORE INSERT ON feedbacks
                BEGIN SELECT RAISE(ABORT, 'Simulated feedback failure'); END
                """);
        assertThrows(IllegalStateException.class, () -> reject(id, "Reason"));
        assertUnreviewed(id);
        assertEquals(1, DataService.getPendingListings().size());
    }

    @Test
    public void storageRechecksAdminAuthorizationBeforeWriting() {
        int id = createListing();
        DatabaseManager.editData("users", "admin", "active", false);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.reviewListing(id, "admin", true, null, version(id)));
        assertUnreviewed(id);
    }

    @Test
    public void initializationUpgradesLegacyListingsWithoutReplacingExistingRows() throws SQLException {
        int id = createListing();
        executeSql("DROP TRIGGER listings_review_version");
        for (String column : List.of("seller_username", "reviewed_at", "reviewed_by", "rejection_reason")) {
            executeSql("ALTER TABLE listings DROP COLUMN " + column);
        }
        executeSql("DROP TABLE notifications");
        Map<String, Object> original = listing(id);

        DatabaseManager.initialize();
        DatabaseManager.initialize();

        Map<String, Object> upgraded = listing(id);
        original.forEach((column, value) -> assertEquals(value, upgraded.get(column)));
        assertNull(upgraded.get("seller_username"));
        assertNull(upgraded.get("reviewed_at"));
        assertEquals(1, DataService.getPendingListings().size());
        assertTrue(notifications().isEmpty());
        assertEquals(id + 1, createListing());
    }

    @Test
    public void databaseFailuresAreReportedInsteadOfReturningAnEmptyQueue() throws SQLException {
        executeSql("DROP TABLE listings");
        assertThrows(IllegalStateException.class, DataService::getPendingListings);
    }

    @Test
    public void changesAfterDisplayBlockBothDetailLoadingAndDecisionsWithoutSideEffects() throws SQLException {
        int id = createListing();
        Listing displayed = DataService.getPendingListings().get(0);
        assertEquals(displayed.getReviewVersion(), DataService.getListingForReview(
                displayed.getId(), displayed.getReviewVersion()).getReviewVersion());
        executeSql("UPDATE listings SET description = 'Changed after display' WHERE listing_id = " + id);
        Map<String, Object> changed = listing(id);

        assertThrows(IllegalStateException.class,
                () -> DataService.getListingForReview(displayed.getId(), displayed.getReviewVersion()));
        assertThrows(IllegalStateException.class,
                () -> DataService.approveListing(displayed.getId(), displayed.getReviewVersion()));
        assertThrows(IllegalStateException.class,
                () -> DataService.rejectListing(displayed.getId(), "Old content", displayed.getReviewVersion()));

        assertEquals(changed, listing(id));
        assertUnreviewed(id);
        assertEquals("Changed after display", DataService.getListingForAdmin(displayed.getId()).getDescription());
        Listing refreshed = DataService.getPendingListings().get(0);
        DataService.approveListing(refreshed.getId(), refreshed.getReviewVersion());
        assertEquals(1, notifications().size());
    }

    @Test
    public void versionDetectsEditsEvenWhenContentIsChangedBack() throws SQLException {
        int id = createListing();
        long displayedVersion = version(id);
        executeSql("UPDATE listings SET item_name = 'Temporary title' WHERE listing_id = " + id);
        executeSql("UPDATE listings SET item_name = 'Seller''s textbook' WHERE listing_id = " + id);
        assertTrue(version(id) > displayedVersion);
        assertThrows(IllegalStateException.class,
                () -> DataService.approveListing(Integer.toString(id), displayedVersion));
        assertUnreviewed(id);
    }

    @Test
    public void decisionRelevantEditsAllInvalidateTheDisplayedVersion() throws SQLException {
        Map<String, Object> edits = new java.util.LinkedHashMap<>();
        edits.put("item_name", "Changed title");
        edits.put("description", "Changed description");
        edits.put("category", "Others");
        edits.put("item_condition", "New");
        edits.put("photo1", "changed.jpg");
        edits.put("numPhotos", 1);
        edits.put("meetup_location", "Student Centre");
        edits.put("seller_username", "otherSeller");
        edits.put("listing_type", "LEND");
        edits.put("price", 300.0);
        edits.put("lending_days", 10);
        edits.put("availability_status", "Unavailable");
        edits.put("submitted_at", "2026-11-08");
        edits.put("posted_date", "2026-11-08");
        for (Map.Entry<String, Object> edit : edits.entrySet()) {
            int id = createListing();
            long displayedVersion = version(id);
            try (Connection connection = DatabaseManager.connect(); PreparedStatement update = connection.prepareStatement(
                    "UPDATE listings SET " + edit.getKey() + " = ? WHERE listing_id = ?")) {
                update.setObject(1, edit.getValue());
                update.setInt(2, id);
                update.executeUpdate();
            }
            Map<String, Object> changed = listing(id);
            assertThrows(edit.getKey(), IllegalStateException.class,
                    () -> DataService.approveListing(Integer.toString(id), displayedVersion));
            assertEquals(changed, listing(id));
        }
        assertTrue(notifications().isEmpty());
    }

    @Test
    public void viewCountsDoNotInvalidateReviewAndExistingPublicationDateIsPreserved() throws SQLException {
        int id = createListing();
        executeSql("UPDATE listings SET posted_date = '2020-01-01T12:00:00' WHERE listing_id = " + id);
        long displayedVersion = version(id);
        executeSql("UPDATE listings SET views = views + 1 WHERE listing_id = " + id);
        assertEquals(displayedVersion, version(id));
        DataService.approveListing(Integer.toString(id), displayedVersion);
        DatabaseManager.initialize();
        assertEquals("2020-01-01T12:00:00", listing(id).get("posted_date"));
        assertEquals("2026-11-09", listing(id).get("submitted_at"));
    }

    @Test
    public void blankPublicationDatesAreSetOnFirstApproval() throws SQLException {
        int id = createListing();
        executeSql("UPDATE listings SET posted_date = '   ' WHERE listing_id = " + id);
        approve(id);
        assertEquals(listing(id).get("reviewed_at"), listing(id).get("posted_date"));
    }

    @Test
    public void deletedSubmissionIsUnavailableAtDetailLoadingAndSave() throws SQLException {
        int id = createListing();
        Listing displayed = DataService.getPendingListings().get(0);
        executeSql("DELETE FROM listings WHERE listing_id = " + id);
        assertNull(DataService.getListingForAdmin(displayed.getId()));
        assertThrows(IllegalStateException.class,
                () -> DataService.getListingForReview(displayed.getId(), displayed.getReviewVersion()));
        assertThrows(IllegalStateException.class,
                () -> DataService.approveListing(displayed.getId(), displayed.getReviewVersion()));
        assertThrows(IllegalStateException.class,
                () -> DataService.rejectListing(displayed.getId(), "Reason", displayed.getReviewVersion()));
        assertTrue(notifications().isEmpty());
        assertTrue(DatabaseManager.fetchRows("feedbacks", Map.of()).isEmpty());
    }

    @Test
    public void allListingTypesAndSeparateDatesLoadForReview() {
        LocalDateTime before = LocalDateTime.now();
        for (String type : List.of("SELL", "LEND", "GIVEAWAY")) {
            int id = DatabaseManager.createListing(type + " item", "Others", "New", null,
                    0, null, null, null, null, null, null, "Library", null, "Available", "seller",
                    type, type.equals("SELL") ? 42.5 : null, type.equals("LEND") ? 7 : null);
            Listing details = DataService.getListingForReview(Integer.toString(id), version(id));
            assertEquals(type, details.getType().name());
            assertFalse(details.getSubmittedAt().isBefore(before));
            assertNull(details.getPostedAt());
            if (type.equals("SELL")) assertEquals(42.5, details.getPrice(), 0.001);
            if (type.equals("LEND")) assertEquals(Integer.valueOf(7), details.getLendingDays());
        }
        assertTrue(DataService.getLiveListings().isEmpty());
    }

    @Test
    public void sellerCanReadSavedDecisionNotificationsButCannotReadOtherUsersNotifications() {
        int id = createListing();
        reject(id, "Wrong category");
        SessionManager.login(new User("seller", "Seller", "unused", UserRole.SELLER));
        var saved = DataService.getNotificationsFor("SELLER");
        assertEquals(1, saved.size());
        assertEquals("Listing Rejected", saved.get(0).getTitle());
        assertTrue(saved.get(0).getMessage().contains("Wrong category"));
        assertFalse(saved.get(0).isRead());
        assertNotNull(saved.get(0).getTimestamp());
        assertNotNull(saved.get(0).getId());
        assertThrows(SecurityException.class, () -> DataService.getNotificationsFor("admin"));
        DatabaseManager.editData("users", "seller", "active", false);
        assertThrows(SecurityException.class, () -> DataService.getNotificationsFor("seller"));
        SessionManager.logout();
        assertThrows(SecurityException.class, () -> DataService.getNotificationsFor("seller"));
    }

    @Test
    public void legacyNotNullSchemaUpgradesDatesAndPreservesIdsIndexesTriggersAndExtraColumns() throws SQLException {
        executeSql("DROP TABLE listings");
        executeSql("""
                CREATE TABLE listings (
                    listing_id INTEGER PRIMARY KEY AUTOINCREMENT, item_name TEXT NOT NULL,
                    category TEXT NOT NULL, item_condition TEXT NOT NULL, description TEXT,
                    numPhotos INTEGER NOT NULL DEFAULT 0,
                    photo1 TEXT, photo2 TEXT, photo3 TEXT, photo4 TEXT, photo5 TEXT, photo6 TEXT,
                    meetup_location TEXT NOT NULL, posted_date DATE NOT NULL,
                    availability_status TEXT NOT NULL, views INTEGER NOT NULL DEFAULT 0,
                    approved INTEGER NOT NULL DEFAULT -1, legacy_extra TEXT DEFAULT 'preserved'
                )
                """);
        executeSql("CREATE INDEX legacy_listing_title ON listings(item_name)");
        executeSql("CREATE TABLE legacy_audit (listing_id INTEGER)");
        executeSql("""
                CREATE TRIGGER legacy_listing_audit AFTER UPDATE OF description ON listings
                BEGIN INSERT INTO legacy_audit VALUES (NEW.listing_id); END
                """);
        executeSql("""
                INSERT INTO listings (listing_id, item_name, category, item_condition, meetup_location,
                    posted_date, availability_status, approved)
                VALUES (1, 'Pending item', 'Others', 'Used', 'Library', '2020-01-01', 'Available', -1),
                       (2, 'Live item', 'Others', 'Used', 'Library', '2020-01-02', 'Available', 1),
                       (3, 'Rejected item', 'Others', 'Used', 'Library', '2020-01-03', 'Available', 0),
                       (10, 'Deleted item', 'Others', 'Used', 'Library', '2020-01-03', 'Available', -1)
                """);
        executeSql("DELETE FROM listings WHERE listing_id = 10");
        DatabaseManager.initialize();
        DatabaseManager.initialize();

        assertEquals("2020-01-01", listing(1).get("submitted_at"));
        assertNull(listing(1).get("posted_date"));
        assertEquals("2020-01-02", listing(2).get("posted_date"));
        assertNull(listing(3).get("posted_date"));
        assertEquals("2020-01-03", listing(3).get("submitted_at"));
        assertEquals("preserved", listing(1).get("legacy_extra"));
        assertEquals(11, createListing());
        executeSql("UPDATE listings SET description = 'Changed' WHERE listing_id = 1");
        assertEquals(1, DatabaseManager.fetchRows("legacy_audit", Map.of()).size());
        assertEquals(1, version(1));
        try (Connection connection = DatabaseManager.connect(); Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("PRAGMA index_list(listings)")) {
            boolean found = false;
            while (indexes.next()) if ("legacy_listing_title".equals(indexes.getString("name"))) found = true;
            assertTrue(found);
        }
    }
}
