package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in desktop tests: -Dmarketplace.uiTests=true. All data is isolated from the local database. */
public class AdminDealsScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static String passwordHash;
    private String oldUrl;
    private Stage stage;

    @BeforeClass public static void startToolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        passwordHash = DataService.hashPassword("Password123");
        AdminFxTestSupport.startToolkit();
    }
    @Before public void openScreen() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("screen.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String name = role.name().toLowerCase();
            DatabaseManager.createUser(name, name, passwordHash, role, null, null, null);
        }
        AdminDealServiceTest.addDeal(1, "Calculus Textbook", "SELL", "ACCEPTED", null, "2026-11-10T10:00:00", null);
        AdminDealServiceTest.addDeal(2, "Calculator", "LEND", "ACCEPTED", "2026-11-02T08:00:00", null, "2026-11-12T11:00:00");
        AdminDealServiceTest.addDeal(3, "Desk Lamp", "GIVEAWAY", "ACCEPTED", null, null, null);
        SessionManager.login(DataService.authenticate("admin", "Password123", UserRole.ADMIN));
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage();
            stage.setTitle("Isolated Admin All Deals Test");
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.setScene(scene);
            stage.show();
            button("btnDeals").fire();
            return null;
        });
    }
    @After public void closeScreen() throws Exception {
        try { fx(() -> { if (stage != null) stage.close(); return null; }); }
        finally {
            SessionManager.logout();
            if (oldUrl == null) System.clearProperty("marketplace.database.url");
            else System.setProperty("marketplace.database.url", oldUrl);
        }
    }
    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
    private Button button(String id) { return (Button) stage.getScene().lookup("#" + id); }
    @SuppressWarnings("unchecked") private TableView<DataService.DealRecord> table() {
        return (TableView<DataService.DealRecord>) stage.getScene().lookup("#deals-table");
    }

    @Test public void allDealsShowsRealRecordsAndEveryRequiredColumn() throws Exception {
        fx(() -> {
            assertNotNull("All Deals must load stored records", table());
            assertEquals(List.of("Item Title", "Buyer Username", "Seller Username", "Deal Type", "Start Date",
                    "Return/Complete Date", "Status"), table().getColumns().stream().map(TableColumn::getText).toList());
            assertEquals(3, table().getItems().size());
            var book = table().getItems().stream().filter(d -> d.itemTitle().equals("Calculus Textbook")).findFirst().orElseThrow();
            assertEquals("buyer", book.buyerUsername());
            assertEquals("seller", book.sellerUsername());
            assertEquals("Calculus Textbook", table().getColumns().get(0).getCellData(book));
            assertEquals("2026-11-10 10:00:00", table().getColumns().get(5).getCellData(book));
            assertEquals("—", table().getColumns().get(5).getCellData(table().getItems().stream()
                    .filter(d -> d.itemTitle().equals("Desk Lamp")).findFirst().orElseThrow()));
            return null;
        });
    }

    @SuppressWarnings("unchecked") private ComboBox<String> combo(String id) {
        return (ComboBox<String>) stage.getScene().lookup("#" + id);
    }
    private TextField search() { return (TextField) stage.getScene().lookup("#deals-search"); }

    private DialogPane dealDialog() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing() && window instanceof Stage s && "Deal Details".equals(s.getTitle())
                    && s.getScene().getRoot() instanceof DialogPane pane) return pane;
        }
        return null;
    }
    private void clickDeal(int requestId) {
        table().applyCss();
        table().layout();
        TableRow<?> row = table().lookupAll(".table-row-cell").stream()
                .filter(node -> node instanceof TableRow<?> r && r.getItem() instanceof DataService.DealRecord d
                        && d.requestId() == requestId).map(node -> (TableRow<?>) node).findFirst().orElseThrow();
        row.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, true, new PickResult(row, 5, 5)));
    }

    @Test public void searchTypeAndStatusFiltersCombineAndClearRestoresAllDeals() throws Exception {
        fx(() -> {
            assertNotNull("Search must be connected", search());
            assertNotNull(combo("deals-type"));
            assertNotNull(combo("deals-status"));
            search().setText("CALC");
            assertEquals(2, table().getItems().size());
            combo("deals-type").setValue("Lend");
            assertEquals(1, table().getItems().size());
            assertEquals("Calculator", table().getItems().get(0).itemTitle());
            combo("deals-status").setValue("Completed");
            assertTrue(table().getItems().isEmpty());
            assertEquals("No deals match these filters.", ((Label) table().getPlaceholder()).getText());
            combo("deals-status").setValue("Returned");
            assertEquals(1, table().getItems().size());
            search().setText("BUYER");
            assertEquals(1, table().getItems().size());
            button("deals-clear").fire();
            assertEquals("", search().getText());
            assertEquals("All Types", combo("deals-type").getValue());
            assertEquals("All Statuses", combo("deals-status").getValue());
            assertEquals(3, table().getItems().size());
            assertEquals("3 deals", ((Label) stage.getScene().lookup("#deals-count")).getText());
            return null;
        });
    }

    @Test public void refreshingLoadsNewDealsAndExplainsDatesAndAnEmptySystem() throws Exception {
        AdminDealServiceTest.addDeal(4, "Fresh Record", "SELL", "ACCEPTED", null, null, null);
        fx(() -> {
            assertNotNull("Refresh must reload stored deals", button("deals-refresh"));
            button("deals-refresh").fire();
            assertEquals(4, table().getItems().size());
            assertEquals("Start Date: borrow start, otherwise request creation. Return/Complete Date: scheduled return (due) for ongoing loans, actual return/completion for finished deals; — means not recorded.",
                    ((Label) stage.getScene().lookup("#deals-date-note")).getText());
            return null;
        });
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) { s.executeUpdate("DELETE FROM listing_requests"); }
        fx(() -> {
            button("deals-refresh").fire();
            assertTrue(table().getItems().isEmpty());
            assertEquals("No deals recorded yet.", ((Label) table().getPlaceholder()).getText());
            return null;
        });
    }

    @Test public void failedLoadsClearStaleRecordsAndRefreshCanRecover() throws Exception {
        SessionManager.logout();
        fx(() -> {
            button("deals-refresh").fire();
            assertTrue(table().getItems().isEmpty());
            Label error = (Label) stage.getScene().lookup("#deals-error");
            assertTrue(error.isVisible());
            assertTrue(error.getText().startsWith("Could not load deals."));
            assertEquals("Could not load deals. Use Refresh to try again.", ((Label) table().getPlaceholder()).getText());
            return null;
        });
        SessionManager.login(DataService.authenticate("admin", "Password123", UserRole.ADMIN));
        fx(() -> {
            button("deals-refresh").fire();
            assertEquals(3, table().getItems().size());
            assertFalse(stage.getScene().lookup("#deals-error").isVisible());
            return null;
        });
    }

    @Test public void ongoingBorrowShowsItsDueDateAndReplacesItWithTheActualReturn() throws Exception {
        AdminDealServiceTest.addDeal(4, "Ongoing Borrow", "LEND", "ACCEPTED", "2026-11-17T18:38:57", null, null);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listing_requests SET borrow_end = '2026-11-30T18:39:02', due_at = '2026-11-30T18:39:02' WHERE request_id = 4");
        }
        fx(() -> {
            button("deals-refresh").fire();
            var borrow = table().getItems().stream().filter(d -> d.requestId() == 4).findFirst().orElseThrow();
            assertEquals("2026-11-30 18:39 (due)", table().getColumns().get(5).getCellData(borrow));
            assertEquals("Accepted", table().getColumns().get(6).getCellData(borrow));
            return null;
        });
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listing_requests SET returned_at = '2026-11-20T10:00:00', completed_at = '2026-11-20T10:00:00' WHERE request_id = 4");
        }
        fx(() -> {
            button("deals-refresh").fire();
            var borrow = table().getItems().stream().filter(d -> d.requestId() == 4).findFirst().orElseThrow();
            assertEquals("2026-11-20 10:00:00", table().getColumns().get(5).getCellData(borrow));
            assertEquals("Returned", table().getColumns().get(6).getCellData(borrow));
            return null;
        });
    }

    @Test public void clickingADealOpensReadOnlyDetailsAndClosingKeepsTheCurrentFilters() throws Exception {
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listings SET description = 'Barely used, eighth edition', price = 180 WHERE listing_id = 1");
            s.executeUpdate("UPDATE listing_requests SET offer_price = 160 WHERE request_id = 1");
        }
        fx(() -> {
            search().setText("textbook");
            clickDeal(1);
            DialogPane pane = dealDialog();
            assertNotNull("A single row click must open Deal Details", pane);
            assertEquals("Calculus Textbook", ((Label) pane.lookup("#deal-detail-item")).getText());
            assertEquals("buyer", ((Label) pane.lookup("#deal-detail-buyer")).getText());
            assertEquals("seller", ((Label) pane.lookup("#deal-detail-seller")).getText());
            assertEquals("HK$160.00", ((Label) pane.lookup("#deal-detail-price")).getText());
            assertEquals("Barely used, eighth edition", ((Label) pane.lookup("#deal-detail-description")).getText());
            assertEquals("2026-11-10 10:00:00", ((Label) pane.lookup("#deal-detail-completed")).getText());
            assertEquals(List.of(ButtonType.CLOSE), List.copyOf(pane.getButtonTypes()));
            assertTrue("Details must be read-only", pane.lookupAll(".text-input").isEmpty());
            ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            assertNull(dealDialog());
            assertEquals("textbook", search().getText());
            assertEquals(1, table().getItems().size());
            return null;
        });
    }

    @Test public void enterOpensTheSelectedBorrowWithScheduledAndActualReturnShownSeparately() throws Exception {
        AdminDealServiceTest.addDeal(4, "Ongoing Borrow", "LEND", "ACCEPTED", "2026-11-17T18:38:57", null, null);
        try (var c = DatabaseManager.connect(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE listing_requests SET borrow_end = '2026-11-30T18:39:02', due_at = '2026-11-30T18:39:02', proposed_days = 13 WHERE request_id = 4");
        }
        fx(() -> {
            button("deals-refresh").fire();
            table().getSelectionModel().select(table().getItems().stream().filter(d -> d.requestId() == 4).findFirst().orElseThrow());
            table().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            DialogPane pane = dealDialog();
            assertNotNull("Enter must open the selected deal", pane);
            assertEquals("Ongoing Borrow", ((Label) pane.lookup("#deal-detail-item")).getText());
            assertEquals("2026-11-17 18:38:57", ((Label) pane.lookup("#deal-detail-start")).getText());
            assertEquals("2026-11-30 18:39:02", ((Label) pane.lookup("#deal-detail-scheduled")).getText());
            assertEquals("2026-11-30 18:39:02", ((Label) pane.lookup("#deal-detail-borrow-end")).getText());
            assertEquals("13 days", ((Label) pane.lookup("#deal-detail-duration")).getText());
            assertEquals("—", ((Label) pane.lookup("#deal-detail-completed")).getText());
            assertNull("A borrowing deal has no sale price", pane.lookup("#deal-detail-price"));
            ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
            return null;
        });
    }
}
