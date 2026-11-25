package com.hkust.marketplace.controllers;

import com.hkust.marketplace.controllers.admin.AdminFxTestSupport;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MarketplaceUiRegressionTest {
    @Rule public TemporaryFolder database = new TemporaryFolder();

    private String previousDatabaseUrl;

    @BeforeClass
    public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit();
    }

    @Before
    public void useTemporaryDatabase() {
        previousDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + database.getRoot().toPath().resolve("ui.db"));
        DatabaseManager.initialize();
    }

    @After
    public void restoreDatabase() {
        SessionManager.logout();
        if (previousDatabaseUrl == null) System.clearProperty("marketplace.database.url");
        else System.setProperty("marketplace.database.url", previousDatabaseUrl);
    }

    @Test
    public void buyerRegistrationIsCenteredAndScrollable() throws Exception {
        checkRegistration("buyer/BuyerRegisterView.fxml");
    }

    @Test
    public void sellerRegistrationIsCenteredAndScrollable() throws Exception {
        checkRegistration("seller/SellerRegisterView.fxml");
    }

    @Test
    public void clearingFiltersAndSortKeepsMarketplaceOpenAndPreservesSearch() throws Exception {
        DatabaseManager.createUser("buyer", "Test Buyer", "unused", UserRole.BUYER, null, null, null);
        SessionManager.login(new User("buyer", "Test Buyer", "unused", UserRole.BUYER));
        onFxThread(() -> {
            Scene scene = loadScene("buyer/BuyerDashboardView.fxml", 780);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                submitDialog(button(scene, "Filter"), pane -> {
                    var fields = ((VBox) pane.getContent()).getChildren();
                    choose((ComboBox<?>) fields.get(1), 1);
                    choose((ComboBox<?>) fields.get(3), 1);
                    choose((ComboBox<?>) fields.get(5), 1);
                    var priceRange = ((HBox) fields.get(7)).getChildren();
                    ((TextField) priceRange.get(0)).setText("50");
                    ((TextField) priceRange.get(1)).setText("100");
                });
                layout(scene);
                submitDialog(button(scene, "Sort"), pane -> choose((ComboBox<?>) pane.getContent(), 1));
                layout(scene);
                assertTrue(hasLabel(scene, "Showing live listings · search all keywords · Textbooks · Sell · New"
                        + " · 50 – 100 · sorted by Price: Low to High"));
                ((TextField) scene.lookup("#marketplace-search")).setText("desk");

                Button reset = button(scene, "Clear filters and sort");
                assertNotNull(reset);
                assertFalse(reset.isDisabled());
                reset.fire();

                assertEquals("Browse Marketplace", ((Label) scene.lookup("#pageTitle")).getText());
                assertEquals("desk", ((TextField) scene.lookup("#marketplace-search")).getText());
                assertNotNull(scene.lookup("#marketplace-results"));
                assertTrue(hasLabel(scene, "Showing live listings · search \"desk\" · All Categories"
                        + " · All Types · All Conditions · any price · sorted by Newest"));
                assertTrue(reset.isDisabled());
            } finally {
                stage.hide();
            }
        });
    }

    private void checkRegistration(String fxml) throws Exception {
        onFxThread(() -> {
            Scene roomy = loadScene(fxml, 780);
            Node form = roomy.lookup(".auth-pane");
            Bounds card = form.localToScene(form.getLayoutBounds());
            assertEquals("Card horizontal center", roomy.getWidth() / 2, card.getCenterX(), 2);
            assertEquals("Card vertical center", roomy.getHeight() / 2, card.getCenterY(), 2);
            assertTrue("Card width is capped", card.getWidth() <= 460);

            Scene shortScene = loadScene(fxml, 480);
            ScrollPane scroll = (ScrollPane) shortScene.lookup(".scroll-pane");
            scroll.setVvalue(scroll.getVmax());
            layout(shortScene);
            Hyperlink back = shortScene.getRoot().lookupAll(".hyperlink").stream().map(Hyperlink.class::cast)
                    .filter(link -> link.getText().contains("Back to Welcome")).findFirst().orElseThrow();
            Bounds link = back.localToScene(back.getLayoutBounds());
            Node viewport = scroll.lookup(".viewport");
            assertTrue("Scrolling reveals the bottom of the form",
                    viewport.localToScene(viewport.getLayoutBounds()).contains(link));
        });
    }

    private Scene loadScene(String fxml, double height) throws Exception {
        Scene scene = new Scene(FXMLLoader.load(getClass().getResource("/fxml/" + fxml)), 1200, height);
        scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
        layout(scene);
        return scene;
    }

    private static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static Button button(Scene scene, String text) {
        return scene.getRoot().lookupAll(".button").stream().map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElse(null);
    }

    private static boolean hasLabel(Scene scene, String text) {
        return scene.getRoot().lookupAll(".label").stream().map(Label.class::cast)
                .anyMatch(label -> text.equals(label.getText()));
    }

    private static void choose(ComboBox<?> combo, int index) {
        combo.getSelectionModel().select(index);
    }

    private static void submitDialog(Button opener, Consumer<DialogPane> fill) throws Exception {
        FutureTask<Void> submit = new FutureTask<>(() -> {
            Window window = Window.getWindows().stream().filter(Window::isShowing)
                    .filter(candidate -> candidate.getScene().getRoot() instanceof DialogPane)
                    .findFirst().orElseThrow();
            DialogPane pane = (DialogPane) window.getScene().getRoot();
            try {
                fill.accept(pane);
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            } finally {
                window.hide();
            }
            return null;
        });
        Platform.runLater(submit);
        opener.fire();
        submit.get(5, TimeUnit.SECONDS);
    }

    private static void onFxThread(FxAction action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
