package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises real FXML, controls and a temporary database. Opt in with marketplace.uiTests. */
public class AdminRatingsScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private Stage stage;
    private String firstId;
    @BeforeClass public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit();
    }
    @Before public void open() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("ratings.db"));
        DatabaseManager.initialize(); AdminRatingsOverviewTest.seedAccounts();
        firstId = AdminRatingsOverviewTest.addReview("buyer", "seller", "Calculus book", 4.5, "2026-11-10T23:59:59", "Useful book");
        AdminRatingsOverviewTest.addReview("buyer2", "seller2", "Physics book", 0.0, "2026-11-11T00:00:00", "Damaged pages");
        AdminRatingsOverviewTest.addReview("buyer2", "seller2", "Undated book", 2.5, null, "Legacy review");
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage(); stage.setScene(new Scene(root));
            stage.getScene().getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.show(); button("btnRatings").fire(); return null;
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
    private Node node(String id) {
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        return stage.getScene().lookup("#" + id);
    }
    private Button button(String id) { return (Button) node(id); }
    private static String text(Node n) {
        String value = n instanceof Labeled l ? l.getText() + "\n" : "";
        if (n instanceof ScrollPane s && s.getContent() != null) value += text(s.getContent());
        if (n instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) value += text(child);
        return value;
    }
    private DialogPane confirmation() {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w.isShowing() && w instanceof Stage s && "Confirm Rating Visibility".equals(s.getTitle())
                    && s.getScene().getRoot() instanceof DialogPane p) return p;
        }
        return null;
    }
    @Test public void overviewShowsOriginalScoresUsersItemDatesAndVisibility() throws Exception {
        fx(() -> {
            String page = text(stage.getScene().getRoot());
            assertTrue(page.contains("4.5 / 5"));
            assertTrue(page.contains("0.0 / 5"));
            assertTrue(page.contains("Calculus book"));
            assertTrue(page.contains("@buyer")); assertTrue(page.contains("@seller"));
            assertTrue(page.contains("2026-11-10 23:59:59"));
            assertTrue(page.contains("Date unavailable"));
            assertTrue(page.contains("Visible"));
            assertTrue(page.contains("3 ratings"));
            return null;
        });
    }
    @Test public void hidingAndRestoringRequireConfirmationAndCancellationStoresNothing() throws Exception {
        fx(() -> {
            String action = "admin-rating-hide-" + firstId + "-buyer";
            button(action).fire();
            assertNotNull(confirmation());
            assertTrue(text(confirmation()).contains("Calculus book"));
            assertFalse(DataService.getAllReviews("buyer", 4.5, null, null).get(0).isHidden());
            ((Button) confirmation().lookupButton(ButtonType.CANCEL)).fire();
            assertFalse(DataService.getAllReviews("buyer", 4.5, null, null).get(0).isHidden());
            button(action).fire();
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            assertTrue(DataService.getAllReviews("buyer", 4.5, null, null).get(0).isHidden());
            assertEquals("Restore", button(action).getText());
            button(action).fire();
            assertTrue(text(confirmation()).contains("Restore"));
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            assertFalse(DataService.getAllReviews("buyer", 4.5, null, null).get(0).isHidden());
            assertTrue(text(stage.getScene().getRoot()).contains("Rating restored"));
            return null;
        });
    }
    @Test public void userExactScoreAndDateFiltersCombineAndClearRestoresAllRatings() throws Exception {
        fx(() -> {
            ((TextField) node("admin-ratings-user")).setText(" BUYER ");
            ((ComboBox<String>) node("admin-ratings-score")).setValue("4.5");
            ((DatePicker) node("admin-ratings-from")).setValue(LocalDate.of(2026, 11, 10));
            ((DatePicker) node("admin-ratings-to")).setValue(LocalDate.of(2026, 11, 10));
            assertTrue(text(node("admin-ratings-list")).contains("Calculus book"));
            assertFalse(text(node("admin-ratings-list")).contains("Physics book"));
            assertTrue(text(node("admin-ratings-count")).contains("1 rating"));
            button("admin-ratings-clear").fire();
            assertTrue(text(node("admin-ratings-count")).contains("3 ratings"));
            ((TextField) node("admin-ratings-user")).setText("seller2");
            ((ComboBox<String>) node("admin-ratings-score")).setValue("0.0");
            assertTrue(text(node("admin-ratings-list")).contains("Physics book"));
            assertFalse(text(node("admin-ratings-list")).contains("Undated book"));
            ((TextField) node("admin-ratings-user")).setText("nobody");
            assertTrue(text(node("admin-ratings-list")).contains("No ratings match"));
            return null;
        });
    }
    @Test public void accessRevokedDuringConfirmationStoresNothingAndClearsPrivateResults() throws Exception {
        fx(() -> {
            button("admin-rating-hide-" + firstId + "-buyer").fire();
            SessionManager.logout();
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            assertTrue(text(node("admin-ratings-feedback")).contains("active admin"));
            assertFalse(text(node("admin-ratings-list")).contains("Calculus book"));
            AdminRatingsOverviewTest.admin();
            assertFalse(DataService.getAllReviews("buyer", 4.5, null, null).get(0).isHidden());
            return null;
        });
    }
    @Test public void invalidFiltersShowClearFeedbackAndRefreshChecksCurrentAuthorization() throws Exception {
        fx(() -> {
            ((DatePicker) node("admin-ratings-from")).setValue(LocalDate.of(2026, 11, 12));
            ((DatePicker) node("admin-ratings-to")).setValue(LocalDate.of(2026, 11, 10));
            assertTrue(text(node("admin-ratings-feedback")).contains("From date"));
            assertFalse(text(node("admin-ratings-list")).contains("Calculus book"));
            button("admin-ratings-clear").fire();
            ((ComboBox<String>) node("admin-ratings-score")).getEditor().setText("invalid");
            assertTrue(text(node("admin-ratings-feedback")).contains("0.0 and 5.0"));
            button("admin-ratings-clear").fire();
            assertTrue(text(node("admin-ratings-count")).contains("3 ratings"));
            SessionManager.logout(); button("admin-ratings-refresh").fire();
            assertTrue(text(node("admin-ratings-feedback")).contains("active admin"));
            assertFalse(text(node("admin-ratings-list")).contains("Calculus book"));
            return null;
        });
    }
    @Test public void moderationKeepsFiltersAndAMissingRatingShowsCurrentResultsWithoutSaving() throws Exception {
        fx(() -> {
            ((TextField) node("admin-ratings-user")).setText("buyer");
            ((ComboBox<String>) node("admin-ratings-score")).setValue("4.5");
            button("admin-rating-hide-" + firstId + "-buyer").fire();
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            assertEquals("buyer", ((TextField) node("admin-ratings-user")).getText());
            assertEquals("4.5", ((ComboBox<String>) node("admin-ratings-score")).getValue());
            assertTrue(text(node("admin-ratings-count")).contains("1 rating"));
            button("admin-rating-hide-" + firstId + "-buyer").fire();
            try (var c = DatabaseManager.connect(); var s = c.prepareStatement("DELETE FROM reviews WHERE buyer_username = ? AND listing_id = ?")) {
                s.setString(1, "buyer"); s.setInt(2, Integer.parseInt(firstId)); s.executeUpdate();
            }
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            assertTrue(text(node("admin-ratings-feedback")).contains("Review was not found"));
            assertTrue(text(node("admin-ratings-list")).contains("No ratings match"));
            assertFalse(text(node("admin-ratings-feedback")).contains("successfully"));
            return null;
        });
    }
}
