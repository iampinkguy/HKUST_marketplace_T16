package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
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

/**
 * Branch-heavy coverage for seller marketplace tasks: listing CRUD, offers/claims/loans,
 * completion, stats, reviews, chat, and auth guards.
 */
public class SellerMarketplaceCoverageTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;
    private String previousChatHistoryPath;
    private String passwordHash;

    @Before
    public void configureDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        previousChatHistoryPath = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("seller-coverage.db"));
        System.setProperty("marketplace.chat.history.path",
                databaseFolder.getRoot().toPath().resolve("chat-history.json").toString());
        DatabaseManager.initialize();
        passwordHash = DataService.hashPassword("SellerPass123");
        DatabaseManager.createUser("admin", "Test Admin", passwordHash, UserRole.ADMIN.name(), null, null, null);
        DatabaseManager.createUser("seller", "Test Seller", passwordHash, UserRole.SELLER.name(), null, null, null);
        DatabaseManager.createUser("buyer", "Test Buyer", passwordHash, UserRole.BUYER.name(), "Hall 1", null, null);
        DatabaseManager.createUser("buyer2", "Second Buyer", passwordHash, UserRole.BUYER.name(), null, null, null);
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
    public void sellerAuthAndSessionGuardsRejectBuyersAndInactiveAccounts() {
        assertNotNull(DataService.authenticate("seller", "SellerPass123", UserRole.SELLER));
        assertNull(DataService.authenticate("seller", "wrong", UserRole.SELLER));
        assertNull(DataService.authenticate("seller", "SellerPass123", UserRole.BUYER));
        assertNull(DataService.authenticate("  ", "SellerPass123", UserRole.SELLER));

        SessionManager.logout();
        assertThrows(SecurityException.class, DataService::getSellerListings);
        assertThrows(SecurityException.class, DataService::getSellerActivityStats);
        assertThrows(SecurityException.class, DataService::getPendingSellerRequests);
        assertThrows(SecurityException.class, DataService::getReviewsForCurrentSeller);

        SessionManager.login(buyer());
        assertThrows(SecurityException.class, () -> DataService.submitListing(baseListing(ListingType.SELL), List.of()));
        assertThrows(SecurityException.class, () -> DataService.canSellerModifyListing("1"));
        assertThrows(SecurityException.class, () -> DataService.listingHasOpenOffers("1"));
        assertThrows(SecurityException.class, () -> DataService.resolveSellerRequest(1, "ACCEPT", null));
        assertThrows(SecurityException.class, () -> DataService.completeSellerListing("1", 0));
        assertThrows(SecurityException.class, () -> DataService.getSellerCompletableDeal("1"));
        assertThrows(SecurityException.class, () -> DataService.replyToReview("buyer", "1", "ok"));
        assertThrows(SecurityException.class, () -> DataService.flagReview("buyer", "1"));

        DatabaseManager.setUserActive("seller", false);
        SessionManager.login(seller());
        assertThrows(SecurityException.class, DataService::getSellerListings);
        DatabaseManager.setUserActive("seller", true);
    }

    @Test
    public void sellerListingValidationCoversSellLendGiveawayAndNullGuards() {
        loginSeller();
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitListing(baseListing(ListingType.SELL), null));

        Listing missing = baseListing(ListingType.SELL);
        missing.setTitle(" ");
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(missing, List.of()));

        Listing badCategory = baseListing(ListingType.SELL);
        badCategory.setCategory("NotACategory");
        badCategory.setPrice(10.0);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(badCategory, List.of()));

        Listing sell = baseListing(ListingType.SELL);
        sell.setPrice(null);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(sell, List.of()));
        sell.setPrice(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(sell, List.of()));
        sell.setPrice(-2.0);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(sell, List.of()));

        Listing lend = baseListing(ListingType.LEND);
        lend.setLendingDays(null);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(lend, List.of()));
        lend.setLendingDays(0);
        assertThrows(IllegalArgumentException.class, () -> DataService.submitListing(lend, List.of()));
        lend.setLendingDays(5);
        lend.setPrice(99.0);
        DataService.submitListing(lend, List.of());
        assertNull(DataService.getSellerListings().get(0).getPrice());
        assertEquals(Integer.valueOf(5), DataService.getSellerListings().get(0).getLendingDays());

        Listing gift = baseListing(ListingType.GIVEAWAY);
        gift.setTitle("Free mug");
        gift.setPrice(5.0);
        gift.setLendingDays(2);
        DataService.submitListing(gift, List.of());
        Listing savedGift = DataService.getSellerListings().stream()
                .filter(item -> "Free mug".equals(item.getTitle())).findFirst().orElseThrow();
        assertNull(savedGift.getPrice());
        assertNull(savedGift.getLendingDays());
    }

    @Test
    public void sellerCanEditDeleteAndIsBlockedByOpenOffersOrTerminalStatus() {
        Listing published = publish(ListingType.SELL, "Desk Lamp", 40.0, null);
        loginSeller();
        Listing live = DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(published.getId())).findFirst().orElseThrow();
        assertTrue(DataService.canSellerModifyListing(live.getId()));
        assertFalse(DataService.listingHasOpenOffers(live.getId()));

        live.setTitle("Desk Lamp Plus");
        live.setPrice(35.0);
        DataService.updateSellerListing(live, List.of(), false);
        assertEquals(ListingStatus.PENDING_REVIEW, DataService.getSellerListings().get(0).getStatus());

        SessionManager.login(admin());
        Listing pending = DataService.getPendingListings().get(0);
        DataService.approveListing(pending.getId(), pending.getReviewVersion());

        loginBuyer();
        int offerId = DataService.submitBuyerRequest(published.getId(), "OFFER", 30.0, null);
        loginSeller();
        assertTrue(DataService.listingHasOpenOffers(published.getId()));
        assertFalse(DataService.canSellerModifyListing(published.getId()));
        assertEquals(1, DataService.getPendingSellerRequests().size());
        assertThrows(IllegalStateException.class,
                () -> DataService.deleteSellerListing(published.getId(),
                        DataService.getSellerListings().get(0).getReviewVersion()));

        DataService.resolveSellerRequest(offerId, "DECLINE", null);
        assertTrue(DataService.canSellerModifyListing(published.getId()));
        Listing current = DataService.getSellerListings().get(0);
        DataService.deleteSellerListing(current.getId(), current.getReviewVersion());
        // Offer history keeps the row as REMOVED instead of hard-deleting it.
        Listing removed = DataService.getSellerListings().get(0);
        assertEquals(ListingStatus.REMOVED, removed.getStatus());
        assertFalse(DataService.canSellerModifyListing(removed.getId()));

        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateSellerListing(null, List.of(), false));
        Listing noId = baseListing(ListingType.SELL);
        noId.setPrice(1.0);
        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateSellerListing(noId, List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateSellerListing(current, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.updateSellerListing(current,
                        java.util.Collections.nCopies(7, java.nio.file.Path.of("x.png")), false));
    }

    @Test
    public void sellerAcceptsSellOfferMarksCompleteAndTracksActivityStats() {
        Listing item = publish(ListingType.SELL, "Textbook", 80.0, null);
        loginBuyer();
        int requestId = DataService.submitBuyerRequest(item.getId(), "OFFER", 70.0, null);
        DataService.sendBuyerListingChatMessage(item.getId(), "Can we meet at the library?");

        loginSeller();
        Map<String, Integer> before = DataService.getSellerActivityStats();
        assertEquals(1, before.get("offers").intValue());
        assertEquals(0, before.get("completed").intValue());
        assertEquals(List.of("buyer"), DataService.getSellerListingChatBuyers(item.getId()));
        assertEquals("Can we meet at the library?",
                DataService.getSellerListingChat(item.getId(), "buyer").getMessages().get(0).getContent());
        DataService.sendSellerListingChatMessage(item.getId(), "buyer", "Yes, noon works.");
        assertThrows(IllegalArgumentException.class,
                () -> DataService.sendSellerListingChatMessage(item.getId(), "buyer", " "));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.resolveSellerRequest(requestId, "COUNTER", 75.0));

        Map<String, Object> open = DataService.getOpenListingRequestForBuyer(item.getId(), "buyer");
        assertNotNull(open);
        assertNull(DataService.getOpenListingRequestForBuyer(item.getId(), " "));
        DataService.resolveSellerRequest(requestId, "ACCEPT", null);

        Listing reserved = DataService.getSellerListings().get(0);
        assertEquals(ListingStatus.RESERVED, reserved.getStatus());
        Map<String, Object> deal = DataService.getSellerCompletableDeal(reserved.getId());
        assertNotNull(deal);
        assertFalse(DataService.completeSellerListing(reserved.getId(), reserved.getReviewVersion()));
        assertThrows(IllegalStateException.class,
                () -> DataService.completeSellerListing(reserved.getId(), reserved.getReviewVersion()));

        loginBuyer();
        assertTrue(DataService.completeBuyerDeal(requestId));
        assertTrue(DataService.isBuyerDealFinished(DataService.getBuyerDeals().get(0)));
        DataService.rateCompletedDeal(requestId, 4.0, "Smooth deal.");

        loginSeller();
        assertEquals(1, DataService.getSellerActivityStats().get("completed").intValue());
        List<Review> reviews = DataService.getReviewsForCurrentSeller();
        assertEquals(1, reviews.size());
        DataService.replyToReview("buyer", item.getId(), "Thanks for buying!");
        assertThrows(IllegalArgumentException.class, () -> DataService.replyToReview(" ", item.getId(), "x"));
        assertThrows(IllegalArgumentException.class, () -> DataService.flagReview(null, item.getId()));
        DataService.flagReview("buyer", item.getId());
    }

    @Test
    public void sellerHandlesGiveawayClaimAndBorrowReturnKeepsListingDisabled() {
        Listing gift = publish(ListingType.GIVEAWAY, "Free Chair", null, null);
        loginBuyer();
        int claimId = DataService.submitBuyerRequest(gift.getId(), "CLAIM", null, null);
        assertFalse(DataService.completeBuyerDeal(claimId));

        loginSeller();
        Listing given = DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(gift.getId())).findFirst().orElseThrow();
        assertEquals(ListingStatus.GIVEN, given.getStatus());
        assertEquals(0, DataService.getSellerActivityStats().get("completed").intValue());
        assertNotNull(DataService.getSellerCompletableDeal(given.getId()));
        assertTrue(DataService.completeSellerListing(given.getId(), given.getReviewVersion()));
        assertEquals(1, DataService.getSellerActivityStats().get("completed").intValue());
        assertNull(DataService.getSellerCompletableDeal(given.getId()));

        Listing camera = publish(ListingType.LEND, "Camera", null, 3);
        loginBuyer();
        LocalDateTime start = LocalDateTime.now();
        int borrowId = DataService.submitBorrowRequest(camera.getId(), start, start.plusHours(2));
        loginSeller();
        DataService.resolveSellerRequest(borrowId, "ACCEPT", null);
        assertEquals(ListingStatus.LENT, DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(camera.getId())).findFirst().orElseThrow().getStatus());
        assertEquals(1, DataService.getSellerActivityStats().get("completed").intValue());

        loginBuyer();
        DataService.selfReturnBorrow(borrowId);
        loginSeller();
        Listing afterReturn = DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(camera.getId())).findFirst().orElseThrow();
        assertEquals(ListingStatus.LENT, afterReturn.getStatus());
        assertEquals("Unavailable", afterReturn.getAvailabilityStatus());
        assertFalse(DataService.canSellerModifyListing(camera.getId()));
        assertEquals(2, DataService.getSellerActivityStats().get("completed").intValue());
        loginBuyer();
        assertThrows(IllegalStateException.class,
                () -> DataService.submitBorrowRequest(camera.getId(), start, start.plusHours(1)));
    }

    @Test
    public void sellerNotificationsCanBeReadIndividuallyAndInBulk() {
        loginSeller();
        DatabaseManager.createNotification("seller", "buyer", "Offer A", "First", "Offer");
        DatabaseManager.createNotification("seller", "buyer", "Chat B", "Second", "Chat");
        assertTrue(DataService.hasUnreadNotifications());
        List<Notification> notes = DataService.getNotificationsFor("seller");
        assertEquals(2, notes.size());
        DataService.markCurrentUserNotificationRead(notes.get(0).getId());
        assertFalse(DataService.getNotificationsFor("seller").get(0).isRead()
                && DataService.getNotificationsFor("seller").get(1).isRead());
        DataService.markAllCurrentUserNotificationsRead();
        assertTrue(DataService.getNotificationsFor("seller").stream().allMatch(Notification::isRead));
        assertFalse(DataService.hasUnreadNotifications());
    }

    @Test
    public void sellerRequestChatRequiresParticipationAndRejectsBlankMessages() {
        Listing item = publish(ListingType.SELL, "Notebook", 20.0, null);
        loginBuyer();
        int requestId = DataService.submitBuyerRequest(item.getId(), "OFFER", 18.0, null);
        DataService.sendRequestMessage(requestId, "Any discount?");
        loginSeller();
        assertEquals(1, DataService.getRequestMessages(requestId).size());
        DataService.sendRequestMessage(requestId, "Best I can do is 19.");
        assertEquals(2, DataService.getRequestMessages(requestId).size());
        assertThrows(IllegalArgumentException.class, () -> DataService.sendRequestMessage(requestId, " "));
        assertThrows(IllegalStateException.class, () -> DataService.acceptSellerCounter(requestId));

        SessionManager.login(new User("buyer2", "Second Buyer", passwordHash, UserRole.BUYER));
        assertThrows(SecurityException.class, () -> DataService.getRequestMessages(requestId));
        assertThrows(SecurityException.class, () -> DataService.sendRequestMessage(requestId, "Hi"));
    }

    @Test
    public void sellerDeclineBorrowAndRejectsInvalidListingIds() {
        Listing lend = publish(ListingType.LEND, "Tripod", null, 2);
        loginBuyer();
        LocalDateTime start = LocalDateTime.now();
        int requestId = DataService.submitBorrowRequest(lend.getId(), start, start.plusDays(1));
        loginSeller();
        DataService.resolveSellerRequest(requestId, "DECLINE", null);
        assertTrue(DataService.canSellerModifyListing(lend.getId()));
        assertEquals(ListingStatus.LIVE, DataService.getSellerListings().stream()
                .filter(item -> item.getId().equals(lend.getId())).findFirst().orElseThrow().getStatus());

        assertThrows(IllegalArgumentException.class, () -> DataService.canSellerModifyListing("abc"));
        assertThrows(IllegalArgumentException.class, () -> DataService.deleteSellerListing("0", 1));
        assertThrows(IllegalStateException.class,
                () -> DataService.completeSellerListing(lend.getId(),
                        DataService.getSellerListings().get(0).getReviewVersion()));
        assertNull(DataService.getSellerCompletableDeal(lend.getId()));
    }

    @Test
    public void sellerProfilePasswordRulesAndUpdatePersist() {
        assertEquals("Password cannot be empty.", DataService.checkPasswordStrength(null));
        assertEquals("Password cannot be empty.", DataService.checkPasswordStrength(""));
        assertEquals("Password must be at least 8 characters.", DataService.checkPasswordStrength("Ab1"));
        assertEquals("Password should contain uppercase, lowercase and a digit.",
                DataService.checkPasswordStrength("abcdefgh"));
        assertEquals("OK", DataService.checkPasswordStrength("SellerPass123"));

        DatabaseManager.updateUserProfile("seller", "Updated Seller", null, "Campus seller", "Library", null);
        var row = DatabaseManager.fetchRow("users", "username", "seller");
        assertEquals("Updated Seller", row.get("full_name"));
        assertEquals("Campus seller", row.get("bio"));
        assertEquals("Library", row.get("preferred_meetup"));
        String newHash = DataService.hashPassword("NewSellerPass1");
        DatabaseManager.updateUserProfile("seller", "Updated Seller", null, "Campus seller", "Library", newHash);
        assertNotNull(DataService.authenticate("seller", "NewSellerPass1", UserRole.SELLER));
        assertNull(DataService.authenticate("seller", "SellerPass123", UserRole.SELLER));
    }

    @Test
    public void sessionRolesAndListingDisplayHelpersCoverSellerBranches() {
        SessionManager.logout();
        assertFalse(SessionManager.isLoggedIn());
        assertFalse(SessionManager.isSeller());
        assertFalse(SessionManager.isBuyer());
        assertFalse(SessionManager.isAdmin());

        SessionManager.login(seller());
        assertTrue(SessionManager.isLoggedIn());
        assertTrue(SessionManager.isSeller());
        assertFalse(SessionManager.isBuyer());
        assertFalse(SessionManager.isAdmin());

        Listing sell = baseListing(ListingType.SELL);
        sell.setPrice(42.0);
        assertEquals("For Sale", sell.getTypeDisplay());
        assertEquals("HK$ 42", sell.getPriceDisplay());
        sell.addPhotoPath("uploads/a.jpg");
        assertEquals(1, sell.getPhotoPaths().size());
        assertThrows(IllegalArgumentException.class,
                () -> sell.setPhotoPaths(java.util.Collections.nCopies(7, "x.jpg")));

        Listing lend = baseListing(ListingType.LEND);
        assertEquals("For Lending", lend.getTypeDisplay());
        assertEquals("Borrow", lend.getPriceDisplay());
        Listing gift = baseListing(ListingType.GIVEAWAY);
        assertEquals("Giveaway", gift.getTypeDisplay());
        assertEquals("Free", gift.getPriceDisplay());
    }

    private Listing publish(ListingType type, String title, Double price, Integer lendingDays) {
        loginSeller();
        Listing listing = baseListing(type);
        listing.setTitle(title);
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

    private Listing baseListing(ListingType type) {
        Listing listing = new Listing();
        listing.setTitle("Seller coverage item");
        listing.setCategory("Electronics");
        listing.setCondition("Used");
        listing.setDescription("A complete description for coverage.");
        listing.setMeetupLocation("Library");
        listing.setType(type);
        if (type == ListingType.SELL) listing.setPrice(10.0);
        if (type == ListingType.LEND) listing.setLendingDays(3);
        return listing;
    }

    private void loginSeller() {
        SessionManager.login(seller());
    }

    private void loginBuyer() {
        SessionManager.login(buyer());
    }

    private User seller() {
        return new User("seller", "Test Seller", passwordHash, UserRole.SELLER);
    }

    private User buyer() {
        return new User("buyer", "Test Buyer", passwordHash, UserRole.BUYER);
    }

    private User admin() {
        return new User("admin", "Test Admin", passwordHash, UserRole.ADMIN);
    }
}
