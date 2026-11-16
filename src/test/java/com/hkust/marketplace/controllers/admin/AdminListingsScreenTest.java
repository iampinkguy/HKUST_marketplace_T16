package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real FXML and controls; run with -Dmarketplace.uiTests=true. */
public class AdminListingsScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private Stage stage;
    private String listingId;
    @BeforeClass public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit();
    }
    @Before public void open() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("listings.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, "unused", role, null, null, null);
        }
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        Listing l = new Listing(); l.setTitle("Calculus book"); l.setDescription("Clean pages");
        l.setType(ListingType.SELL); l.setPrice(80.0); l.setCategory("Textbooks");
        l.setCondition("Used"); l.setMeetupLocation("Library");
        DataService.addListingForSeller("seller", l, List.of()); listingId = l.getId();
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage(); stage.setScene(new Scene(root));
            stage.getScene().getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.show(); button("btnAllListings").fire(); return null;
        });
    }
    @After public void close() throws Exception {
        try { fx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); return null; }); }
        finally {
            SessionManager.logout();
            if (oldUrl == null) System.clearProperty("marketplace.database.url");
            else System.setProperty("marketplace.database.url", oldUrl);
        }
    }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    private Node node(String id) { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); return stage.getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) node(id); }
    private static String text(Node n) {
        String value = n instanceof Labeled l ? l.getText() + "\n" : "";
        if (n instanceof ScrollPane s && s.getContent() != null) value += text(s.getContent());
        if (n instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) value += text(child);
        return value;
    }
    private DialogPane dialog(String title) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w.isShowing() && w instanceof Stage s && title.equals(s.getTitle()) && s.getScene().getRoot() instanceof DialogPane p) return p;
        }
        return null;
    }
    @Test public void addFormRequiresTypeFirstAndPublishesOnlyAfterConfirmation() throws Exception {
        fx(() -> {
            button("admin-listing-add").fire();
            DialogPane form = dialog("Add Listing"); assertNotNull(form);
            assertTrue(form.lookup("#admin-listing-fields").isDisabled());
            ((ComboBox<String>) form.lookup("#admin-listing-type")).setValue("Give Away");
            assertFalse(form.lookup("#admin-listing-fields").isDisabled());
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertTrue(text(form).contains("Complete all required"));
            form.applyCss(); form.layout();
            Node error = form.lookup("#admin-listing-error");
            assertTrue("Validation feedback must be visible without scrolling",
                    error.localToScene(error.getBoundsInLocal()).getMaxY() <= form.getScene().getHeight());
            assertNull(dialog("Confirm Listing Changes"));
            ((ComboBox<String>) form.lookup("#admin-listing-seller")).setValue("seller");
            ((TextField) form.lookup("#admin-listing-name")).setText("Spare lamp");
            ((ComboBox<String>) form.lookup("#admin-listing-category")).setValue("Furniture");
            ((ComboBox<String>) form.lookup("#admin-listing-condition")).setValue("Used");
            ((TextArea) form.lookup("#admin-listing-description")).setText("Working lamp");
            ((ComboBox<String>) form.lookup("#admin-listing-meetup")).setValue("Library");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertNotNull(dialog("Confirm Listing Changes"));
            assertEquals(1, DataService.getAllListings().size());
            ((Button) dialog("Confirm Listing Changes").lookupButton(ButtonType.CANCEL)).fire();
            assertNotNull(dialog("Add Listing"));
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            ((Button) dialog("Confirm Listing Changes").lookupButton(ButtonType.OK)).fire();
            return null;
        });
        awaitClosed("Add Listing");
        assertTrue(DataService.getLiveListings().stream().anyMatch(l -> l.getTitle().equals("Spare lamp")));
    }
    private void awaitClosed(String title) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(() -> dialog(title) == null)) return;
            Thread.sleep(50);
        }
        fail("Dialog did not close: " + title);
    }

    @Test public void editValidatesConditionalFieldsAndSavesAfterConfirmationWithoutRepublishing() throws Exception {
        var posted = DataService.getListingForAdmin(listingId).getPostedAt();
        fx(() -> {
            button("admin-listing-edit-" + listingId).fire();
            DialogPane form = dialog("Edit Listing"); assertNotNull(form);
            assertTrue(form.lookup("#admin-listing-seller").isDisabled());
            ((TextField) form.lookup("#admin-listing-price")).setText("-5");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertTrue(text(form).contains("non-negative price"));
            ((ComboBox<String>) form.lookup("#admin-listing-type")).setValue("Lend");
            assertFalse(form.lookup("#admin-listing-price").getParent().isManaged());
            assertTrue(form.lookup("#admin-listing-days").getParent().isManaged());
            ((TextField) form.lookup("#admin-listing-days")).setText("0");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertNull(dialog("Confirm Listing Changes"));
            ((TextField) form.lookup("#admin-listing-days")).setText("21");
            ((TextField) form.lookup("#admin-listing-name")).setText("Updated book");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertEquals("Calculus book", DataService.getListingForAdmin(listingId).getTitle());
            ((Button) dialog("Confirm Listing Changes").lookupButton(ButtonType.OK)).fire(); return null;
        });
        awaitClosed("Edit Listing");
        Listing saved = DataService.getListingForAdmin(listingId);
        assertEquals("Updated book", saved.getTitle()); assertEquals(ListingType.LEND, saved.getType());
        assertEquals(Integer.valueOf(21), saved.getLendingDays()); assertNull(saved.getPrice());
        assertEquals(ListingStatus.LIVE, saved.getStatus()); assertEquals(posted, saved.getPostedAt());
    }

    @Test public void cancelDiscardsEditsAndRevokedAccessClearsListingData() throws Exception {
        fx(() -> {
            button("admin-listing-edit-" + listingId).fire();
            DialogPane form = dialog("Edit Listing");
            ((TextField) form.lookup("#admin-listing-name")).setText("Unsaved title");
            ((Button) form.lookupButton(ButtonType.CANCEL)).fire();
            assertEquals("Calculus book", DataService.getListingForAdmin(listingId).getTitle());
            SessionManager.logout(); button("admin-listings-refresh").fire();
            assertFalse(text(node("admin-listings-list")).contains("Calculus book"));
            assertTrue(text(node("admin-listings-list")).contains("active admin account is required"));
            return null;
        });
    }

    @Test public void staleTakedownDialogShowsErrorAndDoesNotSaveDecision() throws Exception {
        fx(() -> {
            button("admin-listing-takedown-" + listingId).fire();
            DialogPane form = dialog("Take Down Listing");
            Listing changed = DataService.getListingForAdmin(listingId); changed.setTitle("Seller corrected this");
            DataService.updateListingAsAdmin(changed, List.of(), false);
            ((TextArea) form.lookup("#admin-listing-takedown-reason")).setText("Unsafe item");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            ((Button) dialog("Confirm Listing Takedown").lookupButton(ButtonType.OK)).fire();
            assertTrue(text(form).contains("has changed"));
            assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(listingId).getStatus());
            assertTrue(DataService.getListingManagementHistory(listingId).isEmpty());
            ((Button) form.lookupButton(ButtonType.CANCEL)).fire(); return null;
        });
    }

    @Test public void takedownRequiresReasonAndConfirmationAndCancelStoresNothing() throws Exception {
        fx(() -> {
            button("admin-listing-takedown-" + listingId).fire();
            DialogPane form = dialog("Take Down Listing"); assertNotNull(form);
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertTrue(text(form).contains("A takedown reason is required"));
            ((TextArea) form.lookup("#admin-listing-takedown-reason")).setText("Unsafe item");
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            assertNotNull(dialog("Confirm Listing Takedown"));
            ((Button) dialog("Confirm Listing Takedown").lookupButton(ButtonType.CANCEL)).fire();
            assertEquals(ListingStatus.LIVE, DataService.getListingForAdmin(listingId).getStatus());
            assertTrue(DataService.getListingManagementHistory(listingId).isEmpty());
            ((Button) form.lookupButton(ButtonType.OK)).fire();
            ((Button) dialog("Confirm Listing Takedown").lookupButton(ButtonType.OK)).fire();
            return null;
        });
        awaitClosed("Take Down Listing");
        assertEquals(ListingStatus.REMOVED, DataService.getListingForAdmin(listingId).getStatus());
        fx(() -> {
            assertTrue(button("admin-listing-takedown-" + listingId).isDisabled());
            assertTrue(text(node("admin-listing-" + listingId)).contains("Unsafe item"));
            return null;
        });
    }

    @Test public void allStatusesAreVisibleAndFiltersFindExactListing() throws Exception {
        for (ListingStatus status : ListingStatus.values()) {
            if (status == ListingStatus.LIVE) continue;
            int id = DatabaseManager.createListing(status.name() + " item", "Textbooks", "Used", "Description", 0,
                    null, null, null, null, null, null, "Library", "2026-11-17", "Available", "seller", "SELL", 10.0, null);
            try (var c = DatabaseManager.connect(); var s = c.prepareStatement("UPDATE listings SET listing_status = ? WHERE listing_id = ?")) {
                s.setString(1, status.name()); s.setInt(2, id); s.executeUpdate();
            }
        }
        fx(() -> {
            button("btnAllListings").fire();
            for (ListingStatus status : ListingStatus.values()) assertTrue(text(node("admin-listings-list")).contains(status.name()));
            ((ComboBox<String>) node("admin-listings-status")).setValue("LIVE");
            assertTrue(text(node("admin-listings-list")).contains("Calculus book"));
            assertFalse(text(node("admin-listings-list")).contains("REJECTED item"));
            ((TextField) node("admin-listings-search")).setText("missing");
            assertTrue(text(node("admin-listings-list")).contains("No listings match"));
            return null;
        });
    }
}
