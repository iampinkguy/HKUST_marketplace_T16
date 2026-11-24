package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BuyerMarketplaceCoverageTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;
    private String previousChatHistoryPath;

    @Before
    public void configureDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        previousChatHistoryPath = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("buyer-coverage.db"));
        System.setProperty("marketplace.chat.history.path",
                databaseFolder.getRoot().toPath().resolve("chat-history.json").toString());
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Test Admin", "unused", UserRole.ADMIN.name(), null, null, null);
        DatabaseManager.createUser("seller", "Test Seller", "unused", UserRole.SELLER.name(), null, null, null);
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), "Hall 1", null, null);
    }

    @After
    public void restore() {
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
        if (previousChatHistoryPath == null) System.clearProperty("marketplace.chat.history.path");
        else System.setProperty("marketplace.chat.history.path", previousChatHistoryPath);
        SessionManager.logout();
    }

    @Test
    public void passwordRulesAndBuyerRegistrationRejectWeakOrMismatchedInput() {
        assertEquals("Password cannot be empty.", DataService.checkPasswordStrength(null));
        assertEquals("Password cannot be empty.", DataService.checkPasswordStrength(""));
        assertEquals("Password must be at least 8 characters.", DataService.checkPasswordStrength("Ab1"));
        assertEquals("Password should contain uppercase, lowercase and a digit.",
                DataService.checkPasswordStrength("abcdefgh"));
        assertEquals("Password should contain uppercase, lowercase and a digit.",
                DataService.checkPasswordStrength("ABCDEFGH"));
        assertEquals("Password should contain uppercase, lowercase and a digit.",
                DataService.checkPasswordStrength("Abcdefgh"));
        assertEquals("OK", DataService.checkPasswordStrength("Abcdefg1"));

        assertThrows(IllegalArgumentException.class,
                () -> DataService.registerBuyer("  ", "Buyer", "Abcdefg1", "Abcdefg1", "Hall 1"));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.registerBuyer("newbuyer", "  ", "Abcdefg1", "Abcdefg1", "Hall 1"));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.registerBuyer("newbuyer", "New Buyer", "weak", "weak", null));
        DataService.BuyerInputException mismatch = assertThrows(DataService.BuyerInputException.class,
                () -> DataService.registerBuyer("newbuyer", "New Buyer", "Abcdefg1", "Abcdefg2", "Hall 2"));
        assertTrue(mismatch.isClearConfirmPassword());

        DataService.registerBuyer("  newbuyer  ", "  New Buyer  ", "Abcdefg1", "Abcdefg1", "  ");
        assertThrows(IllegalArgumentException.class,
                () -> DataService.registerBuyer("newbuyer", "Another", "Abcdefg1", "Abcdefg1", null));

        User signedIn = DataService.authenticate("newbuyer", "Abcdefg1", UserRole.BUYER);
        assertNotNull(signedIn);
        assertEquals("New Buyer", signedIn.getFullName());
        assertNull(signedIn.getResidenceHall());
        assertNull(DataService.authenticate("newbuyer", "wrong-pass", UserRole.BUYER));
        assertNull(DataService.authenticate("newbuyer", "Abcdefg1", UserRole.SELLER));
        assertNull(DataService.authenticate("  ", "Abcdefg1", UserRole.BUYER));
    }

    @Test
    public void buyerCanUpdateProfileAndChangePassword() {
        DataService.registerBuyer("profilebuyer", "Profile Buyer", "Abcdefg1", "Abcdefg1", "Hall 1");
        User user = DataService.authenticate("profilebuyer", "Abcdefg1", UserRole.BUYER);
        SessionManager.login(user);

        DataService.updateBuyerProfile("Updated Buyer", "Hall 3", "  ", "  ");
        assertEquals("Updated Buyer", SessionManager.getCurrentUser().getFullName());
        assertEquals("Hall 3", SessionManager.getCurrentUser().getResidenceHall());

        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateBuyerProfile("  ", "Hall 3", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateBuyerProfile("Updated Buyer", "Hall 3", "weakpass", "weakpass"));
        DataService.BuyerInputException mismatch = assertThrows(DataService.BuyerInputException.class,
                () -> DataService.updateBuyerProfile("Updated Buyer", "", "Abcdefg2", "nope"));
        assertTrue(mismatch.isClearConfirmPassword());

        DataService.updateBuyerProfile("Updated Buyer", null, "Abcdefg2", "Abcdefg2");
        SessionManager.logout();
        assertNotNull(DataService.authenticate("profilebuyer", "Abcdefg2", UserRole.BUYER));
        assertNull(DataService.authenticate("profilebuyer", "Abcdefg1", UserRole.BUYER));
    }

    @Test
    public void buyerCanSearchFavouriteOpenAndReportListings() {
        Listing cheap = publish(ListingType.SELL, "Cheap Textbook", 20.0, null, "Textbooks");
        Listing dear = publish(ListingType.SELL, "Dear Electronics", 80.0, null, "Electronics");
        Listing gift = publish(ListingType.GIVEAWAY, "Free Lamp", null, null, "Others");
        loginBuyer();

        List<Listing> byPrice = DataService.searchBuyerMarketplace(
                null, "All Categories", "All Types", "All Conditions", null, null, "Price: Low to High");
        assertEquals(cheap.getId(), byPrice.get(0).getId());
        assertEquals(dear.getId(), byPrice.get(1).getId());
        assertEquals(gift.getId(), byPrice.get(2).getId());

        List<Listing> expensive = DataService.searchBuyerMarketplace(
                "electronics", "Electronics", "Sell", "Used", 50.0, 100.0, "Price: High to Low");
        assertEquals(1, expensive.size());
        assertEquals(dear.getId(), expensive.get(0).getId());
        assertTrue(DataService.searchBuyerMarketplace(
                "missing-word", null, null, null, null, null, "Newest").isEmpty());

        assertNull(DataService.openBuyerListing("9999"));
        Listing opened = DataService.openBuyerListing(cheap.getId());
        assertEquals(1, opened.getViewCount());
        assertEquals(1, DataService.peekBuyerListing(cheap.getId()).getViewCount());

        DataService.setBuyerFavourite(cheap.getId(), true);
        DataService.setBuyerFavourite(gift.getId(), true);
        List<Integer> favouriteIds = DataService.getBuyerFavouriteIds();
        assertEquals(2, favouriteIds.size());
        assertTrue(favouriteIds.contains(Integer.parseInt(cheap.getId())));
        assertTrue(favouriteIds.contains(Integer.parseInt(gift.getId())));
        DataService.setBuyerFavourite(cheap.getId(), false);
        assertEquals(1, DataService.getBuyerFavouriteListings().size());
        assertThrows(IllegalArgumentException.class, () -> DataService.setBuyerFavourite("9999", true));

        DataService.reportBuyerListing(gift.getId());
        assertTrue(DataService.getBuyerFavouriteListings().isEmpty());
        assertTrue(DataService.searchBuyerMarketplace(null, null, null, null, null, null, null).stream()
                .noneMatch(listing -> listing.getId().equals(gift.getId())));
        assertThrows(IllegalStateException.class, () -> DataService.openBuyerListing(gift.getId()));
        assertThrows(IllegalStateException.class, () -> DataService.peekBuyerListing(gift.getId()));

        SessionManager.login(seller());
        assertThrows(SecurityException.class, DataService::getBuyerFavouriteIds);
    }

    @Test
    public void borrowRequestHonoursTheLendingWindowAndCanBeUpdated() {
        Listing lend = publish(ListingType.LEND, "Graphing Calculator", null, 7, "Electronics");
        Listing sell = publish(ListingType.SELL, "Spare Book", 10.0, null, "Textbooks");
        loginBuyer();
        LocalDateTime start = LocalDateTime.now();

        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(lend.getId(), null, start.plusSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(lend.getId(), start.minusMinutes(5), start));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(lend.getId(), start, start.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(lend.getId(), start, start.plusDays(8)));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(sell.getId(), start, start.plusSeconds(10)));

        int requestId = DataService.submitBorrowRequest(lend.getId(), start, start.plusSeconds(10));
        int updatedId = DataService.submitBorrowRequest(lend.getId(), start, start.plusDays(3));
        assertEquals(requestId, updatedId);

        Map<String, Object> request = DatabaseManager.fetchRow("listing_requests", "request_id", requestId);
        assertEquals("PENDING", request.get("status"));
        assertEquals(3, ((Number) request.get("proposed_days")).intValue());
        List<Notification> sellerNotes = DatabaseManager.fetchNotifications("seller");
        assertTrue(sellerNotes.stream().anyMatch(note ->
                "Updated borrow request".equals(note.getTitle())
                        && Integer.valueOf(Integer.parseInt(lend.getId())).equals(note.getListingId())
                        && Integer.valueOf(requestId).equals(note.getRequestId())));

        assertFalse(DataService.hasUnreadNotifications());
        SessionManager.login(seller());
        assertTrue(DataService.hasUnreadNotifications());
        Map<String, Integer> stats = DataService.getSellerActivityStats();
        assertEquals(1, stats.get("offers").intValue());
        assertEquals(0, stats.get("completed").intValue());
    }

    @Test
    public void acceptedBorrowCanBeReturnedAndAnOverdueBorrowSettlesAutomatically() {
        Listing returned = publish(ListingType.LEND, "Camera", null, 2, "Electronics");
        loginBuyer();
        LocalDateTime start = LocalDateTime.now();
        int requestId = DataService.submitBorrowRequest(returned.getId(), start, start.plusHours(2));
        SessionManager.login(seller());
        DataService.resolveSellerRequest(requestId, "ACCEPT", null);
        Map<String, Object> lent = DatabaseManager.fetchRow("listings", "listing_id", Integer.parseInt(returned.getId()));
        assertEquals("LENT", lent.get("listing_status"));

        loginBuyer();
        assertFalse(DataService.isBuyerDealFinished(DataService.getBuyerDeals().get(0)));
        DataService.selfReturnBorrow(requestId);
        Map<String, Object> live = DatabaseManager.fetchRow("listings", "listing_id", Integer.parseInt(returned.getId()));
        assertEquals("LIVE", live.get("listing_status"));
        assertEquals("Available", live.get("availability_status"));
        assertTrue(DataService.isBuyerDealFinished(DataService.getBuyerDeals().get(0)));
        assertTrue(DatabaseManager.fetchNotifications("seller").stream()
                .anyMatch(note -> "Borrowed item returned".equals(note.getTitle())));
        DataService.rateCompletedDeal(requestId, 4.5, "Careful borrower.");
        assertEquals(1, DataService.getBuyerReviews().size());
        assertThrows(IllegalStateException.class,
                () -> DataService.rateCompletedDeal(requestId, 5, "again"));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.rateCompletedDeal(requestId, 6, "too high"));

        Listing overdue = publish(ListingType.LEND, "Tripod", null, 1, "Electronics");
        loginBuyer();
        int overdueId = DataService.submitBorrowRequest(overdue.getId(), start, start.plusMinutes(30));
        SessionManager.login(seller());
        DataService.resolveSellerRequest(overdueId, "ACCEPT", null);
        setDueAt(overdueId, LocalDateTime.now().minusMinutes(1));
        loginBuyer();
        assertEquals(1, DataService.settleExpiredBorrows());
        assertTrue(DatabaseManager.fetchNotifications("seller").stream()
                .anyMatch(note -> "Borrowed item auto-returned".equals(note.getTitle())));
        loginBuyer();
        Notification reminder = DataService.getBuyerNotifications().stream()
                .filter(note -> "Borrow period ended".equals(note.getTitle()))
                .findFirst().orElseThrow();
        assertFalse(reminder.isRead());
        DataService.markBuyerNotificationRead(reminder.getId());
        assertTrue(DataService.getBuyerNotifications().stream()
                .filter(note -> note.getId().equals(reminder.getId()))
                .allMatch(Notification::isRead));
    }

    @Test
    public void claimedGiveawayCompletesWhenBothSidesConfirmAndChatNotifiesTheSeller() {
        Listing gift = publish(ListingType.GIVEAWAY, "Desk Lamp", null, null, "Others");
        loginBuyer();
        int requestId = DataService.submitBuyerRequest(gift.getId(), "CLAIM", null, null);
        DataService.sendBuyerListingChatMessage(gift.getId(), "I can collect this today.");
        assertFalse(DataService.completeBuyerDeal(requestId));

        SessionManager.login(seller());
        Listing current = DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(gift.getId()))
                .findFirst().orElseThrow();
        assertEquals("GIVEN", current.getStatus().name());
        assertTrue(DataService.completeSellerListing(current.getId(), current.getReviewVersion()));
        assertTrue(DataService.getSellerActivityStats().get("completed") >= 1);
        assertTrue(DatabaseManager.fetchNotifications("seller").stream().anyMatch(note ->
                "New chat message".equals(note.getTitle())
                        && Integer.valueOf(Integer.parseInt(gift.getId())).equals(note.getListingId())));

        loginBuyer();
        Map<String, Object> deal = DataService.getBuyerDeals().get(0);
        assertEquals("ACCEPTED", deal.get("status"));
        assertNotNull(deal.get("completed_at"));
        assertTrue(DataService.isBuyerDealFinished(deal));
        DataService.rateCompletedDeal(requestId, 5, null);
        Map<String, Object> rating = DataService.getSellerRating("seller");
        assertEquals(1, ((Number) rating.get("rating_count")).intValue());
        assertEquals(1, DataService.getSellerReviews("seller").size());
        assertTrue(DataService.getSellerReviews(" ").isEmpty());

        SessionManager.login(seller());
        assertThrows(SecurityException.class, DataService::getBuyerDeals);
        Listing stillLive = publish(ListingType.SELL, "Notebook", 15.0, null, "Stationery");
        loginBuyer();
        assertThrows(SecurityException.class, DataService::getSellerActivityStats);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBuyerRequest(stillLive.getId(), "UNKNOWN", null, null));
        assertTrue(invalid.getMessage().contains("Invalid request type"));
    }

    @Test
    public void buyerCannotBorrowOrClaimTheirOwnListing() throws Exception {
        Listing lend = publish(ListingType.LEND, "Own Charger", null, 1, "Electronics");
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE listings SET seller = ?, seller_username = ? WHERE listing_id = ?")) {
            statement.setString(1, "buyer");
            statement.setString(2, "buyer");
            statement.setInt(3, Integer.parseInt(lend.getId()));
            statement.executeUpdate();
        }
        loginBuyer();
        LocalDateTime start = LocalDateTime.now();
        IllegalArgumentException borrow = assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBorrowRequest(lend.getId(), start, start.plusSeconds(10)));
        assertTrue(borrow.getMessage().contains("your own listing"));

        Listing gift = publish(ListingType.GIVEAWAY, "Own Mug", null, null, "Others");
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE listings SET seller = ?, seller_username = ? WHERE listing_id = ?")) {
            statement.setString(1, "buyer");
            statement.setString(2, "buyer");
            statement.setInt(3, Integer.parseInt(gift.getId()));
            statement.executeUpdate();
        }
        loginBuyer();
        IllegalArgumentException claim = assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBuyerRequest(gift.getId(), "CLAIM", null, null));
        assertTrue(claim.getMessage().contains("your own listing"));
    }

    private Listing publish(ListingType type, String title, Double price, Integer lendingDays, String category) {
        SessionManager.login(seller());
        Listing listing = new Listing();
        listing.setTitle(title);
        listing.setCategory(category);
        listing.setCondition("Used");
        listing.setDescription("A complete description for " + title + ".");
        listing.setMeetupLocation("Library");
        listing.setType(type);
        listing.setPrice(price);
        listing.setLendingDays(lendingDays);
        DataService.submitListing(listing, List.of());
        SessionManager.login(admin());
        Listing pending = DataService.getPendingListings().stream()
                .filter(item -> item.getId().equals(listing.getId()))
                .findFirst().orElseThrow();
        DataService.approveListing(pending.getId(), pending.getReviewVersion());
        return listing;
    }

    private void setDueAt(int requestId, LocalDateTime dueAt) {
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE listing_requests SET due_at = ? WHERE request_id = ?")) {
            statement.setString(1, dueAt.toString());
            statement.setInt(2, requestId);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void loginBuyer() {
        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
    }

    private User seller() {
        return new User("seller", "Test Seller", "unused", UserRole.SELLER);
    }

    private User admin() {
        return new User("admin", "Test Admin", "unused", UserRole.ADMIN);
    }
}
