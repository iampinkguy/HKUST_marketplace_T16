package com.hkust.marketplace.utils;

import javax.imageio.ImageIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.models.Deal;
import com.hkust.marketplace.models.Review;

public class DatabaseManager {

    private static final String URL = "jdbc:sqlite:data.db";
    private static final Path UPLOADS_DIR = Path.of("uploads").toAbsolutePath().normalize();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PHOTO_EXTENSION = ".jpg"; // Assuming all photos are JPEGs; adjust as needed

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getProperty("marketplace.database.url", URL));
    }

    private static Path chatHistoryFile() {
        return Path.of(System.getProperty("marketplace.chat.history.path", "chat_history.json"));
    }

    private static void runSql(String sql) {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize database", e);
        }
    }

    public static void initialize() {
        runSql("""
                CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                full_name TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                residence_hall TEXT,
                bio TEXT,
                preferred_meetup TEXT,
                active INTEGER NOT NULL DEFAULT 1, -- 1 for active, 0 for deactivated
                created_at TEXT NOT NULL,
                average_rating REAL NOT NULL DEFAULT 0,
                rating_count INTEGER NOT NULL DEFAULT 0
            )
            """);
        runSql("""
                CREATE TABLE IF NOT EXISTS listings (
                listing_id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_name TEXT NOT NULL,
                listing_type TEXT NOT NULL CHECK(listing_type IN ('SELL', 'LEND', 'GIVEAWAY')),
                category TEXT NOT NULL CHECK(category IN (
                    'Textbooks', 'Electronics', 'Furniture', 'Clothing',
                    'Stationery', 'Sports Equipment', 'Kitchenware', 'Others'
                )),
                item_condition TEXT NOT NULL CHECK(item_condition IN (
                    'New', 'Like New', 'Used', 'For Parts'
                )),
                listing_status TEXT NOT NULL CHECK(listing_status IN (
                    'PENDING_REVIEW', 'LIVE', 'REJECTED',
                    'RESERVED', 'SOLD', 'LENT', 'GIVEN', 'REMOVED')),
                price REAL,                -- Only for SELL
                lending_days INTEGER,      -- Only for LEND
                seller TEXT NOT NULL REFERENCES users(username),
                description TEXT,
                numPhotos INTEGER NOT NULL DEFAULT 0,
                photo1 TEXT,
                photo2 TEXT,
                photo3 TEXT,
                photo4 TEXT,
                photo5 TEXT,
                photo6 TEXT,
                meetup_location TEXT NOT NULL CHECK(meetup_location IN (
                    'Academic Building (LG1)', 'Library', 'Student Centre', 'Hall 1 Lobby',
                    'Hall 2 Lobby', 'Hall 3 Lobby', 'Hall 4 Lobby', 'Hall 5 Lobby',
                    'Hall 6 Lobby', 'Hall 7 Lobby', 'Hall 8 Lobby', 'Hall 9 Lobby',
                    'UG Hall 1', 'UG Hall 2', 'North Gate', 'South Gate',
                    'Shaw Auditorium', 'Coffee Shop (LG5)', 'Sports Hall'
                )),
                posted_date DATE NOT NULL,
                availability_status TEXT NOT NULL CHECK(availability_status IN ('Available', 'Unavailable')),
                views INTEGER NOT NULL DEFAULT 0,
                approved INTEGER NOT NULL DEFAULT -1
            )
            """);

        migrateListingSchema();
        migrateListingReviewSchema();

        runSql("""
                CREATE TABLE IF NOT EXISTS offers (
                buyer_username STRING NOT NULL,
                listing_id INTEGER NOT NULL,
                price REAL NOT NULL,
                chatbox_id STRING NOT NULL,
                PRIMARY KEY(buyer_username, listing_id)
            )
            """);
        runSql("""
                CREATE TABLE IF NOT EXISTS listing_requests (
                    request_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    buyer_username TEXT NOT NULL REFERENCES users(username),
                    listing_id INTEGER NOT NULL REFERENCES listings(listing_id),
                    request_type TEXT NOT NULL CHECK(request_type IN ('OFFER', 'BORROW', 'CLAIM')),
                    offer_price REAL,
                    proposed_days INTEGER,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK(status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'COUNTERED')),
                    counter_price REAL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);
        runSql("""
                CREATE TABLE IF NOT EXISTS request_messages (
                    message_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    request_id INTEGER NOT NULL REFERENCES listing_requests(request_id) ON DELETE CASCADE,
                    sender_username TEXT NOT NULL REFERENCES users(username),
                    sender_role TEXT NOT NULL CHECK(sender_role IN ('BUYER', 'SELLER')),
                    content TEXT NOT NULL,
                    timestamp TEXT NOT NULL
                )
            """);

        runSql("""
                CREATE TABLE IF NOT EXISTS reviews (
                buyer_username STRING NOT NULL,
                listing_id INTEGER NOT NULL,
                rating REAL NOT NULL,
                text STRING,
                PRIMARY KEY(buyer_username, listing_id)
            )
            """);

        runSql("""
                CREATE TABLE IF NOT EXISTS feedbacks (
                admin_username STRING NOT NULL,
                listing_id STRING NOT NULL,
                reason STRING NOT NULL,
                PRIMARY KEY(admin_username, listing_id)
            )
            """);

        runSql("""
                CREATE TABLE IF NOT EXISTS notifications (
                notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                sender TEXT,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                category TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0
            )
            """);
        runSql("""
                CREATE TABLE IF NOT EXISTS favourites (
                buyer_username TEXT NOT NULL REFERENCES users(username),
                listing_id INTEGER NOT NULL REFERENCES listings(listing_id),
                PRIMARY KEY (buyer_username, listing_id)
            )
            """);
        migrateNotificationSchema();
        migrateBuyerDealColumns();
        migrateAdminNotificationSchema();
        runSql("""
                CREATE TABLE IF NOT EXISTS listing_takedowns (
                    takedown_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    listing_id INTEGER NOT NULL REFERENCES listings(listing_id),
                    admin_username TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    occurred_at TEXT NOT NULL
                )
                """);
        runSql("""
                CREATE TABLE IF NOT EXISTS demo_data_seed (
                    dataset_key TEXT PRIMARY KEY, seeded_at TEXT NOT NULL
                )
                """);
    }

    /** Adds account links, atomic event notifications, and missing links for existing admin queues. */
    private static void migrateAdminNotificationSchema() {
        addColumnIfMissing("notifications", "target_username", "TEXT");
        // Capture real profile/status changes from every role's existing update path.
        // No profile values or credentials are copied into the notification body.
        runSql("""
                CREATE TRIGGER IF NOT EXISTS notify_admin_account_update
                AFTER UPDATE OF full_name, password_hash, residence_hall, bio, preferred_meetup, active, role ON users
                WHEN OLD.full_name IS NOT NEW.full_name OR OLD.password_hash IS NOT NEW.password_hash
                    OR OLD.residence_hall IS NOT NEW.residence_hall OR OLD.bio IS NOT NEW.bio
                    OR OLD.preferred_meetup IS NOT NEW.preferred_meetup OR OLD.active IS NOT NEW.active
                    OR OLD.role IS NOT NEW.role
                BEGIN
                    INSERT INTO notifications (username, title, message, category, timestamp, target_username)
                    SELECT username, 'User Account Updated', NEW.username ||
                        CASE WHEN NEW.password_hash LIKE '!deleted-%' AND OLD.password_hash IS NOT NEW.password_hash
                            THEN ' was permanently deleted.'
                        WHEN OLD.active != NEW.active THEN
                            CASE WHEN NEW.active = 1 THEN ' was activated.' ELSE ' was deactivated.' END
                        ELSE ' updated account details.' END,
                        'Account Update', strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), NEW.username
                    FROM users WHERE role = 'ADMIN' AND active = 1;
                END
                """);
        // A report and its admin notifications succeed or roll back together, including duplicate-report handling.
        runSql("""
                CREATE TRIGGER IF NOT EXISTS notify_admin_listing_report
                AFTER INSERT ON listing_reports
                BEGIN
                    INSERT INTO notifications (username, sender, title, message, category, timestamp, listing_id)
                    SELECT username, NEW.buyer_username, 'Listing Reported',
                        NEW.buyer_username || ' reported listing #' || NEW.listing_id || ' for moderation.',
                        'Listing Report', NEW.created_at, NEW.listing_id
                    FROM users WHERE role = 'ADMIN' AND active = 1;
                END
                """);
        // Existing queues get reliable ID-based links; legacy notification text is preserved without guessing a target.
        runSql("""
                INSERT INTO notifications (username, title, message, category, timestamp, listing_id)
                SELECT a.username, 'Listing Awaiting Review', 'Review ' || l.item_name || ' (#' || l.listing_id || ').',
                    'Listing Review', COALESCE(l.submitted_at, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')), l.listing_id
                FROM users a CROSS JOIN listings l
                WHERE a.role = 'ADMIN' AND a.active = 1 AND l.listing_status = 'PENDING_REVIEW'
                    AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.username = a.username
                        AND n.listing_id = l.listing_id AND n.category = 'Listing Review')
                """);
        runSql("""
                INSERT INTO notifications (username, sender, title, message, category, timestamp, listing_id)
                SELECT a.username, r.buyer_username, 'Listing Reported',
                    r.buyer_username || ' reported listing #' || r.listing_id || ' for moderation.',
                    'Listing Report', r.created_at, r.listing_id
                FROM users a CROSS JOIN listing_reports r JOIN listings l ON l.listing_id = r.listing_id
                WHERE a.role = 'ADMIN' AND a.active = 1
                    AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.username = a.username
                        AND n.sender = r.buyer_username AND n.listing_id = r.listing_id AND n.category = 'Listing Report')
                """);
    }

    /** Writes the dataset atomically; the version marker prevents startup from recreating edited/deleted samples. */
    public static void seedData(String datasetKey, List<User> users, List<Listing> listings, List<Deal> deals,
                                List<Notification> notifications, List<Review> reviews) {
        seedData(datasetKey, users, listings, deals, notifications, reviews, Map.of());
    }

    /** Resolves optional notification listing keys to actual generated IDs inside the seed transaction. */
    public static void seedData(String datasetKey, List<User> users, List<Listing> listings, List<Deal> deals,
                                List<Notification> notifications, List<Review> reviews,
                                Map<Notification, String> notificationListingKeys) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (seedId(connection, "SELECT 1 FROM demo_data_seed WHERE dataset_key = ?", datasetKey) != null) {
                    connection.rollback();
                    return;
                }
                Map<String, String> accounts = new LinkedHashMap<>();
                for (User user : users) {
                    Map<String, Object> existing;
                    try (PreparedStatement query = connection.prepareStatement("SELECT * FROM users WHERE username = ? COLLATE NOCASE LIMIT 1")) {
                        query.setString(1, user.getUsername());
                        try (ResultSet result = query.executeQuery()) {
                            existing = result.next() ? resultSetToMap(result) : null;
                        }
                    }
                    if (existing == null) {
                        insertSeedRow(connection, "users", "username, full_name, password_hash, role, residence_hall, bio, preferred_meetup, created_at, average_rating, rating_count",
                                user.getUsername(), user.getFullName(), user.getPasswordHash(), user.getRole().name(),
                                user.getResidenceHall(), user.getBio(), user.getPreferredMeetup(), user.getCreatedAt().toString(),
                                user.getAverageRating(), user.getRatingCount());
                        accounts.put(user.getUsername(), user.getUsername());
                    } else if (user.getRole().name().equals(existing.get("role")) && existing.get("deleted_at") == null
                            && ((Number) existing.get("active")).intValue() == 1) {
                        accounts.put(user.getUsername(), (String) existing.get("username"));
                    }
                }
                Map<String, Integer> listingIds = new LinkedHashMap<>();
                for (Listing listing : listings) {
                    String seller = accounts.get(listing.getSellerUsername());
                    if (seller == null) continue;
                    Integer id = seedId(connection, "SELECT listing_id FROM listings WHERE item_name = ? AND COALESCE(NULLIF(seller, ''), seller_username) = ? COLLATE NOCASE AND listing_type = ? LIMIT 1",
                            listing.getTitle(), seller, listing.getType().name());
                    if (id == null) {
                        id = insertSeedRow(connection, "listings", "item_name, listing_type, category, item_condition, listing_status, price, lending_days, seller, seller_username, description, meetup_location, submitted_at, posted_date, availability_status, approved",
                                listing.getTitle(), listing.getType().name(), listing.getCategory(), listing.getCondition(),
                                listing.getStatus().name(), listing.getPrice(), listing.getLendingDays(), seller, seller,
                                listing.getDescription(), listing.getMeetupLocation(), listing.getSubmittedAt().toString(),
                                listing.getPostedAt() == null ? null : listing.getPostedAt().toString(), listing.getAvailabilityStatus(),
                                listing.getStatus() == ListingStatus.PENDING_REVIEW ? -1 : 1);
                    }
                    listingIds.put(listing.getId(), id);
                }
                java.util.Set<String> offerRecipients = new java.util.HashSet<>();
                for (Deal deal : deals) {
                    Integer id = listingIds.get(deal.getListingId());
                    String buyer = accounts.get(deal.getBuyerUsername());
                    if (id == null || buyer == null || seedId(connection,
                            "SELECT 1 FROM listings WHERE listing_id = ? AND listing_status = 'LIVE' AND availability_status = 'Available'", id) == null) continue;
                    if (seedId(connection, "SELECT request_id FROM listing_requests WHERE buyer_username = ? COLLATE NOCASE AND listing_id = ? AND request_type = 'OFFER' LIMIT 1", buyer, id) == null) {
                        insertSeedRow(connection, "listing_requests", "buyer_username, listing_id, request_type, offer_price, status, created_at, updated_at",
                                buyer, id, "OFFER", deal.getOfferPrice(), deal.getStatus().toUpperCase(java.util.Locale.ROOT),
                                deal.getCreatedAt().toString(), deal.getCreatedAt().toString());
                    }
                    if (seedId(connection, "SELECT 1 FROM listing_requests WHERE buyer_username = ? COLLATE NOCASE AND listing_id = ? AND request_type = 'OFFER' AND status = 'ACCEPTED' AND offer_price = ?",
                            buyer, id, deal.getOfferPrice()) != null) {
                        offerRecipients.add(buyer);
                        String seller = accounts.get(deal.getSellerUsername());
                        if (seller != null) offerRecipients.add(seller);
                    }
                }
                for (Review review : reviews) {
                    String buyer = accounts.get(review.getBuyerUsername());
                    Listing reviewed = listings.stream().filter(listing -> listing.getTitle().equals(review.getListingTitle())
                            && listing.getSellerUsername().equals(review.getSellerUsername())).findFirst().orElse(null);
                    Integer id = reviewed == null ? null : listingIds.get(reviewed.getId());
                    if (id == null || buyer == null
                            || seedId(connection, "SELECT 1 FROM listings WHERE listing_id = ? AND listing_status = 'SOLD'", id) == null) continue;
                    if (seedId(connection, "SELECT 1 FROM reviews WHERE buyer_username = ? COLLATE NOCASE AND listing_id = ?", buyer, id) == null) {
                        insertSeedRow(connection, "reviews", "buyer_username, listing_id, rating, text, created_at",
                                buyer, id, review.getRating(), review.getComment(), review.getCreatedAt().toString());
                    }
                }
                java.util.Set<String> pendingRecipients = new java.util.HashSet<>();
                for (Listing listing : listings) {
                    Integer id = listingIds.get(listing.getId());
                    if (listing.getStatus() != ListingStatus.PENDING_REVIEW || id == null
                            || seedId(connection, "SELECT 1 FROM listings WHERE listing_id = ? AND listing_status = 'PENDING_REVIEW'", id) == null) continue;
                    pendingRecipients.add(accounts.get(listing.getSellerUsername()));
                    for (User user : users) {
                        if (user.getRole() == UserRole.ADMIN && accounts.containsKey(user.getUsername())) {
                            pendingRecipients.add(accounts.get(user.getUsername()));
                        }
                    }
                }
                for (Notification notification : notifications) {
                    String recipient = accounts.get(notification.getUsername());
                    if (recipient == null) continue;
                    if ("Offer".equals(notification.getCategory()) && !offerRecipients.contains(recipient)) continue;
                    if (("System".equals(notification.getCategory()) || "Listing Review".equals(notification.getCategory()))
                            && !pendingRecipients.contains(recipient)) continue;
                    if (seedId(connection, "SELECT 1 FROM notifications WHERE username = ? AND title = ? AND message = ? AND category = ? LIMIT 1",
                            recipient, notification.getTitle(), notification.getMessage(), notification.getCategory()) == null) {
                        String listingKey = notificationListingKeys.get(notification);
                        insertSeedRow(connection, "notifications", "username, title, message, category, timestamp, listing_id",
                                recipient, notification.getTitle(), notification.getMessage(), notification.getCategory(), notification.getTimestamp().toString(),
                                listingKey == null ? null : listingIds.get(listingKey));
                    }
                }
                insertSeedRow(connection, "demo_data_seed", "dataset_key, seeded_at", datasetKey, LocalDateTime.now().toString());
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) { throw new IllegalStateException("Could not seed demo data. No changes were saved.", e); }
    }

    private static Integer seedId(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getInt(1) : null; }
        }
    }

    /** Identifiers are private, fixed seed definitions; every data value is bound. */
    private static int insertSeedRow(Connection connection, String table, String columns, Object... values) throws SQLException {
        String placeholders = String.join(", ", java.util.Collections.nCopies(values.length, "?"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table + " (" + columns
                + ") VALUES (" + placeholders + ")", Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
            try (ResultSet result = statement.getGeneratedKeys()) {
                if (!result.next()) throw new SQLException("Seed record ID was not generated.");
                return result.getInt(1);
            }
        }
    }

    /** Adds buyer deal, review, and report columns without rewriting seller or admin tables. */
    private static void migrateBuyerDealColumns() {
        addColumnIfMissing("listing_requests", "borrow_start", "TEXT");
        addColumnIfMissing("listing_requests", "borrow_end", "TEXT");
        addColumnIfMissing("listing_requests", "due_at", "TEXT");
        addColumnIfMissing("listing_requests", "completed_at", "TEXT");
        addColumnIfMissing("listing_requests", "buyer_completed_at", "TEXT");
        addColumnIfMissing("listing_requests", "seller_completed_at", "TEXT");
        addColumnIfMissing("listing_requests", "returned_at", "TEXT");
        addColumnIfMissing("listing_requests", "rated", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("reviews", "created_at", "TEXT");
        addColumnIfMissing("reviews", "seller_reply", "TEXT");
        addColumnIfMissing("reviews", "hidden", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("reviews", "flagged", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("notifications", "request_id", "INTEGER");
        addColumnIfMissing("notifications", "listing_id", "INTEGER");
        runSql("""
                CREATE TABLE IF NOT EXISTS listing_reports (
                    buyer_username TEXT NOT NULL,
                    listing_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (buyer_username, listing_id)
                )
                """);
    }

    private static void addColumnIfMissing(String table, String column, String definition) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect " + table + " schema", e);
        }
        runSql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static void migrateListingSchema() {
        addListingColumnIfMissing("listing_type", "TEXT NOT NULL DEFAULT 'SELL'");
        addListingColumnIfMissing("listing_status", "TEXT NOT NULL DEFAULT 'LIVE'");
        addListingColumnIfMissing("price", "REAL");
        addListingColumnIfMissing("lending_days", "INTEGER");
        addListingColumnIfMissing("seller", "TEXT");
    }

    private static void addListingColumnIfMissing(String column, String definition) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(listings)");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect listings schema", e);
        }
        runSql("ALTER TABLE listings ADD COLUMN " + column + " " + definition);
        migrateListingReviewSchema();
    }

    private static void migrateNotificationSchema() {
        try (Connection connection = connect()) {
            List<String> columns = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA table_info(notifications)")) {
                while (result.next()) columns.add(result.getString("name"));
            }

            if (columns.contains("notification_id") && columns.contains("is_read")) {
                if (!columns.contains("sender")) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("ALTER TABLE notifications ADD COLUMN sender TEXT");
                    }
                }
                return;
            }

            String idColumn = columns.contains("notification_id") ? "notification_id" : "id";
            String readColumn = columns.contains("is_read") ? "is_read" : "read";
            String senderColumn = columns.contains("sender") ? "sender" : "NULL";
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE notifications_migration (
                            notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            username TEXT NOT NULL,
                            sender TEXT,
                            title TEXT NOT NULL,
                            message TEXT NOT NULL,
                            category TEXT NOT NULL,
                            timestamp TEXT NOT NULL,
                            is_read INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                statement.executeUpdate("INSERT INTO notifications_migration "
                        + "(notification_id, username, sender, title, message, category, timestamp, is_read) "
                        + "SELECT " + idColumn + ", username, " + senderColumn
                        + ", title, message, category, timestamp, " + readColumn
                        + " FROM notifications");
                statement.executeUpdate("DROP TABLE notifications");
                statement.executeUpdate("ALTER TABLE notifications_migration RENAME TO notifications");
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not migrate notifications", e);
        }
    }

    /** Upgrades old databases in one transaction, preserving listing IDs and schema objects. */
    private static void migrateListingReviewSchema() {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                List<String> columns = new ArrayList<>();
                boolean requiredPostedDate = false;
                try (ResultSet result = statement.executeQuery("PRAGMA table_info(listings)")) {
                    while (result.next()) {
                        columns.add(result.getString("name"));
                        if ("posted_date".equals(result.getString("name"))) {
                            requiredPostedDate = result.getInt("notnull") != 0;
                        }
                    }
                }
                // SQLite requires rebuilding a table to remove a NOT NULL constraint.
                if (requiredPostedDate) {
                    String schema;
                    try (ResultSet result = statement.executeQuery(
                            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'listings'")) {
                        result.next();
                        schema = result.getString(1);
                    }
                    List<String> schemaObjects = new ArrayList<>();
                    try (ResultSet result = statement.executeQuery("""
                            SELECT sql FROM sqlite_master WHERE tbl_name = 'listings'
                                AND type IN ('index', 'trigger') AND sql IS NOT NULL
                            """)) {
                        while (result.next()) schemaObjects.add(result.getString(1));
                    }
                    long sequence = 0;
                    try (ResultSet result = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name = 'listings'")) {
                        if (result.next()) sequence = result.getLong(1);
                    }
                    String replacement = schema.replaceFirst(
                            "(?i)CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?(?:\"listings\"|`listings`|\\[listings\\]|listings)",
                            "CREATE TABLE listings_review_migration");
                    String nullableSchema = replacement.replaceFirst("(?i)(\\bposted_date\\s+DATE)\\s+NOT\\s+NULL", "$1");
                    if (replacement.equals(schema) || nullableSchema.equals(replacement)) {
                        throw new SQLException("Unsupported legacy listing schema");
                    }
                    statement.executeUpdate(nullableSchema);
                    String columnList = columns.stream().map(column -> "\"" + column.replace("\"", "\"\"") + "\"")
                            .collect(java.util.stream.Collectors.joining(", "));
                    statement.executeUpdate("INSERT INTO listings_review_migration (" + columnList + ") SELECT "
                            + columnList + " FROM listings");
                    statement.executeUpdate("DROP TABLE listings");
                    statement.executeUpdate("ALTER TABLE listings_review_migration RENAME TO listings");
                    for (String sql : schemaObjects) statement.executeUpdate(sql);
                    try (PreparedStatement update = connection.prepareStatement("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = 'listings'")) {
                        update.setLong(1, sequence);
                        update.executeUpdate();
                    }
                }
                Map<String, String> additions = new LinkedHashMap<>();
                for (String column : List.of("seller_username", "reviewed_at", "reviewed_by", "rejection_reason",
                        "submitted_at", "listing_type")) additions.put(column, "TEXT");
                additions.put("price", "REAL");
                additions.put("lending_days", "INTEGER");
                additions.put("review_version", "INTEGER NOT NULL DEFAULT 0");
                for (Map.Entry<String, String> column : additions.entrySet()) {
                    if (!columns.contains(column.getKey())) {
                        statement.executeUpdate("ALTER TABLE listings ADD COLUMN " + column.getKey() + " " + column.getValue());
                    }
                }
                if (!columns.contains("submitted_at")) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE listings SET submitted_at = COALESCE(NULLIF(TRIM(posted_date), ''), ?)")) {
                        update.setString(1, LocalDateTime.now().toString());
                        update.executeUpdate();
                    }
                    // Legacy unpublished dates represented submission, rather than publication.
                    statement.executeUpdate("UPDATE listings SET posted_date = NULL WHERE approved <> 1");
                }
                statement.executeUpdate("""
                        CREATE TRIGGER IF NOT EXISTS listings_review_version
                        AFTER UPDATE OF item_name, category, item_condition, description, numPhotos,
                            photo1, photo2, photo3, photo4, photo5, photo6, meetup_location,
                            posted_date, availability_status, approved, seller_username, reviewed_at,
                            reviewed_by, rejection_reason, submitted_at, listing_type, price, lending_days ON listings
                        BEGIN
                            UPDATE listings SET review_version = OLD.review_version + 1
                            WHERE listing_id = NEW.listing_id;
                        END
                        """);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not upgrade listing review data", e);
        }
    }

    /** Inserts a user only when its username is not already in use, ignoring case. */
    public static void createUserIfMissing(String username, String fullName, String passwordHash,
                                         String role, String residenceHall, String bio, String preferredMeetup) {
        String sql = """
            INSERT INTO users (username, full_name, password_hash, role, residence_hall, bio, preferred_meetup, created_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = ? COLLATE NOCASE)
            """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, fullName);
            stmt.setString(3, passwordHash);
            stmt.setString(4, role);
            stmt.setString(5, residenceHall);
            stmt.setString(6, bio);
            stmt.setString(7, preferredMeetup);
            stmt.setString(8, LocalDateTime.now().toString());
            stmt.setString(9, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create missing user", e);
        }
    }

    public static boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE LIMIT 1";

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet result = stmt.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check username", e);
        }
    }

    public static void createUser(String username, String fullName, String passwordHash,
                                  String role, String residenceHall, String bio, String preferredMeetup) {

        switch (role) {
            case "BUYER", "SELLER", "ADMIN" -> createUser(username, fullName, passwordHash, UserRole.valueOf(role), residenceHall, bio, preferredMeetup);
            default -> throw new IllegalArgumentException("Invalid role: " + role);
        }
    }


    public static void createUser(String username, String fullName, String passwordHash,
                                  UserRole role, String residenceHall, String bio, String preferredMeetup) {
        String sql = """
            INSERT INTO users (username, full_name, password_hash, role, residence_hall, bio, preferred_meetup, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, fullName);
            stmt.setString(3, passwordHash);
            stmt.setString(4, role.name());
            stmt.setString(5, residenceHall);     // Can be null
            stmt.setString(6, bio);               // Can be null
            stmt.setString(7, preferredMeetup);   // Can be null
            stmt.setString(8, java.time.LocalDateTime.now().toString()); // created_at

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create user", e);
        }
    }

    public static int createListing(String itemName, String category, String itemCondition,
                                    String description, int numPhotos,
                                    String photo1, String photo2, String photo3,
                                    String photo4, String photo5, String photo6,
                                    String meetupLocation, String postedDate, String availabilityStatus) {

        return createListing(itemName, category, itemCondition, description, numPhotos,
                photo1, photo2, photo3, photo4, photo5, photo6,
                meetupLocation, postedDate, availabilityStatus, null);
    }

    /** Seller-aware overload; existing callers retain their original signature. */
    public static int createListing(String itemName, String category, String itemCondition,
                                    String description, int numPhotos,
                                    String photo1, String photo2, String photo3,
                                    String photo4, String photo5, String photo6,
                                    String meetupLocation, String postedDate, String availabilityStatus,
                                    String sellerUsername) {

        return createListing(itemName, category, itemCondition, description, numPhotos,
                photo1, photo2, photo3, photo4, photo5, photo6, meetupLocation, postedDate,
                availabilityStatus, sellerUsername, null, null, null);
    }

    /** New pending submissions have a submission date and no publication date yet. */
    public static int createListing(String itemName, String category, String itemCondition,
                                    String description, int numPhotos,
                                    String photo1, String photo2, String photo3,
                                    String photo4, String photo5, String photo6,
                                    String meetupLocation, String submittedAt, String availabilityStatus,
                                    String sellerUsername, String listingType, Double price, Integer lendingDays) {

        String sql = """
            INSERT INTO listings (
                item_name, listing_type, category, item_condition, listing_status, price, lending_days,
                seller, description, numPhotos,
                photo1, photo2, photo3, photo4, photo5, photo6, 
                meetup_location, posted_date, submitted_at, availability_status, approved, seller_username
            ) VALUES (?, COALESCE(?, 'SELL'), ?, ?, 'PENDING_REVIEW', ?, ?, COALESCE(?, ''),
                ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, -1, ?)
        """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, itemName);
            stmt.setString(2, listingType);
            stmt.setString(3, category);
            stmt.setString(4, itemCondition);
            stmt.setObject(5, price);
            stmt.setObject(6, lendingDays);
            stmt.setString(7, sellerUsername);
            stmt.setString(8, description);
            stmt.setInt(9, numPhotos);
            stmt.setString(10, photo1);
            stmt.setString(11, photo2);
            stmt.setString(12, photo3);
            stmt.setString(13, photo4);
            stmt.setString(14, photo5);
            stmt.setString(15, photo6);
            stmt.setString(16, meetupLocation);
            stmt.setString(17, submittedAt == null || submittedAt.isBlank() ? LocalDateTime.now().toString() : submittedAt);
            stmt.setString(18, availabilityStatus);
            stmt.setString(19, sellerUsername);

            stmt.executeUpdate();

            // Retrieve the auto-generated listing_id
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Creating listing failed, no ID obtained.");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create listing", e);
        }
    }

    /** Saves a seller submission, its photos, and admin notifications as one database operation. */
    public static String submitListing(Listing listing, String sellerUsername, List<Path> photos) {
        return submitListing(listing, sellerUsername, photos, null);
    }

    public static void addAdminListing(String admin, String seller, Listing listing, List<Path> photos) {
        submitListing(listing, seller, photos, admin);
    }

    private static void checkListingAdmin(Connection connection, String admin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM users WHERE username = ? AND role = 'ADMIN' AND active = 1")) {
            statement.setString(1, admin);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException("An active admin account is required.");
            }
        }
    }

    private static String submitListing(Listing listing, String sellerUsername, List<Path> photos, String admin) {
        if (listing == null || sellerUsername == null || sellerUsername.isBlank() || photos == null) {
            throw new IllegalArgumentException("Listing, seller, and photos are required.");
        }
        if (photos.size() > 6) {
            throw new IllegalArgumentException("A listing cannot have more than 6 photos.");
        }

        List<Path> storedPhotos = new ArrayList<>();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (admin != null) checkListingAdmin(connection, admin);
                String canonicalUsername;
                String sellerName;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT username, full_name FROM users
                        WHERE username = ? COLLATE NOCASE AND role = 'SELLER' AND active = 1
                        """)) {
                    statement.setString(1, sellerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SecurityException("An active seller account is required.");
                        }
                        canonicalUsername = result.getString("username");
                        sellerName = result.getString("full_name");
                    }
                }

                String[] photoNames = new String[6];
                for (int i = 0; i < photos.size(); i++) {
                    Path source = photos.get(i);
                    String fileName = source == null ? "" : source.getFileName().toString();
                    int extensionIndex = fileName.lastIndexOf('.');
                    String extension = extensionIndex < 0 ? "" : fileName.substring(extensionIndex).toLowerCase();
                    if (source == null || !Files.isRegularFile(source)
                            || !List.of(".jpg", ".jpeg", ".png", ".gif", ".bmp")
                            .contains(extension)) {
                        throw new IllegalArgumentException("Choose a valid image file for each photo.");
                    }
                    if (ImageIO.read(source.toFile()) == null) {
                        throw new IllegalArgumentException("Choose a valid image file for each photo.");
                    }

                    String storedName = UUID.randomUUID() + extension;
                    Path destination = UPLOADS_DIR.resolve(storedName).normalize();
                    if (!destination.getParent().equals(UPLOADS_DIR)) {
                        throw new IllegalArgumentException("Invalid photo path.");
                    }
                    Files.createDirectories(UPLOADS_DIR);
                    Files.copy(source, destination);
                    storedPhotos.add(destination);
                    photoNames[i] = Path.of("uploads", storedName).toString().replace('\\', '/');
                }

                LocalDateTime submittedAt = LocalDateTime.now();
                String insertSql = """
                        INSERT INTO listings (
                            item_name, listing_type, category, item_condition, listing_status,
                            price, lending_days, seller, seller_username, description, numPhotos,
                            photo1, photo2, photo3, photo4, photo5, photo6, meetup_location,
                            posted_date, submitted_at, availability_status, approved
                        ) VALUES (?, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 'Available', -1)
                        """;
                int listingId;
                try (PreparedStatement statement = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, listing.getTitle());
                    statement.setString(2, listing.getType().name());
                    statement.setString(3, listing.getCategory());
                    statement.setString(4, listing.getCondition());
                    statement.setObject(5, listing.getPrice());
                    statement.setObject(6, listing.getLendingDays());
                    statement.setString(7, canonicalUsername);
                    statement.setString(8, canonicalUsername);
                    statement.setString(9, listing.getDescription());
                    statement.setInt(10, photos.size());
                    for (int i = 0; i < 6; i++) statement.setString(11 + i, photoNames[i]);
                    statement.setString(17, listing.getMeetupLocation());
                    statement.setString(18, submittedAt.toString());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Creating listing failed, no ID obtained.");
                        listingId = keys.getInt(1);
                    }
                }

                if (admin != null) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE listings SET listing_status = 'LIVE', approved = 1, posted_date = ?,
                                reviewed_at = ?, reviewed_by = ? WHERE listing_id = ?
                            """)) {
                        statement.setString(1, submittedAt.toString());
                        statement.setString(2, submittedAt.toString());
                        statement.setString(3, admin);
                        statement.setInt(4, listingId);
                        statement.executeUpdate();
                    }
                    insertNotification(connection, canonicalUsername, admin, "Listing Created",
                            admin + " published '" + listing.getTitle() + "' on your behalf.", "System", null, listingId);
                } else try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (username, sender, title, message, category, timestamp, listing_id)
                        SELECT username, ?, 'New Listing Submitted', ?, 'Listing Review', ?, ?
                        FROM users WHERE role = 'ADMIN' AND active = 1
                        """)) {
                    statement.setString(1, canonicalUsername);
                    statement.setString(2, sellerName + " submitted '" + listing.getTitle() + "' for review.");
                    statement.setString(3, submittedAt.toString());
                    statement.setInt(4, listingId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalStateException("No active admin account is available to notify.");
                    }
                }

                connection.commit();
                listing.setId(Integer.toString(listingId));
                listing.setSellerUsername(canonicalUsername);
                listing.setSellerFullName(sellerName);
                listing.setStatus(admin == null ? ListingStatus.PENDING_REVIEW : ListingStatus.LIVE);
                listing.setPostedAt(admin == null ? null : submittedAt);
                listing.setReviewVersion(admin == null ? 0 : 1);
                listing.setIsApproved(admin != null);
                listing.setSubmittedAt(submittedAt);
                listing.setPhotoPaths(java.util.Arrays.stream(photoNames)
                        .filter(name -> name != null).toList());
                return listing.getId();
            } catch (SQLException | IOException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                for (Path photo : storedPhotos) {
                    try {
                        Files.deleteIfExists(photo);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                }
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not submit listing.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not submit listing.", e);
        }
    }

    /** Updates a seller-owned listing and sends it back through admin review atomically. */
    public static void updateSellerListing(Listing listing, String sellerUsername,
                                           List<Path> photos, boolean replacePhotos) {
        updateListing(listing, sellerUsername, photos, replacePhotos, null);
    }

    public static void updateAdminListing(String admin, Listing listing, List<Path> photos, boolean replacePhotos) {
        Map<String, Object> current = fetchRow("listings", "listing_id", parseListingId(listing.getId()));
        if (current == null) throw new IllegalStateException("Listing no longer exists. Refresh and try again.");
        updateListing(listing, current.get("seller_username").toString(), photos, replacePhotos, admin);
    }

    private static void updateListing(Listing listing, String sellerUsername,
                                      List<Path> photos, boolean replacePhotos, String admin) {
        if (listing == null || sellerUsername == null || sellerUsername.isBlank() || photos == null) {
            throw new IllegalArgumentException("Listing, seller, and photos are required.");
        }
        if (photos.size() > 6) {
            throw new IllegalArgumentException("A listing cannot have more than 6 photos.");
        }

        int listingId = parseListingId(listing.getId());
        List<Path> storedPhotos = new ArrayList<>();
        List<String> oldPhotos = new ArrayList<>();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (admin != null) checkListingAdmin(connection, admin);
                String canonicalUsername;
                String sellerName;
                String[] photoNames = new String[6];
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT l.seller_username, l.photo1, l.photo2, l.photo3, l.photo4, l.photo5, l.photo6,
                            u.username AS account_username, u.full_name
                        FROM listings l
                        JOIN users u ON u.username = l.seller_username COLLATE NOCASE
                            AND u.role = 'SELLER' AND (u.active = 1 OR ? = 1)
                        WHERE l.listing_id = ? AND l.seller_username = ? COLLATE NOCASE
                            AND l.review_version = ?
                        """)) {
                    statement.setInt(1, admin == null ? 0 : 1);
                    statement.setInt(2, listingId);
                    statement.setString(3, sellerUsername);
                    statement.setLong(4, listing.getReviewVersion());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("This listing is no longer available or has changed. Refresh your listings and try again.");
                        }
                        canonicalUsername = result.getString("account_username");
                        sellerName = result.getString("full_name");
                        for (int i = 0; i < 6; i++) {
                            String oldPhoto = result.getString("photo" + (i + 1));
                            if (oldPhoto != null && !oldPhoto.isBlank()) oldPhotos.add(oldPhoto);
                            photoNames[i] = replacePhotos ? null : oldPhoto;
                        }
                    }
                }

                if (replacePhotos) {
                    for (int i = 0; i < photos.size(); i++) {
                        Path source = photos.get(i);
                        String fileName = source == null ? "" : source.getFileName().toString();
                        int extensionIndex = fileName.lastIndexOf('.');
                        String extension = extensionIndex < 0 ? "" : fileName.substring(extensionIndex).toLowerCase();
                        if (source == null || !Files.isRegularFile(source)
                                || !List.of(".jpg", ".jpeg", ".png", ".gif", ".bmp").contains(extension)
                                || ImageIO.read(source.toFile()) == null) {
                            throw new IllegalArgumentException("Choose a valid image file for each photo.");
                        }
                        String storedName = UUID.randomUUID() + extension;
                        Path destination = UPLOADS_DIR.resolve(storedName).normalize();
                        if (!destination.getParent().equals(UPLOADS_DIR)) {
                            throw new IllegalArgumentException("Invalid photo path.");
                        }
                        Files.createDirectories(UPLOADS_DIR);
                        Files.copy(source, destination);
                        storedPhotos.add(destination);
                        photoNames[i] = Path.of("uploads", storedName).toString().replace('\\', '/');
                    }
                }

                LocalDateTime submittedAt = LocalDateTime.now();
                String updateSql = """
                        UPDATE listings SET item_name = ?, listing_type = ?, category = ?, item_condition = ?,
                            %s price = ?, lending_days = ?, description = ?,
                            numPhotos = ?, photo1 = ?, photo2 = ?, photo3 = ?, photo4 = ?, photo5 = ?, photo6 = ?,
                            meetup_location = ?, %s
                        WHERE listing_id = ? AND seller_username = ? COLLATE NOCASE AND review_version = ?
                        """.formatted(admin == null ? "listing_status = 'PENDING_REVIEW'," : "",
                        admin == null ? "posted_date = NULL, submitted_at = ?, availability_status = 'Available', approved = -1, reviewed_at = NULL, reviewed_by = NULL, rejection_reason = NULL"
                                : "submitted_at = COALESCE(submitted_at, ?)");
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setString(1, listing.getTitle());
                    statement.setString(2, listing.getType().name());
                    statement.setString(3, listing.getCategory());
                    statement.setString(4, listing.getCondition());
                    statement.setObject(5, listing.getPrice());
                    statement.setObject(6, listing.getLendingDays());
                    statement.setString(7, listing.getDescription());
                    statement.setInt(8, replacePhotos ? photos.size() : oldPhotos.size());
                    for (int i = 0; i < 6; i++) statement.setString(9 + i, photoNames[i]);
                    statement.setString(15, listing.getMeetupLocation());
                    statement.setString(16, submittedAt.toString());
                    statement.setInt(17, listingId);
                    statement.setString(18, canonicalUsername);
                    statement.setLong(19, listing.getReviewVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("This listing is no longer available or has changed. Refresh your listings and try again.");
                    }
                }

                if (admin != null) {
                    insertNotification(connection, canonicalUsername, admin, "Listing Updated",
                            admin + " updated '" + listing.getTitle() + "'. Its status is unchanged.", "System", null, listingId);
                } else try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (username, sender, title, message, category, timestamp, listing_id)
                        SELECT username, ?, 'Listing Updated - Review Required', ?, 'Listing Review', ?, ?
                        FROM users WHERE role = 'ADMIN' AND active = 1
                        """)) {
                    statement.setString(1, canonicalUsername);
                    statement.setString(2, sellerName + " updated '" + listing.getTitle() + "' and it is awaiting re-approval.");
                    statement.setString(3, submittedAt.toString());
                    statement.setInt(4, listingId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalStateException("No active admin account is available to notify.");
                    }
                }

                connection.commit();
                listing.setSellerUsername(canonicalUsername);
                listing.setSellerFullName(sellerName);
                if (admin == null) {
                    listing.setStatus(ListingStatus.PENDING_REVIEW);
                    listing.setSubmittedAt(submittedAt);
                }
                if (replacePhotos) {
                    listing.setPhotoPaths(java.util.Arrays.stream(photoNames)
                            .filter(name -> name != null).toList());
                }
            } catch (SQLException | IOException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                for (Path photo : storedPhotos) {
                    try {
                        Files.deleteIfExists(photo);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                }
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not update listing.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update listing.", e);
        }

        if (replacePhotos) deleteStoredPhotos(oldPhotos, "updated");
    }

    /**
     * Records the seller's completion confirmation for the accepted deal on this listing.
     * The listing is only finalized when the buyer has also marked complete.
     *
     * @return true if both sides have now confirmed and the deal is fully completed
     */
    public static boolean completeSellerListing(String listingId, String sellerUsername, long expectedVersion) {
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller account is required.");
        }
        int id = parseListingId(listingId);
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT listing_id FROM listings
                        WHERE listing_id = ? AND seller_username = ? COLLATE NOCASE
                            AND review_version = ?
                        """)) {
                    statement.setInt(1, id);
                    statement.setString(2, sellerUsername);
                    statement.setLong(3, expectedVersion);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException(
                                    "This listing is no longer available or has changed. Refresh and try again.");
                        }
                    }
                }
                Map<String, Object> request = fetchAcceptedCompletableDeal(connection, id, sellerUsername);
                if (request == null) {
                    throw new IllegalStateException(
                            "Accept a buyer's offer or claim first. Both buyer and seller must mark complete.");
                }
                if (request.get("seller_completed_at") != null) {
                    throw new IllegalStateException("You already marked this deal as complete.");
                }
                int requestId = ((Number) request.get("request_id")).intValue();
                String buyer = (String) request.get("buyer_username");
                String itemName = request.get("item_name") == null ? "the item" : (String) request.get("item_name");
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listing_requests
                        SET seller_completed_at = ?, updated_at = ?
                        WHERE request_id = ? AND seller_completed_at IS NULL
                            AND completed_at IS NULL AND returned_at IS NULL AND status = 'ACCEPTED'
                        """)) {
                    update.setString(1, now.toString());
                    update.setString(2, now.toString());
                    update.setInt(3, requestId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This deal cannot be marked complete.");
                    }
                }
                request.put("seller_completed_at", now.toString());
                boolean fullyDone = request.get("buyer_completed_at") != null
                        && finalizeMutualDealCompletion(connection, request, now);
                if (!fullyDone) {
                    insertNotification(connection, buyer, sellerUsername, "Seller marked complete",
                            sellerUsername + " marked the deal for " + itemName
                                    + " as complete. Mark complete on your side to finish the listing.",
                            "System", requestId, id);
                }
                connection.commit();
                return fullyDone;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not complete listing.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not complete listing.", e);
        }
    }

    public static Map<String, Object> fetchSellerCompletableDeal(String listingId, String sellerUsername) {
        int id = parseListingId(listingId);
        try (Connection connection = connect()) {
            return fetchAcceptedCompletableDeal(connection, id, sellerUsername);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load the accepted deal.", e);
        }
    }

    private static Map<String, Object> fetchAcceptedCompletableDeal(Connection connection, int listingId,
                                                                    String sellerUsername) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.request_id, r.buyer_username, r.listing_id, r.request_type, r.status,
                       r.completed_at, r.buyer_completed_at, r.seller_completed_at, r.returned_at,
                       l.item_name, l.seller, l.listing_type, l.listing_status
                FROM listing_requests r
                JOIN listings l ON l.listing_id = r.listing_id
                WHERE r.listing_id = ? AND r.status = 'ACCEPTED'
                    AND r.request_type IN ('OFFER', 'CLAIM')
                    AND r.completed_at IS NULL AND r.returned_at IS NULL
                    AND (l.seller = ? COLLATE NOCASE OR l.seller_username = ? COLLATE NOCASE)
                ORDER BY r.updated_at DESC
                LIMIT 1
                """)) {
            statement.setInt(1, listingId);
            statement.setString(2, sellerUsername);
            statement.setString(3, sellerUsername);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? resultSetToMap(result) : null;
            }
        }
    }

    /** Finalizes the deal only when both buyer_completed_at and seller_completed_at are set. */
    private static boolean finalizeMutualDealCompletion(Connection connection, Map<String, Object> request,
                                                        LocalDateTime now) throws SQLException {
        if (request.get("buyer_completed_at") == null || request.get("seller_completed_at") == null) {
            return false;
        }
        int requestId = ((Number) request.get("request_id")).intValue();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE listing_requests
                SET completed_at = ?, updated_at = ?
                WHERE request_id = ? AND completed_at IS NULL AND returned_at IS NULL
                    AND buyer_completed_at IS NOT NULL AND seller_completed_at IS NOT NULL
                """)) {
            update.setString(1, now.toString());
            update.setString(2, now.toString());
            update.setInt(3, requestId);
            if (update.executeUpdate() != 1) return false;
        }
        if (request.get("listing_id") != null && "SELL".equals(request.get("listing_type"))
                && "RESERVED".equals(request.get("listing_status"))) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE listings SET listing_status = 'SOLD', availability_status = 'Unavailable'
                    WHERE listing_id = ? AND listing_status = 'RESERVED'
                    """)) {
                update.setInt(1, ((Number) request.get("listing_id")).intValue());
                update.executeUpdate();
            }
        }
        String buyer = (String) request.get("buyer_username");
        String seller = (String) request.get("seller");
        String itemName = request.get("item_name") == null ? "the item" : (String) request.get("item_name");
        Integer listingId = request.get("listing_id") instanceof Number value ? value.intValue() : null;
        if (buyer != null) {
            insertNotification(connection, buyer, seller == null ? "system" : seller, "Deal completed",
                    "Both sides marked complete. The deal for " + itemName + " is finished.",
                    "System", requestId, listingId);
        }
        if (seller != null) {
            insertNotification(connection, seller, buyer == null ? "system" : buyer, "Deal completed",
                    "Both sides marked complete. The deal for " + itemName + " is finished.",
                    "System", requestId, listingId);
        }
        return true;
    }

    /** Permanently removes a seller-owned listing and its saved favourites. */
    public static void deleteSellerListing(String listingId, String sellerUsername, long expectedVersion) {
        if (sellerUsername == null || sellerUsername.isBlank()) {
            throw new IllegalArgumentException("A seller account is required.");
        }
        int id = parseListingId(listingId);
        List<String> photos = new ArrayList<>();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT l.photo1, l.photo2, l.photo3, l.photo4, l.photo5, l.photo6
                        FROM listings l
                        JOIN users u ON u.username = l.seller_username COLLATE NOCASE
                            AND u.role = 'SELLER' AND u.active = 1
                        WHERE l.listing_id = ? AND l.seller_username = ? COLLATE NOCASE
                            AND l.review_version = ?
                        """)) {
                    statement.setInt(1, id);
                    statement.setString(2, sellerUsername);
                    statement.setLong(3, expectedVersion);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("This listing is no longer available or has changed. Refresh your listings and try again.");
                        }
                        for (int i = 1; i <= 6; i++) {
                            String photo = result.getString("photo" + i);
                            if (photo != null && !photo.isBlank()) photos.add(photo);
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM favourites WHERE listing_id = ?")) {
                    statement.setInt(1, id);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM listings WHERE listing_id = ? AND seller_username = ? COLLATE NOCASE
                            AND review_version = ?
                        """)) {
                    statement.setInt(1, id);
                    statement.setString(2, sellerUsername);
                    statement.setLong(3, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("This listing is no longer available or has changed. Refresh your listings and try again.");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not delete listing.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete listing.", e);
        }
        deleteStoredPhotos(photos, "deleted");
    }

    private static void deleteStoredPhotos(List<String> storedPhotoPaths, String completedAction) {
        for (String storedPhoto : storedPhotoPaths) {
            Path photo = Path.of(storedPhoto).toAbsolutePath().normalize();
            if (!photo.getParent().equals(UPLOADS_DIR)) continue;
            try {
                Files.deleteIfExists(photo);
            } catch (IOException e) {
                throw new IllegalStateException("Listing was " + completedAction
                        + ", but an old photo could not be removed: " + storedPhoto, e);
            }
        }
    }

    private static int parseListingId(String listingId) {
        try {
            int id = Integer.parseInt(listingId);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Listing IDs in SQLite are positive integers.
        }
        throw new IllegalArgumentException("A valid listing ID is required.");
    }

    /** Saves a pending review and its feedback/notification atomically. */
    static void reviewListing(int listingId, String adminUsername, boolean approved, String reason, long expectedVersion) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String seller;
                String title;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT l.item_name, u.username AS recipient
                        FROM listings l LEFT JOIN users u
                            ON u.username = l.seller_username COLLATE NOCASE AND u.role = 'SELLER'
                        WHERE l.listing_id = ? AND l.approved = -1 AND l.review_version = ?
                        """)) {
                    statement.setInt(1, listingId);
                    statement.setLong(2, expectedVersion);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("Listing is unavailable or has changed since it was displayed. Please review it again.");
                        }
                        seller = result.getString("recipient");
                        title = result.getString("item_name");
                        if (seller == null) {
                            throw new IllegalStateException("Listing must be linked to a seller account before review.");
                        }
                    }
                }

                String reviewedAt = LocalDateTime.now().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE listings SET approved = ?, listing_status = ?, reviewed_at = ?, reviewed_by = ?, rejection_reason = ?,
                            posted_date = CASE WHEN ? = 1 AND (posted_date IS NULL OR TRIM(posted_date) = '')
                                THEN ? ELSE posted_date END
                        WHERE listing_id = ? AND approved = -1 AND review_version = ?
                          AND EXISTS (SELECT 1 FROM users
                              WHERE username = ? COLLATE NOCASE AND role = 'ADMIN' AND active = 1)
                        """)) {
                    statement.setInt(1, approved ? 1 : 0);
                    statement.setString(2, approved ? "LIVE" : "REJECTED");
                    statement.setString(3, reviewedAt);
                    statement.setString(4, adminUsername);
                    statement.setString(5, approved ? null : reason);
                    statement.setInt(6, approved ? 1 : 0);
                    statement.setString(7, reviewedAt);
                    statement.setInt(8, listingId);
                    statement.setLong(9, expectedVersion);
                    statement.setString(10, adminUsername);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Listing is no longer pending or the admin account is inactive.");
                    }
                }

                if (!approved) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO feedbacks (admin_username, listing_id, reason) VALUES (?, ?, ?)
                            ON CONFLICT(admin_username, listing_id) DO UPDATE SET reason = excluded.reason
                            """)) {
                        statement.setString(1, adminUsername);
                        statement.setInt(2, listingId);
                        statement.setString(3, reason);
                        statement.executeUpdate();
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (username, title, message, category, timestamp, listing_id)
                        VALUES (?, ?, ?, 'System', ?, ?)
                        """)) {
                    statement.setString(1, seller);
                    statement.setString(2, approved ? "Listing Approved" : "Listing Rejected");
                    statement.setString(3, "Your listing '" + title + "' " + (approved
                            ? "is now live!" : "was rejected. Reason: " + reason));
                    statement.setString(4, reviewedAt);
                    statement.setInt(5, listingId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save listing review. Please try again.", e);
        }
    }

    public static void createFeedback(String adminUsername, String listingId, String reason) {
        String sql = """
        INSERT INTO feedbacks (admin_username, listing_id, reason)
        VALUES (?, ?, ?)
    """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, adminUsername);
            stmt.setString(2, listingId); // Note: Schema says STRING, not INTEGER
            stmt.setString(3, reason);

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create feedback", e);
        }
    }

    public static void createOffer(String buyerUsername, int listingId, double price, String chatboxId) {
        String sql = """
        INSERT INTO offers (buyer_username, listing_id, price, chatbox_id)
        VALUES (?, ?, ?, ?)
    """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, buyerUsername);
            stmt.setInt(2, listingId);
            stmt.setDouble(3, price);
            stmt.setString(4, chatboxId);

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create offer", e);
        }
    }

    public static List<Map<String, Object>> fetchBuyerOffers(String buyerUsername) {
        String sql = """
            SELECT o.buyer_username, o.listing_id, o.price, o.chatbox_id,
                   l.item_name, l.listing_type, l.listing_status, l.posted_date,
                   l.seller, u.full_name AS seller_name
            FROM offers o
            JOIN listings l ON l.listing_id = o.listing_id
            JOIN users u ON u.username = l.seller
            WHERE o.buyer_username = ?
            ORDER BY l.posted_date DESC
            """;
        return fetchQuery(sql, buyerUsername);
    }

    public static int createListingRequest(String buyerUsername, int listingId, String requestType,
                                           Double offerPrice, Integer proposedDays) {
        String normalizedType = requestType == null ? "" : requestType.toUpperCase();
        if (!List.of("OFFER", "BORROW", "CLAIM").contains(normalizedType)) {
            throw new IllegalArgumentException("Invalid request type.");
        }
        LocalDateTime now = LocalDateTime.now();
        String insertRequest = """
                INSERT INTO listing_requests
                    (buyer_username, listing_id, request_type, offer_price, proposed_days, created_at, updated_at)
                SELECT ?, l.listing_id, ?, ?, ?, ?, ?
                FROM listings l
                WHERE l.listing_id = ? AND l.listing_status = 'LIVE'
                    AND l.availability_status = 'Available'
                    AND ((? = 'OFFER' AND l.listing_type = 'SELL')
                        OR (? = 'BORROW' AND l.listing_type = 'LEND')
                        OR (? = 'CLAIM' AND l.listing_type = 'GIVEAWAY'))
                """;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    insertRequest, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, buyerUsername);
                statement.setString(2, normalizedType);
                if (offerPrice == null) statement.setNull(3, Types.REAL);
                else statement.setDouble(3, offerPrice);
                if (proposedDays == null) statement.setNull(4, Types.INTEGER);
                else statement.setInt(4, proposedDays);
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.setInt(7, listingId);
                statement.setString(8, normalizedType);
                statement.setString(9, normalizedType);
                statement.setString(10, normalizedType);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("This listing is no longer available for requests.");
                }
                int requestId;
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Request ID was not generated.");
                    requestId = keys.getInt(1);
                }
                String seller = fetchString(connection,
                        "SELECT seller FROM listings WHERE listing_id = ?", listingId);
                String itemName = fetchString(connection,
                        "SELECT item_name FROM listings WHERE listing_id = ?", listingId);
                insertNotification(connection, seller, buyerUsername, "New " + normalizedType.toLowerCase() + " request",
                        buyerUsername + " submitted a " + normalizedType.toLowerCase()
                                + " request for " + itemName + ".", "Offer", requestId, listingId);
                connection.commit();
                return requestId;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not submit listing request.", e);
        }
    }

    public static List<Map<String, Object>> fetchSellerPendingRequests(String sellerUsername) {
        return fetchQuery("""
                SELECT r.request_id, r.buyer_username, buyer.full_name AS buyer_name, r.listing_id,
                       r.request_type, r.offer_price, r.proposed_days, r.status, r.counter_price,
                       r.created_at, r.updated_at, l.item_name, l.listing_type, l.listing_status
                FROM listing_requests r
                JOIN listings l ON l.listing_id = r.listing_id
                JOIN users buyer ON buyer.username = r.buyer_username
                WHERE l.seller = ? AND r.status = 'PENDING'
                ORDER BY r.created_at
                """, sellerUsername);
    }

    public static List<Map<String, Object>> fetchBuyerRequests(String buyerUsername) {
        return fetchQuery("""
                SELECT r.request_id, r.buyer_username, r.listing_id, r.request_type, r.offer_price,
                       r.proposed_days, r.status, r.counter_price, r.created_at, r.updated_at,
                       l.item_name, l.listing_type, l.listing_status, l.seller,
                       seller.full_name AS seller_name
                FROM listing_requests r
                JOIN listings l ON l.listing_id = r.listing_id
                JOIN users seller ON seller.username = l.seller
                WHERE r.buyer_username = ?
                ORDER BY r.created_at DESC
                """, buyerUsername);
    }

    public static void resolveListingRequest(String sellerUsername, int requestId, String action,
                                             Double counterPrice) {
        String normalizedAction = action == null ? "" : action.toUpperCase();
        if (!List.of("ACCEPT", "DECLINE", "COUNTER").contains(normalizedAction)) {
            throw new IllegalArgumentException("Invalid request action.");
        }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> request = fetchRequestForSeller(connection, sellerUsername, requestId);
                if (request == null || !"PENDING".equals(request.get("status"))) {
                    throw new IllegalStateException("This request is no longer pending.");
                }
                String buyer = (String) request.get("buyer_username");
                int listingId = ((Number) request.get("listing_id")).intValue();
                String itemName = (String) request.get("item_name");
                String newStatus = switch (normalizedAction) {
                    case "ACCEPT" -> "ACCEPTED";
                    case "DECLINE" -> "DECLINED";
                    default -> "COUNTERED";
                };
                LocalDateTime now = LocalDateTime.now();
                String dueAt = null;
                if ("ACCEPT".equals(normalizedAction) && "LEND".equals(request.get("listing_type"))) {
                    Object borrowEnd = request.get("borrow_end");
                    if (borrowEnd != null && !borrowEnd.toString().isBlank()) {
                        dueAt = borrowEnd.toString();
                    } else {
                        int days = request.get("proposed_days") == null
                                ? 1 : Math.max(1, ((Number) request.get("proposed_days")).intValue());
                        dueAt = now.plusDays(days).toString();
                    }
                }

                if ("ACCEPT".equals(normalizedAction)) {
                    String listingStatus = switch ((String) request.get("listing_type")) {
                        case "SELL" -> "RESERVED";
                        case "LEND" -> "LENT";
                        case "GIVEAWAY" -> "GIVEN";
                        default -> throw new IllegalStateException("Unsupported listing type.");
                    };
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE listings SET listing_status = ?, availability_status = 'Unavailable'
                            WHERE listing_id = ? AND seller = ? AND listing_status = 'LIVE'
                                AND availability_status = 'Available'
                            """)) {
                        update.setString(1, listingStatus);
                        update.setInt(2, listingId);
                        update.setString(3, sellerUsername);
                        if (update.executeUpdate() != 1) {
                            throw new IllegalStateException("This listing is no longer available.");
                        }
                    }
                }

                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listing_requests
                        SET status = ?, counter_price = ?, updated_at = ?, due_at = COALESCE(?, due_at)
                        WHERE request_id = ? AND status = 'PENDING'
                        """)) {
                    update.setString(1, newStatus);
                    if ("COUNTER".equals(normalizedAction)) update.setDouble(2, counterPrice);
                    else update.setNull(2, Types.REAL);
                    update.setString(3, now.toString());
                    if (dueAt == null) update.setNull(4, Types.VARCHAR);
                    else update.setString(4, dueAt);
                    update.setInt(5, requestId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This request is no longer pending.");
                    }
                }

                String title = switch (normalizedAction) {
                    case "ACCEPT" -> "Request accepted";
                    case "DECLINE" -> "Request declined";
                    default -> "Seller sent a counteroffer";
                };
                String message = switch (normalizedAction) {
                    case "ACCEPT" -> "Your request for " + itemName + " was accepted.";
                    case "DECLINE" -> "Your request for " + itemName + " was declined.";
                    default -> "The seller countered your offer for " + itemName
                            + " at HK$" + counterPrice + ".";
                };
                insertNotification(connection, buyer, sellerUsername, title, message, "Offer", requestId, listingId);
                if (dueAt != null) {
                    String dueDate = dueAt.length() >= 10 ? dueAt.substring(0, 10) : dueAt;
                    insertNotification(connection, buyer, sellerUsername, "Borrow due-date reminder",
                            "Return " + itemName + " by " + dueDate + ".", "Reminder",
                            requestId, listingId);
                }

                if ("ACCEPT".equals(normalizedAction)) {
                    try (PreparedStatement updateOthers = connection.prepareStatement("""
                            UPDATE listing_requests SET status = 'DECLINED', updated_at = ?
                            WHERE listing_id = ? AND request_id <> ? AND status = 'PENDING'
                            """)) {
                        updateOthers.setString(1, now.toString());
                        updateOthers.setInt(2, listingId);
                        updateOthers.setInt(3, requestId);
                        updateOthers.executeUpdate();
                    }
                    try (PreparedStatement others = connection.prepareStatement("""
                            SELECT request_id, buyer_username FROM listing_requests
                            WHERE listing_id = ? AND request_id <> ? AND status = 'DECLINED'
                                AND updated_at = ?
                            """)) {
                        others.setInt(1, listingId);
                        others.setInt(2, requestId);
                        others.setString(3, now.toString());
                        try (ResultSet results = others.executeQuery()) {
                            while (results.next()) {
                                insertNotification(connection, results.getString("buyer_username"), sellerUsername,
                                        "Listing no longer available",
                                        itemName + " is no longer available because another request was accepted.",
                                        "Offer", results.getInt("request_id"), listingId);
                            }
                        }
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update listing request.", e);
        }
    }

    public static List<Map<String, Object>> fetchRequestMessages(int requestId) {
        return fetchQuery("""
                SELECT sender_username, sender_role, content, timestamp
                FROM request_messages WHERE request_id = ? ORDER BY message_id
                """, requestId);
    }

    public static boolean isRequestParticipant(String username, int requestId) {
        return !fetchQuery("""
                SELECT 1 FROM listing_requests r
                JOIN listings l ON l.listing_id = r.listing_id
                WHERE r.request_id = ? AND (r.buyer_username = ? OR l.seller = ?)
                """, requestId, username, username).isEmpty();
    }

    public static void appendRequestMessage(int requestId, String senderUsername,
                                            String senderRole, String content) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO request_messages
                         (request_id, sender_username, sender_role, content, timestamp)
                     SELECT ?, ?, ?, ?, ?
                     WHERE EXISTS (
                         SELECT 1 FROM listing_requests r JOIN listings l ON l.listing_id = r.listing_id
                         WHERE r.request_id = ? AND (r.buyer_username = ? OR l.seller = ?)
                     )
                     """)) {
            statement.setInt(1, requestId);
            statement.setString(2, senderUsername);
            statement.setString(3, senderRole);
            statement.setString(4, content);
            statement.setString(5, LocalDateTime.now().toString());
            statement.setInt(6, requestId);
            statement.setString(7, senderUsername);
            statement.setString(8, senderUsername);
            if (statement.executeUpdate() != 1) {
                throw new SecurityException("You are not a participant in this request.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not send request message.", e);
        }
    }

    private static Map<String, Object> fetchRequestForSeller(Connection connection, String sellerUsername,
                                                              int requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.request_id, r.buyer_username, r.listing_id, r.request_type, r.offer_price,
                       r.proposed_days, r.borrow_end, r.status, l.item_name, l.listing_type
                FROM listing_requests r JOIN listings l ON l.listing_id = r.listing_id
                WHERE r.request_id = ? AND l.seller = ?
                """)) {
            statement.setInt(1, requestId);
            statement.setString(2, sellerUsername);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? resultSetToMap(result) : null;
            }
        }
    }

    private static String fetchString(Connection connection, String sql, int value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, value);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Expected listing data was not found.");
                return result.getString(1);
            }
        }
    }

    private static void insertNotification(Connection connection, String username, String sender,
                                           String title, String message, String category) throws SQLException {
        insertNotification(connection, username, sender, title, message, category, null, null);
    }

    private static void insertNotification(Connection connection, String username, String sender,
                                           String title, String message, String category,
                                           Integer requestId, Integer listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications
                    (username, sender, title, message, category, timestamp, request_id, listing_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, username);
            statement.setString(2, sender);
            statement.setString(3, title);
            statement.setString(4, message);
            statement.setString(5, category);
            statement.setString(6, LocalDateTime.now().toString());
            if (requestId == null) statement.setNull(7, Types.INTEGER);
            else statement.setInt(7, requestId);
            if (listingId == null) statement.setNull(8, Types.INTEGER);
            else statement.setInt(8, listingId);
            statement.executeUpdate();
        }
    }

    public static List<Listing> fetchFavouriteListings(String buyerUsername) {
        String sql = """
            SELECT l.* FROM listings l
            JOIN favourites f ON f.listing_id = l.listing_id
            WHERE f.buyer_username = ?
            ORDER BY l.posted_date DESC
            """;
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : fetchQuery(sql, buyerUsername)) {
            listings.add(DataService.toListing(row));
        }
        return listings;
    }

    public static List<Integer> fetchFavouriteListingIds(String buyerUsername) {
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> row : fetchQuery(
                "SELECT listing_id FROM favourites WHERE buyer_username = ?", buyerUsername)) {
            ids.add(((Number) row.get("listing_id")).intValue());
        }
        return ids;
    }

    public static void setFavourite(String buyerUsername, int listingId, boolean favourite) {
        String sql = favourite
                ? "INSERT OR IGNORE INTO favourites (buyer_username, listing_id) VALUES (?, ?)"
                : "DELETE FROM favourites WHERE buyer_username = ? AND listing_id = ?";
        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, buyerUsername);
            stmt.setInt(2, listingId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update favourite listing", e);
        }
    }

    public static void updateUserProfile(String username, String fullName,
                                         String residenceHall, String bio,
                                         String preferredMeetup, String passwordHash) {
        String sql = """
            UPDATE users
            SET full_name = ?, residence_hall = ?, bio = ?,
                preferred_meetup = ?, password_hash = COALESCE(?, password_hash)
            WHERE username = ?
            """;
        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, fullName);
            stmt.setString(2, residenceHall);
            stmt.setString(3, bio);
            stmt.setString(4, preferredMeetup);
            stmt.setString(5, passwordHash);
            stmt.setString(6, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update user profile", e);
        }
    }

    public static void createNotification(String username, String sender, String title,
                                          String message, String category) {
        createNotification(username, sender, title, message, category, null, null);
    }

    public static void createNotification(String username, String sender, String title,
                                          String message, String category,
                                          Integer requestId, Integer listingId) {
        String sql = """
        INSERT INTO notifications
            (username, sender, title, message, category, timestamp, request_id, listing_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, sender);
            stmt.setString(3, title);
            stmt.setString(4, message);
            stmt.setString(5, category);
            stmt.setString(6, LocalDateTime.now().toString());
            if (requestId == null) stmt.setNull(7, Types.INTEGER);
            else stmt.setInt(7, requestId);
            if (listingId == null) stmt.setNull(8, Types.INTEGER);
            else stmt.setInt(8, listingId);

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create notification", e);
        }
    }

    /** Newest request this buyer has on the listing, if any. */
    public static Integer latestBuyerRequestId(String buyerUsername, int listingId) {
        List<Map<String, Object>> rows = fetchQuery("""
                SELECT request_id FROM listing_requests
                WHERE buyer_username = ? AND listing_id = ?
                ORDER BY request_id DESC LIMIT 1
                """, buyerUsername, listingId);
        if (rows.isEmpty() || !(rows.get(0).get("request_id") instanceof Number requestId)) return null;
        return requestId.intValue();
    }

    public static void deleteData(String tableName, String key) {
        // Determine the primary key column based on the table name
        String pkColumn = tableName.equals("users") ? "username" : "id";

        String sql = "DELETE FROM " + tableName + " WHERE " + pkColumn + " = ?";

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, key);
            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete data from " + tableName, e);
        }
    }

    public static void createReview(String buyerUsername, int listingId, double rating, String text) {
        String sql = """
        INSERT INTO reviews (buyer_username, listing_id, rating, text)
        VALUES (?, ?, ?, ?)
    """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, buyerUsername);
            stmt.setInt(2, listingId);
            stmt.setDouble(3, rating);
            stmt.setString(4, text); // Can be null

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create review", e);
        }
    }

    public static void editData(String tableName, String key, String field, Object value) {
        // Determine the primary key column based on the table name
        String pkColumn = tableName.equals("users") ? "username" : "id";

        String sql = "UPDATE " + tableName + " SET " + field + " = ? WHERE " + pkColumn + " = ?";

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            // Dynamically set the value based on its type
            if (value instanceof Integer) {
                stmt.setInt(1, (Integer) value);
            } else if (value instanceof Boolean) {
                stmt.setInt(1, ((Boolean) value) ? 1 : 0); // SQLite stores booleans as 1/0
            } else if (value instanceof Double) {
                stmt.setDouble(1, (Double) value);
            } else if (value instanceof Float) {
                stmt.setFloat(1, (Float) value);
            } else if (value instanceof Long) {
                stmt.setLong(1, (Long) value);
            } else {
                // Default to String (handles TEXT and nulls)
                stmt.setString(1, value != null ? value.toString() : null);
            }

            // Set the key for the WHERE clause
            stmt.setString(2, key);

            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new IllegalStateException("Could not edit data in " + tableName, e);
        }
    }

    public static void uploadPhoto(Path sourcePhoto, String listingId, int photoIndex) {
        String photoName = listingId + "_" + photoIndex + PHOTO_EXTENSION; // Example naming convention
        if (sourcePhoto == null || photoName.isBlank()) {
            throw new IllegalArgumentException("Photo and photo name are required.");
        }

        Path destination = UPLOADS_DIR.resolve(photoName)
                .normalize();

        if (!destination.getParent().equals(UPLOADS_DIR)) {
            throw new IllegalArgumentException("Invalid photo name.");
        }

        try {
            if (!Files.isRegularFile(sourcePhoto)) {
                throw new IllegalArgumentException("Source photo does not exist.");
            }

            Files.createDirectories(UPLOADS_DIR);
            Files.copy(sourcePhoto, destination);
        } catch (IOException e) {
            throw new IllegalStateException("Could not upload photo.", e);
        }
    }

    public static byte[] fetchPhoto(String listingId, int photoIndex) {
        String photoName = listingId + "_" + photoIndex + PHOTO_EXTENSION; // Example naming convention
        if (photoName.isBlank()) {
            throw new IllegalArgumentException("Photo name is required.");
        }

        Path photo = UPLOADS_DIR.resolve(photoName)
                .normalize();

        if (!photo.getParent().equals(UPLOADS_DIR)) {
            throw new IllegalArgumentException("Invalid photo name.");
        }

        try {
            if (!Files.isRegularFile(photo)) {
                throw new IllegalArgumentException("Photo does not exist.");
            }

            return Files.readAllBytes(photo);
        } catch (IOException e) {
            throw new IllegalStateException("Could not fetch photo.", e);
        }
    }

    private static Map<String, Object> resultSetToMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData metadata = rs.getMetaData();

        for (int i = 1; i <= metadata.getColumnCount(); i++) 
            row.put(metadata.getColumnName(i), rs.getObject(i));

        return row;
    }

    public static Map<String, Object> fetchRow(String tableName, String keyColumn, Object keyValue) {
        String sql = "SELECT * FROM " + tableName
                + " WHERE " + keyColumn + " = ?";

        // Keep account lookup consistent with usernameExists and account seeding.
        if ("users".equals(tableName) && "username".equals(keyColumn)) {
            sql += " COLLATE NOCASE";
        }

        try (Connection connection = connect();
            PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setObject(1, keyValue);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next())
                    return null;

                return resultSetToMap(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not fetch row from " + tableName, e);
        }
    }

    public static List<Map<String, Object>> fetchRows(String tableName, Map<String, Object> filters) {

        StringBuilder sql = new StringBuilder("SELECT * FROM " + tableName);

        List<Object> parameters = new ArrayList<>();

        if (!filters.isEmpty()) {
            sql.append(" WHERE ");

            List<String> conditions = new ArrayList<>();

            for (Map.Entry<String, Object> filter : filters.entrySet()) {
                if (filter.getValue() == null) {
                    conditions.add(filter.getKey() + " IS NULL");
                } else {
                    conditions.add(filter.getKey() + " = ?");
                    parameters.add(filter.getValue());
                }
            }

            sql.append(String.join(" AND ", conditions));
        }

        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection connection = connect();
            PreparedStatement stmt = connection.prepareStatement(sql.toString())) {

            for (int i = 0; i < parameters.size(); i++)
                stmt.setObject(i + 1, parameters.get(i));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next())
                    rows.add(resultSetToMap(rs));
            }

            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not filter rows from " + tableName, e);
        }
    }

    public static List<Listing> fetchListings(Map<String, Object> filters) {
        List<Map<String, Object>> rows = fetchRows("listings", filters);
        List<Listing> listings = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            listings.add(DataService.toListing(row));
        }

        return listings;
    }

    public static List<Notification> fetchNotifications(String username) {
        List<Map<String, Object>> rows = fetchQuery("""
                SELECT * FROM notifications
                WHERE username = ?
                ORDER BY timestamp DESC, notification_id DESC
                """, username);
        List<Notification> notifications = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            notifications.add(DataService.toNotification(row));
        }

        return notifications;
    }

    public static void markNotificationRead(String username, String notificationId) {
        String sql = """
                UPDATE notifications SET is_read = 1
                WHERE username = ? AND notification_id = ?
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setLong(2, Long.parseLong(notificationId));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Notification was not found for this account.");
            }
        } catch (SQLException | NumberFormatException e) {
            throw new IllegalStateException("Could not mark notification as read", e);
        }
    }

    public static boolean hasUnreadNotifications(String username) {
        if (username == null || username.isBlank()) return false;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM notifications
                     WHERE username = ? COLLATE NOCASE AND is_read = 0
                     LIMIT 1
                     """)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check unread notifications", e);
        }
    }

    private static List<Map<String, Object>> fetchQuery(String sql, Object... parameters) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(resultSetToMap(rs));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not execute database query", e);
        }
    }

    public static ChatHistory fetchChatHistoryOf(String listingId, String buyerUsername) {
        validateChatIdentity(listingId, buyerUsername);
        Path chatHistoryFile = chatHistoryFile();
        if (!Files.exists(chatHistoryFile)) return null;

        try {
            JsonObject history = JsonParser.parseString(
                    Files.readString(chatHistoryFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!history.has(listingId)) return null;
            JsonObject listingHistory = history.getAsJsonObject(listingId);
            JsonObject conversation = listingHistory.has("conversations")
                    ? listingHistory.getAsJsonObject("conversations").getAsJsonObject(buyerUsername)
                    : null;
            if (conversation != null) {
                return toChatHistory(listingId, conversation, false);
            }

            // Legacy listing-wide chats are filtered to this buyer's own messages only.
            if (!listingHistory.has("messages")) return null;
            JsonArray ownMessages = new JsonArray();
            for (var element : listingHistory.getAsJsonArray("messages")) {
                JsonObject message = element.getAsJsonObject();
                if ("buyer".equalsIgnoreCase(message.get("role").getAsString())
                        && buyerUsername.equalsIgnoreCase(message.get("user").getAsString())) {
                    ownMessages.add(message.deepCopy());
                }
            }
            return ownMessages.isEmpty() ? null : toChatHistory(listingId,
                    newChatObject(ownMessages), true);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw new IllegalStateException("Could not read chat history", e);
        }
    }

    public static List<String> fetchChatBuyerUsernames(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            throw new IllegalArgumentException("Listing ID must not be blank");
        }
        Path chatHistoryFile = chatHistoryFile();
        if (!Files.exists(chatHistoryFile)) return List.of();
        try {
            JsonObject history = JsonParser.parseString(
                    Files.readString(chatHistoryFile, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!history.has(listingId)) return List.of();
            JsonObject listingHistory = history.getAsJsonObject(listingId);
            java.util.LinkedHashSet<String> buyers = new java.util.LinkedHashSet<>();
            if (listingHistory.has("conversations")) {
                buyers.addAll(listingHistory.getAsJsonObject("conversations").keySet());
            }
            if (listingHistory.has("messages")) {
                for (var element : listingHistory.getAsJsonArray("messages")) {
                    JsonObject message = element.getAsJsonObject();
                    if ("buyer".equalsIgnoreCase(message.get("role").getAsString())) {
                        buyers.add(message.get("user").getAsString());
                    }
                }
            }
            return List.copyOf(buyers);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw new IllegalStateException("Could not read chat participants", e);
        }
    }

    public static void appendMessageToChatHistory(String listingId, String buyerUsername, Message message) {
        validateChatIdentity(listingId, buyerUsername);
        if (message == null) throw new IllegalArgumentException("Chat message must not be null");

        JsonObject history = readChatHistoryFile();
        JsonObject listingHistory = history.has(listingId)
                ? history.getAsJsonObject(listingId) : new JsonObject();
        JsonObject conversations = listingHistory.has("conversations")
                ? listingHistory.getAsJsonObject("conversations") : new JsonObject();
        JsonObject conversation = conversations.has(buyerUsername)
                ? conversations.getAsJsonObject(buyerUsername) : newChatObject(new JsonArray());
        JsonArray messages = conversation.getAsJsonArray("messages");
        JsonObject messageJson = new JsonObject();
        messageJson.addProperty("timestamp", message.getTimestamp().toString());
        messageJson.addProperty("user", message.getSender_id());
        messageJson.addProperty("role", message.getSender_role());
        messageJson.addProperty("content", message.getContent());
        messages.add(messageJson);
        conversation.add("messages", messages);
        conversations.add(buyerUsername, conversation);
        listingHistory.add("conversations", conversations);
        listingHistory.remove("messages");
        listingHistory.remove("seen_buyer");
        listingHistory.remove("seen_seller");
        history.add(listingId, listingHistory);
        writeChatHistoryFile(history);
    }

    public static void markChatHistorySeen(String listingId, String buyerUsername, String role) {
        validateChatIdentity(listingId, buyerUsername);
        if (!"buyer".equals(role) && !"seller".equals(role)) {
            throw new IllegalArgumentException("Chat role must be buyer or seller");
        }
        JsonObject history = readChatHistoryFile();
        JsonObject listingHistory = history.has(listingId)
                ? history.getAsJsonObject(listingId) : new JsonObject();
        JsonObject conversations = listingHistory.has("conversations")
                ? listingHistory.getAsJsonObject("conversations") : new JsonObject();
        JsonObject conversation = conversations.has(buyerUsername)
                ? conversations.getAsJsonObject(buyerUsername) : newChatObject(new JsonArray());
        conversation.addProperty("seen_" + role, true);
        conversations.add(buyerUsername, conversation);
        listingHistory.add("conversations", conversations);
        history.add(listingId, listingHistory);
        writeChatHistoryFile(history);
    }

    private static ChatHistory toChatHistory(String listingId, JsonObject conversation, boolean legacy) {
        List<Message> messages = new ArrayList<>();
        for (var element : conversation.getAsJsonArray("messages")) {
            JsonObject messageJson = element.getAsJsonObject();
            Message message = new Message();
            message.setTimestamp(LocalDateTime.parse(messageJson.get("timestamp").getAsString()));
            message.setSender_id(messageJson.get("user").getAsString());
            message.setSender_role(messageJson.get("role").getAsString());
            message.setContent(messageJson.get("content").getAsString());
            messages.add(message);
        }
        return new ChatHistory(listingId, messages,
                !legacy && conversation.has("seen_buyer") && conversation.get("seen_buyer").getAsBoolean(),
                !legacy && conversation.has("seen_seller") && conversation.get("seen_seller").getAsBoolean());
    }

    private static JsonObject newChatObject(JsonArray messages) {
        JsonObject conversation = new JsonObject();
        conversation.add("messages", messages);
        conversation.addProperty("seen_buyer", false);
        conversation.addProperty("seen_seller", false);
        return conversation;
    }

    private static JsonObject readChatHistoryFile() {
        Path file = chatHistoryFile();
        try {
            return Files.exists(file)
                    ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw new IllegalStateException("Could not read chat history", e);
        }
    }

    private static void writeChatHistoryFile(JsonObject history) {
        try {
            Files.writeString(chatHistoryFile(), JSON.toJson(history), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write chat history", e);
        }
    }

    private static void validateChatIdentity(String listingId, String buyerUsername) {
        if (listingId == null || listingId.isBlank() || buyerUsername == null || buyerUsername.isBlank()) {
            throw new IllegalArgumentException("Listing and buyer are required for a chat.");
        }
    }

    /** Atomically gives the item to the first buyer whose claim commits. */
    public static int claimGiveaway(String buyerUsername, int listingId) {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String seller;
                String itemName;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT seller, item_name FROM listings
                        WHERE listing_id = ? AND listing_type = 'GIVEAWAY'
                            AND listing_status = 'LIVE' AND availability_status = 'Available'
                        """)) {
                    statement.setInt(1, listingId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("This listing is no longer available.");
                        }
                        seller = result.getString("seller");
                        itemName = result.getString("item_name");
                    }
                }
                if (buyerUsername.equalsIgnoreCase(seller)) {
                    throw new IllegalArgumentException("You cannot request your own listing.");
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listings SET listing_status = 'GIVEN', availability_status = 'Unavailable'
                        WHERE listing_id = ? AND listing_type = 'GIVEAWAY'
                            AND listing_status = 'LIVE' AND availability_status = 'Available'
                        """)) {
                    update.setInt(1, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This listing is no longer available.");
                    }
                }
                int requestId;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO listing_requests
                            (buyer_username, listing_id, request_type, status, created_at, updated_at)
                        VALUES (?, ?, 'CLAIM', 'ACCEPTED', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, buyerUsername);
                    insert.setInt(2, listingId);
                    insert.setString(3, now.toString());
                    insert.setString(4, now.toString());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Claim ID was not generated.");
                        requestId = keys.getInt(1);
                    }
                }
                try (PreparedStatement others = connection.prepareStatement("""
                        UPDATE listing_requests SET status = 'DECLINED', updated_at = ?
                        WHERE listing_id = ? AND request_id <> ? AND status = 'PENDING'
                        """)) {
                    others.setString(1, now.toString());
                    others.setInt(2, listingId);
                    others.setInt(3, requestId);
                    others.executeUpdate();
                }
                insertNotification(connection, seller, buyerUsername, "Giveaway claimed",
                        buyerUsername + " claimed " + itemName + ".", "Offer", requestId, listingId);
                connection.commit();
                return requestId;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not claim this giveaway.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not claim this giveaway.", e);
        }
    }

    public static int createBorrowRequest(String buyerUsername, int listingId,
                                          LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Choose a borrow start date and end date.");
        }
        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        if (startDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Borrow start date cannot be in the past.");
        }
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            int lendingDays;
            try (PreparedStatement listing = connection.prepareStatement(
                    "SELECT lending_days FROM listings WHERE listing_id = ? AND listing_type = 'LEND'")) {
                listing.setInt(1, listingId);
                try (ResultSet result = listing.executeQuery()) {
                    if (!result.next() || result.getObject("lending_days") == null) {
                        throw new IllegalArgumentException("This listing does not have a lending period.");
                    }
                    lendingDays = result.getInt("lending_days");
                }
            }
            if (lendingDays <= 0) {
                throw new IllegalArgumentException("This listing does not have a lending period.");
            }
            long requestedDays = ChronoUnit.DAYS.between(startDate, endDate);
            if (requestedDays < 1) {
                throw new IllegalArgumentException("Borrow at least 1 day.");
            }
            if (requestedDays > lendingDays) {
                throw new IllegalArgumentException("You can borrow this item for at most " + lendingDays
                        + (lendingDays == 1 ? " day." : " days."));
            }
            String insertRequest = """
                INSERT INTO listing_requests
                    (buyer_username, listing_id, request_type, proposed_days, borrow_start, borrow_end,
                     created_at, updated_at)
                SELECT ?, l.listing_id, 'BORROW', ?, ?, ?, ?, ?
                FROM listings l
                WHERE l.listing_id = ? AND l.listing_status = 'LIVE'
                    AND l.availability_status = 'Available' AND l.listing_type = 'LEND'
                    AND l.lending_days >= ?
                    AND l.seller <> ? COLLATE NOCASE
                """;
            try (PreparedStatement statement = connection.prepareStatement(
                    insertRequest, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, buyerUsername);
                statement.setInt(2, (int) requestedDays);
                statement.setString(3, startDate.atStartOfDay().toString());
                statement.setString(4, endDate.atStartOfDay().toString());
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.setInt(7, listingId);
                statement.setInt(8, (int) requestedDays);
                statement.setString(9, buyerUsername);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("This listing is no longer available for requests.");
                }
                int requestId;
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Request ID was not generated.");
                    requestId = keys.getInt(1);
                }
                String seller = fetchString(connection, "SELECT seller FROM listings WHERE listing_id = ?", listingId);
                String itemName = fetchString(connection, "SELECT item_name FROM listings WHERE listing_id = ?", listingId);
                insertNotification(connection, seller, buyerUsername, "New borrow request",
                        buyerUsername + " requested to borrow " + itemName + " from "
                                + startDate + " to " + endDate + " (" + requestedDays
                                + (requestedDays == 1 ? " day)." : " days)."), "Offer", requestId, listingId);
                connection.commit();
                return requestId;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not submit borrow request.", e);
        }
    }

    public static void incrementListingViews(int listingId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE listings SET views = views + 1 WHERE listing_id = ?")) {
            statement.setInt(1, listingId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Listing was not found.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update listing views.", e);
        }
    }

    public static void reportListing(String buyerUsername, int listingId) {
        String sql = """
                INSERT INTO listing_reports (buyer_username, listing_id, created_at)
                SELECT ?, listing_id, ?
                FROM listings WHERE listing_id = ?
                ON CONFLICT(buyer_username, listing_id) DO NOTHING
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, buyerUsername);
            statement.setString(2, LocalDateTime.now().toString());
            statement.setInt(3, listingId);
            if (statement.executeUpdate() != 1 && !hasListingReport(buyerUsername, listingId)) {
                throw new IllegalArgumentException("Listing was not found.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not report listing.", e);
        }
    }

    public static boolean hasListingReport(String buyerUsername, int listingId) {
        return !fetchQuery("""
                SELECT 1 FROM listing_reports WHERE buyer_username = ? AND listing_id = ?
                """, buyerUsername, listingId).isEmpty();
    }

    public static List<Integer> fetchReportedListingIds(String buyerUsername) {
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> row : fetchQuery(
                "SELECT listing_id FROM listing_reports WHERE buyer_username = ?", buyerUsername)) {
            ids.add(((Number) row.get("listing_id")).intValue());
        }
        return ids;
    }

    public static List<Map<String, Object>> fetchBuyerDeals(String buyerUsername) {
        return fetchQuery("""
                SELECT r.request_id, r.buyer_username, r.listing_id, r.request_type, r.offer_price,
                       r.proposed_days, r.status, r.counter_price, r.created_at, r.updated_at,
                       r.borrow_start, r.borrow_end, r.due_at, r.completed_at, r.buyer_completed_at,
                       r.seller_completed_at, r.returned_at, r.rated,
                       l.item_name, l.listing_type, l.listing_status, l.category, l.seller,
                       l.availability_status, l.review_version, seller.full_name AS seller_name
                FROM listing_requests r
                LEFT JOIN listings l ON l.listing_id = r.listing_id
                LEFT JOIN users seller ON seller.username = l.seller
                WHERE r.buyer_username = ?
                ORDER BY COALESCE(r.completed_at, r.returned_at, r.updated_at, r.created_at) DESC
                """, buyerUsername);
    }

    /** Returns how many borrowed items were returned because their due time passed. */
    public static int settleExpiredBorrows(String buyerUsername) {
        List<Map<String, Object>> due = fetchQuery("""
                SELECT r.request_id
                FROM listing_requests r
                WHERE r.buyer_username = ? AND r.request_type = 'BORROW' AND r.status = 'ACCEPTED'
                    AND r.returned_at IS NULL AND r.due_at IS NOT NULL AND r.due_at <= ?
                """, buyerUsername, LocalDateTime.now().toString());
        int returned = 0;
        for (Map<String, Object> row : due) {
            returnBorrow(buyerUsername, ((Number) row.get("request_id")).intValue(), true);
            returned++;
        }
        return returned;
    }

    public static void returnBorrow(String buyerUsername, int requestId, boolean automatic) {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> request;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.request_id, r.listing_id, r.status, r.due_at, r.returned_at, l.item_name, l.seller,
                               l.listing_status
                        FROM listing_requests r
                        LEFT JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.request_id = ? AND r.buyer_username = ? AND r.request_type = 'BORROW'
                        """)) {
                    statement.setInt(1, requestId);
                    statement.setString(2, buyerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        request = result.next() ? resultSetToMap(result) : null;
                    }
                }
                if (request == null || !"ACCEPTED".equals(request.get("status")) || request.get("returned_at") != null) {
                    throw new IllegalStateException("This borrowed item cannot be returned.");
                }
                String dueAt = (String) request.get("due_at");
                boolean pastDue = dueAt != null && !LocalDateTime.parse(dueAt).isAfter(now);
                if (!automatic && pastDue) {
                    throw new IllegalStateException("The borrowing period has ended and the item is returned automatically.");
                }
                if (automatic && !pastDue) {
                    connection.rollback();
                    return;
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listing_requests
                        SET returned_at = ?, completed_at = ?, updated_at = ?
                        WHERE request_id = ? AND returned_at IS NULL
                        """)) {
                    update.setString(1, now.toString());
                    update.setString(2, now.toString());
                    update.setString(3, now.toString());
                    update.setInt(4, requestId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This borrowed item cannot be returned.");
                    }
                }
                if (request.get("listing_id") != null && "LENT".equals(request.get("listing_status"))) {
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE listings SET listing_status = 'LIVE', availability_status = 'Available'
                            WHERE listing_id = ? AND listing_status = 'LENT'
                            """)) {
                        update.setInt(1, ((Number) request.get("listing_id")).intValue());
                        update.executeUpdate();
                    }
                }
                String itemName = request.get("item_name") == null ? "your borrowed item" : (String) request.get("item_name");
                String seller = (String) request.get("seller");
                Integer listingId = request.get("listing_id") instanceof Number id ? id.intValue() : null;
                if (seller != null) {
                    insertNotification(connection, seller, buyerUsername,
                            automatic ? "Borrowed item auto-returned" : "Borrowed item returned",
                            itemName + (automatic
                                    ? " was automatically returned because the borrowing period ended."
                                    : " was returned by the borrower."),
                            "System", requestId, listingId);
                }
                if (automatic) {
                    insertNotification(connection, buyerUsername, seller, "Borrow period ended",
                            itemName + " was automatically returned because the due time passed.", "Reminder",
                            requestId, listingId);
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not return the borrowed item.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not return the borrowed item.", e);
        }
    }

    /**
     * Records the buyer's completion confirmation.
     * The listing/deal is only finalized when the seller has also marked complete.
     *
     * @return true if both sides have now confirmed and the deal is fully completed
     */
    public static boolean completeBuyerDeal(String buyerUsername, int requestId) {
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> request;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.request_id, r.listing_id, r.request_type, r.status, r.completed_at,
                               r.buyer_completed_at, r.seller_completed_at, r.returned_at, r.buyer_username,
                               l.item_name, l.seller, l.listing_type, l.listing_status
                        FROM listing_requests r
                        LEFT JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.request_id = ? AND r.buyer_username = ?
                        """)) {
                    statement.setInt(1, requestId);
                    statement.setString(2, buyerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        request = result.next() ? resultSetToMap(result) : null;
                    }
                }
                if (request == null || !"ACCEPTED".equals(request.get("status"))
                        || request.get("completed_at") != null || request.get("returned_at") != null) {
                    throw new IllegalStateException("This deal cannot be marked complete.");
                }
                String type = (String) request.get("request_type");
                if (!"OFFER".equals(type) && !"CLAIM".equals(type)) {
                    throw new IllegalStateException("Borrowed items are completed by returning them.");
                }
                if (request.get("buyer_completed_at") != null) {
                    throw new IllegalStateException("You already marked this deal as complete.");
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listing_requests
                        SET buyer_completed_at = ?, updated_at = ?
                        WHERE request_id = ? AND buyer_completed_at IS NULL
                            AND completed_at IS NULL AND returned_at IS NULL
                        """)) {
                    update.setString(1, now.toString());
                    update.setString(2, now.toString());
                    update.setInt(3, requestId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This deal cannot be marked complete.");
                    }
                }
                request.put("buyer_completed_at", now.toString());
                boolean fullyDone = request.get("seller_completed_at") != null
                        && finalizeMutualDealCompletion(connection, request, now);
                if (!fullyDone) {
                    String seller = (String) request.get("seller");
                    String itemName = request.get("item_name") == null ? "the item" : (String) request.get("item_name");
                    Integer listingId = request.get("listing_id") instanceof Number id ? id.intValue() : null;
                    if (seller != null) {
                        insertNotification(connection, seller, buyerUsername, "Buyer marked complete",
                                buyerUsername + " marked the deal for " + itemName
                                        + " as complete. Mark complete on your side to finish the listing.",
                                "System", requestId, listingId);
                    }
                }
                connection.commit();
                return fullyDone;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not complete the deal.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not complete the deal.", e);
        }
    }

    public static void createBuyerReview(String buyerUsername, int requestId, double rating, String text) {
        if (rating < 0.0 || rating > 5.0 || !Double.isFinite(rating)) {
            throw new IllegalArgumentException("Rating must be between 0.0 and 5.0.");
        }
        String comment = text == null ? "" : text.trim();
        if (comment.length() > 1000) {
            throw new IllegalArgumentException("Review text must be 1000 characters or fewer.");
        }
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                int listingId;
                String seller;
                String itemName;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.listing_id, r.rated, r.completed_at, r.returned_at, l.seller, l.item_name
                        FROM listing_requests r
                        JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.request_id = ? AND r.buyer_username = ? AND r.status = 'ACCEPTED'
                        """)) {
                    statement.setInt(1, requestId);
                    statement.setString(2, buyerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("Only a completed deal can be rated.");
                        }
                        if (result.getString("completed_at") == null && result.getString("returned_at") == null) {
                            throw new IllegalStateException("Only a completed deal can be rated.");
                        }
                        if (result.getInt("rated") != 0) {
                            throw new IllegalStateException("This deal has already been rated.");
                        }
                        listingId = result.getInt("listing_id");
                        seller = result.getString("seller");
                        itemName = result.getString("item_name");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO reviews (buyer_username, listing_id, rating, text, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, buyerUsername);
                    insert.setInt(2, listingId);
                    insert.setDouble(3, rating);
                    insert.setString(4, comment.isEmpty() ? null : comment);
                    insert.setString(5, now.toString());
                    insert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE listing_requests SET rated = 1, updated_at = ? WHERE request_id = ? AND rated = 0")) {
                    update.setString(1, now.toString());
                    update.setInt(2, requestId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This deal has already been rated.");
                    }
                }
                try (PreparedStatement ratingUpdate = connection.prepareStatement("""
                        UPDATE users SET
                            average_rating = COALESCE((
                                SELECT AVG(r.rating) FROM reviews r
                                JOIN listings l ON l.listing_id = r.listing_id
                                WHERE (l.seller = users.username OR l.seller_username = users.username)
                                  AND COALESCE(r.hidden, 0) = 0
                            ), 0),
                            rating_count = (
                                SELECT COUNT(*) FROM reviews r
                                JOIN listings l ON l.listing_id = r.listing_id
                                WHERE (l.seller = users.username OR l.seller_username = users.username)
                                  AND COALESCE(r.hidden, 0) = 0
                            )
                        WHERE username = ?
                        """)) {
                    ratingUpdate.setString(1, seller);
                    ratingUpdate.executeUpdate();
                }
                insertNotification(connection, seller, buyerUsername, "New seller rating",
                        buyerUsername + " rated the deal for " + itemName + " " + rating + " / 5.", "Rating",
                        requestId, listingId);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not save the review.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save the review.", e);
        }
    }

    public static List<Map<String, Object>> fetchSellerReviews(String sellerUsername) {
        return fetchQuery("""
                SELECT r.rating, r.text, r.buyer_username, r.listing_id, r.created_at, r.seller_reply,
                       COALESCE(r.flagged, 0) AS flagged, l.item_name,
                       COALESCE(l.seller, l.seller_username) AS seller_username
                FROM reviews r
                JOIN listings l ON l.listing_id = r.listing_id
                WHERE (l.seller = ? OR l.seller_username = ?) AND COALESCE(r.hidden, 0) = 0
                ORDER BY COALESCE(r.created_at, '') DESC
                """, sellerUsername, sellerUsername);
    }

    public static List<Map<String, Object>> fetchAllReviews() {
        return fetchQuery("""
                SELECT r.buyer_username, r.listing_id, r.rating, r.text, r.created_at, r.seller_reply,
                       COALESCE(r.hidden, 0) AS hidden, COALESCE(r.flagged, 0) AS flagged, l.item_name,
                       COALESCE(l.seller, l.seller_username) AS seller_username
                FROM reviews r
                JOIN listings l ON l.listing_id = r.listing_id
                ORDER BY COALESCE(r.flagged, 0) DESC, julianday(r.created_at) DESC, r.listing_id, r.buyer_username
                """);
    }

    /** Visibility and public rating totals are saved together. */
    public static void setReviewHidden(String admin, String buyerUsername, int listingId, boolean hidden) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                checkListingAdmin(connection, admin);
                String seller;
                try (PreparedStatement find = connection.prepareStatement("""
                        SELECT COALESCE(l.seller, l.seller_username) AS seller
                        FROM reviews r JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.buyer_username = ? AND r.listing_id = ?
                        """)) {
                    find.setString(1, buyerUsername); find.setInt(2, listingId);
                    try (ResultSet result = find.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Review was not found. Refresh the ratings overview.");
                        seller = result.getString("seller");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE reviews SET hidden = ? WHERE buyer_username = ? AND listing_id = ?")) {
                    statement.setInt(1, hidden ? 1 : 0);
                    statement.setString(2, buyerUsername); statement.setInt(3, listingId);
                    if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Review was not found.");
                }
                try (PreparedStatement totals = connection.prepareStatement("""
                        UPDATE users SET
                            average_rating = COALESCE((SELECT AVG(r.rating) FROM reviews r
                                JOIN listings l ON l.listing_id = r.listing_id
                                WHERE COALESCE(l.seller, l.seller_username) = users.username AND COALESCE(r.hidden, 0) = 0), 0),
                            rating_count = (SELECT COUNT(*) FROM reviews r
                                JOIN listings l ON l.listing_id = r.listing_id
                                WHERE COALESCE(l.seller, l.seller_username) = users.username AND COALESCE(r.hidden, 0) = 0)
                        WHERE username = ?
                        """)) {
                    totals.setString(1, seller); totals.executeUpdate();
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not update review visibility.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update review visibility.", e);
        }
    }

    /** Saves/updates a seller reply on a buyer review and notifies the buyer. */
    public static void replyToSellerReview(String sellerUsername, String buyerUsername, int listingId, String replyText) {
        if (sellerUsername == null || sellerUsername.isBlank()
                || buyerUsername == null || buyerUsername.isBlank()) {
            throw new IllegalArgumentException("Seller and buyer are required.");
        }
        String reply = replyText == null ? "" : replyText.trim();
        if (reply.isEmpty() || reply.length() > 1000) {
            throw new IllegalArgumentException("Reply must contain 1 to 1000 characters.");
        }
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String itemName;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.buyer_username, l.item_name
                        FROM reviews r
                        JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.buyer_username = ? COLLATE NOCASE AND r.listing_id = ?
                            AND COALESCE(r.hidden, 0) = 0
                            AND (l.seller = ? COLLATE NOCASE OR l.seller_username = ? COLLATE NOCASE)
                        """)) {
                    statement.setString(1, buyerUsername);
                    statement.setInt(2, listingId);
                    statement.setString(3, sellerUsername);
                    statement.setString(4, sellerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("Review was not found for your listing.");
                        }
                        itemName = result.getString("item_name");
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE reviews SET seller_reply = ?
                        WHERE buyer_username = ? COLLATE NOCASE AND listing_id = ?
                        """)) {
                    update.setString(1, reply);
                    update.setString(2, buyerUsername);
                    update.setInt(3, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("Could not save the seller reply.");
                    }
                }
                insertNotification(connection, buyerUsername, sellerUsername, "Seller replied to your feedback",
                        sellerUsername + " replied to your review for " + itemName + ": " + reply,
                        "Rating", null, listingId);
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not save the seller reply.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save the seller reply.", e);
        }
    }

    public static List<Map<String, Object>> fetchBuyerReviews(String buyerUsername) {
        return fetchQuery("""
                SELECT r.rating, r.text, r.buyer_username, r.listing_id, r.created_at, r.seller_reply,
                       COALESCE(r.flagged, 0) AS flagged,
                       l.item_name, COALESCE(l.seller, l.seller_username) AS seller_username,
                       seller.full_name AS seller_name
                FROM reviews r
                JOIN listings l ON l.listing_id = r.listing_id
                LEFT JOIN users seller ON seller.username = COALESCE(l.seller, l.seller_username)
                WHERE r.buyer_username = ? COLLATE NOCASE AND COALESCE(r.hidden, 0) = 0
                ORDER BY COALESCE(r.created_at, '') DESC
                """, buyerUsername);
    }

    /** Flags a review for admin moderation and notifies all active admins. */
    public static void flagSellerReview(String sellerUsername, String buyerUsername, int listingId) {
        if (sellerUsername == null || sellerUsername.isBlank()
                || buyerUsername == null || buyerUsername.isBlank()) {
            throw new IllegalArgumentException("Seller and buyer are required.");
        }
        LocalDateTime now = LocalDateTime.now();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String itemName;
                String comment;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT r.text, r.flagged, l.item_name
                        FROM reviews r
                        JOIN listings l ON l.listing_id = r.listing_id
                        WHERE r.buyer_username = ? COLLATE NOCASE AND r.listing_id = ?
                            AND COALESCE(r.hidden, 0) = 0
                            AND (l.seller = ? COLLATE NOCASE OR l.seller_username = ? COLLATE NOCASE)
                        """)) {
                    statement.setString(1, buyerUsername);
                    statement.setInt(2, listingId);
                    statement.setString(3, sellerUsername);
                    statement.setString(4, sellerUsername);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalStateException("Review was not found for your listing.");
                        }
                        if (result.getInt("flagged") != 0) {
                            throw new IllegalStateException("This review is already flagged for admin moderation.");
                        }
                        itemName = result.getString("item_name");
                        comment = result.getString("text");
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE reviews SET flagged = 1
                        WHERE buyer_username = ? COLLATE NOCASE AND listing_id = ? AND COALESCE(flagged, 0) = 0
                        """)) {
                    update.setString(1, buyerUsername);
                    update.setInt(2, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This review is already flagged for admin moderation.");
                    }
                }
                String snippet = comment == null || comment.isBlank() ? "(no written comment)" : comment.trim();
                if (snippet.length() > 120) snippet = snippet.substring(0, 117) + "...";
                try (PreparedStatement notify = connection.prepareStatement("""
                        INSERT INTO notifications (username, sender, title, message, category, timestamp, listing_id)
                        SELECT username, ?, 'Review flagged for moderation', ?, 'System', ?, ?
                        FROM users WHERE role = 'ADMIN' AND active = 1
                        """)) {
                    notify.setString(1, sellerUsername);
                    notify.setString(2, sellerUsername + " flagged a review by @" + buyerUsername
                            + " on '" + itemName + "': " + snippet);
                    notify.setString(3, now.toString());
                    notify.setInt(4, listingId);
                    if (notify.executeUpdate() == 0) {
                        throw new IllegalStateException("No active admin account is available to notify.");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not flag the review.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not flag the review.", e);
        }
    }

    public static void setUserActive(String username, boolean active) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE users SET active = ? WHERE username = ? COLLATE NOCASE
                     """)) {
            statement.setInt(1, active ? 1 : 0);
            statement.setString(2, username);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("User was not found.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update the user account.", e);
        }
    }

    /** Retains listing and deal/chat history; decision, pending requests and notices commit together. */
    public static void takeDownListing(int listingId, String admin, String reason, long expectedVersion) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A takedown reason is required.");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                checkListingAdmin(connection, admin);
                String title;
                String seller;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT item_name, seller_username FROM listings
                        WHERE listing_id = ? AND listing_status = 'LIVE' AND review_version = ?
                        """)) {
                    statement.setInt(1, listingId); statement.setLong(2, expectedVersion);
                    try (ResultSet r = statement.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("Listing is no longer live or has changed. Refresh and try again.");
                        title = r.getString("item_name"); seller = r.getString("seller_username");
                    }
                }
                String now = LocalDateTime.now().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE listings SET listing_status = 'REMOVED', availability_status = 'Unavailable'
                        WHERE listing_id = ? AND listing_status = 'LIVE' AND review_version = ?
                        """)) {
                    statement.setInt(1, listingId); statement.setLong(2, expectedVersion);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Listing has changed. Refresh and try again.");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO listing_takedowns (listing_id, admin_username, reason, occurred_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setInt(1, listingId); statement.setString(2, admin);
                    statement.setString(3, reason.trim()); statement.setString(4, now); statement.executeUpdate();
                }
                String message = "'" + title + "' was taken down by " + admin + ". Reason: " + reason.trim();
                insertNotification(connection, seller, admin, "Listing Taken Down", message, "System", null, listingId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT request_id, buyer_username FROM listing_requests
                        WHERE listing_id = ? AND status IN ('PENDING', 'COUNTERED')
                            AND completed_at IS NULL AND returned_at IS NULL
                        """)) {
                    statement.setInt(1, listingId);
                    try (ResultSet r = statement.executeQuery()) {
                        while (r.next()) insertNotification(connection, r.getString("buyer_username"), admin,
                                "Listing Taken Down", message + " Your pending request was declined.", "Offer",
                                r.getInt("request_id"), listingId);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE listing_requests SET status = 'DECLINED', updated_at = ?
                        WHERE listing_id = ? AND status IN ('PENDING', 'COUNTERED')
                            AND completed_at IS NULL AND returned_at IS NULL
                        """)) {
                    statement.setString(1, now); statement.setInt(2, listingId); statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Could not take down listing.", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Could not take down listing.", e); }
    }

    /** Legacy removal API; new admin decisions use takeDownListing with a reason and version. */
    public static void removeListing(int listingId) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String itemName;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT item_name, listing_status FROM listings WHERE listing_id = ?")) {
                    statement.setInt(1, listingId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Listing was not found.");
                        if ("REMOVED".equals(result.getString("listing_status"))) {
                            throw new IllegalStateException("This listing is already removed.");
                        }
                        itemName = result.getString("item_name");
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE listings SET listing_status = 'REMOVED', availability_status = 'Unavailable'
                        WHERE listing_id = ? AND listing_status <> 'REMOVED'
                        """)) {
                    update.setInt(1, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new IllegalStateException("This listing is already removed.");
                    }
                }
                try (PreparedStatement requests = connection.prepareStatement("""
                        SELECT request_id, buyer_username FROM listing_requests
                        WHERE listing_id = ? AND status IN ('PENDING', 'COUNTERED', 'ACCEPTED')
                            AND completed_at IS NULL AND returned_at IS NULL
                        """)) {
                    requests.setInt(1, listingId);
                    try (ResultSet result = requests.executeQuery()) {
                        while (result.next()) {
                            String marker = "(request " + result.getInt("request_id") + ")";
                            insertNotification(connection, result.getString("buyer_username"), "admin",
                                    "Listing removed",
                                    itemName + " was removed. This notice is only for your open offer. " + marker,
                                    "Offer", result.getInt("request_id"), listingId);
                        }
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Could not remove the listing.", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not remove the listing.", e);
        }
    }

    public static void notifyBuyerIfListingRemoved(String buyerUsername) {
        List<Map<String, Object>> rows = fetchQuery("""
                SELECT r.request_id, COALESCE(l.item_name, 'A listing') AS item_name, l.listing_id, l.listing_status
                FROM listing_requests r
                LEFT JOIN listings l ON l.listing_id = r.listing_id
                WHERE r.buyer_username = ?
                    AND r.status IN ('PENDING', 'COUNTERED', 'ACCEPTED')
                    AND r.completed_at IS NULL AND r.returned_at IS NULL
                    AND (l.listing_id IS NULL OR l.listing_status = 'REMOVED')
                """, buyerUsername);
        for (Map<String, Object> row : rows) {
            String marker = "(request " + row.get("request_id") + ")";
            boolean alreadySent = fetchQuery("""
                    SELECT 1 FROM notifications
                    WHERE username = ? AND category = 'Offer' AND message LIKE ?
                    """, buyerUsername, "%" + marker).stream().findAny().isPresent();
            if (alreadySent) continue;
            String itemName = String.valueOf(row.get("item_name"));
            Integer requestId = row.get("request_id") instanceof Number id ? id.intValue() : null;
            Integer listingId = row.get("listing_id") instanceof Number id ? id.intValue() : null;
            createNotification(buyerUsername, "admin", "Listing removed",
                    itemName + " was removed. This notice is only for your open offer. " + marker, "Offer",
                    requestId, listingId);
        }
    }
}
