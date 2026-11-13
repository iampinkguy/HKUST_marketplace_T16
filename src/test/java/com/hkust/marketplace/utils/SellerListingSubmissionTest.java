package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SellerListingSubmissionTest {
    @Rule public TemporaryFolder databaseFolder = new TemporaryFolder();

    private String previousDatabaseUrl;

    @Before
    public void configureDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url",
                "jdbc:sqlite:" + databaseFolder.getRoot().toPath().resolve("seller-listings.db"));
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Test Admin", "unused", UserRole.ADMIN.name(), null, null, null);
        DatabaseManager.createUser("seller", "Test Seller", "unused", UserRole.SELLER.name(), null, null, null);
        SessionManager.login(new User("seller", "Test Seller", "unused", UserRole.SELLER));
    }

    @After
    public void restoreDatabaseUrl() {
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
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
