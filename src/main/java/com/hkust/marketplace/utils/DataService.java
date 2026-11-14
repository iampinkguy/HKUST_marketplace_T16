package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.Notification;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /** Seeds the README demo accounts without changing accounts that already exist. */
    public static void seedUserIfMissing() {
        record DemoUser(String username, String fullName, String password, UserRole role,
                        String residenceHall, String bio, String preferredMeetup) {}

        List<DemoUser> demoUsers = List.of(
                new DemoUser("admin", "HKUST Marketplace Admin", "admin123", UserRole.ADMIN,
                        null, null, null),
                new DemoUser("alice", "Alice Chan", "password1", UserRole.BUYER,
                        "Hall 3", null, null),
                new DemoUser("bob", "Bob Wong", "password1", UserRole.BUYER,
                        "Off-campus", null, null),
                new DemoUser("charlie", "Charlie Lee", "password1", UserRole.SELLER,
                        null, "Final year CSE student selling textbooks and gadgets.", "Library"),
                new DemoUser("diana", "Diana Ng", "password1", UserRole.SELLER,
                        null, "Selling furniture and kitchen items before graduation.", "Hall 5 Lobby")
        );

        for (DemoUser user : demoUsers) {
            if (!DatabaseManager.usernameExists(user.username())) {
                DatabaseManager.createUserIfMissing(user.username(), user.fullName(),
                        hashPassword(user.password()), user.role().name(), user.residenceHall(),
                        user.bio(), user.preferredMeetup());
            }
        }
    }

    /** Loads SQLite pending listings; no dummy listings are imported or seeded. */
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
            }
            default -> throw new IllegalArgumentException("Invalid request type.");
        }
        if (buyer.equalsIgnoreCase((String) listing.get("seller"))) {
            throw new IllegalArgumentException("You cannot request your own listing.");
        }
        return DatabaseManager.createListingRequest(buyer, id, type, offerPrice, proposedDays);
    }

    public static void resolveSellerRequest(int requestId, String action, Double counterPrice) {
        String seller = requireSeller();
        if ("COUNTER".equalsIgnoreCase(action)
                && (counterPrice == null || !Double.isFinite(counterPrice) || counterPrice <= 0)) {
            throw new IllegalArgumentException("Enter a valid positive counteroffer.");
        }
        DatabaseManager.resolveListingRequest(seller, requestId, action, counterPrice);
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
                "Chat");
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
        DatabaseManager.createNotification(seller, buyer, "New chat message",
                user.getFullName() + " sent you a message about " + listing.get("item_name"), "Chat");
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
            Notification notification = new Notification((String) row.get("username"), (String) row.get("title"),
                    (String) row.get("message"), (String) row.get("category"));
            notification.setId(row.get("notification_id").toString());
            notification.setTimestamp(parseDate((String) row.get("timestamp")));
            notification.setRead(((Number) row.get("is_read")).intValue() != 0);
            notifications.add(notification);
        }
        notifications.sort(Comparator.comparing(Notification::getTimestamp).reversed());
        return notifications;
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
        listing.setSellerUsername((String) row.get("seller_username"));
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
        notification.setTimestamp(parseDate((String) row.get("timestamp")));
        notification.setRead(((Number) row.get("is_read")).intValue() != 0);
        return notification;
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
}
