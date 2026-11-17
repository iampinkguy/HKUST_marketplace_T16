package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real desktop tests against an isolated database; opt in with -Dmarketplace.uiTests=true. */
public class AdminNotificationsScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private Stage stage;
    private int listingId;
    private String reviewId;
    private String announcementId;
    @BeforeClass public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit();
    }
    @Before public void open() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("board.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, "unused", role, null, null, null);
        }
        SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN));
        listingId = DatabaseManager.createListing("Review this book", "Textbooks", "Used", "Clean pages", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 80.0, null);
        DatabaseManager.createNotification("admin", null, "Maintenance", "Service resumes at noon.", "Announcement");
        announcementId = DataService.getAdminNotifications().get(0).getId();
        DatabaseManager.createNotification("admin", "seller", "New Listing Submitted", "A book awaits review.", "Listing Review", null, listingId);
        reviewId = DataService.getAdminNotifications().get(0).getId();
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE notifications SET timestamp = '2026-11-16T09:30:00'");
        }
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.setScene(scene);
            stage.show();
            button("btnNotifications").fire();
            return null;
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
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
    private Button button(String id) { return (Button) stage.getScene().lookup("#" + id); }
    private VBox list() { return (VBox) stage.getScene().lookup("#admin-notification-list"); }
    private static String text(Node node) {
        String result = node instanceof Labeled l ? l.getText() + "\n" : "";
        if (node instanceof ScrollPane s && s.getContent() != null) result += text(s.getContent());
        if (node instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) result += text(child);
        return result;
    }
    private DialogPane dialog(String title) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w.isShowing() && w instanceof Stage s && title.equals(s.getTitle()) && s.getScene().getRoot() instanceof DialogPane p) return p;
        }
        return null;
    }
    private void closeDialog(String title, ButtonType action) {
        assertNotNull("Expected " + title, dialog(title));
        ((Button) dialog(title).lookupButton(action)).fire();
    }
    private Node card(String id) { return stage.getScene().lookup("#admin-notification-" + id); }

    @Test public void boardShowsClearCategorizedTimestampedNotificationsWithUnreadIndicators() throws Exception {
        fx(() -> {
            assertEquals("Notification Board", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            assertNotNull(list());
            assertEquals(2, list().getChildren().size());
            assertEquals("admin-notification-" + reviewId, list().getChildren().get(0).getId());
            assertTrue(text(card(reviewId)).contains("Listing Review · 16 Nov 2026 09:30:00"));
            assertTrue(text(card(announcementId)).contains("Service resumes at noon."));
            assertTrue(text(card(reviewId)).contains("Unread"));
            assertNotNull(button("open-admin-notification-" + reviewId));
            return null;
        });
    }

    @Test public void clickingSubmissionCardOpensThatExactListingForReviewAndPersistsReadState() throws Exception {
        // Another listing has the same title; routing must use the ID, never title matching.
        DatabaseManager.createListing("Review this book", "Textbooks", "Used", "Different submission", 0,
                null, null, null, null, null, null, "Library", "2026-11-16", "Available", "seller", "SELL", 90.0, null);
        fx(() -> {
            Node target = card(reviewId);
            assertNotNull(target);
            Platform.runLater(() -> target.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5,
                    MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true,
                    new PickResult(target, 5, 5))));
            return null;
        });
        fx(() -> {
            assertEquals("Listing Detail", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            assertNotNull("A single click must open the inline listing detail", stage.getScene().lookup("#admin-listing-detail"));
            assertTrue(text(stage.getScene().lookup("#admin-listing-detail")).contains("Review this book (#" + listingId + ")"));
            assertNotNull(button("approve-listing"));
            assertNotNull(button("reject-listing"));
            Platform.runLater(() -> button("close-listing-detail").fire());
            return null;
        });
        fx(() -> {
            assertEquals("Notification Board", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            return null;
        });
        assertTrue(DataService.getAdminNotifications().stream().filter(n -> n.getId().equals(reviewId)).findFirst().orElseThrow().isRead());
        assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(Integer.toString(listingId)).getStatus());
    }
    @Test public void enterOpensAnnouncementAndMarksItReadWithoutChangingPage() throws Exception {
        fx(() -> {
            Node target = card(announcementId);
            assertTrue("Cards must be keyboard accessible", target.isFocusTraversable());
            Platform.runLater(() -> target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
            return null;
        });
        fx(() -> {
            assertNotNull(dialog("Notification"));
            assertEquals("Maintenance", dialog("Notification").getHeaderText());
            assertTrue(text(dialog("Notification")).contains("Service resumes at noon."));
            assertTrue(text(dialog("Notification")).contains("16 Nov 2026 09:30:00"));
            closeDialog("Notification", ButtonType.CLOSE);
            assertEquals("Notification Board", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            assertFalse(text(card(announcementId)).contains("Unread"));
            return null;
        });
        assertTrue(DataService.getAdminNotifications().stream().filter(n -> n.getId().equals(announcementId)).findFirst().orElseThrow().isRead());
    }
    @Test public void reportOpensReportedListingAndReporterContextWithoutApprovalActions() throws Exception {
        DatabaseManager.reportListing("buyer", listingId);
        String reportId = DataService.getAdminNotifications().stream().filter(n -> "Listing Report".equals(n.getCategory())).findFirst().orElseThrow().getId();
        fx(() -> {
            button("btnNotifications").fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Button open = button("open-admin-notification-" + reportId);
            assertNotNull(open);
            Platform.runLater(open::fire);
            return null;
        });
        fx(() -> {
            assertEquals("Listing Detail", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            Node detail = stage.getScene().lookup("#admin-listing-detail");
            assertNotNull("A report must open moderation details inline", detail);
            assertTrue(text(detail).contains("buyer reported listing #" + listingId));
            assertTrue(text(detail).contains("Clean pages"));
            assertTrue(text(detail).contains("#" + listingId));
            assertNull("Reports must not expose approval actions", button("approve-listing"));
            assertNull(button("reject-listing"));
            Platform.runLater(() -> button("close-listing-detail").fire());
            return null;
        });
        fx(() -> {
            assertEquals("Notification Board", ((Label) stage.getScene().lookup("#pageTitle")).getText());
            return null;
        });
    }
    @Test public void accountUpdateOpensTheExactUsersCurrentProfile() throws Exception {
        DatabaseManager.updateUserProfile("buyer", "Updated Buyer", "Hall 3", null, null, null);
        String id = DataService.getAdminNotifications().stream().filter(n -> "Account Update".equals(n.getCategory())).findFirst().orElseThrow().getId();
        fx(() -> {
            button("btnNotifications").fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Button open = button("open-admin-notification-" + id);
            assertNotNull(open);
            Platform.runLater(open::fire);
            return null;
        });
        fx(() -> {
            assertNotNull("Account updates must link to that account", dialog("User Details"));
            assertEquals("Updated Buyer (@buyer)", dialog("User Details").getHeaderText());
            assertTrue(text(dialog("User Details")).contains("Hall 3"));
            closeDialog("User Details", ButtonType.CLOSE);
            return null;
        });
    }
    @SuppressWarnings("unchecked")
    @Test public void categoryAndReadFiltersCombineRefreshLoadsNewNoticesAndClearRestoresBoard() throws Exception {
        fx(() -> {
            ComboBox<String> categories = (ComboBox<String>) stage.getScene().lookup("#admin-notification-category");
            ComboBox<String> state = (ComboBox<String>) stage.getScene().lookup("#admin-notification-state");
            assertNotNull("Category filter is required", categories);
            assertNotNull(state);
            categories.setValue("Announcement");
            assertEquals(1, list().getChildren().size());
            assertNotNull(card(announcementId));
            state.setValue("Read");
            assertTrue(text(list()).contains("No notifications match these filters."));
            button("admin-notification-clear").fire();
            assertEquals("All", categories.getValue());
            assertEquals("All", state.getValue());
            assertEquals(2, list().getChildren().size());
            return null;
        });
        DatabaseManager.createNotification("admin", null, "New announcement", "A fresh update.", "Announcement");
        fx(() -> {
            button("admin-notification-refresh").fire();
            assertEquals(3, list().getChildren().size());
            assertTrue(text(list()).contains("A fresh update."));
            SessionManager.logout();
            button("admin-notification-refresh").fire();
            assertTrue(text(list()).contains("Could not load notifications"));
            assertFalse(text(list()).contains("A fresh update."));
            return null;
        });
    }
    @Test public void alreadyReviewedSubmissionShowsItsCurrentStateWithoutOfferingAnotherDecision() throws Exception {
        var listing = DataService.getListingForAdmin(Integer.toString(listingId));
        DataService.approveListing(listing.getId(), listing.getReviewVersion());
        fx(() -> {
            Button open = button("open-admin-notification-" + reviewId);
            Platform.runLater(open::fire);
            return null;
        });
        fx(() -> {
            assertNotNull("Already handled submissions must show current details", dialog("Listing Details"));
            assertTrue(text(dialog("Listing Details")).contains("Status: LIVE"));
            assertEquals(List.of(ButtonType.CLOSE), dialog("Listing Details").getButtonTypes());
            closeDialog("Listing Details", ButtonType.CLOSE);
            return null;
        });
    }
    @Test public void expiredAdminSessionCannotFollowAccountNotificationAndShowsAnError() throws Exception {
        DatabaseManager.updateUserProfile("buyer", "Updated Buyer", "Hall 3", null, null, null);
        String id = DataService.getAdminNotifications().stream().filter(n -> "Account Update".equals(n.getCategory())).findFirst().orElseThrow().getId();
        fx(() -> {
            button("btnNotifications").fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Button open = button("open-admin-notification-" + id);
            assertNotNull(open);
            SessionManager.logout();
            open.fire();
            assertTrue(text(list()).contains("Could not open notification"));
            assertNull(dialog("User Details"));
            return null;
        });
    }
    @Test public void updatesToAnotherAdminRemainReadableAsNotifications() throws Exception {
        DatabaseManager.createUser("other-admin", "Other Admin", "unused", UserRole.ADMIN, null, null, null);
        DatabaseManager.updateUserProfile("other-admin", "Updated Admin", null, null, null, null);
        String id = DataService.getAdminNotifications().stream().filter(n -> "other-admin".equals(n.getTargetUsername())).findFirst().orElseThrow().getId();
        fx(() -> {
            button("btnNotifications").fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            Button open = button("open-admin-notification-" + id);
            assertNotNull(open);
            Platform.runLater(open::fire);
            return null;
        });
        fx(() -> {
            assertNotNull(dialog("Notification"));
            assertTrue(text(dialog("Notification")).contains("other-admin updated account details."));
            closeDialog("Notification", ButtonType.CLOSE);
            return null;
        });
    }
}
