package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.Review;
import com.hkust.marketplace.models.Deal;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;

public class DataService {

    public static final List<String> HKUST_LOCATIONS = List.of(
        "Academic Building (LG1)",
        "Library",
        "Student Centre",
        "Hall 1 Lobby",
        "Hall 2 Lobby",
        "Hall 3 Lobby",
        "Hall 4 Lobby",
        "Hall 5 Lobby",
        "Hall 6 Lobby",
        "Hall 7 Lobby",
        "Hall 8 Lobby",
        "Hall 9 Lobby",
        "UG Hall 1",
        "UG Hall 2",
        "North Gate",
        "South Gate",
        "Shaw Auditorium",
        "Coffee Shop (LG5)",
        "Sports Hall"
    );

    public static final List<String> CATEGORIES = List.of(
        "Textbooks", "Electronics", "Furniture", "Clothing",
        "Stationery", "Sports Equipment", "Kitchenware", "Others"
    );

    public static final List<String> CONDITIONS = List.of(
        "New", "Like New", "Used", "For Parts"
    );

    public static final List<String> RESIDENCES = List.of(
        "Hall 1", "Hall 2", "Hall 3", "Hall 4", "Hall 5",
        "Hall 6", "Hall 7", "Hall 8", "Hall 9",
        "UG Hall 1", "UG Hall 2", "Off-campus"
    );

