package com.hkust.marketplace.utils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;

public class DatabaseManager {

    private static final String URL = "jdbc:sqlite:data.db";
    private static final Path UPLOADS_DIR = Path.of("uploads").toAbsolutePath().normalize();
    private static final String PHOTO_EXTENSION = ".jpg"; // Assuming all photos are JPEGs; adjust as needed

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getProperty("marketplace.database.url", URL));
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
                posted_at DATE NOT NULL,
                availability_status TEXT NOT NULL,
                views INTEGER NOT NULL DEFAULT 0,
                approved INTEGER NOT NULL DEFAULT -1
            )
            """);

        migrateListingSchema();

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
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username STRING NOT NULL,
                sender STRING NOT NULL,
                title STRING NOT NULL,
                message STRING NOT NULL,
                category STRING NOT NULL,
                timestamp TEXT NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )
            """);

        runSql("""
                CREATE TABLE IF NOT EXISTS favourites (
                buyer_username TEXT NOT NULL REFERENCES users(username),
                listing_id INTEGER NOT NULL REFERENCES listings(listing_id),
                PRIMARY KEY (buyer_username, listing_id)
            )
            """);
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

        runSql("""
            CREATE TABLE IF NOT EXISTS notifications (
                notification_id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                title TEXT NOT NULL,
                message TEXT NOT NULL,
                category TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0
            )
            """);
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
                item_name, category, item_condition, description, numPhotos, 
                photo1, photo2, photo3, photo4, photo5, photo6, 
                meetup_location, submitted_at, availability_status, seller_username, listing_type, price, lending_days
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, itemName);
            stmt.setString(2, category);
            stmt.setString(3, itemCondition);
            stmt.setString(4, description); // Can be null
            stmt.setInt(5, numPhotos);
            stmt.setString(6, photo1);      // Can be null
            stmt.setString(7, photo2);      // Can be null
            stmt.setString(8, photo3);      // Can be null
            stmt.setString(9, photo4);      // Can be null
            stmt.setString(10, photo5);     // Can be null
            stmt.setString(11, photo6);     // Can be null
            stmt.setString(12, meetupLocation);
            stmt.setString(13, submittedAt == null || submittedAt.isBlank() ? LocalDateTime.now().toString() : submittedAt);
            stmt.setString(14, availabilityStatus);
            stmt.setString(15, sellerUsername);
            stmt.setString(16, listingType);
            stmt.setObject(17, price);
            stmt.setObject(18, lendingDays);

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
                        UPDATE listings SET approved = ?, reviewed_at = ?, reviewed_by = ?, rejection_reason = ?,
                            posted_date = CASE WHEN ? = 1 AND (posted_date IS NULL OR TRIM(posted_date) = '')
                                THEN ? ELSE posted_date END
                        WHERE listing_id = ? AND approved = -1 AND review_version = ?
                          AND EXISTS (SELECT 1 FROM users
                              WHERE username = ? COLLATE NOCASE AND role = 'ADMIN' AND active = 1)
                        """)) {
                    statement.setInt(1, approved ? 1 : 0);
                    statement.setString(2, reviewedAt);
                    statement.setString(3, adminUsername);
                    statement.setString(4, approved ? null : reason);
                    statement.setInt(5, approved ? 1 : 0);
                    statement.setString(6, reviewedAt);
                    statement.setInt(7, listingId);
                    statement.setLong(8, expectedVersion);
                    statement.setString(9, adminUsername);
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
                        INSERT INTO notifications (username, title, message, category, timestamp)
                        VALUES (?, ?, ?, 'System', ?)
                        """)) {
                    statement.setString(1, seller);
                    statement.setString(2, approved ? "Listing Approved" : "Listing Rejected");
                    statement.setString(3, "Your listing '" + title + "' " + (approved
                            ? "is now live!" : "was rejected. Reason: " + reason));
                    statement.setString(4, reviewedAt);
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
                   l.item_name, l.listing_type, l.listing_status, l.posted_at,
                   l.seller, u.full_name AS seller_name
            FROM offers o
            JOIN listings l ON l.listing_id = o.listing_id
            JOIN users u ON u.username = l.seller
            WHERE o.buyer_username = ?
            ORDER BY l.posted_at DESC
            """;
        return fetchQuery(sql, buyerUsername);
    }

    public static List<Listing> fetchFavouriteListings(String buyerUsername) {
        String sql = """
            SELECT l.* FROM listings l
            JOIN favourites f ON f.listing_id = l.listing_id
            WHERE f.buyer_username = ?
            ORDER BY l.posted_at DESC
            """;
        List<Listing> listings = new ArrayList<>();
        for (Map<String, Object> row : fetchQuery(sql, buyerUsername)) {
            listings.add(DataService.toListing(row));
        }
        return listings;
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

    public static void createNotification(String username, String sender, String title, String message, String category) {
        String sql = """
        INSERT INTO notifications (username, sender, title, message, category, timestamp)
        VALUES (?, ?, ?, ?, ?, ?)
    """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, sender);
            stmt.setString(3, title);
            stmt.setString(4, message);
            stmt.setString(5, category);
            stmt.setString(6, LocalDateTime.now().toString());

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create notification", e);
        }
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
        List<Map<String, Object>> rows = fetchRows("notifications", Map.of("username", username));
        List<Notification> notifications = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            notifications.add(DataService.toNotification(row));
        }

        return notifications;
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
}
