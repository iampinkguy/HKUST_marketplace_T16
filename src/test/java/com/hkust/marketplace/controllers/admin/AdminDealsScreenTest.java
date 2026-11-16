package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
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
            assertEquals("Start Date: borrow start, otherwise request creation. Return/Complete Date: actual return or completion; — means not yet recorded.",
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
}
