package com.hkust.marketplace.controllers.seller;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.hkust.marketplace.controllers.admin.AdminFxTestSupport;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.ListingDescriptionService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SellerListingDescriptionScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final CountDownLatch releaseFirstRequest = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JsonArray> requestMessages = new AtomicReference<>();
    private volatile boolean rejectFirstRequest = true;
    private Stage stage;
    private HttpServer stub;
    private String oldDatabaseUrl;

    @BeforeClass
    public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit();
    }

    @Before
    public void openCreateListing() throws Exception {
        oldDatabaseUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("seller.db"));
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Demo Admin", "unused", UserRole.ADMIN, null, null, null);
        DatabaseManager.createUser("seller", "Demo Seller", "unused", UserRole.SELLER, null, null, null);
        SessionManager.login(new User("seller", "Demo Seller", "unused", UserRole.SELLER));
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/chat/completions", this::complete);
        stub.start();
        URI endpoint = URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + "/chat/completions");

        fx(() -> {
            var loader = new FXMLLoader(getClass().getResource("/fxml/seller/SellerDashboardView.fxml"));
            loader.setControllerFactory(type -> new SellerDashboardController(
                    new ListingDescriptionService(endpoint, "test-only-key")));
            stage = new Stage();
            SceneManager.init(stage);
            var scene = new Scene(loader.load(), 1200, 890);
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            ((Button) scene.lookup("#btnCreate")).fire();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return null;
        });
    }

    @After
    public void close() throws Exception {
        releaseFirstRequest.countDown();
        if (stub != null) stub.stop(0);
        try {
            fx(() -> {
                for (Window window : List.copyOf(Window.getWindows())) window.hide();
                SceneManager.init(null);
                return null;
            });
        } finally {
            SessionManager.logout();
            if (oldDatabaseUrl == null) System.clearProperty("marketplace.database.url");
            else System.setProperty("marketplace.database.url", oldDatabaseUrl);
        }
    }

    @Test
    public void sellerCanPolishAnExistingListingAndSaveTheEditedResultForReview() throws Exception {
        rejectFirstRequest = false;
        Listing existing = new Listing();
        existing.setTitle("Desk lamp");
        existing.setCategory("Electronics");
        existing.setCondition("Used");
        existing.setType(ListingType.GIVEAWAY);
        existing.setMeetupLocation("Library");
        existing.setDescription("Used desk lamp with a scratch on the shade.");
        DataService.submitListing(existing, List.of());

        fx(() -> {
            ((Button) stage.getScene().lookup("#btnListings")).fire();
            buttonWithText("Edit").fire();
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertNotNull("Seller Edit Listing should offer description assistance.", generate());
            assertEquals("Polish Description", generate().getText());
            generate().fire();
            return null;
        });
        releaseFirstRequest.countDown();

        awaitUi("polished existing description", () -> "Draft ready".equals(feedback().getText()));
        String finalDescription = "The used desk lamp has a small scratch on the shade.";
        fx(() -> {
            assertEquals("Used desk lamp with a small scratch on the shade.", description().getText());
            description().setText(finalDescription);
            buttonWithText("Save Changes for Review").fire();
            return null;
        });

        awaitUi("final description confirmation", () -> confirmation() != null);
        Listing saved = DataService.getSellerListings().get(0);
        assertEquals(existing.getId(), saved.getId());
        assertEquals(finalDescription, saved.getDescription());
        assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
        fx(() -> {
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            return null;
        });
    }

    @Test
    public void sellerCanRetryPolishingNotesThenEditAndSubmitTheDescription() throws Exception {
        String notes = "- Used desk lamp\n- Small scratch on the shade";
        fx(() -> {
            select("Listing type *", "Give Away");
            enterDraftDetails();
            select("Meet-up location *", "Library");
            assertEquals("Generate Description", generate().getText());
            description().setText(notes);
            assertEquals("Polish Description", generate().getText());
            @SuppressWarnings("unchecked")
            var length = (ComboBox<ListingDescriptionService.Length>) stage.getScene().lookup("#listing-draft-length");
            assertEquals(List.of(ListingDescriptionService.Length.values()), length.getItems());
            assertEquals(ListingDescriptionService.Length.MEDIUM, length.getValue());
            length.setValue(ListingDescriptionService.Length.SHORT);
            generate().fire();
            assertEquals("Polishing…", generate().getText());
            assertTrue(generate().isDisabled());
            assertTrue(description().isEditable());
            return null;
        });

        releaseFirstRequest.countDown();
        awaitUi("failure feedback", () -> "Generation failed".equals(feedback().getText()));
        fx(() -> {
            assertEquals(notes, description().getText());
            assertEquals("Try Again", generate().getText());
            generate().fire();
            return null;
        });

        awaitUi("polished description", () -> "Draft ready".equals(feedback().getText()));
        JsonArray messages = requestMessages.get();
        String instructions = messages.get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(instructions.contains("one sentence"));
        var facts = JsonParser.parseString(messages.get(1).getAsJsonObject().get("content").getAsString());
        assertEquals(notes, facts.getAsJsonObject().get("sellerNotes").getAsString());
        fx(() -> {
            assertEquals("Used desk lamp with a small scratch on the shade.", description().getText());
            assertFalse(generate().isDisabled());
            assertEquals("Polish Description", generate().getText());
            description().clear();
            assertEquals("Generate Description", generate().getText());
            description().setText("Used desk lamp. The shade has a small scratch.");
            Button submit = stage.getScene().getRoot().lookupAll(".button").stream()
                    .map(Button.class::cast).filter(button -> "Submit for Review".equals(button.getText()))
                    .findFirst().orElseThrow();
            submit.fire();
            return null;
        });

        awaitUi("final description confirmation", () -> confirmation() != null);
        Listing saved = DataService.getSellerListings().get(0);
        assertEquals("Used desk lamp. The shade has a small scratch.", saved.getDescription());
        assertEquals(ListingStatus.PENDING_REVIEW, saved.getStatus());
        fx(() -> {
            ((Button) confirmation().lookupButton(ButtonType.OK)).fire();
            return null;
        });
    }

    @Test
    public void sellerKeepsEditsMadeWhileTheDraftIsGenerating() throws Exception {
        rejectFirstRequest = false;
        fx(() -> {
            enterDraftDetails();
            generate().fire();
            description().setText("I added these details during generation.");
            return null;
        });
        releaseFirstRequest.countDown();

        awaitUi("generation to finish", () -> !generate().isDisabled());
        assertEquals("I added these details during generation.", fx(() -> description().getText()));
    }

    private void complete(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
            requestMessages.set(messages);
            boolean first = requests.incrementAndGet() == 1;
            if (first) {
                try {
                    releaseFirstRequest.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean reject = first && rejectFirstRequest;
            var facts = JsonParser.parseString(messages.get(1).getAsJsonObject().get("content").getAsString());
            String draft = facts.getAsJsonObject().has("sellerNotes")
                    ? "Used desk lamp with a small scratch on the shade." : "Used desk lamp.";
            String response = reject ? "{\"error\":{\"message\":\"Unavailable\"}}" : """
                    {"choices":[{"finish_reason":"stop","message":{"content":"%s"}}]}
                    """.formatted(draft);
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reject ? 503 : 200, data.length);
            exchange.getResponseBody().write(data);
        }
    }

    private void enterDraftDetails() {
        ((TextField) stage.getScene().lookup(".text-field")).setText("Desk lamp");
        select("Category *", "Electronics");
        select("Condition *", "Used");
    }

    private TextArea description() {
        return (TextArea) stage.getScene().lookup(".text-area");
    }

    private Button buttonWithText(String text) {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        return stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private Button generate() {
        return (Button) stage.getScene().lookup("#listing-draft-generate");
    }

    private Label feedback() {
        return (Label) stage.getScene().lookup("#listing-draft-feedback");
    }

    private DialogPane confirmation() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing() && window.getScene().getRoot() instanceof DialogPane pane
                    && pane.getContentText() != null) {
                assertTrue(pane.getContentText(), pane.getContentText().contains("Your final description was saved."));
                return pane;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void select(String prompt, String value) {
        for (Node node : stage.getScene().getRoot().lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && prompt.equals(combo.getPromptText())) {
                ((ComboBox<String>) combo).setValue(value);
                return;
            }
        }
        fail("No listing field for " + prompt);
    }

    private static void awaitUi(String expected, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (fx(condition)) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + expected);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