    /** Returns the active user on success, or null for invalid credentials or role. */
    public static User authenticate(String username, String password, UserRole expectedRole) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || expectedRole == null) {
            return null;
        }

        Map<String, Object> row = DatabaseManager.fetchRow("users", "username", username.trim());
        if (row == null || ((Number) row.get("active")).intValue() == 0
                || !expectedRole.name().equals(row.get("role"))) {
            return null;
        }

        String storedHash = (String) row.get("password_hash");
        if (storedHash == null || storedHash.length() != 60) {
            return null;
        }

        try {
            if (!checkPassword(password, storedHash)) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            // Legacy plaintext or malformed hashes must not grant access.
            return null;
        }

        User user = new User();
        user.setUsername((String) row.get("username"));
        user.setFullName((String) row.get("full_name"));
        user.setPasswordHash(storedHash);
        user.setRole(expectedRole);
        user.setResidenceHall((String) row.get("residence_hall"));
        user.setBio((String) row.get("bio"));
        user.setPreferredMeetup((String) row.get("preferred_meetup"));
        user.setActive(((Number) row.get("active")).intValue() != 0);
        user.setCreatedAt(LocalDateTime.parse((String) row.get("created_at")));
        user.setAverageRating(((Number) row.get("average_rating")).doubleValue());
        user.setRatingCount(((Number) row.get("rating_count")).intValue());
        return user;
    }

    /** Seeds the supplied demo dataset once, without resetting existing records or changing the session. */
    public static void seedData() {
        String datasetKey = "marketplace-demo-v1";
        if (DatabaseManager.fetchRow("demo_data_seed", "dataset_key", datasetKey) != null) return;
        record DemoUser(String username, String fullName, String password, UserRole role,
                        String residenceHall, String bio, String preferredMeetup, double rating, int ratingCount) {}

        List<DemoUser> demoUsers = List.of(
                new DemoUser("admin", "HKUST Marketplace Admin", "admin123", UserRole.ADMIN,
                        null, null, null, 0, 0),
                new DemoUser("alice", "Alice Chan", "password1", UserRole.BUYER,
                        "Hall 3", null, null, 0, 0),
                new DemoUser("bob", "Bob Wong", "password1", UserRole.BUYER,
                        "Off-campus", null, null, 0, 0),
                new DemoUser("charlie", "Charlie Lee", "password1", UserRole.SELLER,
                        null, "Final year CSE student selling textbooks and gadgets.", "Library", 4.7, 12),
                new DemoUser("diana", "Diana Ng", "password1", UserRole.SELLER,
                        null, "Selling furniture and kitchen items before graduation.", "Hall 5 Lobby", 4.3, 8)
        );
        List<User> users = new ArrayList<>();
        for (DemoUser user : demoUsers) {
            User account = new User(user.username(), user.fullName(), hashPassword(user.password()), user.role());
            account.setResidenceHall(user.residenceHall());
            account.setBio(user.bio());
            account.setPreferredMeetup(user.preferredMeetup());
            account.setAverageRating(user.rating());
            account.setRatingCount(user.ratingCount());
            users.add(account);
        }

        record DemoListing(String key, String title, String description, String category, ListingType type,
                           String condition, Double price, Integer days, String meetup, String seller, ListingStatus status) {}
        List<DemoListing> demoListings = List.of(
                new DemoListing("calculus", "Calculus Textbook (Stewart)", "Barely used, 8th edition. Perfect for MATH 1013.",
                        "Textbooks", ListingType.SELL, "Like New", 180.0, null, "Library", "charlie", ListingStatus.LIVE),
                new DemoListing("charger", "MacBook Pro Charger 61W", "Original Apple charger, works perfectly.",
                        "Electronics", ListingType.SELL, "Used", 250.0, null, "Academic Building (LG1)", "charlie", ListingStatus.LIVE),
                new DemoListing("lamp", "IKEA Desk Lamp", "Good condition, LED bulb included.",
                        "Furniture", ListingType.GIVEAWAY, "Used", null, null, "Hall 5 Lobby", "diana", ListingStatus.LIVE),
                new DemoListing("calculator", "Graphing Calculator TI-84", "Can lend for one semester.",
                        "Electronics", ListingType.LEND, "Like New", null, 90, "Student Centre", "diana", ListingStatus.LIVE),
                new DemoListing("fridge", "Mini Fridge", "Works well, cleaning required.",
                        "Furniture", ListingType.SELL, "Used", 400.0, null, "Hall 2 Lobby", "diana", ListingStatus.PENDING_REVIEW),
                // Historical support record: reviews require a real listing ID in SQLite.
                new DemoListing("chemistry", "Old Chemistry Book", "Historical listing for the sample review.",
                        "Textbooks", ListingType.SELL, "Used", null, null, "Library", "charlie", ListingStatus.SOLD)
        );
        LocalDateTime now = LocalDateTime.now();
        List<Listing> listings = new ArrayList<>();
        for (DemoListing item : demoListings) {
            Listing listing = new Listing();
            listing.setId(item.key());
            listing.setTitle(item.title());
            listing.setDescription(item.description());
            listing.setCategory(item.category());
            listing.setType(item.type());
            listing.setCondition(item.condition());
            listing.setPrice(item.price());
            listing.setLendingDays(item.days());
            listing.setMeetupLocation(item.meetup());
            listing.setSellerUsername(item.seller());
            listing.setStatus(item.status());
            listing.setAvailabilityStatus(item.status() == ListingStatus.SOLD ? "Unavailable" : "Available");
            listing.setSubmittedAt(now);
            listing.setPostedAt(item.status() == ListingStatus.PENDING_REVIEW ? null : now);
            listings.add(listing);
        }

        Deal deal = new Deal();
        deal.setListingId("calculus");
        deal.setBuyerUsername("alice");
        deal.setSellerUsername("charlie");
        deal.setDealType(ListingType.SELL);
        deal.setStatus("Accepted");
        deal.setOfferPrice(160.0);
        List<Notification> notifications = List.of(
                new Notification("alice", "Offer Accepted", "Charlie accepted your offer of HK$160 for Calculus Textbook.", "Offer"),
                new Notification("charlie", "New Offer Received", "Alice offered HK$160 for your Calculus Textbook.", "Offer"),
                new Notification("diana", "Listing Pending", "Your Mini Fridge listing is awaiting admin review.", "System"),
                new Notification("admin", "New Listing Submitted", "Diana submitted 'Mini Fridge' for review.", "Listing Review")
        );
        Review review = new Review();
        review.setSellerUsername("charlie");
        review.setBuyerUsername("bob");
        review.setListingTitle("Old Chemistry Book");
        review.setRating(5);
        review.setComment("Very smooth transaction, book in excellent condition!");
        DatabaseManager.seedData(datasetKey, users, listings, List.of(deal), notifications, List.of(review),
                Map.of(notifications.get(3), "fridge"));
    }

    /** Loads persisted pending listings from SQLite. */
    public static List<Listing> getPendingListings() {
        requireAdmin();
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings", Map.of("approved", -1))) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getSubmittedAt).thenComparing(Listing::getId));
        return listings;
    }

    /** Checks the queue snapshot again when opening a submission. */
    public static Listing getListingForReview(String listingId, long expectedVersion) {
        Listing listing = getListingForAdmin(listingId);
        if (listing == null || listing.getStatus() != ListingStatus.PENDING_REVIEW
                || listing.getReviewVersion() != expectedVersion) {
            throw new IllegalStateException("Listing is unavailable or has changed since it was displayed. Please review it again.");
        }
        return listing;
    }

    /** Also used to show the current state after an unavailable/stale submission. */
    public static Listing getListingForAdmin(String listingId) {
        requireAdmin();
        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id", parseListingId(listingId));
        return row == null ? null : toListing(row);
    }

    /** The expected version must come from the submission actually displayed to the admin. */
    public static void approveListing(String listingId, long expectedVersion) {
        String adminUsername = requireAdmin();
        DatabaseManager.reviewListing(parseListingId(listingId), adminUsername, true, null, expectedVersion);
    }

    public static void rejectListing(String listingId, String reason, long expectedVersion) {
        String adminUsername = requireAdmin();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required.");
        }
        DatabaseManager.reviewListing(parseListingId(listingId), adminUsername, false, reason.trim(), expectedVersion);
    }

    /** Shared read for the buyer team's database integration; approval publishes into this result. */
    public static List<Listing> getLiveListings() {
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings", Map.of("listing_status", ListingStatus.LIVE.name()))) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getPostedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Listing::getId));
        return listings;
    }

    public static List<Listing> getSellerListings() {
        String sellerUsername = requireSeller();
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings",
                Map.of("seller_username", sellerUsername))) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Listing::getId));
        return listings;
    }

    public static void submitListing(Listing listing, List<Path> photos) {
        String sellerUsername = requireSeller();
        if (listing == null || photos == null) {
            throw new IllegalArgumentException("Listing details and photos are required.");
        }
        if (photos.size() > 6) {
            throw new IllegalArgumentException("A listing cannot have more than 6 photos.");
        }
        validateListingDetails(listing);
        listing.setStatus(ListingStatus.PENDING_REVIEW);
        DatabaseManager.submitListing(listing, sellerUsername, List.copyOf(photos));
    }

    public static void updateSellerListing(Listing listing, List<Path> photos, boolean replacePhotos) {
        String sellerUsername = requireSeller();
        if (listing == null || listing.getId() == null || photos == null) {
            throw new IllegalArgumentException("A listing, its ID, and photos are required.");
        }
        if (photos.size() > 6) {
            throw new IllegalArgumentException("A listing cannot have more than 6 photos.");
        }
        validateListingDetails(listing);
        listing.setTitle(listing.getTitle().trim());
        listing.setDescription(listing.getDescription().trim());
        DatabaseManager.updateSellerListing(listing, sellerUsername, List.copyOf(photos), replacePhotos);
    }

    public static void deleteSellerListing(String listingId, long expectedVersion) {
        String sellerUsername = requireSeller();
        DatabaseManager.deleteSellerListing(listingId, sellerUsername, expectedVersion);
    }

    /**
     * Strict rule: seller may edit/delete only with no PENDING/COUNTERED/ACCEPTED offers
     * and listing not reserved/sold/lent/given/removed.
     */
    public static boolean canSellerModifyListing(String listingId) {
        String seller = requireSeller();
        return DatabaseManager.canSellerModifyListing(seller, parseListingId(listingId));
    }

    public static boolean listingHasOpenOffers(String listingId) {
        String seller = requireSeller();
        return DatabaseManager.listingHasOpenOffers(seller, parseListingId(listingId));
    }

    public static Map<String, Integer> getSellerActivityStats() {
        return DatabaseManager.fetchSellerActivityStats(requireSeller());
    }

    /** @return true if both buyer and seller have confirmed and the listing deal is fully completed */
    public static boolean completeSellerListing(String listingId, long expectedVersion) {
        String sellerUsername = requireSeller();
        return DatabaseManager.completeSellerListing(listingId, sellerUsername, expectedVersion);
    }

    public static Map<String, Object> getSellerCompletableDeal(String listingId) {
        String sellerUsername = requireSeller();
        return DatabaseManager.fetchSellerCompletableDeal(listingId, sellerUsername);
    }

    private static void validateListingDetails(Listing listing) {
        if (listing.getType() == null
                || listing.getTitle() == null || listing.getTitle().isBlank()
                || listing.getCategory() == null || !CATEGORIES.contains(listing.getCategory())
                || listing.getCondition() == null || !CONDITIONS.contains(listing.getCondition())
                || listing.getDescription() == null || listing.getDescription().isBlank()
                || listing.getMeetupLocation() == null || !HKUST_LOCATIONS.contains(listing.getMeetupLocation())) {
            throw new IllegalArgumentException("Complete all required listing details.");
        }

        switch (listing.getType()) {
            case SELL -> {
                if (listing.getPrice() == null || !Double.isFinite(listing.getPrice()) || listing.getPrice() < 0) {
                    throw new IllegalArgumentException("Enter a valid non-negative price for a Sell listing.");
                }
                listing.setLendingDays(null);
            }
            case LEND -> {
                if (listing.getLendingDays() == null || listing.getLendingDays() <= 0) {
                    throw new IllegalArgumentException("Enter a positive lending duration in days.");
                }
                listing.setPrice(null);
            }
            case GIVEAWAY -> {
                listing.setPrice(null);
                listing.setLendingDays(null);
            }
        }
        listing.setTitle(listing.getTitle().trim());
        listing.setDescription(listing.getDescription().trim());
    }

    private static String requireSeller() {
        User user = SessionManager.getCurrentUser();
        if (user == null || user.getRole() != UserRole.SELLER || !user.isActive()) {
            throw new SecurityException("An active seller account is required.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", user.getUsername());
        if (account == null || !"SELLER".equals(account.get("role"))
                || ((Number) account.get("active")).intValue() != 1) {
            throw new SecurityException("An active seller account is required.");
        }
        return (String) account.get("username");
    }

    public static List<Map<String, Object>> getPendingSellerRequests() {
        return DatabaseManager.fetchSellerPendingRequests(requireSeller());
    }

    public static List<Map<String, Object>> getBuyerRequests() {
        return DatabaseManager.fetchBuyerRequests(requireBuyer());
    }

    public static int submitBuyerRequest(String listingId, String requestType,
                                         Double offerPrice, Integer proposedDays) {
        String buyer = requireBuyer();
        int id = parseListingId(listingId);
        Map<String, Object> listing = DatabaseManager.fetchRow("listings", "listing_id", id);
        if (listing == null || !"LIVE".equals(listing.get("listing_status"))
                || !"Available".equals(listing.get("availability_status"))) {
            throw new IllegalStateException("This listing is no longer available.");
        }
        String type = requestType == null ? "" : requestType.toUpperCase();
        String listingType = (String) listing.get("listing_type");
        switch (type) {
            case "OFFER" -> {
                if (!"SELL".equals(listingType) || offerPrice == null
                        || !Double.isFinite(offerPrice) || offerPrice <= 0) {
                    throw new IllegalArgumentException("Enter a valid positive offer for a sell listing.");
                }
            }
            case "BORROW" -> {
                if (!"LEND".equals(listingType) || proposedDays == null || proposedDays <= 0) {
                    throw new IllegalArgumentException("Enter a valid borrowing duration.");
                }
            }
            case "CLAIM" -> {
                if (!"GIVEAWAY".equals(listingType)) {
                    throw new IllegalArgumentException("Claims are only available for giveaway listings.");
                }
                if (buyer.equalsIgnoreCase((String) listing.get("seller"))) {
                    throw new IllegalArgumentException("You cannot request your own listing.");
                }
                return DatabaseManager.claimGiveaway(buyer, id);
            }
            default -> throw new IllegalArgumentException("Invalid request type.");
        }
        if (buyer.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new IllegalArgumentException("You cannot request your own listing.");
        }
        return DatabaseManager.createListingRequest(buyer, id, type, offerPrice, proposedDays);
    }

    public static void resolveSellerRequest(int requestId, String action, Double counterPrice) {
        resolveSellerRequest(requestId, action, counterPrice, null);
    }

    public static void resolveSellerRequest(int requestId, String action, Double counterPrice,
                                            Integer counterDays) {
        String seller = requireSeller();
        if ("COUNTER".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException(
                    "Sellers cannot submit a formal counter. Negotiate in chat; only the buyer can offer a price.");
        }
        DatabaseManager.resolveListingRequest(seller, requestId, action, counterPrice, counterDays);
    }

    /** Latest PENDING or COUNTERED request between this buyer and listing, if any. */
    public static Map<String, Object> getOpenListingRequest(String listingId) {
        String buyer = requireBuyer();
        return DatabaseManager.fetchOpenListingRequest(parseListingId(listingId), buyer);
    }

    /** Seller view of the open request from a specific buyer on a listing. */
    public static Map<String, Object> getOpenListingRequestForBuyer(String listingId, String buyerUsername) {
        requireSeller();
        if (buyerUsername == null || buyerUsername.isBlank()) return null;
        return DatabaseManager.fetchOpenListingRequest(parseListingId(listingId), buyerUsername.trim());
    }

    /**
     * @deprecated Formal seller counters were removed. Buyers update their own offer; sellers Accept/Decline.
     */
    @Deprecated
    public static void acceptSellerCounter(int requestId) {
        throw new IllegalStateException(
                "Seller counters are no longer used. Update your offer in chat; the seller can Accept or Decline.");
    }

    public static List<Map<String, Object>> getRequestMessages(int requestId) {
        String username = requireAuthenticatedUser();
        if (!isRequestParticipant(username, requestId)) {
            throw new SecurityException("You are not a participant in this request.");
        }
        return DatabaseManager.fetchRequestMessages(requestId);
    }

    public static void sendRequestMessage(int requestId, String content) {
        String username = requireAuthenticatedUser();
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Messages must contain 1 to 2000 characters.");
        }
        if (!isRequestParticipant(username, requestId)) {
            throw new SecurityException("You are not a participant in this request.");
        }
        User user = SessionManager.getCurrentUser();
        DatabaseManager.appendRequestMessage(requestId, username, user.getRole().name(), content.trim());
        Map<String, Object> request = DatabaseManager.fetchRow("listing_requests", "request_id", requestId);
        if (request != null) {
            String recipient = username.equals(request.get("buyer_username"))
                    ? (String) DatabaseManager.fetchRow("listings", "listing_id",
                            ((Number) request.get("listing_id")).intValue()).get("seller")
                    : (String) request.get("buyer_username");
            DatabaseManager.createNotification(recipient, username, "New request chat message",
                    "You have a new message about a listing request.", "Chat");
        }
    }

    public static List<String> getSellerListingChatBuyers(String listingId) {
        String seller = requireSeller();
        Map<String, Object> listing = DatabaseManager.fetchRow(
                "listings", "listing_id", parseListingId(listingId));
        if (listing == null || !seller.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new SecurityException("You can only view chats for your own listings.");
        }
        return DatabaseManager.fetchChatBuyerUsernames(listingId);
    }

    public static ChatHistory getSellerListingChat(String listingId, String buyerUsername) {
        String seller = requireSeller();
        Map<String, Object> listing = DatabaseManager.fetchRow(
                "listings", "listing_id", parseListingId(listingId));
        if (listing == null || !seller.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new SecurityException("You can only view chats for your own listings.");
        }
        requireSellerChatParticipant(listingId, buyerUsername);
        DatabaseManager.markChatHistorySeen(listingId, buyerUsername, "seller");
        return DatabaseManager.fetchChatHistoryOf(listingId, buyerUsername);
    }

    public static void sendSellerListingChatMessage(String listingId, String buyerUsername, String content) {
        String seller = requireSeller();
        Map<String, Object> listing = DatabaseManager.fetchRow(
                "listings", "listing_id", parseListingId(listingId));
        if (listing == null || !seller.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new SecurityException("You can only reply to chats for your own listings.");
        }
        requireSellerChatParticipant(listingId, buyerUsername);
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Messages must contain 1 to 2000 characters.");
        }
        DatabaseManager.appendMessageToChatHistory(
                listingId, buyerUsername, new Message(seller, "seller", content.trim()));
        DatabaseManager.markChatHistorySeen(listingId, buyerUsername, "seller");
        DatabaseManager.createNotification(buyerUsername, seller,
                "New seller chat message",
                "The seller replied to your message about " + listing.get("item_name") + ".",
                "Chat", DatabaseManager.latestBuyerRequestId(buyerUsername, parseListingId(listingId)),
                parseListingId(listingId));
    }

    public static ChatHistory getBuyerListingChat(String listingId) {
        String buyer = requireBuyer();
        Map<String, Object> listing = DatabaseManager.fetchRow(
                "listings", "listing_id", parseListingId(listingId));
        if (listing == null || buyer.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new SecurityException("You can only chat with a seller about another seller's listing.");
        }
        DatabaseManager.markChatHistorySeen(listingId, buyer, "buyer");
        return DatabaseManager.fetchChatHistoryOf(listingId, buyer);
    }

    public static void sendBuyerListingChatMessage(String listingId, String content) {
        String buyer = requireBuyer();
        Map<String, Object> listing = DatabaseManager.fetchRow(
                "listings", "listing_id", parseListingId(listingId));
        if (listing == null || buyer.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new SecurityException("You can only chat with a seller about another seller's listing.");
        }
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Messages must contain 1 to 2000 characters.");
        }
        String seller = (String) listing.get("seller");
        DatabaseManager.appendMessageToChatHistory(
                listingId, buyer, new Message(buyer, "buyer", content.trim()));
        DatabaseManager.markChatHistorySeen(listingId, buyer, "buyer");
        User user = SessionManager.getCurrentUser();
        int listingNumber = parseListingId(listingId);
        DatabaseManager.createNotification(seller, buyer, "New chat message",
                user.getFullName() + " sent you a message about " + listing.get("item_name"), "Chat",
                DatabaseManager.latestBuyerRequestId(buyer, listingNumber), listingNumber);
    }

    private static void requireSellerChatParticipant(String listingId, String buyerUsername) {
        if (buyerUsername == null || buyerUsername.isBlank()
                || !DatabaseManager.fetchChatBuyerUsernames(listingId).contains(buyerUsername)) {
            throw new SecurityException("This buyer has no chat for the selected listing.");
        }
    }

    private static boolean isRequestParticipant(String username, int requestId) {
        User user = SessionManager.getCurrentUser();
        return (user.getRole() == UserRole.BUYER || user.getRole() == UserRole.SELLER)
                && DatabaseManager.isRequestParticipant(username, requestId);
    }

    private static String requireBuyer() {
        User user = SessionManager.getCurrentUser();
        if (user == null || user.getRole() != UserRole.BUYER || !user.isActive()) {
            throw new SecurityException("An active buyer account is required.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", user.getUsername());
        if (account == null || !"BUYER".equals(account.get("role"))
                || ((Number) account.get("active")).intValue() != 1) {
            throw new SecurityException("An active buyer account is required.");
        }
        return (String) account.get("username");
    }

    private static String requireAuthenticatedUser() {
        User user = SessionManager.getCurrentUser();
        if (user == null || !user.isActive()) {
            throw new SecurityException("An active authenticated account is required.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", user.getUsername());
        if (account == null || ((Number) account.get("active")).intValue() != 1
                || !user.getRole().name().equals(account.get("role"))) {
            throw new SecurityException("An active authenticated account is required.");
        }
        return (String) account.get("username");
    }

    /** Shared read for the seller team's notification integration. */
    public static List<Notification> getNotificationsFor(String username) {
        User user = SessionManager.getCurrentUser();
        if (user == null || username == null || (!SessionManager.isAdmin()
                && !username.equalsIgnoreCase(user.getUsername()))) {
            throw new SecurityException("You cannot access another user's notifications.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", user.getUsername());
        if (!user.isActive() || account == null || ((Number) account.get("active")).intValue() != 1
                || !user.getRole().name().equals(account.get("role"))) {
            throw new SecurityException("An active authenticated account is required.");
        }
        Map<String, Object> recipient = DatabaseManager.fetchRow("users", "username", username);
        if (recipient == null) return List.of();
        List<Notification> notifications = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("notifications", Map.of("username", recipient.get("username")))) {
            notifications.add(toNotification(row));
        }
        notifications.sort(Comparator.comparing(Notification::getTimestamp).reversed());
        return notifications;
    }

    /** Current admin's notification board, including stable ordering for equal timestamps. */
    public static List<Notification> getAdminNotifications() {
        String admin = requireAdmin();
        List<Notification> notifications = new ArrayList<>(getNotificationsFor(admin));
        notifications.sort(Comparator.comparing(Notification::getTimestamp,
                Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(n -> Long.parseLong(n.getId())).reversed());
        return notifications;
    }

    public static void markAdminNotificationRead(String notificationId) {
        String admin = requireAdmin();
        long id;
        try { id = Long.parseLong(notificationId); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Select a valid notification."); }
        Map<String, Object> row = DatabaseManager.fetchRow("notifications", "notification_id", id);
        if (row == null || !admin.equals(row.get("username"))) {
            throw new IllegalArgumentException("Notification is no longer available.");
        }
        DatabaseManager.markNotificationRead(admin, notificationId);
    }

    private static String requireAdmin() {
        User currentUser = SessionManager.getCurrentUser();
        if (currentUser == null || currentUser.getRole() != UserRole.ADMIN || !currentUser.isActive()) {
            throw new SecurityException("An active admin account is required.");
        }
        Map<String, Object> account = DatabaseManager.fetchRow("users", "username", currentUser.getUsername());
        if (account == null || !"ADMIN".equals(account.get("role"))
                || ((Number) account.get("active")).intValue() != 1) {
            throw new SecurityException("An active admin account is required.");
        }
        return (String) account.get("username");
    }

    private static int parseListingId(String listingId) {
        try {
            int id = Integer.parseInt(listingId);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Listing IDs in SQLite are positive integers, unlike the dummy IDs.
        }
        throw new IllegalArgumentException("A valid listing ID is required.");
    }

    /** Maps database fields to the existing model, including separate submission/publication dates. */
    public static Listing toListing(Map<String, Object> row) {
        Listing listing = new Listing();
        listing.setId(row.get("listing_id").toString());
        listing.setTitle((String) row.get("item_name"));
        listing.setDescription((String) row.get("description"));
        listing.setCategory((String) row.get("category"));
        listing.setCondition((String) row.get("item_condition"));
        listing.setMeetupLocation((String) row.get("meetup_location"));
        String listingStatus = (String) row.get("listing_status");
        listing.setStatus(listingStatus == null
                ? switch (((Number) row.get("approved")).intValue()) {
                    case -1 -> ListingStatus.PENDING_REVIEW;
                    case 0 -> ListingStatus.REJECTED;
                    case 1 -> ListingStatus.LIVE;
                    default -> throw new IllegalStateException("Unknown listing review status.");
                }
                : ListingStatus.valueOf(listingStatus));
        String type = (String) row.get("listing_type");
        listing.setType(type == null ? null : ListingType.valueOf(type));
        Number price = (Number) row.get("price");
        listing.setPrice(price == null ? null : price.doubleValue());
        Number days = (Number) row.get("lending_days");
        listing.setLendingDays(days == null ? null : days.intValue());
        listing.setAvailabilityStatus((String) row.get("availability_status"));
        listing.setReviewVersion(((Number) row.get("review_version")).longValue());
        listing.setViewCount(((Number) row.get("views")).intValue());
        String sellerUsername = (String) row.get("seller_username");
        if ((sellerUsername == null || sellerUsername.isBlank())
                && row.get("seller") instanceof String seller && !seller.isBlank()) {
            sellerUsername = seller;
        }
        listing.setSellerUsername(sellerUsername == null || sellerUsername.isBlank() ? null : sellerUsername);
        if (listing.getSellerUsername() != null) {
            Map<String, Object> seller = DatabaseManager.fetchRow("users", "username", listing.getSellerUsername());
            listing.setSellerFullName(seller == null ? listing.getSellerUsername() : (String) seller.get("full_name"));
        }
        try {
            LocalDateTime postedAt = parseDate((String) row.get("posted_date"));
            if (postedAt == null && listingStatus == null) {
                postedAt = parseDate((String) row.get("submitted_at"));
            }
            listing.setPostedAt(postedAt);
            listing.setSubmittedAt(parseDate((String) row.get("submitted_at")));
            listing.setReviewedAt(parseDate((String) row.get("reviewed_at")));
            listing.setRejectionReason((String) row.get("rejection_reason"));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Listing " + listing.getId() + " has an invalid date.", e);
        }
        List<String> photos = new ArrayList<>();
        for (int i = 1; i <= Math.min(6, ((Number) row.get("numPhotos")).intValue()); i++) {
            String photo = (String) row.get("photo" + i);
            if (photo != null && !photo.isBlank()) photos.add(photo);
        }
        listing.setPhotoPaths(photos);
        return listing;
    }

    public static Notification toNotification(Map<String, Object> row) {
        Notification notification = new Notification(
                (String) row.get("username"),
                (String) row.get("title"),
                (String) row.get("message"),
                (String) row.get("category"));
        notification.setId(row.get("notification_id").toString());
        notification.setSender((String) row.get("sender"));
        notification.setTimestamp(parseDate((String) row.get("timestamp")));
        notification.setRead(((Number) row.get("is_read")).intValue() != 0);
        if (row.get("request_id") instanceof Number requestId) notification.setRequestId(requestId.intValue());
        if (row.get("listing_id") instanceof Number listingId) notification.setListingId(listingId.intValue());
        notification.setTargetUsername((String) row.get("target_username"));
        return notification;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() == 10 ? LocalDate.parse(value).atStartOfDay()
                : LocalDateTime.parse(value.replace(' ', 'T'));
    }

    public static String hashPassword(String password) {
        int logRounds = 12;
        String salt = BCrypt.gensalt(logRounds);
        return BCrypt.hashpw(password, salt);
    }

    public static boolean checkPassword(String password, String storedHash) {
        return BCrypt.checkpw(password, storedHash);
    }

    public static String checkPasswordStrength(String password) {
        if (password == null || password.isEmpty()) return "Password cannot be empty.";
        if (password.length() < 8) return "Password must be at least 8 characters.";
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasUpper || !hasLower || !hasDigit) {
            return "Password should contain uppercase, lowercase and a digit.";
        }
        return "OK";
    }

    public static User toUser(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            if (!map.containsKey("role")) {
                throw new IllegalArgumentException("Map does not contain 'role' key.");
            }
            UserRole role;
            try {
                role = UserRole.valueOf((String) map.get("role"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid role value in map: " + map.get("role"));
            }
            String username = (String) map.get("username");
            String fullName = (String) map.get("full_name");
            String passwordHash = (String) map.get("password_hash");
            String residenceHall = (String) map.get("residence_hall");
            String bio = (String) map.get("bio");
            String preferredMeetup = (String) map.get("preferred_meetup");
            boolean active = ((Number) map.get("active")).intValue() == 1;
            LocalDateTime createdAt = LocalDateTime.parse((String) map.get("created_at"));
            double averageRating = ((Number) map.get("average_rating")).doubleValue();
            int ratingCount = ((Number) map.get("rating_count")).intValue();

            User user = new User(username, fullName, passwordHash, role);
            user.setResidenceHall(residenceHall);
            user.setBio(bio);
            user.setPreferredMeetup(preferredMeetup);
            user.setActive(active);
            user.setCreatedAt(createdAt);
            user.setAverageRating(averageRating);
            user.setRatingCount(ratingCount);

            return user;

        } else {
            throw new IllegalArgumentException("Object is not a valid Map representation of a User.");
        }
    }

    public static Listing toListing(Object obj) {
        // Implementation for converting an object to a Listing model
        // This method can be implemented similarly to toUser, depending on the structure of the Listing model
        if (!(obj instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Object is not a valid Map representation of a Listing.");
        }

        Listing listing = new Listing();
        listing.setId((String) map.get("listing_id"));
        listing.setItemName((String) map.get("item_name"));
        listing.setCategory((String) map.get("category"));
        listing.setCondition((String) map.get("item_condition"));
        listing.setDescription((String) map.get("description"));
        
        for (int i = 1; i <= ((Number) map.get("photo_count")).intValue(); i++) {
            String photoPathKey = "photo" + i;
            if (map.containsKey(photoPathKey))
                listing.getPhotoPaths().add((String) map.get(photoPathKey));
        }
        
        listing.setMeetupLocation((String) map.get("meetup_location"));
        listing.setPostedAt(LocalDateTime.parse((String) map.get("posted_date")));
        listing.setAvailabilityStatus((String) map.get("availability_status"));
        listing.setViewCount(((Number) map.get("views")).intValue());
        listing.setIsApproved(((Boolean) map.get("approved")).booleanValue() == true);

        listing.setType(ListingType.valueOf((String) map.get("listing_type")));
        listing.setStatus(ListingStatus.valueOf((String) map.get("listing_status")));
        listing.setPrice(map.get("price") != null ? ((Number) map.get("price")).doubleValue() : null);
        listing.setLendingDays(map.get("lending_days") != null ? ((Number) map.get("lending_days")).intValue() : null);
        listing.setSeller((String) map.get("seller"));

        // These shall be accessed in `feedbacks` table in data.db
        // listing.setReviewedAt(map.get("reviewed_at") != null ? LocalDateTime.parse((String) map.get("reviewed_at")) : null);
        // listing.setRejectionReason((String) map.get("rejection_reason"));
        return listing;
    }

    public static ChatHistory toChatHistory(Map<String, Object> row) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setListing_id((String) row.get("listing_id"));
        chatHistory.setSeen_buyer(((Number) row.get("seen_buyer")).intValue() != 0);
        chatHistory.setSeen_seller(((Number) row.get("seen_seller")).intValue() != 0);

        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= ((Number) row.get("message_count")).intValue(); i++) {
            String messageKey = "message" + i;
            if (row.containsKey(messageKey)) {
                Map<String, Object> messageRow = (Map<String, Object>) row.get(messageKey);
                Message message = new Message();
                message.setSender_id((String) messageRow.get("sender_id"));
                message.setSender_role((String) messageRow.get("sender_role"));
                message.setContent((String) messageRow.get("content"));
                message.setTimestamp(LocalDateTime.parse((String) messageRow.get("timestamp")));
                messages.add(message);
            }
        }
        chatHistory.setMessages(messages);
        return chatHistory;
    }

    /**
     * Buyer registration. Password rules: at least 8 characters, one uppercase letter,
     * one lowercase letter, and one digit. The password is stored only as a BCrypt hash.
     */
    public static void registerBuyer(String username, String fullName, String password,
                                     String confirmPassword, String residenceHall) {
        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedName = fullName == null ? "" : fullName.trim();
        if (normalizedUsername.isEmpty()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (DatabaseManager.usernameExists(normalizedUsername)) {
            throw new IllegalArgumentException("The username has been used.");
        }
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Full name cannot be empty.");
        }
        if (!"OK".equals(checkPasswordStrength(password))) {
            throw new IllegalArgumentException("The password is too weak to be used.");
        }
        if (confirmPassword == null || !confirmPassword.equals(password)) {
            throw new BuyerInputException("The confirm-password was incorrect.", true);
        }
        String residence = residenceHall == null || residenceHall.isBlank() ? null : residenceHall;
        DatabaseManager.createUser(normalizedUsername, normalizedName, hashPassword(password),
                UserRole.BUYER, residence, null, null);
    }

    /** Updates the signed-in buyer's name and, when supplied, password. */
    public static void updateBuyerProfile(String fullName, String residenceHall,
                                          String newPassword, String confirmPassword) {
        String buyer = requireBuyer();
        String normalizedName = fullName == null ? "" : fullName.trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Full name cannot be empty.");
        }
        boolean changePassword = newPassword != null && !newPassword.isBlank();
        String passwordHash = null;
        if (changePassword) {
            if (!"OK".equals(checkPasswordStrength(newPassword))) {
                throw new IllegalArgumentException("The password is too weak to be used.");
            }
            if (confirmPassword == null || !confirmPassword.equals(newPassword)) {
                throw new BuyerInputException("The confirm-password was incorrect.", true);
            }
            passwordHash = hashPassword(newPassword);
        }
        User user = SessionManager.getCurrentUser();
        String residence = residenceHall == null || residenceHall.isBlank() ? null : residenceHall;
        DatabaseManager.updateUserProfile(buyer, normalizedName, residence, user.getBio(),
                user.getPreferredMeetup(), passwordHash);
        user.setFullName(normalizedName);
        user.setResidenceHall(residence);
        if (passwordHash != null) user.setPasswordHash(passwordHash);
    }

    public static List<Listing> searchBuyerMarketplace(String keyword, String category, String listingType,
                                                       String condition, Double minPrice, Double maxPrice,
                                                       String sort) {
        String buyer = requireBuyer();
        List<Integer> hidden = DatabaseManager.fetchReportedListingIds(buyer);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Listing> matches = new ArrayList<>();
        for (Listing listing : getLiveListings()) {
            if (hidden.contains(Integer.parseInt(listing.getId()))) continue;
            if (category != null && !category.isBlank() && !"All Categories".equals(category)
                    && !category.equals(listing.getCategory())) continue;
            if (listingType != null && !listingType.isBlank() && !"All Types".equals(listingType)
                    && !listingType.equalsIgnoreCase(listing.getType().name())
                    && !listingType.equalsIgnoreCase(listing.getTypeDisplay())) continue;
            if (condition != null && !condition.isBlank() && !"All Conditions".equals(condition)
                    && !condition.equals(listing.getCondition())) continue;
            if (minPrice != null || maxPrice != null) {
                if (listing.getPrice() == null) continue;
                if (minPrice != null && listing.getPrice() < minPrice) continue;
                if (maxPrice != null && listing.getPrice() > maxPrice) continue;
            }
            if (!needle.isEmpty()) {
                String haystack = String.join(" ",
                        text(listing.getTitle()), text(listing.getDescription()), text(listing.getCategory()),
                        text(listing.getSellerFullName()), text(listing.getMeetupLocation()),
                        text(listing.getCondition())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(needle)) continue;
            }
            matches.add(listing);
        }
        Comparator<Listing> comparator = switch (sort == null ? "Newest" : sort) {
            case "Price: Low to High" -> Comparator.comparing(Listing::getPrice,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "Price: High to Low" -> Comparator.comparing(Listing::getPrice,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(Listing::getPostedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
        matches.sort(comparator.thenComparing(Listing::getId));
        return matches;
    }

    /** Opens a listing detail and counts one view. Reported listings stay hidden. */
    public static Listing openBuyerListing(String listingId) {
        String buyer = requireBuyer();
        int id = parseListingId(listingId);
        if (DatabaseManager.hasListingReport(buyer, id)) {
            throw new IllegalStateException("This listing is hidden because you reported it.");
        }
        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id", id);
        if (row == null) return null;
        DatabaseManager.incrementListingViews(id);
        row = DatabaseManager.fetchRow("listings", "listing_id", id);
        return toListing(row);
    }

    /** Reads the current listing without counting another view. */
    public static Listing peekBuyerListing(String listingId) {
        String buyer = requireBuyer();
        int id = parseListingId(listingId);
        if (DatabaseManager.hasListingReport(buyer, id)) {
            throw new IllegalStateException("This listing is hidden because you reported it.");
        }
        Map<String, Object> row = DatabaseManager.fetchRow("listings", "listing_id", id);
        return row == null ? null : toListing(row);
    }

    public static void reportBuyerListing(String listingId) {
        String buyer = requireBuyer();
        DatabaseManager.reportListing(buyer, parseListingId(listingId));
    }

    public static List<Integer> getBuyerFavouriteIds() {
        return DatabaseManager.fetchFavouriteListingIds(requireBuyer());
    }

    public static void setBuyerFavourite(String listingId, boolean favourite) {
        String buyer = requireBuyer();
        int id = parseListingId(listingId);
        if (DatabaseManager.fetchRow("listings", "listing_id", id) == null) {
            throw new IllegalArgumentException("Listing was not found.");
        }
        DatabaseManager.setFavourite(buyer, id, favourite);
    }

    public static List<Listing> getBuyerFavouriteListings() {
        String buyer = requireBuyer();
        List<Integer> hidden = DatabaseManager.fetchReportedListingIds(buyer);
        List<Listing> favourites = new ArrayList<>();
        for (Listing listing : DatabaseManager.fetchFavouriteListings(buyer)) {
            if (!hidden.contains(Integer.parseInt(listing.getId()))) favourites.add(listing);
        }
        return favourites;
    }

    public static int submitBorrowRequestForDates(String listingId, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Choose a borrow start and end date.");
        }
        var submissionTime = LocalDateTime.now().toLocalTime();
        return submitBorrowRequest(listingId, start.atTime(submissionTime), end.atTime(submissionTime));
    }

    public static int submitBorrowRequest(String listingId, LocalDateTime start, LocalDateTime end) {
        String buyer = requireBuyer();
        int id = parseListingId(listingId);
        Map<String, Object> listing = DatabaseManager.fetchRow("listings", "listing_id", id);
        if (listing == null || !"LEND".equals(listing.get("listing_type"))) {
            throw new IllegalArgumentException("Borrow requests are only available for lend listings.");
        }
        if (buyer.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new IllegalArgumentException("You cannot request your own listing.");
        }
        return DatabaseManager.createBorrowRequest(buyer, id, start, end);
    }

    public record DealRecord(int requestId, String itemTitle, String buyerUsername, String sellerUsername,
                             ListingType dealType, String startDate, String completeDate,
                             String scheduledReturnDate, String status) {}

    public record DealDetails(DealRecord deal, String category, String condition, String description,
                              String meetupLocation, Double offerPrice, String borrowEnd,
                              Integer proposedDays, String requestedAt) {}

    /** Reloads a selected deal for read-only admin inspection. */
    public static DealDetails getDealDetails(int requestId) {
        requireAdmin();
        var request = DatabaseManager.fetchRow("listing_requests", "request_id", requestId);
        if (request == null || !"ACCEPTED".equals(request.get("status"))) {
            throw new IllegalStateException("This deal is no longer available. Refresh the records.");
        }
        var listing = DatabaseManager.fetchRow("listings", "listing_id", request.get("listing_id"));
        if (listing == null) throw new IllegalStateException("This deal's listing is no longer available. Refresh the records.");
        return new DealDetails(toDealRecord(request, listing), (String) listing.get("category"),
                (String) listing.get("item_condition"), (String) listing.get("description"),
                (String) listing.get("meetup_location"),
                request.get("offer_price") instanceof Number n ? n.doubleValue() : null,
                (String) request.get("borrow_end"),
                request.get("proposed_days") instanceof Number n ? n.intValue() : null,
                (String) request.get("created_at"));
    }

    /**
     * Read-only system-wide accepted deals, including completed/returned history; requires an active admin.
     * Null type/status disables that filter. Search is a case-insensitive literal title/username match.
     * Start date is borrow_start when present, otherwise request creation; complete date is the actual
     * return/completion timestamp. Scheduled return is exposed separately for borrowed items.
     */
    public static List<DealRecord> getAllDeals(String search, ListingType type, String status) {
        requireAdmin();
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Map<Integer, Map<String, Object>> listings = new HashMap<>();
        for (var row : DatabaseManager.fetchRows("listings", Map.of())) {
            listings.put(((Number) row.get("listing_id")).intValue(), row);
        }
        List<DealRecord> deals = new ArrayList<>();
        var requests = DatabaseManager.fetchRows("listing_requests", Map.of("status", "ACCEPTED"));
        requests.sort(Comparator.comparing((Map<String, Object> row) -> {
            Object at = row.get("returned_at");
            if (at == null) at = row.get("completed_at");
            if (at == null) at = row.get("updated_at");
            if (at == null) at = row.get("created_at");
            return at.toString().replace(' ', 'T');
        }).reversed().thenComparing(row -> ((Number) row.get("request_id")).intValue(), Comparator.reverseOrder()));
        for (var row : requests) {
            var listing = listings.get(((Number) row.get("listing_id")).intValue());
            if (listing == null) continue;
            DealRecord deal = toDealRecord(row, listing);
            if (type != null && type != deal.dealType()) continue;
            if (status != null && !status.equals(deal.status())) continue;
            if (!needle.isEmpty() && !deal.itemTitle().toLowerCase(Locale.ROOT).contains(needle)
                    && !deal.buyerUsername().toLowerCase(Locale.ROOT).contains(needle)
                    && !deal.sellerUsername().toLowerCase(Locale.ROOT).contains(needle)) continue;
            deals.add(deal);
        }
        return deals;
    }

    private static DealRecord toDealRecord(Map<String, Object> row, Map<String, Object> listing) {
        return new DealRecord(((Number) row.get("request_id")).intValue(),
                (String) listing.get("item_name"), (String) row.get("buyer_username"), (String) listing.get("seller"),
                switch ((String) row.get("request_type")) {
                    case "OFFER" -> ListingType.SELL;
                    case "BORROW" -> ListingType.LEND;
                    case "CLAIM" -> ListingType.GIVEAWAY;
                    default -> throw new IllegalStateException("Unsupported deal type.");
                },
                (String) (row.get("borrow_start") == null ? row.get("created_at") : row.get("borrow_start")),
                (String) (row.get("returned_at") == null ? row.get("completed_at") : row.get("returned_at")),
                "BORROW".equals(row.get("request_type"))
                        ? (String) (row.get("due_at") == null ? row.get("borrow_end") : row.get("due_at")) : null,
                row.get("returned_at") != null ? "Returned" : row.get("completed_at") != null ? "Completed" : "Accepted");
    }

    public static List<Map<String, Object>> getBuyerDeals() {
        String buyer = requireBuyer();
        DatabaseManager.settleExpiredBorrows(buyer);
        return DatabaseManager.fetchBuyerDeals(buyer);
    }

    public static int settleExpiredBorrows() {
        return DatabaseManager.settleExpiredBorrows(requireBuyer());
    }

    public static void selfReturnBorrow(int requestId) {
        DatabaseManager.returnBorrow(requireBuyer(), requestId, false);
    }

    /** @return true if both buyer and seller have confirmed and the deal is fully completed */
    public static boolean completeBuyerDeal(int requestId) {
        return DatabaseManager.completeBuyerDeal(requireBuyer(), requestId);
    }

    public static void rateCompletedDeal(int requestId, double rating, String reviewText) {
        DatabaseManager.createBuyerReview(requireBuyer(), requestId, rating, reviewText);
    }

    public static List<Map<String, Object>> getSellerReviews(String sellerUsername) {
        requireBuyer();
        if (sellerUsername == null || sellerUsername.isBlank()) return List.of();
        return DatabaseManager.fetchSellerReviews(sellerUsername);
    }

    public static Map<String, Object> getSellerRating(String sellerUsername) {
        requireBuyer();
        Map<String, Object> seller = DatabaseManager.fetchRow("users", "username", sellerUsername);
        if (seller == null) return Map.of("average_rating", 0.0, "rating_count", 0, "full_name", sellerUsername);
        return seller;
    }

    public static List<Notification> getBuyerNotifications() {
        String buyer = requireBuyer();
        DatabaseManager.settleExpiredBorrows(buyer);
        DatabaseManager.notifyBuyerIfListingRemoved(buyer);
        return getNotificationsFor(buyer);
    }

    public static boolean hasUnreadNotifications() {
        User user = SessionManager.getCurrentUser();
        if (user == null || !user.isActive()) return false;
        return DatabaseManager.hasUnreadNotifications(user.getUsername());
    }

    public static void markBuyerNotificationRead(String notificationId) {
        DatabaseManager.markNotificationRead(requireBuyer(), notificationId);
    }

    public static void markCurrentUserNotificationRead(String notificationId) {
        DatabaseManager.markNotificationRead(requireAuthenticatedUser(), notificationId);
    }

    /** Marks every notification for the signed-in buyer, seller, or admin as read. */
    public static void markAllCurrentUserNotificationsRead() {
        DatabaseManager.markAllNotificationsRead(requireAuthenticatedUser());
    }

    public static boolean isBuyerDealFinished(Map<String, Object> deal) {
        return deal.get("completed_at") != null || deal.get("returned_at") != null
                || "DECLINED".equals(deal.get("status"));
    }

    public static List<Review> getReviewsForCurrentSeller() {
        return mapReviews(DatabaseManager.fetchSellerReviews(requireSeller()));
    }

    public static void replyToReview(String buyerUsername, String listingId, String replyText) {
        String seller = requireSeller();
        if (buyerUsername == null || buyerUsername.isBlank() || listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("Buyer and listing are required.");
        }
        DatabaseManager.replyToSellerReview(seller, buyerUsername, parseListingId(listingId), replyText);
    }

    public static void flagReview(String buyerUsername, String listingId) {
        String seller = requireSeller();
        if (buyerUsername == null || buyerUsername.isBlank() || listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("Buyer and listing are required.");
        }
        DatabaseManager.flagSellerReview(seller, buyerUsername, parseListingId(listingId));
    }

    public static List<Review> getBuyerReviews() {
        return mapReviews(DatabaseManager.fetchBuyerReviews(requireBuyer()));
    }

    public static List<Review> getAllReviews() {
        return getAllReviews(null, null, null, null);
    }

    /** Admin overview; username matches buyer or seller, with inclusive calendar dates. Null filters mean all. */
    public static List<Review> getAllReviews(String user, Double score, LocalDate from, LocalDate to) {
        requireAdmin();
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 5)) {
            throw new IllegalArgumentException("Rating must be between 0.0 and 5.0.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("From date must be on or before To date.");
        }
        String query = user == null ? "" : user.trim().toLowerCase(java.util.Locale.ROOT);
        return mapReviews(DatabaseManager.fetchAllReviews()).stream()
                .filter(r -> query.isEmpty() || r.getBuyerUsername().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || r.getSellerUsername().toLowerCase(java.util.Locale.ROOT).contains(query))
                .filter(r -> score == null || Double.compare(r.getScore(), score) == 0)
                .filter(r -> from == null || r.getCreatedAt() != null && !r.getCreatedAt().toLocalDate().isBefore(from))
                .filter(r -> to == null || r.getCreatedAt() != null && !r.getCreatedAt().toLocalDate().isAfter(to))
                .toList();
    }

    public static void setReviewHidden(String buyerUsername, String listingId, boolean hidden) {
        String admin = requireAdmin();
        DatabaseManager.setReviewHidden(admin, buyerUsername, parseListingId(listingId), hidden);
    }

    public static List<Listing> getAllListings() {
        requireAdmin();
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("listings", Map.of())) {
            listings.add(toListing(row));
        }
        listings.sort(Comparator.comparing(Listing::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Listing::getId));
        return listings;
    }

    /** Publishes immediately on behalf of an active seller, using the seller form validation. */
    public static void addListingForSeller(String sellerUsername, Listing listing, List<Path> photos) {
        String admin = requireAdmin();
        if (listing == null || photos == null || photos.size() > 6) {
            throw new IllegalArgumentException("Listing details and up to 6 photos are required.");
        }
        validateListingDetails(listing);
        DatabaseManager.addAdminListing(admin, sellerUsername, listing, List.copyOf(photos));
    }

    /** Edits content without republishing or resetting the listing's lifecycle. */
    public static void updateListingAsAdmin(Listing listing, List<Path> photos, boolean replacePhotos) {
        String admin = requireAdmin();
        if (listing == null || listing.getId() == null || photos == null || photos.size() > 6) {
            throw new IllegalArgumentException("Listing details, ID and up to 6 photos are required.");
        }
        validateListingDetails(listing);
        DatabaseManager.updateAdminListing(admin, listing, List.copyOf(photos), replacePhotos);
    }

    public record ListingManagementEntry(String adminUsername, String reason, LocalDateTime occurredAt) {}

    public static List<ListingManagementEntry> getListingManagementHistory(String listingId) {
        requireAdmin();
        return DatabaseManager.fetchRows("listing_takedowns", Map.of("listing_id", parseListingId(listingId)))
                .stream().sorted(Comparator.comparingInt((Map<String, Object> row) -> ((Number) row.get("takedown_id")).intValue()).reversed())
                .map(row -> new ListingManagementEntry(
                        row.get("admin_username").toString(), row.get("reason").toString(),
                        LocalDateTime.parse(row.get("occurred_at").toString()))).toList();
    }

    public static void takeDownListing(String listingId, String reason, long expectedVersion) {
        String admin = requireAdmin();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A takedown reason is required.");
        DatabaseManager.takeDownListing(parseListingId(listingId), admin, reason.trim(), expectedVersion);
    }

    public static void removeListing(String listingId) {
        requireAdmin();
        DatabaseManager.removeListing(parseListingId(listingId));
    }

    public static List<User> getAllUsers() {
        requireAdmin();
        List<User> users = new ArrayList<>();
        for (Map<String, Object> row : DatabaseManager.fetchRows("users", Map.of())) {
            users.add(toUser(row));
        }
        users.sort(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER));
        return users;
    }

    public static void setUserActive(String username, boolean active) {
        requireAdmin();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A username is required.");
        }
        DatabaseManager.setUserActive(username, active);
    }

    private static List<Review> mapReviews(List<Map<String, Object>> rows) {
        List<Review> reviews = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Review review = new Review();
            if (row.get("listing_id") != null) review.setId(row.get("listing_id").toString());
            review.setBuyerUsername((String) row.get("buyer_username"));
            review.setSellerUsername((String) row.get("seller_username"));
            review.setListingTitle((String) row.get("item_name"));
            review.setComment(row.get("text") == null ? "" : (String) row.get("text"));
            review.setSellerReply((String) row.get("seller_reply"));
            double score = row.get("rating") instanceof Number number ? number.doubleValue() : 0;
            review.setScore(score);
            review.setHidden(row.get("hidden") instanceof Number number && number.intValue() != 0);
            review.setFlagged(row.get("flagged") instanceof Number number && number.intValue() != 0);
            if (row.get("created_at") == null || row.get("created_at").toString().isBlank()) {
                review.setCreatedAt(null);
            } else {
                review.setCreatedAt(parseDate(row.get("created_at").toString()));
            }
            reviews.add(review);
        }
        return reviews;
    }

    /** Validation failure that tells the form whether to clear the confirm-password field. */
    public static class BuyerInputException extends IllegalArgumentException {
        private final boolean clearConfirmPassword;

        public BuyerInputException(String message, boolean clearConfirmPassword) {
            super(message);
            this.clearConfirmPassword = clearConfirmPassword;
        }

        public boolean isClearConfirmPassword() {
            return clearConfirmPassword;
        }
    }
}
