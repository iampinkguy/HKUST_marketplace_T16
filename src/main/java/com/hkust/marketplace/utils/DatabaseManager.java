package com.hkust.marketplace.utils;

import javax.imageio.ImageIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
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

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (username, sender, title, message, category, timestamp)
                        SELECT username, ?, 'New Listing Submitted', ?, 'System', ?
                        FROM users WHERE role = 'ADMIN' AND active = 1
                        """)) {
                    statement.setString(1, canonicalUsername);
                    statement.setString(2, sellerName + " submitted '" + listing.getTitle() + "' for review.");
                    statement.setString(3, submittedAt.toString());
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalStateException("No active admin account is available to notify.");
                    }
                }

                connection.commit();
                listing.setId(Integer.toString(listingId));
                listing.setSellerUsername(canonicalUsername);
                listing.setSellerFullName(sellerName);
                listing.setStatus(ListingStatus.PENDING_REVIEW);
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
                String canonicalUsername;
                String sellerName;
                String[] photoNames = new String[6];
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT l.seller_username, l.photo1, l.photo2, l.photo3, l.photo4, l.photo5, l.photo6,
                            u.username AS account_username, u.full_name
                        FROM listings l
                        JOIN users u ON u.username = l.seller_username COLLATE NOCASE
                            AND u.role = 'SELLER' AND u.active = 1
                        WHERE l.listing_id = ? AND l.seller_username = ? COLLATE NOCASE
                            AND l.review_version = ?
                        """)) {
                    statement.setInt(1, listingId);
                    statement.setString(2, sellerUsername);
                    statement.setLong(3, listing.getReviewVersion());
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
                            listing_status = 'PENDING_REVIEW', price = ?, lending_days = ?, description = ?,
                            numPhotos = ?, photo1 = ?, photo2 = ?, photo3 = ?, photo4 = ?, photo5 = ?, photo6 = ?,
                            meetup_location = ?, posted_date = NULL, submitted_at = ?, availability_status = 'Available',
                            approved = -1, reviewed_at = NULL, reviewed_by = NULL, rejection_reason = NULL
                        WHERE listing_id = ? AND seller_username = ? COLLATE NOCASE AND review_version = ?
                        """;
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

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO notifications (username, sender, title, message, category, timestamp)
                        SELECT username, ?, 'Listing Updated - Review Required', ?, 'System', ?
                        FROM users WHERE role = 'ADMIN' AND active = 1
                        """)) {
                    statement.setString(1, canonicalUsername);
                    statement.setString(2, sellerName + " updated '" + listing.getTitle() + "' and it is awaiting re-approval.");
                    statement.setString(3, submittedAt.toString());
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalStateException("No active admin account is available to notify.");
                    }
                }

                connection.commit();
                listing.setSellerUsername(canonicalUsername);
                listing.setSellerFullName(sellerName);
                listing.setStatus(ListingStatus.PENDING_REVIEW);
                listing.setSubmittedAt(submittedAt);
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
                                + " request for " + itemName + ".", "Offer");
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
                        SET status = ?, counter_price = ?, updated_at = ?
                        WHERE request_id = ? AND status = 'PENDING'
                        """)) {
                    update.setString(1, newStatus);
                    if ("COUNTER".equals(normalizedAction)) update.setDouble(2, counterPrice);
                    else update.setNull(2, Types.REAL);
                    update.setString(3, now.toString());
                    update.setInt(4, requestId);
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
                insertNotification(connection, buyer, sellerUsername, title, message, "Offer");

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
                            SELECT buyer_username FROM listing_requests
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
                                        "Offer");
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
                       r.proposed_days, r.status, l.item_name, l.listing_type
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
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (username, sender, title, message, category, timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, username);
            statement.setString(2, sender);
            statement.setString(3, title);
            statement.setString(4, message);
            statement.setString(5, category);
            statement.setString(6, LocalDateTime.now().toString());
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
}
