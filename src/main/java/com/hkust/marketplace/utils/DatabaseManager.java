package com.hkust.marketplace.utils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DatabaseManager {

    private static final String URL = "jdbc:sqlite:data.db";
    private static final Path UPLOADS_DIR = Path.of("uploads").toAbsolutePath().normalize();
    private static final String PHOTO_EXTENSION = ".jpg"; // Assuming all photos are JPEGs; adjust as needed

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
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
                active INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL,
                average_rating REAL NOT NULL DEFAULT 0,
                rating_count INTEGER NOT NULL DEFAULT 0
            )
            """);

        runSql("""
                CREATE TABLE IF NOT EXISTS listings (
                listing_id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_name TEXT NOT NULL,
                category TEXT NOT NULL CHECK(category IN (
                    'Textbooks', 'Electronics', 'Furniture', 'Clothing',
                    'Stationery', 'Sports Equipment', 'Kitchenware', 'Others'
                )),
                item_condition TEXT NOT NULL CHECK(item_condition IN (
                    'New', 'Like New', 'Used', 'For Parts'
                )),
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
                availability_status TEXT NOT NULL,
                views INTEGER NOT NULL DEFAULT 0,
                approved INTEGER NOT NULL DEFAULT -1
            )
            """);

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
        String sql = """
            INSERT INTO users (username, full_name, password_hash, role, residence_hall, bio, preferred_meetup, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection connection = connect();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, fullName);
            stmt.setString(3, passwordHash);
            stmt.setString(4, role);
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

        String sql = """
            INSERT INTO listings (
                item_name, category, item_condition, description, numPhotos, 
                photo1, photo2, photo3, photo4, photo5, photo6, 
                meetup_location, posted_date, availability_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            stmt.setString(13, postedDate);
            stmt.setString(14, availabilityStatus);

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

}