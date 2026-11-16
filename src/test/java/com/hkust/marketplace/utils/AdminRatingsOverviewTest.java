package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;

public class AdminRatingsOverviewTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private String firstId;

    @Before public void setup() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("ratings.db"));
        DatabaseManager.initialize();
        seedAccounts();
        firstId = addReview("buyer", "seller", "Calculus book", 4.5, "2026-11-10T23:59:59", "Useful book");
    }
    @After public void cleanup() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }
    public static void admin() { SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN)); }
    public static void seedAccounts() {
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, "unused", role, null, null, null);
        }
        DatabaseManager.createUser("buyer2", "Buyer Two", "unused", UserRole.BUYER, null, null, null);
        DatabaseManager.createUser("seller2", "Seller Two", "unused", UserRole.SELLER, null, null, null);
        admin();
    }
    /** Historical completed-rating fixtures; assertions use the public application APIs. */
    public static String addReview(String buyer, String seller, String title, double score, String date, String comment) throws Exception {
        Listing listing = new Listing(); listing.setTitle(title); listing.setDescription("Clean pages");
        listing.setType(ListingType.SELL); listing.setPrice(80.0); listing.setCategory("Textbooks");
        listing.setCondition("Used"); listing.setMeetupLocation("Library");
        DataService.addListingForSeller(seller, listing, List.of());
        try (var c = DatabaseManager.connect(); var s = c.prepareStatement(
                "INSERT INTO reviews (buyer_username, listing_id, rating, text, created_at) VALUES (?, ?, ?, ?, ?)")) {
            s.setString(1, buyer); s.setInt(2, Integer.parseInt(listing.getId())); s.setDouble(3, score);
            s.setString(4, comment); s.setString(5, date); s.executeUpdate();
        }
        return listing.getId();
    }
    @Test public void overviewPreservesTheBuyersDecimalScoreAndOriginalTimestamp() {
        var review = DataService.getAllReviews().get(0);
        assertEquals(4.5, review.getScore(), 0.001);
        assertEquals("seller", review.getSellerUsername());
        assertEquals("buyer", review.getBuyerUsername());
        assertEquals("Calculus book", review.getListingTitle());
        assertEquals("Useful book", review.getComment());
        assertEquals("2026-11-10T23:59:59", review.getCreatedAt().toString());
        assertFalse(review.isHidden());
    }
    @Test public void userScoreAndInclusiveDatesFilterTogetherWithoutRoundingScores() throws Exception {
        addReview("buyer2", "seller2", "Physics", 5.0, "2026-11-11T00:00:00", "Great");
        addReview("buyer", "seller", "Notebook", 0.0, "2026-11-09T12:00:00", "Poor");
        LocalDate day = LocalDate.of(2026, 11, 10);
        assertEquals(List.of("Calculus book"), DataService.getAllReviews("  BUYER  ", 4.5, day, day)
                .stream().map(Review::getListingTitle).toList());
        assertEquals(2, DataService.getAllReviews("SeLlEr", null, null, day).size());
        assertEquals(1, DataService.getAllReviews("seller2", 5.0, day, null).size());
        assertEquals(1, DataService.getAllReviews(null, 0.0, null, null).size());
        assertTrue(DataService.getAllReviews("%", null, null, null).isEmpty());
        assertTrue(DataService.getAllReviews("buyer", 5.0, day, day).isEmpty());
    }
    @Test public void hidingAndRestoringRetainsAdminHistoryAndRecalculatesPublicSellerTotals() throws Exception {
        addReview("buyer2", "seller", "Physics", 1.5, "2026-11-11T00:00:00", "Inappropriate text");
        DataService.setReviewHidden("buyer", firstId, true);
        assertEquals(2, DataService.getAllReviews().size());
        assertTrue(DataService.getAllReviews().stream().filter(r -> r.getId().equals(firstId)).findFirst().orElseThrow().isHidden());
        SessionManager.login(new User("buyer", "Buyer", "unused", UserRole.BUYER));
        assertEquals(1, DataService.getSellerReviews("seller").size());
        assertEquals(1.5, ((Number) DataService.getSellerRating("seller").get("average_rating")).doubleValue(), 0.001);
        assertEquals(1, ((Number) DataService.getSellerRating("seller").get("rating_count")).intValue());
        SessionManager.login(new User("seller", "Seller", "unused", UserRole.SELLER));
        assertEquals(1, DataService.getReviewsForCurrentSeller().size());
        admin(); DataService.setReviewHidden("buyer", firstId, false);
        SessionManager.login(new User("buyer", "Buyer", "unused", UserRole.BUYER));
        assertEquals(2, DataService.getSellerReviews("seller").size());
        assertEquals(3.0, ((Number) DataService.getSellerRating("seller").get("average_rating")).doubleValue(), 0.001);
        assertEquals(2, ((Number) DataService.getSellerRating("seller").get("rating_count")).intValue());
    }
    @Test public void legacyReviewsWithoutDatesStayUndatedAndDoNotMatchDateFilters() throws Exception {
        addReview("buyer2", "seller2", "Undated book", 2.5, null, "Legacy review");
        var undated = DataService.getAllReviews("seller2", null, null, null).get(0);
        assertNull(undated.getCreatedAt());
        assertTrue(DataService.getAllReviews("seller2", null, LocalDate.of(2020, 1, 1), null).isEmpty());
        assertTrue(DataService.getAllReviews("seller2", null, null, LocalDate.of(2030, 1, 1)).isEmpty());
    }
    @Test public void invalidScoresAndReversedDateRangesHaveClearValidationErrors() {
        LocalDate day = LocalDate.of(2026, 11, 10);
        for (double score : List.of(-0.5, 5.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> DataService.getAllReviews(null, score, null, null)).getMessage().contains("0.0 and 5.0"));
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DataService.getAllReviews(null, null, day.plusDays(1), day)).getMessage().contains("From date"));
    }
    @Test public void aNewBuyerRatingDoesNotReintroduceHiddenRatingsIntoSellerTotals() throws Exception {
        DataService.setReviewHidden("buyer", firstId, true);
        int request = DatabaseManager.createListingRequest("buyer2", Integer.parseInt(firstId), "OFFER", 80.0, null);
        try (var c = DatabaseManager.connect(); var s = c.prepareStatement(
                "UPDATE listing_requests SET status = 'ACCEPTED', completed_at = '2026-11-12T12:00:00' WHERE request_id = ?")) {
            s.setInt(1, request); s.executeUpdate();
        }
        SessionManager.login(new User("buyer2", "Buyer Two", "unused", UserRole.BUYER));
        assertEquals(0, ((Number) DataService.getSellerRating("seller").get("rating_count")).intValue());
        DataService.rateCompletedDeal(request, 1.0, "Arrived damaged");
        assertEquals(1.0, ((Number) DataService.getSellerRating("seller").get("average_rating")).doubleValue(), 0.001);
        assertEquals(1, ((Number) DataService.getSellerRating("seller").get("rating_count")).intValue());
        assertEquals(1, DataService.getSellerReviews("seller").size());
        admin();
        assertEquals(2, DataService.getAllReviews().size());
        assertEquals(1, DataService.getAllReviews().stream().filter(Review::isHidden).count());
    }
    @Test public void onlyAnActivePersistedAdminCanViewOrModerateRatings() {
        for (UserRole role : List.of(UserRole.BUYER, UserRole.SELLER)) {
            SessionManager.login(new User(role.name().toLowerCase(), "User", "unused", role));
            assertThrows(SecurityException.class, DataService::getAllReviews);
            assertThrows(SecurityException.class, () -> DataService.setReviewHidden("buyer", firstId, true));
        }
        SessionManager.logout();
        assertThrows(SecurityException.class, DataService::getAllReviews);
        admin(); DatabaseManager.editData("users", "admin", "active", 0);
        assertThrows(SecurityException.class, DataService::getAllReviews);
        assertThrows(SecurityException.class, () -> DataService.setReviewHidden("buyer", firstId, true));
        DatabaseManager.editData("users", "admin", "active", 1);
        assertFalse(DataService.getAllReviews().get(0).isHidden());
        assertThrows(IllegalArgumentException.class, () -> DataService.setReviewHidden("missing", firstId, true));
        assertThrows(IllegalArgumentException.class, () -> DataService.setReviewHidden("buyer", "invalid", true));
    }
    @Test public void failedTotalsUpdateRollsBackVisibilityAndModerationTargetsOnlyTheSelectedBuyer() throws Exception {
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER reject_rating_total BEFORE UPDATE OF average_rating ON users BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> DataService.setReviewHidden("buyer", firstId, true));
        assertFalse(DataService.getAllReviews().get(0).isHidden());
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("DROP TRIGGER reject_rating_total");
            s.executeUpdate("INSERT INTO reviews (buyer_username, listing_id, rating, text, created_at) VALUES ('buyer2', "
                    + Integer.parseInt(firstId) + ", 2.0, 'Another buyer', '2026-11-11T12:00:00')");
        }
        DataService.setReviewHidden("buyer", firstId, true);
        assertFalse(DataService.getAllReviews("buyer2", null, null, null).get(0).isHidden());
        assertTrue(DataService.getAllReviews().stream().filter(r -> r.getBuyerUsername().equals("buyer")).findFirst().orElseThrow().isHidden());
    }
    @Test public void newestRatingsComeFirstAcrossLegacySqliteAndIsoTimestampsWithUndatedLast() throws Exception {
        addReview("buyer2", "seller2", "Later review", 3.0, "2026-11-10 23:59:59.500", "Later");
        addReview("buyer2", "seller", "Undated review", 2.0, null, "Legacy");
        assertEquals(List.of("Later review", "Calculus book", "Undated review"),
                DataService.getAllReviews().stream().map(Review::getListingTitle).toList());
    }
}
