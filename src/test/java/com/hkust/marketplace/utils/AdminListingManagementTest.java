package com.hkust.marketplace.utils;

import com.hkust.marketplace.models.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.List;
import static org.junit.Assert.*;

public class AdminListingManagementTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    @Before public void setup() {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("listings.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, "unused", role, null, null, null);
        }
        admin();
    }
    @After public void cleanup() {
        SessionManager.logout();
        if (oldUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", oldUrl);
    }
    private void admin() { SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN)); }
    private Listing item(ListingType type) {
        Listing l = new Listing();
        l.setTitle("  Calculus book  "); l.setDescription("  Clean pages.  ");
        l.setCategory("Textbooks"); l.setCondition("Used"); l.setMeetupLocation("Library");
        l.setType(type); l.setPrice(120.0); l.setLendingDays(14);
        return l;
    }
    @Test public void takedownRetainsListingReasonAndAdminAndDeclinesPendingBuyerRequests() {
        Listing draft = item(ListingType.SELL);
        DataService.addListingForSeller("seller", draft, List.of());
        int request = DatabaseManager.createListingRequest("buyer", Integer.parseInt(draft.getId()), "OFFER", 90.0, null);
        Listing before = DataService.getListingForAdmin(draft.getId());
        DataService.takeDownListing(before.getId(), "  Unsafe item  ", before.getReviewVersion());
        assertEquals(ListingStatus.REMOVED, DataService.getListingForAdmin(before.getId()).getStatus());
        assertTrue(DataService.getLiveListings().isEmpty());
        var entry = DataService.getListingManagementHistory(before.getId()).get(0);
        assertEquals("admin", entry.adminUsername());
        assertEquals("Unsafe item", entry.reason());
        assertNotNull(entry.occurredAt());
        var notice = DataService.getNotificationsFor("buyer").get(0);
        assertTrue(notice.getMessage().contains("Unsafe item"));
        assertEquals(Integer.valueOf(request), notice.getRequestId());
        assertTrue(DataService.getNotificationsFor("seller").get(0).getMessage().contains("Unsafe item"));
        SessionManager.login(new User("buyer", "Buyer", "unused", UserRole.BUYER));
        assertEquals("DECLINED", DataService.getBuyerRequests().get(0).get("status"));
    }

    @Test public void blankReasonStaleDecisionAndNonLiveListingStoreNoTakedownOrBuyerNotice() {
        Listing l = item(ListingType.SELL); DataService.addListingForSeller("seller", l, List.of());
        Listing before = DataService.getListingForAdmin(l.getId());
        assertThrows(IllegalArgumentException.class, () -> DataService.takeDownListing(l.getId(), "  ", before.getReviewVersion()));
        before.setDescription("Changed content"); DataService.updateListingAsAdmin(before, List.of(), false);
        assertThrows(IllegalStateException.class, () -> DataService.takeDownListing(l.getId(), "Unsafe", before.getReviewVersion()));
        assertTrue(DataService.getListingManagementHistory(l.getId()).isEmpty());
        assertTrue(DataService.getNotificationsFor("buyer").isEmpty());
        assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(l.getId()).getStatus());
    }

    @Test public void editingListingPreservesItsStatusAndPublication() throws Exception {
        Listing draft = item(ListingType.SELL);
        DataService.addListingForSeller("seller", draft, List.of());
        Listing before = DataService.getListingForAdmin(draft.getId());
        before.setTitle("Corrected calculus book");
        DataService.updateListingAsAdmin(before, List.of(), false);
        Listing saved = DataService.getListingForAdmin(draft.getId());
        assertEquals("Corrected calculus book", saved.getTitle());
        assertEquals(ListingStatus.LIVE, saved.getStatus());
        assertEquals(before.getPostedAt(), saved.getPostedAt());
        assertTrue(saved.getReviewVersion() > before.getReviewVersion());
        assertThrows(IllegalStateException.class, () -> DataService.updateListingAsAdmin(before, List.of(), false));
    }

    @Test public void everyStatusCanBeEditedWithoutChangingLifecycleOrSeller() throws Exception {
        for (ListingStatus status : ListingStatus.values()) {
            Listing l = item(ListingType.SELL); DataService.addListingForSeller("seller", l, List.of());
            try (var c = DatabaseManager.connect(); var s = c.prepareStatement("UPDATE listings SET listing_status = ?, availability_status = 'Unavailable' WHERE listing_id = ?")) {
                s.setString(1, status.name()); s.setInt(2, Integer.parseInt(l.getId())); s.executeUpdate();
            }
            Listing current = DataService.getListingForAdmin(l.getId());
            current.setTitle("Edited " + status); current.setSellerUsername("buyer");
            DataService.updateListingAsAdmin(current, List.of(), false);
            Listing saved = DataService.getListingForAdmin(l.getId());
            assertEquals("Edited " + status, saved.getTitle()); assertEquals(status, saved.getStatus());
            assertEquals("Unavailable", saved.getAvailabilityStatus()); assertEquals("seller", saved.getSellerUsername());
            assertEquals(current.getPostedAt(), saved.getPostedAt());
            if (status != ListingStatus.LIVE) assertThrows(IllegalStateException.class,
                    () -> DataService.takeDownListing(saved.getId(), "Reason", saved.getReviewVersion()));
        }
    }

    @Test public void editingAcceptedBorrowPreservesAgreedTermsAndChat() {
        Listing l = item(ListingType.LEND); DataService.addListingForSeller("seller", l, List.of());
        int request = DatabaseManager.createListingRequest("buyer", Integer.parseInt(l.getId()), "BORROW", null, 7);
        DatabaseManager.resolveListingRequest("seller", request, "ACCEPT", null);
        DatabaseManager.appendRequestMessage(request, "buyer", "BUYER", "Collect at noon.");
        var before = DataService.getDealDetails(request);
        Listing edit = DataService.getListingForAdmin(l.getId());
        edit.setType(ListingType.SELL); edit.setPrice(180.0);
        DataService.updateListingAsAdmin(edit, List.of(), false);
        var after = DataService.getDealDetails(request);
        assertEquals(before.deal(), after.deal());
        assertEquals(before.proposedDays(), after.proposedDays());
        assertEquals(ListingStatus.LENT, DataService.getListingForAdmin(l.getId()).getStatus());
        SessionManager.login(new User("buyer", "Buyer", "unused", UserRole.BUYER));
        assertEquals("Collect at noon.", DataService.getRequestMessages(request).get(0).get("content"));
    }

    @Test public void accountRoleAndRevokedAdminSessionCannotCreateEditOrTakeDown() {
        Listing l = item(ListingType.SELL); DataService.addListingForSeller("seller", l, List.of());
        Listing saved = DataService.getListingForAdmin(l.getId());
        for (UserRole role : List.of(UserRole.BUYER, UserRole.SELLER)) {
            SessionManager.login(new User(role.name().toLowerCase(), "User", "unused", role));
            assertManagementDenied(saved);
        }
        SessionManager.logout(); assertManagementDenied(saved);
        admin(); DatabaseManager.setUserActive("admin", false); assertManagementDenied(saved);
        DatabaseManager.setUserActive("admin", true); admin();
        assertEquals(1, DataService.getAllListings().size());
        assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(l.getId()).getStatus());
        assertTrue(DataService.getListingManagementHistory(l.getId()).isEmpty());
    }
    private void assertManagementDenied(Listing saved) {
        assertThrows(SecurityException.class, () -> DataService.addListingForSeller("seller", item(ListingType.SELL), List.of()));
        assertThrows(SecurityException.class, () -> DataService.updateListingAsAdmin(saved, List.of(), false));
        assertThrows(SecurityException.class, () -> DataService.takeDownListing(saved.getId(), "Reason", saved.getReviewVersion()));
        assertThrows(SecurityException.class, () -> DataService.getListingManagementHistory(saved.getId()));
    }

    @Test public void formValidationMatchesSellerAndRequiresAnActiveSeller() {
        Listing missing = item(ListingType.SELL); missing.setTitle(" ");
        assertThrows(IllegalArgumentException.class, () -> DataService.addListingForSeller("seller", missing, List.of()));
        Listing badPrice = item(ListingType.SELL); badPrice.setPrice(Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> DataService.addListingForSeller("seller", badPrice, List.of()));
        Listing badDays = item(ListingType.LEND); badDays.setLendingDays(0);
        assertThrows(IllegalArgumentException.class, () -> DataService.addListingForSeller("seller", badDays, List.of()));
        Listing badCategory = item(ListingType.SELL); badCategory.setCategory("Invalid");
        assertThrows(IllegalArgumentException.class, () -> DataService.addListingForSeller("seller", badCategory, List.of()));
        assertThrows(IllegalArgumentException.class, () -> DataService.addListingForSeller("seller", item(ListingType.SELL),
                java.util.Collections.nCopies(7, Path.of("unused.png"))));
        assertThrows(SecurityException.class, () -> DataService.addListingForSeller("buyer", item(ListingType.SELL), List.of()));
        DatabaseManager.setUserActive("seller", false);
        assertThrows(SecurityException.class, () -> DataService.addListingForSeller("seller", item(ListingType.SELL), List.of()));
        assertTrue(DataService.getAllListings().isEmpty());
        DatabaseManager.setUserActive("seller", true);
        Listing give = item(ListingType.GIVEAWAY); DataService.addListingForSeller("seller", give, List.of());
        Listing saved = DataService.getListingForAdmin(give.getId());
        assertNull(saved.getPrice()); assertNull(saved.getLendingDays());
    }

    @Test public void takedownIsAtomicAndNotifiesPendingAndCounteredBuyersOnly() throws Exception {
        DatabaseManager.createUser("buyer2", "Buyer Two", "unused", UserRole.BUYER, null, null, null);
        Listing l = item(ListingType.SELL); DataService.addListingForSeller("seller", l, List.of());
        DatabaseManager.createListingRequest("buyer", Integer.parseInt(l.getId()), "OFFER", 80.0, null);
        int counter = DatabaseManager.createListingRequest("buyer2", Integer.parseInt(l.getId()), "OFFER", 70.0, null);
        try (var c = DatabaseManager.connect();
             var update = c.prepareStatement(
                     "UPDATE listing_requests SET status = 'COUNTERED', counter_price = 100.0 WHERE request_id = ?")) {
            update.setInt(1, counter);
            assertEquals(1, update.executeUpdate());
        }
        DatabaseManager.appendRequestMessage(counter, "buyer2", "BUYER", "Can collect tomorrow.");
        long version = DataService.getListingForAdmin(l.getId()).getReviewVersion();
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER block_takedown_notice BEFORE INSERT ON notifications WHEN NEW.title = 'Listing Taken Down' AND NEW.username = 'buyer2' BEGIN SELECT RAISE(ABORT, 'Test notification failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> DataService.takeDownListing(l.getId(), "Unsafe", version));
        assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(l.getId()).getStatus());
        assertTrue(DataService.getListingManagementHistory(l.getId()).isEmpty());
        assertFalse(DataService.getNotificationsFor("buyer").stream().anyMatch(n -> n.getTitle().equals("Listing Taken Down")));
        SessionManager.login(new User("buyer2", "Buyer Two", "unused", UserRole.BUYER));
        assertEquals("COUNTERED", DataService.getBuyerRequests().get(0).get("status")); admin();
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) { s.executeUpdate("DROP TRIGGER block_takedown_notice"); }
        DataService.takeDownListing(l.getId(), "Unsafe", version);
        for (String buyer : List.of("buyer", "buyer2")) {
            assertEquals(1, DataService.getNotificationsFor(buyer).stream().filter(n -> n.getTitle().equals("Listing Taken Down")).count());
        }
        SessionManager.login(new User("buyer2", "Buyer Two", "unused", UserRole.BUYER));
        assertEquals("DECLINED", DataService.getBuyerRequests().get(0).get("status"));
        assertEquals("Can collect tomorrow.", DataService.getRequestMessages(counter).get(0).get("content"));
    }

    @Test public void sixPhotosCanBeKeptReplacedOrClearedAndInvalidReplacementDoesNotSave() throws Exception {
        java.util.ArrayList<Path> cleanup = new java.util.ArrayList<>();
        try {
            Path source = folder.getRoot().toPath().resolve("book.png");
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", source.toFile());
            Listing l = item(ListingType.SELL);
            DataService.addListingForSeller("seller", l, java.util.Collections.nCopies(6, source));
            Listing saved = DataService.getListingForAdmin(l.getId());
            List<String> originals = saved.getPhotoPaths(); originals.stream().map(Path::of).forEach(cleanup::add);
            assertEquals(6, originals.size());
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(Path.of(originals.get(0))));
            saved.setDescription("Corrected description"); DataService.updateListingAsAdmin(saved, List.of(), false);
            saved = DataService.getListingForAdmin(l.getId()); assertEquals(originals, saved.getPhotoPaths());
            Path invalid = folder.getRoot().toPath().resolve("broken.png"); Files.writeString(invalid, "not an image");
            Listing invalidEdit = saved; invalidEdit.setTitle("Must not save");
            assertThrows(IllegalArgumentException.class, () -> DataService.updateListingAsAdmin(invalidEdit, List.of(source, invalid), true));
            saved = DataService.getListingForAdmin(l.getId());
            assertEquals("Calculus book", saved.getTitle()); assertEquals(originals, saved.getPhotoPaths());
            DataService.updateListingAsAdmin(saved, List.of(source), true);
            saved = DataService.getListingForAdmin(l.getId()); saved.getPhotoPaths().stream().map(Path::of).forEach(cleanup::add);
            assertEquals(1, saved.getPhotoPaths().size());
            for (String original : originals) assertFalse(Files.exists(Path.of(original)));
            DataService.updateListingAsAdmin(saved, List.of(), true);
            assertTrue(DataService.getListingForAdmin(l.getId()).getPhotoPaths().isEmpty());
        } finally { for (Path photo : cleanup) Files.deleteIfExists(photo); }
    }

    @Test public void adminCreatesLiveListingForSellerAndSellerReceivesConfirmation() {
        Listing draft = item(ListingType.SELL);
        DataService.addListingForSeller("seller", draft, List.of());
        Listing saved = DataService.getListingForAdmin(draft.getId());
        assertEquals("Calculus book", saved.getTitle());
        assertEquals("seller", saved.getSellerUsername());
        assertEquals(ListingStatus.LIVE, saved.getStatus());
        assertEquals(Double.valueOf(120), saved.getPrice());
        assertNull(saved.getLendingDays());
        assertNotNull(saved.getPostedAt());
        assertEquals(saved.getReviewVersion(), draft.getReviewVersion());
        assertTrue(DataService.getLiveListings().stream().anyMatch(l -> l.getId().equals(saved.getId())));
        Notification notice = DataService.getNotificationsFor("seller").get(0);
        assertEquals(Integer.valueOf(saved.getId()), notice.getListingId());
        assertTrue(notice.getMessage().contains("admin"));
    }
}
