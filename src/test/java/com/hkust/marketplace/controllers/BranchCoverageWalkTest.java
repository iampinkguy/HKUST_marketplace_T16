package com.hkust.marketplace.controllers;

import com.hkust.marketplace.controllers.admin.AdminFxTestSupport;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.controllers.admin.AdminUserService;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DialogStyle;
import com.hkust.marketplace.utils.PhotoViewer;
import com.hkust.marketplace.utils.SessionManager;
import com.hkust.marketplace.utils.StarRating;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Opens the real JavaFX screens against a temporary database so branch coverage
 * includes the buyer, seller, and admin pages.
 */
public class BranchCoverageWalkTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private String oldDatabaseUrl;
    private String oldChatPath;
    private Stage stage;
    private final AtomicBoolean closeDialogs = new AtomicBoolean(true);
    private int sellId;
    private int lendId;
    private int giftId;
    private int pendingId;
    private int soldId;

    @BeforeClass
    public static void toolkit() throws Exception {
        AdminFxTestSupport.startToolkit();
    }

    @Before
    public void database() throws Exception {
        oldDatabaseUrl = System.getProperty("marketplace.database.url");
        oldChatPath = System.getProperty("marketplace.chat.history.path");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + folder.getRoot().toPath().resolve("coverage.db"));
        System.setProperty("marketplace.chat.history.path",
                folder.getRoot().toPath().resolve("chat.json").toString());
        DatabaseManager.initialize();
        DatabaseManager.createUser("buyer", "Alice Buyer", "unused", UserRole.BUYER, "Hall 1", null, null);
        DatabaseManager.createUser("inactive", "Inactive Buyer", "unused", UserRole.BUYER, null, null, null);
        DatabaseManager.createUser("seller", "Charlie Seller", "unused", UserRole.SELLER, null,
                "I sell textbooks on campus.", "Library");
        DatabaseManager.createUser("seller2", "No Bio Seller", "unused", UserRole.SELLER, null, null, null);
        DatabaseManager.createUser("admin", "HKUST Admin", "unused", UserRole.ADMIN, null, null, null);
        sql("UPDATE users SET active = 0 WHERE username = 'inactive'");
        sql("UPDATE users SET average_rating = 4.4, rating_count = 4 WHERE username = 'seller'");

        sellId = listing("Calculus Textbook", "SELL", 180.0, null, "Textbooks", "LIVE", "Available");
        lendId = listing("Graphing Calculator", "LEND", null, 7, "Electronics", "LIVE", "Available");
        giftId = listing("Desk Lamp", "GIVEAWAY", null, null, "Others", "LIVE", "Available");
        pendingId = listing("Pending Mug", "SELL", 20.0, null, "Kitchenware", "PENDING_REVIEW", "Available");
        int rejected = listing("Blurry Camera", "SELL", 50.0, null, "Electronics", "REJECTED", "Unavailable");
        int reserved = listing("Reserved Fridge", "SELL", 300.0, null, "Furniture", "RESERVED", "Unavailable");
        int sold = listing("Sold Notebook", "SELL", 15.0, null, "Stationery", "SOLD", "Unavailable");
        soldId = sold;
        listing("Lent Camera", "LEND", null, 3, "Electronics", "LENT", "Unavailable");
        listing("Given Mug", "GIVEAWAY", null, null, "Kitchenware", "GIVEN", "Unavailable");
        listing("Removed Chair", "SELL", 25.0, null, "Furniture", "REMOVED", "Unavailable");
        int other = listing("Plain Charger", "SELL", 40.0, null, "Electronics", "LIVE", "Available");
        sql("UPDATE listings SET seller = 'seller2', seller_username = 'seller2' WHERE listing_id = " + other);
        sql("UPDATE listings SET rejection_reason = 'Photo is blurry' WHERE listing_id = " + rejected);
        sql("UPDATE listings SET numPhotos = 1, photo1 = 'uploads/missing.png' WHERE listing_id = " + sellId);

        request(sellId, "OFFER", "PENDING", 150.0, null, null, null);
        request(sellId, "OFFER", "DECLINED", 100.0, null, null, null);
        request(reserved, "OFFER", "ACCEPTED", 280.0, null, null, null);
        request(sold, "OFFER", "ACCEPTED", 15.0, "2026-11-22T12:00:00", null, "2026-11-22T11:00:00");
        request(lendId, "BORROW", "ACCEPTED", null, null, null, null);
        request(lendId, "BORROW", "ACCEPTED", null, "2026-11-23T12:00:00", "2026-11-23T12:00:00", null);
        request(giftId, "CLAIM", "ACCEPTED", null, "2026-11-21T15:00:00", null, null);
        request(sellId, "OFFER", "COUNTERED", 160.0, null, null, null);
        sql("""
                UPDATE listing_requests SET borrow_start = '2026-11-20T10:00:00', borrow_end = '2026-11-27T10:00:00',
                    due_at = '2026-11-27T10:00:00', proposed_days = 7
                WHERE listing_id = %d AND request_type = 'BORROW'
                """.formatted(lendId));

        sql("""
                INSERT INTO reviews (buyer_username, listing_id, rating, text, created_at, seller_reply)
                VALUES ('buyer', %d, 5.0, 'Very smooth transaction', '2026-11-22T12:30:00', 'Thanks')
                """.formatted(sold));
        sql("""
                INSERT INTO reviews (buyer_username, listing_id, rating, text, created_at)
                VALUES ('buyer', %d, 3.0, 'Late meetup', '2026-11-23T13:00:00')
                """.formatted(lendId));
        DatabaseManager.setFavourite("buyer", sellId, true);
        DatabaseManager.setFavourite("buyer", giftId, true);

        DatabaseManager.createNotification("buyer", "seller", "Request accepted",
                "Your request for Calculus Textbook was accepted.", "Offer", 1, sellId);
        DatabaseManager.createNotification("buyer", "seller", "New seller rating",
                "Thanks for rating Calculus Textbook.", "Rating", null, sellId);
        DatabaseManager.createNotification("buyer", "admin", "Listing removed",
                "Calculus Textbook was removed.", "System", null, sellId);
        DatabaseManager.createNotification("buyer", null, "Campus fair",
                "Meet at the library this week.", "Announcement", null, null);
        DatabaseManager.createNotification("buyer", "seller", "New chat message",
                "Charlie Seller sent you a message about Calculus Textbook", "Chat", null, sellId);
        DatabaseManager.createNotification("seller", "buyer", "New offer request",
                "buyer submitted an offer request for Calculus Textbook.", "Offer", 1, sellId);
        DatabaseManager.createNotification("seller", "admin", "Listing Approved",
                "Your listing 'Calculus Textbook' is now live!", "System", null, sellId);
        DatabaseManager.createNotification("seller", "buyer", "New seller rating",
                "buyer rated the deal for Sold Notebook 5 / 5.", "Rating", null, sold);
        DatabaseManager.createNotification("admin", "seller", "New Listing Submitted",
                "Pending Mug awaits review.", "Listing Review", null, pendingId);
        DatabaseManager.createNotification("admin", null, "Maintenance",
                "Service resumes at noon.", "Announcement", null, null);
        DatabaseManager.createNotification("admin", "buyer", "Listing Report",
                "buyer reported Desk Lamp.", "Listing Report", null, giftId);
    }

    @After
    public void cleanup() throws Exception {
        closeDialogs.set(false);
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) window.hide();
            return null;
        });
        SessionManager.logout();
        if (oldDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldDatabaseUrl);
        if (oldChatPath == null) System.clearProperty("marketplace.chat.history.path");
        else System.setProperty("marketplace.chat.history.path", oldChatPath);
    }

    @Test
    public void modelDisplayBranchesAreCovered() {
        Listing sell = new Listing();
        sell.setType(ListingType.SELL);
        sell.setPrice(180.0);
        assertEquals("HK$ 180", sell.getPriceDisplay());
        assertEquals("For Sale", sell.getTypeDisplay());
        sell.setType(ListingType.LEND);
        assertEquals("Borrow", sell.getPriceDisplay());
        assertEquals("For Lending", sell.getTypeDisplay());
        sell.setType(ListingType.GIVEAWAY);
        sell.setPrice(null);
        assertEquals("Free", sell.getPriceDisplay());
        assertEquals("Giveaway", sell.getTypeDisplay());
        sell.addPhotoPath("a.png");
        assertEquals(1, sell.getPhotoPaths().size());
        assertThrows(IllegalArgumentException.class, () -> sell.setPhotoPaths(List.of("1", "2", "3", "4", "5", "6", "7")));
        for (int i = 0; i < 5; i++) sell.addPhotoPath("p" + i);
        assertEquals(6, sell.getPhotoPaths().size());
        assertThrows(IllegalStateException.class, () -> sell.addPhotoPath("too-many"));

        User user = new User("buyer", "Alice Buyer", "hash", UserRole.BUYER);
        user.setBio("hello");
        user.setActive(false);
        user.setAverageRating(4.5);
        user.setRatingCount(2);
        assertTrue(user.toString().contains("Alice Buyer"));
        assertEquals("hello", user.getBio());

        Review review = new Review();
        review.setScore(3.5);
        review.setHidden(true);
        review.setFlagged(true);
        review.setSellerReply("Thanks");
        review.setCreatedAt(null);
        assertEquals(4, review.getRating());
        assertTrue(review.isHidden());
        assertEquals(null, review.getCreatedAt());

        Deal deal = new Deal();
        deal.setDealType(ListingType.LEND);
        deal.setStatus("Returned");
        deal.setRated(true);
        deal.setBorrowStart(LocalDate.of(2026, 11, 20));
        assertTrue(deal.isRated());
        assertEquals(ListingType.LEND, deal.getDealType());

        Message message = new Message("buyer", "buyer", "hello");
        message.setSender_role("BUYER");
        assertEquals("hello", message.getContent());
        ChatHistory history = new ChatHistory("1", new java.util.ArrayList<>(), false, true);
        history.appendMessage(message);
        history.setSeen_buyer(true);
        assertEquals(1, history.getMessages().size());
        assertTrue(history.isSeen_seller());

        Notification notification = new Notification("buyer", "Title", "Message", "Offer");
        notification.setRead(true);
        notification.setRequestId(4);
        notification.setListingId(sellId);
        assertTrue(notification.isRead());
        assertEquals(Integer.valueOf(sellId), notification.getListingId());

        assertTrue(StarRating.bar(0).contains("☆"));
        assertTrue(StarRating.bar(3.5).contains("◐"));
        assertTrue(StarRating.bar(5).startsWith("★★★★★"));
        assertTrue(StarRating.bar(-1).contains("☆"));
        assertTrue(StarRating.bar(9).startsWith("★★★★★"));
    }

    @Test
    public void serviceDecisionBranchesAreCovered() throws Exception {
        SessionManager.login(new User("buyer", "Alice Buyer", "unused", UserRole.BUYER));
        assertTrue(DataService.searchBuyerMarketplace(
                "calculus complete textbooks charlie library",
                "Textbooks", "For Sale", "Like New", 10.0, 200.0, "Price: Low to High").size() >= 1);
        DataService.searchBuyerMarketplace("zzz", "Sports Equipment", "LEND", "New", null, 5.0, "Price: High to Low");
        DataService.searchBuyerMarketplace(null, null, null, null, null, null, null);
        DatabaseManager.appendMessageToChatHistory(Integer.toString(sellId), "buyer",
                new Message("buyer", "buyer", "Still available?"));
        assertNotNull(DataService.getBuyerListingChat(Integer.toString(sellId)));
        SessionManager.login(new User("seller", "Charlie Seller", "unused", UserRole.SELLER));
        assertNotNull(DataService.getSellerListingChat(Integer.toString(sellId), "buyer"));
        assertThrows(IllegalArgumentException.class, () -> DataService.replyToReview(" ", " ", "ok"));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.replyToReview("buyer", Integer.toString(soldId), " "));
        assertThrows(IllegalArgumentException.class,
                () -> DataService.replyToReview("buyer", Integer.toString(soldId), "x".repeat(1001)));
        DataService.replyToReview("buyer", Integer.toString(soldId), "Glad it went smoothly.");
        assertThrows(IllegalStateException.class,
                () -> DataService.replyToReview("missing", Integer.toString(soldId), "Hello"));
        assertThrows(IllegalArgumentException.class, () -> DataService.flagReview("", Integer.toString(lendId)));
        DataService.flagReview("buyer", Integer.toString(lendId));
        assertThrows(IllegalStateException.class,
                () -> DataService.flagReview("buyer", Integer.toString(lendId)));

        int sale = listing("Counter Book", "SELL", 90.0, null, "Textbooks", "LIVE", "Available");
        int second = listing("Counter Pen", "SELL", 20.0, null, "Stationery", "LIVE", "Available");
        int borrow = listing("Counter Lamp", "LEND", null, 5, "Others", "LIVE", "Available");
        int gift = listing("Counter Plant", "GIVEAWAY", null, null, "Others", "LIVE", "Available");
        int priced = DatabaseManager.createListingRequest("buyer", sale, "OFFER", 80.0, null);
        sql("UPDATE listing_requests SET status = 'COUNTERED', counter_price = 85 WHERE request_id = " + priced);
        DatabaseManager.acceptBuyerCounter("buyer", priced);
        int unpriced = DatabaseManager.createListingRequest("buyer", second, "OFFER", 70.0, null);
        sql("UPDATE listing_requests SET status = 'COUNTERED', counter_price = NULL WHERE request_id = " + unpriced);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.acceptBuyerCounter("buyer", unpriced));
        int pending = DatabaseManager.createListingRequest("buyer", borrow, "BORROW", null, 3);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.acceptBuyerCounter("buyer", pending));
        sql("UPDATE listing_requests SET status = 'COUNTERED' WHERE request_id = " + pending);
        DatabaseManager.acceptBuyerCounter("buyer", pending);
        int claim = DatabaseManager.createListingRequest("buyer", gift, "CLAIM", null, null);
        sql("UPDATE listing_requests SET status = 'COUNTERED' WHERE request_id = " + claim);
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.acceptBuyerCounter("buyer", claim));
        assertThrows(IllegalStateException.class, () -> DatabaseManager.acceptBuyerCounter("buyer", 99999));

        SessionManager.login(new User("admin", "HKUST Admin", "unused", UserRole.ADMIN));
        DataService.removeListing(Integer.toString(pendingId));
        assertThrows(IllegalStateException.class, () -> DatabaseManager.removeListing(pendingId));
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.removeListing(99999));
        assertTrue(DataService.getAllUsers().size() >= 3);

        java.util.Map<String, Object> user = new java.util.HashMap<>();
        user.put("role", "BUYER");
        user.put("username", "buyer");
        user.put("full_name", "Alice Buyer");
        user.put("password_hash", "hash");
        user.put("residence_hall", "Hall 1");
        user.put("bio", null);
        user.put("preferred_meetup", "Library");
        user.put("active", 0);
        user.put("created_at", "2026-11-20T10:00:00");
        user.put("average_rating", 0);
        user.put("rating_count", 0);
        assertEquals("buyer", DataService.toUser(user).getUsername());
        user.put("active", 1);
        user.put("role", "NOT_A_ROLE");
        assertThrows(IllegalArgumentException.class, () -> DataService.toUser(user));
        assertThrows(IllegalArgumentException.class, () -> DataService.toUser(java.util.Map.of("username", "buyer")));
        assertThrows(IllegalArgumentException.class, () -> DataService.toUser("buyer"));

        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("listing_id", "4");
        row.put("item_name", "Counter Book");
        row.put("category", "Textbooks");
        row.put("item_condition", "Like New");
        row.put("description", "A book");
        row.put("photo_count", 2);
        row.put("photo1", "uploads/a.png");
        row.put("meetup_location", "Library");
        row.put("posted_date", "2026-11-20T10:00:00");
        row.put("availability_status", "Available");
        row.put("views", 3);
        row.put("approved", Boolean.TRUE);
        row.put("listing_type", "SELL");
        row.put("listing_status", "LIVE");
        row.put("price", 90);
        row.put("lending_days", null);
        row.put("seller", "seller");
        assertEquals("Counter Book", DataService.toListing((Object) row).getItemName());
        row.put("approved", Boolean.FALSE);
        row.put("price", null);
        row.put("lending_days", 5);
        row.put("listing_type", "LEND");
        row.put("listing_status", "LENT");
        DataService.toListing((Object) row);
        assertThrows(IllegalArgumentException.class, () -> DataService.toListing((Object) "row"));

        row.put("review_version", 1L);
        row.put("numPhotos", 2);
        row.put("seller_username", "seller");
        row.put("submitted_at", "2026-11-19T10:00:00");
        row.put("reviewed_at", "2026-11-20T11:00:00");
        row.put("rejection_reason", null);
        assertEquals("Counter Book", DataService.toListing(row).getTitle());
        row.put("listing_status", null);
        row.put("approved", -1);
        row.put("posted_date", null);
        row.put("seller_username", " ");
        row.put("seller", "seller");
        row.put("numPhotos", 1);
        row.put("photo1", " ");
        DataService.toListing(row);
        row.put("approved", 0);
        DataService.toListing(row);
        row.put("approved", 1);
        DataService.toListing(row);
        row.put("approved", 9);
        assertThrows(IllegalStateException.class, () -> DataService.toListing(row));

        java.util.Map<String, Object> chat = new java.util.HashMap<>();
        chat.put("listing_id", Integer.toString(sellId));
        chat.put("seen_buyer", 1);
        chat.put("seen_seller", 0);
        chat.put("message_count", 2);
        chat.put("message1", java.util.Map.of(
                "sender_id", "buyer", "sender_role", "buyer", "content", "Hi",
                "timestamp", "2026-11-22T10:00:00"));
        assertEquals(1, DataService.toChatHistory(chat).getMessages().size());

        Method relevance = DataService.class.getDeclaredMethod("searchRelevance", Listing.class, String[].class);
        relevance.setAccessible(true);
        Listing sample = new Listing();
        sample.setTitle("Calculus Textbook");
        sample.setDescription("A complete description");
        sample.setSellerFullName("Charlie Seller");
        sample.setSellerUsername("seller");
        sample.setCategory("Textbooks");
        sample.setMeetupLocation("Library");
        relevance.invoke(null, sample, new String[]{"calculus", "complete", "charlie", "textbooks", "library", "zzz"});
        relevance.invoke(null, new Listing(), new String[]{"calculus"});

        assertFalse(DialogStyle.isInternalUiError(null));
        assertFalse(DialogStyle.isInternalUiError(new RuntimeException()));
        assertFalse(DialogStyle.isInternalUiError(new RuntimeException("  ")));
        assertFalse(DialogStyle.isInternalUiError(new RuntimeException("plain failure")));
        assertTrue(DialogStyle.isInternalUiError(new RuntimeException("showAndWait is not allowed")));
        assertTrue(DialogStyle.isInternalUiError(new RuntimeException("animation or layout processing")));
        assertTrue(DialogStyle.isInternalUiError(new RuntimeException("not on FX application thread")));
        assertEquals("fallback", DialogStyle.userFacing((Throwable) null, "fallback"));
        assertNotNull(DialogStyle.userFacing((Throwable) null, null));
        assertEquals("fallback", DialogStyle.userFacing(new RuntimeException("  "), "fallback"));
        assertEquals("Saved", DialogStyle.userFacing("Saved", "fallback"));
        assertEquals("fallback", DialogStyle.userFacing("showAndWait is not allowed", "fallback"));
        assertEquals(null, DialogStyle.userFacing("animation or layout processing", null));
        assertNotNull(DialogStyle.userFacing((String) null, null));
        assertEquals("fallback", DialogStyle.userFacing("  ", "fallback"));

        int openBorrow = requestId("BORROW", false);
        DatabaseManager.returnBorrow("buyer", openBorrow, true);
        sql("UPDATE listing_requests SET due_at = '2020-01-01T00:00:00' WHERE request_id = " + openBorrow);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.returnBorrow("buyer", openBorrow, false));
        DatabaseManager.returnBorrow("buyer", openBorrow, true);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.returnBorrow("buyer", openBorrow, false));
        assertThrows(IllegalStateException.class, () -> DatabaseManager.returnBorrow("missing", 1, false));
        assertThrows(IllegalStateException.class, () -> DatabaseManager.completeBuyerDeal("buyer", openBorrow));
        assertThrows(IllegalStateException.class, () -> DatabaseManager.completeBuyerDeal("buyer", 99999));
        int openOffer = requestId("OFFER", false);
        DatabaseManager.completeBuyerDeal("buyer", openOffer);
        assertThrows(IllegalStateException.class, () -> DatabaseManager.completeBuyerDeal("buyer", openOffer));
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.deleteSellerListing("1", " ", 0));
        assertThrows(IllegalArgumentException.class, () -> DatabaseManager.deleteSellerListing("nope", "seller", 0));
        java.util.Map<String, Object> rejected = DatabaseManager.fetchRow("listings", "item_name", "Blurry Camera");
        assertThrows(IllegalStateException.class, () -> DatabaseManager.deleteSellerListing(
                rejected.get("listing_id").toString(), "seller", 999));
        DatabaseManager.deleteSellerListing(rejected.get("listing_id").toString(), "seller",
                ((Number) rejected.get("review_version")).longValue());

        Method validate = DataService.class.getDeclaredMethod("validateListingDetails", Listing.class);
        validate.setAccessible(true);
        Listing invalid = new Listing();
        expectListingRejected(validate, invalid);
        invalid.setType(ListingType.SELL);
        invalid.setTitle("  ");
        expectListingRejected(validate, invalid);
        invalid.setTitle(" Spare Mug ");
        invalid.setCategory("Not a category");
        expectListingRejected(validate, invalid);
        invalid.setCategory("Kitchenware");
        invalid.setCondition("Broken");
        expectListingRejected(validate, invalid);
        invalid.setCondition("Used");
        invalid.setDescription("  ");
        expectListingRejected(validate, invalid);
        invalid.setDescription(" A spare mug. ");
        invalid.setMeetupLocation("Off campus");
        expectListingRejected(validate, invalid);
        invalid.setMeetupLocation("Library");
        invalid.setPrice(-1.0);
        expectListingRejected(validate, invalid);
        invalid.setPrice(Double.NaN);
        expectListingRejected(validate, invalid);
        invalid.setPrice(12.0);
        validate.invoke(null, invalid);
        invalid.setType(ListingType.LEND);
        invalid.setLendingDays(0);
        expectListingRejected(validate, invalid);
        invalid.setLendingDays(4);
        validate.invoke(null, invalid);
        invalid.setType(ListingType.GIVEAWAY);
        validate.invoke(null, invalid);
    }

    private static void expectListingRejected(Method validate, Listing listing) throws Exception {
        try {
            validate.invoke(null, listing);
            throw new AssertionError("Invalid listing was accepted");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (!(ex.getCause() instanceof IllegalArgumentException)) throw ex;
        }
    }

    private int requestId(String type, boolean finished) {
        for (java.util.Map<String, Object> row : DatabaseManager.fetchRows("listing_requests", java.util.Map.of())) {
            if (!type.equals(row.get("request_type")) || !"ACCEPTED".equals(row.get("status"))) continue;
            boolean done = row.get("completed_at") != null || row.get("returned_at") != null;
            if (done == finished) return ((Number) row.get("request_id")).intValue();
        }
        throw new IllegalStateException("Missing " + type + " request");
    }

    @Test
    public void screensExerciseBuyerSellerAndAdminBranches() throws Exception {
        Thread closer = new Thread(this::closeExtraDialogs, "dialog-closer");
        closer.setDaemon(true);
        closer.start();
        try {
            exerciseLogins();
            exerciseRegistration();
            fx(() -> {
                PhotoViewer.show(null, "Desk");
                PhotoViewer.show(new WritableImage(2, 2), " ");
                PhotoViewer.show(new WritableImage(2, 2), "Desk Lamp");
                return null;
            });
            Object buyer = open("/fxml/buyer/BuyerDashboardView.fxml",
                    new User("buyer", "Alice Buyer", "unused", UserRole.BUYER));
            buyerPages(buyer);
            stopTimelines(buyer);
            hideStage();

            Object seller = open("/fxml/seller/SellerDashboardView.fxml",
                    new User("seller", "Charlie Seller", "unused", UserRole.SELLER));
            sellerPages(seller);
            stopTimelines(seller);
            hideStage();

            Object admin = open("/fxml/admin/AdminDashboardView.fxml",
                    new User("admin", "HKUST Admin", "unused", UserRole.ADMIN));
            adminPages(admin);
            stopTimelines(admin);
            fx(() -> {
                assertNotNull(StarRating.graphic(4.4));
                assertNotNull(StarRating.graphic(0));
                assertNotNull(StarRating.graphic(3.5));
                return null;
            });
        } finally {
            closeDialogs.set(false);
            closer.join(1000);
        }
    }

    private void exerciseRegistration() throws Exception {
        Object seller = show("/fxml/seller/SellerRegisterView.fxml");
        call(seller, "handleRegister");
        fill(seller, "usernameField", "buyer");
        call(seller, "handleRegister");
        fill(seller, "usernameField", "newsell");
        call(seller, "handleRegister");
        fill(seller, "fullNameField", "New Seller");
        fill(seller, "passwordField", "weak");
        call(seller, "handleRegister");
        fill(seller, "passwordField", "Secret1ok");
        fill(seller, "confirmPasswordField", "nope");
        call(seller, "handleRegister");
        fill(seller, "confirmPasswordField", "Secret1ok");
        fill(seller, "bioArea", "  ");
        call(seller, "handleRegister");
        call(seller, "togglePassword");
        call(seller, "togglePassword");
        call(seller, "toggleConfirmPassword");

        Object buyer = show("/fxml/buyer/BuyerRegisterView.fxml");
        call(buyer, "handleRegister");
        fill(buyer, "usernameField", "buyer");
        fill(buyer, "fullNameField", "Alice Two");
        fill(buyer, "passwordField", "Secret1ok");
        fill(buyer, "confirmPasswordField", "Secret1ok");
        call(buyer, "handleRegister");
        fill(buyer, "usernameField", "newbuyer");
        fill(buyer, "passwordField", "weak");
        fill(buyer, "confirmPasswordField", "weak");
        call(buyer, "handleRegister");
        fill(buyer, "passwordField", "Secret1ok");
        fill(buyer, "confirmPasswordField", "different");
        call(buyer, "handleRegister");
        fill(buyer, "confirmPasswordField", "Secret1ok");
        call(buyer, "handleRegister");
        call(buyer, "togglePassword");
        call(buyer, "toggleConfirmPassword");
        hideStage();
    }

    private static void fill(Object controller, String field, String value) throws Exception {
        fx(() -> {
            Field target = controller.getClass().getDeclaredField(field);
            target.setAccessible(true);
            ((javafx.scene.control.TextInputControl) target.get(controller)).setText(value);
            return null;
        });
    }

    private Object show(String fxml) throws Exception {
        return fx(() -> {
            FXMLLoader loader = loader(fxml);
            Parent root = loader.load();
            stage = new Stage();
            stage.setScene(new Scene(root, 900, 720));
            stage.show();
            return loader.getController();
        });
    }

    private void exerciseLogins() throws Exception {
        loginAttempt("/fxml/buyer/BuyerLoginView.fxml", "", "");
        loginAttempt("/fxml/buyer/BuyerLoginView.fxml", "seller", "unused");
        loginAttempt("/fxml/buyer/BuyerLoginView.fxml", "inactive", "unused");
        loginAttempt("/fxml/buyer/BuyerLoginView.fxml", "buyer", "wrong-password");
        loginAttempt("/fxml/seller/SellerLoginView.fxml", "buyer", "unused");
        loginAttempt("/fxml/seller/SellerLoginView.fxml", "", "secret");
        loginAttempt("/fxml/admin/AdminLoginView.fxml", "buyer", "unused");
        loginAttempt("/fxml/admin/AdminLoginView.fxml", "admin", "");
    }

    private void loginAttempt(String fxml, String username, String password) throws Exception {
        fx(() -> {
            Parent root = loader(fxml).load();
            TextField user = (TextField) root.lookup("#usernameField");
            PasswordField pass = (PasswordField) root.lookup("#passwordField");
            user.setText(username);
            pass.setText(password);
            Button toggle = (Button) root.lookup("#togglePasswordButton");
            toggle.fire();
            toggle.fire();
            Button login = null;
            for (javafx.scene.Node node : root.lookupAll(".button")) {
                if (node instanceof Button button && button.getText() != null
                        && button.getText().startsWith("Login")) {
                    login = button;
                }
            }
            if (login != null) login.fire();
            return null;
        });
    }

    private void exerciseBuyerDecisions(Object controller) throws Exception {
        Listing listing = DataService.peekBuyerListing(Integer.toString(sellId));
        DatabaseManager.appendMessageToChatHistory(listing.getId(), "buyer",
                new Message("buyer", "buyer", "Is the book still available?"));
        DatabaseManager.appendMessageToChatHistory(listing.getId(), "buyer",
                new Message("system", "system", "The seller accepted another offer."));
        call(controller, "showSellerProfile", new Class<?>[]{Listing.class}, listing);
        call(controller, "showChatbox", new Class<?>[]{Listing.class, boolean.class}, listing, true);
        call(controller, "showChatbox", new Class<?>[]{Listing.class, boolean.class}, listing, false);

        LocalDate today = LocalDate.now();
        call(controller, "borrowDateError", new Class<?>[]{LocalDate.class, LocalDate.class, int.class},
                null, null, 7);
        call(controller, "borrowDateError", new Class<?>[]{LocalDate.class, LocalDate.class, int.class},
                today.minusDays(1), today, 7);
        call(controller, "borrowDateError", new Class<?>[]{LocalDate.class, LocalDate.class, int.class},
                today, today, 7);
        call(controller, "borrowDateError", new Class<?>[]{LocalDate.class, LocalDate.class, int.class},
                today, today.plusDays(30), 7);
        call(controller, "borrowDateError", new Class<?>[]{LocalDate.class, LocalDate.class, int.class},
                today, today.plusDays(3), 7);
        call(controller, "lendingPeriodText", new Class<?>[]{Integer.class}, new Object[]{null});
        call(controller, "lendingPeriodText", new Class<?>[]{Integer.class}, 0);
        call(controller, "lendingPeriodText", new Class<?>[]{Integer.class}, 1);
        call(controller, "lendingPeriodText", new Class<?>[]{Integer.class}, 7);

        Review reply = new Review();
        reply.setComment("Smooth");
        reply.setSellerReply("Thanks");
        Review blank = new Review();
        blank.setComment("  ");
        java.util.Map<String, Review> reviews = java.util.Map.of(Integer.toString(sellId), reply, "9", blank);
        for (java.util.Map<String, Object> deal : java.util.List.of(
                deal("OFFER", "PENDING", sellId, null, null, null, null, null, 10.0),
                deal("BORROW", "ACCEPTED", lendId, null, null, null, null, "2026-11-27T18:00:00", null),
                deal("OFFER", "COUNTERED", sellId, null, null, null, null, null, 12.0),
                deal("CLAIM", "ACCEPTED", giftId, "2026-11-21T15:00:00", null, null, null, null, null),
                deal("OTHER", "PENDING", sellId, null, null, null, null, null, null),
                deal("BORROW", "ACCEPTED", lendId, null, "2026-11-23T12:00:00", null, null, null, null),
                deal("OFFER", "ACCEPTED", soldId, null, null, "2026-11-22T11:00:00", null, null, 15.0),
                deal("OFFER", "ACCEPTED", sellId, null, null, null, "2026-11-22T11:00:00", null, 15.0),
                deal("OFFER", "ACCEPTED", soldId, "2026-11-22T12:00:00", null, "2026-11-22T11:00:00",
                        "2026-11-22T11:00:00", null, 15.0),
                deal("OFFER", "DECLINED", sellId, "2026-11-22T12:00:00", null, null, null, null, 10.0),
                deal("CLAIM", "ACCEPTED", null, null, null, null, null, null, null))) {
            if (deal.get("listing_id") == null) deal.remove("listing_id");
            java.util.Map<String, Object> rated = new java.util.HashMap<>(deal);
            rated.put("rated", 1);
            call(controller, "dealCard", new Class<?>[]{java.util.Map.class, java.util.Map.class}, deal, reviews);
            call(controller, "dealCard", new Class<?>[]{java.util.Map.class, java.util.Map.class}, rated, reviews);
            call(controller, "isOngoing", new Class<?>[]{java.util.Map.class}, deal);
        }
        call(controller, "historyCard", new Class<?>[]{String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, String.class},
                "Calculus Textbook", "Charlie Seller", "Textbooks", "22 Nov 2026", "Bought", "Completed",
                "HK$15", Integer.toString(sellId));
        call(controller, "historyCard", new Class<?>[]{String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, String.class},
                "Calculator", "Charlie Seller", "Electronics", "23 Nov 2026", "Borrowed", "Returned",
                "7 days", Integer.toString(lendId));
        call(controller, "historyCard", new Class<?>[]{String.class, String.class, String.class, String.class,
                        String.class, String.class, String.class, String.class},
                "Lamp", "Charlie Seller", "Others", "21 Nov 2026", "Received", "Completed",
                "Free", null);

        java.util.Map<String, Object> acceptedDeal = java.util.Map.of("status", "ACCEPTED", "returned_at", "x");
        java.util.Map<String, Object> openDeal = java.util.Map.of("status", "ACCEPTED");
        for (String title : java.util.List.of("Request declined", "Listing no longer available",
                "Request accepted", "Borrow period ended", "Item returned", "Borrow due-date reminder",
                "Due tomorrow", "Hello")) {
            Notification notification = new Notification("buyer", title, "Calculus Textbook (request 3)", "Offer");
            call(controller, "statusMatchesNotification", new Class<?>[]{Notification.class, java.util.Map.class},
                    notification, title.contains("due") || title.contains("reminder") ? openDeal : acceptedDeal);
            call(controller, "dealForNotification", new Class<?>[]{Notification.class, java.util.List.class},
                    notification, java.util.List.of(deal("OFFER", "ACCEPTED", sellId, null, null, null, null, null, 10.0)));
        }
        Notification blankNotice = new Notification("buyer", null, "   ", "System");
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, java.util.Map.class},
                blankNotice, null);
        Notification linked = new Notification("buyer", "Update", "Please see Calculus Textbook", "Offer");
        linked.setListingId(sellId);
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, java.util.Map.class},
                linked, java.util.Map.of("listing_id", sellId));
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, java.util.Map.class},
                new Notification("buyer", "Update", "Please see Calculus Textbook", "Offer"), null);
        exerciseBuyerNotifications(controller);
    }

    private void exerciseBuyerNotifications(Object controller) throws Exception {
        java.util.Map<String, Object> finished = java.util.Map.of(
                "status", "ACCEPTED", "completed_at", "2026-11-22T12:00:00");
        openNotice(controller, "Listing no longer available", "System", Integer.toString(sellId), null);
        openNotice(controller, "Listing unavailable", "System", null, null);
        openNotice(controller, "Update", "System", Integer.toString(sellId), null);
        openNotice(controller, "Update", "System", Integer.toString(sellId), finished);
        openNotice(controller, "New rating", "Rating", null, null);
        openNotice(controller, "Request accepted", "Offer", null, null);
        openNotice(controller, "Seller sent a counter", "Deal", null, null);
        openNotice(controller, "Borrow due soon", "Reminder", null, null);
        openNotice(controller, "Item removed from the marketplace", "Other", null, null);
        openNotice(controller, "Listing taken down", "Other", "99999", null);
        openNotice(controller, "Campus fair", "Announcement", null, null);
        openNotice(controller, "Hello", "Other", "99999", null);
        openNotice(controller, null, null, null, null);
    }

    private void openNotice(Object controller, String title, String category, String listingId,
                            java.util.Map<String, Object> deal) throws Exception {
        Notification notification = new Notification("buyer", title, "Calculus Textbook", category);
        notification.setRead(true);
        if (listingId != null) notification.setListingId(Integer.parseInt(listingId));
        call(controller, "openBuyerNotification",
                new Class<?>[]{Notification.class, java.util.Map.class, String.class},
                notification, deal, listingId);
    }

    private static java.util.Map<String, Object> deal(String type, String status, Integer listingId,
                                                      String completed, String returned, String buyerDone,
                                                      String sellerDone, String due, Double price) {
        java.util.Map<String, Object> deal = new java.util.HashMap<>();
        deal.put("request_type", type);
        deal.put("status", status);
        deal.put("request_id", 1);
        deal.put("item_name", "Calculus Textbook");
        deal.put("seller_name", "Charlie Seller");
        deal.put("category", "Textbooks");
        deal.put("offer_price", price);
        deal.put("borrow_start", "2026-11-20");
        deal.put("borrow_end", "2026-11-27");
        deal.put("created_at", "2026-11-21T09:00:00");
        deal.put("updated_at", "2026-11-22T09:00:00");
        if (listingId != null) deal.put("listing_id", listingId);
        deal.put("completed_at", completed);
        deal.put("returned_at", returned);
        deal.put("buyer_completed_at", buyerDone);
        deal.put("seller_completed_at", sellerDone);
        deal.put("due_at", due);
        deal.put("rated", 0);
        return deal;
    }

    private void buyerPages(Object controller) throws Exception {
        set(controller, "dealCategory", "All Categories");
        set(controller, "dealType", "All Types");
        set(controller, "dealKeyword", "");
        set(controller, "dealSeller", "");
        set(controller, "dealSort", "Newest");
        call(controller, "showMyDeals");
        set(controller, "dealSort", "Oldest");
        call(controller, "showMyDeals");
        set(controller, "dealSort", "Title");
        call(controller, "showMyDeals");
        set(controller, "historyFrom", null);
        set(controller, "historyTo", null);
        set(controller, "historyCategory", "All Categories");
        set(controller, "historySeller", "");
        set(controller, "historyKeyword", "");
        call(controller, "showHistory");
        exerciseBuyerDecisions(controller);
        Class<?> mode = Class.forName("com.hkust.marketplace.controllers.buyer.BuyerDashboardController$DetailMode");
        for (String value : List.of("MARKET", "DEAL", "FINISHED", "HISTORY", "NOTICE", "NOTICE_FINISHED")) {
            call(controller, "showListingDetail", new Class<?>[]{String.class, mode},
                    Integer.toString(sellId), Enum.valueOf(mode.asSubclass(Enum.class), value));
            call(controller, "showListingDetail", new Class<?>[]{String.class, mode},
                    Integer.toString(lendId), Enum.valueOf(mode.asSubclass(Enum.class), value));
            call(controller, "showListingDetail", new Class<?>[]{String.class, mode},
                    Integer.toString(giftId), Enum.valueOf(mode.asSubclass(Enum.class), value));
        }
        Thread.sleep(1200);
        set(controller, "keyword", "book");
        set(controller, "category", "Textbooks");
        set(controller, "listingType", "Sell");
        set(controller, "condition", "Used");
        set(controller, "minPrice", 10.0);
        set(controller, "maxPrice", 200.0);
        set(controller, "sort", "Price: Low to High");
        call(controller, "showBrowse");
        set(controller, "sort", "Price: High to Low");
        set(controller, "listingType", "For Lending");
        set(controller, "category", "Electronics");
        set(controller, "keyword", "");
        set(controller, "minPrice", null);
        set(controller, "maxPrice", null);
        call(controller, "showBrowse");
        set(controller, "category", "Sports Equipment");
        set(controller, "keyword", "missing-item");
        call(controller, "showBrowse");

        set(controller, "dealKeyword", "book");
        set(controller, "dealCategory", "Textbooks");
        set(controller, "dealType", "For Sale");
        set(controller, "dealSeller", "charlie");
        set(controller, "dealSort", "Price: Low to High");
        call(controller, "showMyDeals");
        set(controller, "dealType", "For Lending");
        set(controller, "dealSort", "Price: High to Low");
        set(controller, "dealKeyword", "");
        call(controller, "showMyDeals");

        set(controller, "historyFrom", LocalDate.of(2026, 11, 1));
        set(controller, "historyTo", LocalDate.of(2026, 11, 30));
        set(controller, "historyCategory", "Textbooks");
        set(controller, "historySeller", "charlie");
        set(controller, "historyKeyword", "note");
        call(controller, "showHistory");
        set(controller, "historyFrom", null);
        set(controller, "historyTo", null);
        set(controller, "historyKeyword", "");
        call(controller, "showHistory");
        call(controller, "showFavourites");
        for (String category : List.of("All", "Offer", "Chat", "Reminder", "Rating", "System", "Announcement")) {
            // Category combo is rebuilt by the page; click through after the first render.
            call(controller, "showNotifications");
            fx(() -> {
                Parent content = (Parent) stage.getScene().lookup("#contentPane");
                content.lookupAll(".combo-box").forEach(node -> {
                    if (node instanceof javafx.scene.control.ComboBox<?> combo
                            && combo.getItems().contains(category)) {
                        @SuppressWarnings("unchecked")
                        javafx.scene.control.ComboBox<String> choices =
                                (javafx.scene.control.ComboBox<String>) combo;
                        choices.getSelectionModel().select(category);
                    }
                });
                return null;
            });
        }
        fireContentButtons("Go ->", "Check Seller", "Read All Notifications");
        call(controller, "showProfile");
        call(controller, "showBrowse");
    }

    private void sellerPages(Object controller) throws Exception {
        List<Listing> listings = DataService.getSellerListings();
        assertTrue(listings.size() >= 3);
        for (Listing listing : listings) {
            call(controller, "showListingDetails", new Class<?>[]{Listing.class, boolean.class}, listing, true);
            call(controller, "showListingDetails", new Class<?>[]{Listing.class, boolean.class}, listing, false);
        }
        set(controller, "listingKeyword", "book");
        set(controller, "listingCategory", "Textbooks");
        set(controller, "listingType", "For Sale");
        set(controller, "listingStatus", "Live");
        call(controller, "showMyListings");
        set(controller, "listingKeyword", "nothing-matches");
        set(controller, "listingStatus", "All Statuses");
        call(controller, "showMyListings");
        set(controller, "listingKeyword", "");
        set(controller, "listingCategory", "All Categories");
        set(controller, "listingType", "All Types");
        call(controller, "showMyListings");
        for (String status : List.of(
                "Active (Live / Pending / Reserved / Rejected)",
                "Finished (Sold / Lent / Given / Removed)",
                "Pending Review", "Live", "Rejected", "Reserved", "Sold", "Lent", "Given", "Removed", "Other")) {
            set(controller, "listingKeyword", "");
            set(controller, "listingCategory", "All Categories");
            set(controller, "listingType", "All Types");
            set(controller, "listingStatus", status);
            call(controller, "showMyListings");
        }
        call(controller, "showCreateListing");
        call(controller, "showStats");
        call(controller, "showReviews");
        for (String category : List.of("All", "Offer", "Chat", "Rating", "System")) {
            set(controller, "notificationCategory", category);
            call(controller, "showNotifications");
        }
        fireContentButtons("View", "Go ->", "View review", "Read All Notifications", "Edit");
        exerciseSellerForms(controller);
        call(controller, "showProfile");
    }

    private void exerciseSellerForms(Object controller) throws Exception {
        List<Listing> listings = DataService.getSellerListings();
        Notification linked = new Notification("seller", "New offer request",
                "buyer submitted an offer request for Calculus Textbook.", "Offer");
        linked.setListingId(sellId);
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, List.class}, linked, listings);
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, List.class},
                new Notification("seller", "Update", "Please see Desk Lamp and Calculus Textbook", "Offer"), listings);
        call(controller, "listingIdForNotification", new Class<?>[]{Notification.class, List.class},
                new Notification("seller", "Update", "   ", "System"), List.of());

        fx(() -> {
            TextField name = new TextField();
            PasswordField password = new PasswordField();
            PasswordField confirmation = new PasswordField();
            TextArea bio = new TextArea();
            ComboBox<String> meetup = new ComboBox<>();
            meetup.getItems().add("Library");
            Method method = controller.getClass().getDeclaredMethod("saveProfile", User.class, TextField.class,
                    PasswordField.class, PasswordField.class, TextArea.class, ComboBox.class);
            method.setAccessible(true);
            User seller = new User("seller", "Charlie Seller", "unused", UserRole.SELLER);
            method.invoke(controller, new Object[]{null, name, password, confirmation, bio, meetup});
            name.setText(" ");
            method.invoke(controller, seller, name, password, confirmation, bio, meetup);
            name.setText("Charlie Seller");
            password.setText("secret");
            confirmation.setText("other");
            method.invoke(controller, seller, name, password, confirmation, bio, meetup);
            confirmation.setText("secret");
            method.invoke(controller, seller, name, password, confirmation, bio, meetup);
            password.setText("Secret1ok");
            confirmation.setText("Secret1ok");
            bio.setText("  ");
            meetup.setValue("Library");
            method.invoke(controller, seller, name, password, confirmation, bio, meetup);
            password.clear();
            confirmation.clear();
            bio.setText("I sell textbooks on campus.");
            method.invoke(controller, seller, name, password, confirmation, bio, meetup);
            return null;
        });

        call(controller, "showListingForm", new Class<?>[]{Listing.class}, new Object[]{null});
        submitSellerForm(null, "", "", "", "", "", "");
        submitSellerForm("Sell", "Used Charger", "Electronics", "Used", "A spare charger for campus.", "bad", "");
        submitSellerForm("Lend", "Used Charger", "Electronics", "Used", "A spare charger for campus.", "", "0");
        submitSellerForm("Give Away", "Spare Cable", "Electronics", "Used", "A spare cable for campus.", "", "");
        Thread.sleep(600);
        if (!listings.isEmpty()) {
            call(controller, "showListingForm", new Class<?>[]{Listing.class}, listings.get(0));
            submitSellerForm(null, null, null, null, null, null, null);
            Thread.sleep(600);
        }
        Listing chatListing = listings.stream()
                .filter(listing -> Integer.toString(sellId).equals(listing.getId()))
                .findFirst().orElse(listings.isEmpty() ? null : listings.get(0));
        if (chatListing != null) {
            fx(() -> {
                Method method = controller.getClass().getDeclaredMethod(
                        "createSellerListingChat", Listing.class, String.class);
                method.setAccessible(true);
                Parent preferred = (Parent) method.invoke(controller, chatListing, "buyer");
                method.invoke(controller, chatListing, null);
                method.invoke(controller, chatListing, "stranger");
                preferred.lookupAll(".text-field").forEach(node -> {
                    if (node instanceof TextField field) field.setText("available");
                });
                preferred.lookupAll(".text-field").forEach(node -> {
                    if (node instanceof TextField field) field.setText("zzzz-no-match");
                });
                preferred.lookupAll(".text-area").forEach(node -> {
                    if (node instanceof TextArea area) area.setText(" ");
                });
                for (javafx.scene.Node node : List.copyOf(preferred.lookupAll(".button"))) {
                    if (node instanceof Button button && "Send".equals(button.getText())) button.fire();
                }
                preferred.lookupAll(".text-area").forEach(node -> {
                    if (node instanceof TextArea area) area.setText("I can meet at the library.");
                });
                for (javafx.scene.Node node : List.copyOf(preferred.lookupAll(".button"))) {
                    if (node instanceof Button button && ("Send".equals(button.getText())
                            || "Refresh".equals(button.getText()))) {
                        button.fire();
                    }
                }
                return null;
            });
        }
    }

    private void submitSellerForm(String type, String name, String category, String condition,
                                  String description, String price, String days) throws Exception {
        fx(() -> {
            Parent content = (Parent) stage.getScene().lookup("#contentPane");
            if (content == null) return null;
            if (type != null) {
                java.util.List<ComboBox<String>> combos = new java.util.ArrayList<>();
                content.lookupAll(".combo-box").forEach(node -> {
                    if (node instanceof ComboBox<?> combo) {
                        @SuppressWarnings("unchecked")
                        ComboBox<String> choices = (ComboBox<String>) combo;
                        combos.add(choices);
                    }
                });
                java.util.List<TextField> fields = new java.util.ArrayList<>();
                content.lookupAll(".text-field").forEach(node -> {
                    if (node instanceof TextField field) fields.add(field);
                });
                java.util.List<TextArea> areas = new java.util.ArrayList<>();
                content.lookupAll(".text-area").forEach(node -> {
                    if (node instanceof TextArea area) areas.add(area);
                });
                if (combos.size() >= 4) {
                    combos.get(0).setValue(type);
                    combos.get(1).setValue(category);
                    combos.get(2).setValue(condition);
                    combos.get(combos.size() - 1).setValue("Library");
                }
                if (!fields.isEmpty()) fields.get(0).setText(name);
                if (fields.size() > 1) fields.get(1).setText(price);
                if (fields.size() > 2) fields.get(2).setText(days);
                if (!areas.isEmpty()) areas.get(0).setText(description);
            }
            for (javafx.scene.Node node : content.lookupAll(".button")) {
                if (node instanceof Button button && button.getText() != null
                        && (button.getText().startsWith("Submit") || button.getText().startsWith("Save Changes"))) {
                    button.setDisable(false);
                    button.fire();
                }
            }
            return null;
        });
    }

    private void adminPages(Object controller) throws Exception {
        call(controller, "showReview");
        call(controller, "showAllListings");
        call(controller, "showUsers");
        call(controller, "showDeals");
        call(controller, "showRatings");
        call(controller, "showNotifications");
        call(controller, "showProfile");
        SessionManager.login(new User("admin", "HKUST Admin", "unused", UserRole.ADMIN));
        var accepted = DatabaseManager.fetchRows("listing_requests", java.util.Map.of("status", "ACCEPTED"));
        if (!accepted.isEmpty()) {
            call(controller, "showDealDetails", new Class<?>[]{int.class},
                    ((Number) accepted.get(0).get("request_id")).intValue());
        }
        call(controller, "showManagedListingForm", new Class<?>[]{Listing.class}, new Object[]{null});
        java.util.List<Listing> allListings = DataService.getAllListings();
        if (!allListings.isEmpty()) {
            call(controller, "showManagedListingForm", new Class<?>[]{Listing.class}, allListings.get(0));
        }
        for (Listing listing : allListings) {
            call(controller, "showListingDetails", new Class<?>[]{Listing.class, boolean.class}, listing, false);
            call(controller, "showListingDetails", new Class<?>[]{Listing.class, boolean.class}, listing, true);
        }
        fireContentButtons("Go ->", "Read All Notifications", "Refresh", "Clear filters");
        Listing pending = allListings.stream()
                .filter(listing -> "Pending Mug".equals(listing.getTitle()))
                .findFirst().orElse(allListings.isEmpty() ? null : allListings.get(0));
        if (pending != null) {
            call(controller, "confirmListingDecision", new Class<?>[]{Listing.class, boolean.class, boolean.class},
                    pending, true, false);
            call(controller, "confirmListingDecision", new Class<?>[]{Listing.class, boolean.class, boolean.class},
                    pending, false, true);
        }
        LocalDateTime created = LocalDateTime.now();
        call(controller, "confirmManagedUserChange", new Class<?>[]{AdminUserService.Account.class, boolean.class},
                new AdminUserService.Account("ghost", "Ghost User", UserRole.BUYER, null, null, null,
                        true, created, null, 0), true);
        call(controller, "confirmManagedUserChange", new Class<?>[]{AdminUserService.Account.class, boolean.class},
                new AdminUserService.Account("seller2", "No Bio Seller", UserRole.SELLER, null, null, "Library",
                        true, created, null, 0), false);
        call(controller, "confirmManagedUserChange", new Class<?>[]{AdminUserService.Account.class, boolean.class},
                new AdminUserService.Account("inactive", "Inactive User", UserRole.BUYER, null, null, null,
                        false, created, null, 0), false);
        driveAdminListingForm(controller);
    }

    private void driveAdminListingForm(Object controller) throws Exception {
        closeDialogs.set(false);
        call(controller, "showManagedListingForm", new Class<?>[]{Listing.class}, new Object[]{null});
        publishAdminListing(null, null, "", null, null, "", "", "", null);
        publishAdminListing("Sell", null, "", null, null, "", "", "", null);
        publishAdminListing("Sell", "seller", "", null, null, "", "", "", null);
        publishAdminListing("Sell", "seller", "Campus Mug", null, null, "", "", "", null);
        publishAdminListing("Sell", "seller", "Campus Mug", "Kitchenware", null, "", "", "", null);
        publishAdminListing("Sell", "seller", "Campus Mug", "Kitchenware", "Used", "", "", "", null);
        publishAdminListing("Sell", "seller", "Campus Mug", "Kitchenware", "Used", "A spare mug.", "", "", null);
        publishAdminListing("Sell", "seller", "Campus Mug", "Kitchenware", "Used", "A spare mug.", "bad", "", "Library");
        publishAdminListing("Lend", "seller", "Campus Mug", "Kitchenware", "Used", "A spare mug.", "", "0", "Library");
        publishAdminListing("Give Away", "seller", "Campus Mug", "Kitchenware", "Used", "A spare mug.", "", "", "Library");
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage dialog && dialog != stage) dialog.hide();
            }
            return null;
        });
        closeDialogs.set(true);
    }

    private void publishAdminListing(String type, String seller, String name, String category, String condition,
                                     String description, String price, String days, String meetup) throws Exception {
        fx(() -> {
            Stage dialog = null;
            for (Window window : Window.getWindows()) {
                if (window instanceof Stage candidate && "Add Listing".equals(candidate.getTitle())
                        && candidate.isShowing()) {
                    dialog = candidate;
                }
            }
            if (dialog == null) return null;
            Parent root = dialog.getScene().getRoot();
            choose(root, "#admin-listing-type", type);
            choose(root, "#admin-listing-seller", seller);
            write(root, "#admin-listing-name", name);
            choose(root, "#admin-listing-category", category);
            choose(root, "#admin-listing-condition", condition);
            if (root.lookup("#admin-listing-description") instanceof TextArea area) area.setText(description);
            write(root, "#admin-listing-price", price);
            write(root, "#admin-listing-days", days);
            choose(root, "#admin-listing-meetup", meetup);
            for (javafx.scene.Node node : root.lookupAll(".button")) {
                if (node instanceof Button button && "Publish Listing".equals(button.getText())) button.fire();
            }
            return null;
        });
        fx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (!(window instanceof Stage dialog) || !"Confirm Listing Changes".equals(dialog.getTitle())) continue;
                dialog.getScene().getRoot().lookupAll(".button").forEach(node -> {
                    if (node instanceof Button button && "Cancel".equals(button.getText())) button.fire();
                });
            }
            return null;
        });
    }

    private static void choose(Parent root, String id, String value) {
        if (root.lookup(id) instanceof ComboBox<?> combo) {
            @SuppressWarnings("unchecked")
            ComboBox<String> choices = (ComboBox<String>) combo;
            choices.setValue(value);
        }
    }

    private static void write(Parent root, String id, String value) {
        if (root.lookup(id) instanceof TextField field) field.setText(value == null ? "" : value);
    }

    private Object open(String fxml, User user) throws Exception {
        SessionManager.login(user);
        return fx(() -> {
            FXMLLoader loader = loader(fxml);
            Parent root = loader.load();
            stage = new Stage();
            stage.setScene(new Scene(root, 1200, 780));
            stage.show();
            return loader.getController();
        });
    }

    private void hideStage() throws Exception {
        fx(() -> {
            if (stage != null) stage.hide();
            return null;
        });
    }

    private void fireContentButtons(String... labels) throws Exception {
        fx(() -> {
            Parent content = (Parent) stage.getScene().lookup("#contentPane");
            if (content == null) return null;
            for (javafx.scene.Node node : List.copyOf(content.lookupAll(".button"))) {
                if (!(node instanceof Button button) || button.getText() == null || button.isDisabled()) continue;
                String text = button.getText().trim();
                for (String label : labels) {
                    if (text.equals(label)) button.fire();
                }
            }
            return null;
        });
    }

    private static FXMLLoader loader(String fxml) {
        return new FXMLLoader(BranchCoverageWalkTest.class.getResource(fxml));
    }

    private static void call(Object target, String name) throws Exception {
        call(target, name, new Class<?>[]{});
    }

    private static void call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        fx(() -> {
            method.invoke(target, args);
            return null;
        });
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void stopTimelines(Object target) throws Exception {
        fx(() -> {
            for (Field field : target.getClass().getDeclaredFields()) {
                if (!Timeline.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Timeline timeline = (Timeline) field.get(target);
                if (timeline != null) timeline.stop();
            }
            return null;
        });
    }

    private void closeExtraDialogs() {
        while (closeDialogs.get()) {
            try {
                fx(() -> {
                    Stage main = stage;
                    for (Window window : List.copyOf(Window.getWindows())) {
                        if (!(window instanceof Stage dialog) || dialog == main || dialog.getScene() == null) continue;
                        dialog.getScene().getRoot().lookupAll(".button").forEach(node -> {
                            if (!(node instanceof Button button) || button.getText() == null) return;
                            String text = button.getText().trim();
                            if (text.equals("Close") || text.equals("OK") || text.equals("X")
                                    || text.equals("Cancel") || text.equals("Apply")) {
                                button.fire();
                            }
                        });
                        if (window.isShowing() && window != main) {
                            boolean titled = dialog.getTitle() != null && !dialog.getTitle().isBlank();
                            if (titled) dialog.hide();
                        }
                    }
                    return null;
                });
                Thread.sleep(100);
            } catch (Exception ignored) {
                if (!closeDialogs.get()) return;
            }
        }
    }

    private int listing(String title, String type, Double price, Integer days, String category,
                        String status, String availability) throws Exception {
        int id = DatabaseManager.createListing(title, category, "Like New",
                "A complete description for " + title + ".", 0,
                null, null, null, null, null, null, "Library", "2026-11-20T10:00:00",
                availability, "seller", type, price, days);
        int approved = switch (status) {
            case "PENDING_REVIEW" -> -1;
            case "REJECTED" -> 0;
            default -> 1;
        };
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE listings SET listing_status = ?, availability_status = ?, approved = ?,
                         posted_date = ?, seller = 'seller', seller_username = 'seller'
                     WHERE listing_id = ?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, availability);
            statement.setInt(3, approved);
            statement.setString(4, "2026-11-20T10:00:00");
            statement.setInt(5, id);
            statement.executeUpdate();
        }
        return id;
    }

    private void request(int listingId, String type, String status, Double price,
                         String completedAt, String returnedAt, String buyerCompleted) throws Exception {
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO listing_requests
                         (buyer_username, listing_id, request_type, offer_price, status, created_at, updated_at,
                          completed_at, returned_at, buyer_completed_at)
                     VALUES ('buyer', ?, ?, ?, ?, '2026-11-21T09:00:00', '2026-11-22T09:00:00', ?, ?, ?)
                     """)) {
            statement.setInt(1, listingId);
            statement.setString(2, type);
            if (price == null) statement.setNull(3, java.sql.Types.REAL);
            else statement.setDouble(3, price);
            statement.setString(4, status);
            statement.setString(5, completedAt);
            statement.setString(6, returnedAt);
            statement.setString(7, buyerCompleted);
            statement.executeUpdate();
        }
    }

    private static void sql(String statement) throws Exception {
        try (Connection connection = DatabaseManager.connect();
             PreparedStatement prepared = connection.prepareStatement(statement)) {
            prepared.executeUpdate();
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(25, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unused")
    private static String labelText(Parent root) {
        StringBuilder text = new StringBuilder();
        for (javafx.scene.Node node : root.lookupAll(".label")) {
            if (node instanceof Labeled labeled && labeled.getText() != null) text.append(labeled.getText());
        }
        return text.toString();
    }
}
