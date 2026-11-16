package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import static org.junit.Assert.*;

public class AdminDealServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private static String passwordHash;

    @BeforeClass public static void preparePassword() {
        passwordHash = DataService.hashPassword("Password123");
    }
    @Before public void setUp() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("deals.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, passwordHash, role, null, null, null);
        }
        SessionManager.login(DataService.authenticate("admin", "Password123", UserRole.ADMIN));
    }
    @After public void tearDown() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }

    // Fixture writes only; behavior is observed through the public service.
    static void addDeal(int id, String title, String type, String requestStatus,
                        String start, String completed, String returned) throws Exception {
        try (var connection = DatabaseManager.connect()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO listings (listing_id, item_name, listing_type, category, item_condition,
                        listing_status, seller, meetup_location, availability_status, submitted_at, approved)
                    VALUES (?, ?, ?, 'Electronics', 'Used', 'LIVE', 'seller', 'Library', 'Available', '2026-11-01T08:00:00', 1)
                    """)) {
                insert.setInt(1, id); insert.setString(2, title); insert.setString(3, type);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO listing_requests (request_id, buyer_username, listing_id, request_type,
                        status, created_at, updated_at, borrow_start, completed_at, returned_at)
                    VALUES (?, 'buyer', ?, ?, ?, '2026-11-01T09:00:00', '2026-11-15T12:00:00', ?, ?, ?)
                    """)) {
                insert.setInt(1, id); insert.setInt(2, id);
                insert.setString(3, switch (type) { case "LEND" -> "BORROW"; case "GIVEAWAY" -> "CLAIM"; default -> "OFFER"; });
                insert.setString(4, requestStatus); insert.setString(5, start);
                insert.setString(6, completed); insert.setString(7, returned);
                insert.executeUpdate();
            }
        }
    }

    @Test public void adminSeesAStoredDealWithItsParticipantsAndDates() throws Exception {
        addDeal(1, "Calculus Textbook", "SELL", "ACCEPTED", null, null, null);
        var records = DataService.getAllDeals(null, null, null);
        assertEquals(1, records.size());
        var deal = records.get(0);
        assertEquals("Calculus Textbook", deal.itemTitle());
        assertEquals("buyer", deal.buyerUsername());
        assertEquals("seller", deal.sellerUsername());
        assertEquals(ListingType.SELL, deal.dealType());
        assertEquals("2026-11-01T09:00:00", deal.startDate());
        assertNull(deal.completeDate());
        assertEquals("Accepted", deal.status());
    }

    @Test public void recordsIncludeSoldLentAndGivenDealsButExcludeUnacceptedRequests() throws Exception {
        addDeal(1, "Sold Book", "SELL", "ACCEPTED", null, "2026-11-10T10:00:00", null);
        addDeal(2, "Returned Calculator", "LEND", "ACCEPTED", "2026-11-02T08:00:00",
                "2026-11-12T11:00:00", "2026-11-12T11:00:00");
        addDeal(3, "Given Lamp", "GIVEAWAY", "ACCEPTED", null, "2026-11-11T12:00:00", null);
        addDeal(4, "Pending", "SELL", "PENDING", null, null, null);
        addDeal(5, "Countered", "SELL", "COUNTERED", null, null, null);
        addDeal(6, "Declined", "SELL", "DECLINED", null, null, null);
        var records = DataService.getAllDeals(null, null, null);
        assertEquals(3, records.size());
        var sold = records.stream().filter(d -> d.requestId() == 1).findFirst().orElseThrow();
        assertEquals("Completed", sold.status());
        assertEquals("2026-11-10T10:00:00", sold.completeDate());
        var lent = records.stream().filter(d -> d.requestId() == 2).findFirst().orElseThrow();
        assertEquals(ListingType.LEND, lent.dealType());
        assertEquals("2026-11-02T08:00:00", lent.startDate());
        assertEquals("Returned", lent.status());
        assertEquals("2026-11-12T11:00:00", lent.completeDate());
        assertEquals(ListingType.GIVEAWAY, records.stream().filter(d -> d.requestId() == 3).findFirst().orElseThrow().dealType());
    }

    @Test public void searchAndFiltersCombineWithoutTreatingSearchAsSqlOrWildcards() throws Exception {
        addDeal(1, "100% Calculator", "LEND", "ACCEPTED", "2026-11-02", null, null);
        addDeal(2, "Desk Lamp", "GIVEAWAY", "ACCEPTED", null, "2026-11-12T11:00:00", null);
        addDeal(3, "Returned Calculator", "LEND", "ACCEPTED", "2026-11-03", null, "2026-11-13T11:00:00");
        assertEquals(List.of(1), ids("  100% cALC  ", null, null));
        assertEquals(List.of(1), ids("%", null, null));
        assertEquals(3, ids("BUYER", null, null).size());
        assertEquals(3, ids("SELLER", null, null).size());
        assertEquals(List.of(1), ids("calculator", ListingType.LEND, "Accepted"));
        assertEquals(List.of(3), ids(null, ListingType.LEND, "Returned"));
        assertEquals(List.of(2), ids(null, ListingType.GIVEAWAY, "Completed"));
        assertTrue(ids("missing", null, null).isEmpty());
        assertTrue(ids("' OR 1=1 --", null, null).isEmpty());
        assertTrue(ids("lamp", ListingType.SELL, null).isEmpty());
        assertEquals(3, ids("  ", null, null).size());
    }

    private static List<Integer> ids(String search, ListingType type, String status) {
        return DataService.getAllDeals(search, type, status).stream().map(DataService.DealRecord::requestId).toList();
    }

    @Test public void systemWideRecordsRequireAnActiveAdminSessionAndStoredAccount() throws Exception {
        var admin = SessionManager.getCurrentUser();
        SessionManager.logout();
        assertThrows(SecurityException.class, () -> DataService.getAllDeals(null, null, null));
        assertThrows(SecurityException.class, () -> DataService.getDealDetails(1));
        for (UserRole role : List.of(UserRole.BUYER, UserRole.SELLER)) {
            SessionManager.login(DataService.authenticate(role.name().toLowerCase(), "Password123", role));
            assertThrows(SecurityException.class, () -> DataService.getAllDeals(null, null, null));
            assertThrows(SecurityException.class, () -> DataService.getDealDetails(1));
        }
        SessionManager.login(admin);
        admin.setActive(false);
        assertThrows(SecurityException.class, () -> DataService.getAllDeals(null, null, null));
        assertThrows(SecurityException.class, () -> DataService.getDealDetails(1));
        admin.setActive(true);
        for (String change : List.of("UPDATE users SET active = 0 WHERE username = 'admin'",
                "UPDATE users SET active = 1, role = 'BUYER' WHERE username = 'admin'",
                "DELETE FROM users WHERE username = 'admin'")) {
            try (var c = DatabaseManager.connect(); var s = c.createStatement()) { s.executeUpdate(change); }
            assertThrows(SecurityException.class, () -> DataService.getAllDeals(null, null, null));
            assertThrows(SecurityException.class, () -> DataService.getDealDetails(1));
        }
    }

    @Test public void mostRecentlyChangedDealsAppearFirstWithStableOrdering() throws Exception {
        addDeal(1, "Older", "SELL", "ACCEPTED", null, "2026-11-10T10:00:00", null);
        addDeal(2, "Recently Returned", "LEND", "ACCEPTED", "2026-11-01", null, "2026-11-16T12:00:00");
        addDeal(3, "Recent Acceptance", "GIVEAWAY", "ACCEPTED", null, null, null);
        assertEquals(List.of(2, 3, 1), ids(null, null, null));
    }

    @Test public void dealTypeFollowsTheOriginalRequestWhenAReturnedListingIsRepurposed() throws Exception {
        addDeal(1, "Calculator", "LEND", "ACCEPTED", "2026-11-01", null, "2026-11-12T11:00:00");
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listings SET listing_type = 'SELL', listing_status = 'REMOVED' WHERE listing_id = 1");
            s.executeUpdate("UPDATE users SET active = 0 WHERE username IN ('buyer', 'seller')");
        }
        var records = DataService.getAllDeals(null, ListingType.LEND, "Returned");
        assertEquals(1, records.size());
        assertEquals("buyer", records.get(0).buyerUsername());
        assertEquals("seller", records.get(0).sellerUsername());
        assertEquals(ListingType.LEND, records.get(0).dealType());
    }

    @Test public void ongoingBorrowsExposeTheScheduledReturnWithoutClaimingAnActualReturn() throws Exception {
        addDeal(1, "Borrowed Calculator", "LEND", "ACCEPTED", "2026-11-17T18:38:57", null, null);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listing_requests SET borrow_end = '2026-11-29T18:39:02', due_at = '2026-11-30T18:39:02' WHERE request_id = 1");
        }
        var borrow = DataService.getAllDeals(null, ListingType.LEND, "Accepted").get(0);
        assertEquals("2026-11-30T18:39:02", borrow.scheduledReturnDate());
        assertNull(borrow.completeDate());
        assertEquals("Accepted", borrow.status());
    }

    @Test public void dealDetailsIncludeTheSelectedItemAndTheAgreedOfferRatherThanItsAskingPrice() throws Exception {
        addDeal(1, "Calculus Textbook", "SELL", "ACCEPTED", null, "2026-11-10T10:00:00", null);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listings SET price = 180, description = 'Eighth edition, barely used' WHERE listing_id = 1");
            s.executeUpdate("UPDATE listing_requests SET offer_price = 160 WHERE request_id = 1");
        }
        var details = DataService.getDealDetails(1);
        assertEquals("Calculus Textbook", details.deal().itemTitle());
        assertEquals("buyer", details.deal().buyerUsername());
        assertEquals("seller", details.deal().sellerUsername());
        assertEquals("Completed", details.deal().status());
        assertEquals("Electronics", details.category());
        assertEquals("Used", details.condition());
        assertEquals("Eighth edition, barely used", details.description());
        assertEquals("Library", details.meetupLocation());
        assertEquals(Double.valueOf(160), details.offerPrice());
        assertEquals("2026-11-01T09:00:00", details.requestedAt());
    }

    @Test public void unavailableAndUnacceptedRequestsCannotBeOpenedAsDealDetails() throws Exception {
        assertThrows(IllegalStateException.class, () -> DataService.getDealDetails(999));
        addDeal(1, "Pending Offer", "SELL", "PENDING", null, null, null);
        assertThrows(IllegalStateException.class, () -> DataService.getDealDetails(1));
    }
}
