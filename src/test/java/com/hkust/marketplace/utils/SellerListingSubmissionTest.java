package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SellerListingSubmissionTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;
    private String previousChatHistoryPath;

    @Before
    public void configureDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        previousChatHistoryPath = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("seller-listings.db"));
        System.setProperty("marketplace.chat.history.path",
                databaseFolder.getRoot().toPath().resolve("chat-history.json").toString());
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Test Admin", "unused", UserRole.ADMIN.name(), null, null, null);
        DatabaseManager.createUser("seller", "Test Seller", "unused", UserRole.SELLER.name(), null, null, null);
        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
    }

    @After
    public void restoreDatabaseUrl() {
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
        if (previousChatHistoryPath == null) System.clearProperty("marketplace.chat.history.path");
        else System.setProperty("marketplace.chat.history.path", previousChatHistoryPath);
        SessionManager.logout();
    }

    @Test
    public void sellSubmissionIsPendingVisibleToSellerAndNotifiesAdmin() {
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(125.50);

        DataService.submitListing(listing, List.of());

        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id",
                Integer.parseInt(listing.getId()));
        assertEquals("Pending review item", row.get("item_name"));
        assertEquals("SELL", row.get("listing_type"));
        assertEquals("PENDING_REVIEW", row.get("listing_status"));
        assertEquals(-1, ((Number) row.get("approved")).intValue());
        assertEquals("seller", row.get("seller_username"));
        assertEquals(125.50, ((Number) row.get("price")).doubleValue(), 0.001);
        assertNull(row.get("lending_days"));
        assertNull(row.get("posted_date"));
        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());

        List<Listing> sellerListings = DataService.getSellerListings();
        assertEquals(1, sellerListings.size());
        assertEquals(listing.getId(), sellerListings.get(0).getId());
        assertEquals(1, DatabaseManager.fetchRows("notifications", Map.of()).size());
        Map<String, Object> notification = DatabaseManager.fetchRows("notifications", Map.of()).get(0);
        assertEquals("admin", notification.get("username"));
        assertEquals("New Listing Submitted", notification.get("title"));
        assertTrue(notification.get("message").toString().contains("Pending review item"));
    }

    @Test
    public void giveawayClearsPriceAndDurationAndNegativeSellPriceIsRejected() {
        Listing giveaway = listing(ListingType.GIVEAWAY);
        giveaway.setPrice(25.0);
        giveaway.setLendingDays(7);
        DataService.submitListing(giveaway, List.of());

        Map<String, Object> giveawayRow = DatabaseManager.fetchRow("listings", "listing_id",
                Integer.parseInt(giveaway.getId()));
        assertNull(giveawayRow.get("price"));
        assertNull(giveawayRow.get("lending_days"));

        Listing invalid = listing(ListingType.SELL);
        invalid.setPrice(-1.0);
        try {
            DataService.submitListing(invalid, List.of());
            fail("Negative price should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-negative price"));
        }
        assertEquals(1, DatabaseManager.fetchRows("listings", Map.of()).size());
        assertEquals(1, DatabaseManager.fetchRows("notifications", Map.of()).size());
    }

    @Test
    public void selectedPhotoIsCopiedAndItsPathIsSaved() throws Exception {
        Path source = databaseFolder.getRoot().toPath().resolve("item.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        byte[] imageBytes = Files.readAllBytes(source);
        Listing listing = listing(ListingType.GIVEAWAY);
        Path storedPhoto = null;
        try {
            DataService.submitListing(listing, List.of(source));
            Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id",
                    Integer.parseInt(listing.getId()));
            storedPhoto = Path.of((String) row.get("photo1"));
            assertEquals(1, ((Number) row.get("numPhotos")).intValue());
            assertTrue(Files.exists(storedPhoto));
            assertArrayEquals(imageBytes, Files.readAllBytes(storedPhoto));
            assertEquals(List.of(row.get("photo1")), listing.getPhotoPaths());
        } finally {
            if (storedPhoto != null) Files.deleteIfExists(storedPhoto);
            Path uploads = Path.of("uploads");
            if (Files.isDirectory(uploads)) {
                try (var entries = Files.list(uploads)) {
                    if (entries.findAny().isEmpty()) Files.deleteIfExists(uploads);
                }
            }
        }
    }

    @Test
    public void moreThanSixPhotosAreRejectedBeforeSaving() {
        Listing listing = listing(ListingType.GIVEAWAY);
        List<Path> photos = java.util.Collections.nCopies(7, Path.of("unused.png"));
        try {
            DataService.submitListing(listing, photos);
            fail("More than six photos should be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("6 photos"));
        }
        assertTrue(DatabaseManager.fetchRows("listings", Map.of()).isEmpty());
        assertTrue(DatabaseManager.fetchRows("notifications", Map.of()).isEmpty());
    }

    @Test
    public void editingLiveListingResetsReviewStateAndNotifiesAdmin() {
        Listing original = listing(ListingType.SELL);
        original.setPrice(20.0);
        DataService.submitListing(original, List.of());

        SessionManager.login(new User("admin", "Test Admin", "unused", UserRole.ADMIN));
        Listing pending = DataService.getPendingListings().get(0);
        DataService.approveListing(pending.getId(), pending.getReviewVersion());
        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));

        Listing edited = DataService.getSellerListings().get(0);
        edited.setTitle("Updated listing title");
        edited.setPrice(15.0);
        DataService.updateSellerListing(edited, List.of(), false);

        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id",
                Integer.parseInt(edited.getId()));
        assertEquals("Updated listing title", row.get("item_name"));
        assertEquals("PENDING_REVIEW", row.get("listing_status"));
        assertEquals(-1, ((Number) row.get("approved")).intValue());
        assertNull(row.get("posted_date"));
        assertNull(row.get("reviewed_at"));
        assertEquals(15.0, ((Number) row.get("price")).doubleValue(), 0.001);
        assertEquals(ListingStatus.PENDING_REVIEW, edited.getStatus());

        List<Map<String, Object>> notifications = DatabaseManager.fetchRows("notifications", Map.of());
        assertEquals(3, notifications.size());
        assertTrue(notifications.stream().anyMatch(notification ->
                "Listing Updated - Review Required".equals(notification.get("title"))));
    }

    @Test
    public void deletingListingRemovesItAndItsFavourites() throws Exception {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.GIVEAWAY);
        DataService.submitListing(listing, List.of());
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO favourites (buyer_username, listing_id) VALUES (?, ?)")) {
            statement.setString(1, "buyer");
            statement.setInt(2, Integer.parseInt(listing.getId()));
            statement.executeUpdate();
        }

        DataService.deleteSellerListing(listing.getId(), listing.getReviewVersion());

        assertNull(DatabaseManager.fetchRow("listings", "listing_id", Integer.parseInt(listing.getId())));
        assertTrue(DatabaseManager.fetchRows("favourites", Map.of()).isEmpty());
        assertTrue(DataService.getSellerListings().isEmpty());
    }

    @Test
    public void staleListingEditIsRejectedWithoutChangingTheListing() {
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(20.0);
        DataService.submitListing(listing, List.of());
        listing.setReviewVersion(listing.getReviewVersion() + 1);
        listing.setTitle("Stale edit");

        assertThrows(IllegalStateException.class,
                () -> DataService.updateSellerListing(listing, List.of(), false));

        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id",
                Integer.parseInt(listing.getId()));
        assertEquals("Pending review item", row.get("item_name"));
        assertEquals(1, DatabaseManager.fetchRows("notifications", Map.of()).size());
    }

    @Test
    public void buyerOfferIsPersistedAndVisibleToTheOwningSeller() {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(50.0);
        DataService.submitListing(listing, List.of());
        publishListing(listing);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        int requestId = DataService.submitBuyerRequest(listing.getId(), "OFFER", 42.0, null);
        assertTrue(requestId > 0);

        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
        List<Map<String, Object>> requests = DataService.getPendingSellerRequests();
        assertEquals(1, requests.size());
        assertEquals(requestId, ((Number) requests.get(0).get("request_id")).intValue());
        assertEquals("OFFER", requests.get(0).get("request_type"));
        assertEquals(42.0, ((Number) requests.get(0).get("offer_price")).doubleValue(), 0.001);
        assertTrue(DatabaseManager.fetchRows("notifications", Map.of()).stream()
                .anyMatch(notification -> "New offer request".equals(notification.get("title"))));
    }

    @Test
    public void acceptingRequestReservesListingAndNotifiesBuyer() {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        DatabaseManager.createUser("other", "Other Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(50.0);
        DataService.submitListing(listing, List.of());
        publishListing(listing);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        int acceptedId = DataService.submitBuyerRequest(listing.getId(), "OFFER", 42.0, null);
        SessionManager.login(new User("other", "Other Buyer", "unused", UserRole.BUYER));
        DataService.submitBuyerRequest(listing.getId(), "OFFER", 40.0, null);

        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
        DataService.resolveSellerRequest(acceptedId, "ACCEPT", null);

        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id",
                Integer.parseInt(listing.getId()));
        assertEquals("RESERVED", row.get("listing_status"));
        assertEquals("Unavailable", row.get("availability_status"));
        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        assertEquals("ACCEPTED", DataService.getBuyerRequests().get(0).get("status"));
        assertTrue(DatabaseManager.fetchRows("notifications", Map.of()).stream()
                .anyMatch(notification -> "Request accepted".equals(notification.get("title"))));

        SessionManager.login(new User("other", "Other Buyer", "unused", UserRole.BUYER));
        assertEquals("DECLINED", DataService.getBuyerRequests().get(0).get("status"));
    }

    @Test
    public void counterofferAndPrivateRequestMessagesArePersisted() {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(50.0);
        DataService.submitListing(listing, List.of());
        publishListing(listing);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        int requestId = DataService.submitBuyerRequest(listing.getId(), "OFFER", 42.0, null);
        DataService.sendRequestMessage(requestId, "Would you consider a different price?");
        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
        assertEquals(1, DataService.getRequestMessages(requestId).size());
        DataService.resolveSellerRequest(requestId, "COUNTER", 47.0);
        assertTrue(DataService.getPendingSellerRequests().isEmpty());

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        Map<String, Object> request = DataService.getBuyerRequests().get(0);
        assertEquals("COUNTERED", request.get("status"));
        assertEquals(47.0, ((Number) request.get("counter_price")).doubleValue(), 0.001);
    }

    @Test
    public void buyerCannotRequestUnavailableListingsOrInvalidAmounts() {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(50.0);
        DataService.submitListing(listing, List.of());
        publishListing(listing);
        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));

        assertThrows(IllegalArgumentException.class,
                () -> DataService.submitBuyerRequest(listing.getId(), "OFFER", -1.0, null));
        assertTrue(DataService.getBuyerRequests().isEmpty());
    }

    @Test
    public void sellerCanDeclineAndAcceptingAnUnavailableListingIsRejected() throws Exception {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing unavailable = listing(ListingType.SELL);
        unavailable.setPrice(50.0);
        DataService.submitListing(unavailable, List.of());
        publishListing(unavailable);
        Listing declined = listing(ListingType.SELL);
        declined.setTitle("Second sell listing");
        declined.setPrice(30.0);
        DataService.submitListing(declined, List.of());
        publishListing(declined);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        int unavailableRequest = DataService.submitBuyerRequest(unavailable.getId(), "OFFER", 45.0, null);
        int declinedRequest = DataService.submitBuyerRequest(declined.getId(), "OFFER", 25.0, null);
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE listings SET listing_status = 'SOLD', availability_status = 'Unavailable'
                     WHERE listing_id = ?
                     """)) {
            statement.setInt(1, Integer.parseInt(unavailable.getId()));
            statement.executeUpdate();
        }

        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
        assertThrows(IllegalStateException.class,
                () -> DataService.resolveSellerRequest(unavailableRequest, "ACCEPT", null));
        DataService.resolveSellerRequest(declinedRequest, "DECLINE", null);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        Map<Integer, String> statuses = new java.util.HashMap<>();
        for (Map<String, Object> request : DataService.getBuyerRequests()) {
            statuses.put(((Number) request.get("request_id")).intValue(), (String) request.get("status"));
        }
        assertEquals("PENDING", statuses.get(unavailableRequest));
        assertEquals("DECLINED", statuses.get(declinedRequest));
    }

    @Test
    public void sellerCanSeeBuyerListingChatAndReplyInTheSameRoom() {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        DatabaseManager.createUser("otherBuyer", "Other Buyer", "unused", UserRole.BUYER.name(), null, null, null);
        Listing listing = listing(ListingType.SELL);
        listing.setPrice(50.0);
        DataService.submitListing(listing, List.of());
        publishListing(listing);

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        DataService.sendBuyerListingChatMessage(listing.getId(), "Is pickup available tomorrow?");
        ChatHistory buyerOneBeforeReply = DataService.getBuyerListingChat(listing.getId());
        assertEquals(1, buyerOneBeforeReply.getMessages().size());

        SessionManager.login(new User("otherBuyer", "Other Buyer", "unused", UserRole.BUYER));
        ChatHistory otherBuyerInitialChat = DataService.getBuyerListingChat(listing.getId());
        assertTrue(otherBuyerInitialChat.getMessages().isEmpty());
        DataService.sendBuyerListingChatMessage(listing.getId(), "Can I meet at the library?");
        assertEquals("Can I meet at the library?",
                DataService.getBuyerListingChat(listing.getId()).getMessages().get(0).getContent());

        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
        assertEquals(List.of("buyer", "otherBuyer"),
                DataService.getSellerListingChatBuyers(listing.getId()));
        ChatHistory sellerView = DataService.getSellerListingChat(listing.getId(), "buyer");
        assertEquals(1, sellerView.getMessages().size());
        assertEquals("Is pickup available tomorrow?", sellerView.getMessages().get(0).getContent());
        DataService.sendSellerListingChatMessage(listing.getId(), "buyer", "Yes, that works.");
        assertThrows(SecurityException.class,
                () -> DataService.getSellerListingChat(listing.getId(), "unrelatedBuyer"));

        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        ChatHistory buyerView = DataService.getBuyerListingChat(listing.getId());
        assertEquals(2, buyerView.getMessages().size());
        assertEquals("Yes, that works.", buyerView.getMessages().get(1).getContent());
        assertTrue(DatabaseManager.fetchRows("notifications", Map.of()).stream()
                .anyMatch(notification -> "New seller chat message".equals(notification.get("title"))));

        SessionManager.login(new User("otherBuyer", "Other Buyer", "unused", UserRole.BUYER));
        ChatHistory otherBuyerView = DataService.getBuyerListingChat(listing.getId());
        assertEquals(1, otherBuyerView.getMessages().size());
        assertEquals("Can I meet at the library?", otherBuyerView.getMessages().get(0).getContent());
    }

    private void publishListing(Listing listing) {
        SessionManager.login(new User("admin", "Test Admin", "unused", UserRole.ADMIN));
        Listing pending = DataService.getPendingListings().stream()
                .filter(item -> item.getId().equals(listing.getId()))
                .findFirst().orElseThrow();
        DataService.approveListing(pending.getId(), pending.getReviewVersion());
        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
    }

    private Listing listing(ListingType type) {
        Listing listing = new Listing();
        listing.setTitle("Pending review item");
        listing.setCategory("Textbooks");
        listing.setCondition("Used");
        listing.setDescription("A complete description.");
        listing.setMeetupLocation("Library");
        listing.setType(type);
        return listing;
    }
}
