package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Opt-in desktop tests: run with -Dmarketplace.uiTests=true in a desktop session. */
public class AdminManageUsersScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static boolean started;
    private static String passwordHash;
    private Stage stage;
    private String oldUrl;
    private record Step(BooleanSupplier ready, Runnable action) {}

    @BeforeClass public static void startToolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        passwordHash = DataService.hashPassword("TestPassword123");
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        started = true;
    }
    @AfterClass public static void stopToolkit() { if (started) Platform.exit(); }
    @Before public void openScreen() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("screen.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) {
            String username = role.name().toLowerCase(Locale.ROOT);
            DatabaseManager.createUser(username, "Test " + username, passwordHash, role,
                    role == UserRole.BUYER ? "Hall 3" : null, role == UserRole.SELLER ? "Seller bio" : null,
                    role == UserRole.SELLER ? "Library" : null);
        }
        SessionManager.login(DataService.authenticate("admin", "TestPassword123", UserRole.ADMIN));
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage();
            stage.setTitle("Isolated Manage Users Test");
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.setScene(scene);
            stage.show();
            ((Button) scene.lookup("#btnUsers")).fire();
            return null;
        });
    }
    @After public void closeScreen() throws Exception {
        try { fx(() -> {
            String screenshot = System.getProperty("marketplace.uiScreenshot");
            if (screenshot != null && stage != null && stage.isShowing()) {
                var image = stage.getScene().snapshot(null);
                var png = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
                    png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
                javax.imageio.ImageIO.write(png, "png", Path.of(screenshot).toFile());
            }
            for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
            return null;
        }); }
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
    private DialogPane dialog(String title) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.isShowing() && window instanceof Stage s && title.equals(s.getTitle())
                    && s.getScene().getRoot() instanceof DialogPane pane) return pane;
        }
        return null;
    }
    private static String text(Node node) {
        String result = node instanceof Labeled label ? label.getText() + "\n" : "";
        if (node instanceof TextInputControl input) result += input.getText() + "\n";
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) result += text(scroll.getContent());
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) result += text(child);
        return result;
    }
    private AdminUserService.Account account(String username) {
        return AdminUserService.getAllUsers(true).stream().filter(user -> user.username().equals(username)).findFirst().orElseThrow();
    }
    private Step click(String selector) {
        return new Step(() -> stage.getScene().lookup(selector) != null,
                () -> ((Button) stage.getScene().lookup(selector)).fire());
    }
    private Step press(String title, ButtonType button) {
        return new Step(() -> dialog(title) != null, () -> ((Button) dialog(title).lookupButton(button)).fire());
    }
    private void scenario(Step... actions) throws Exception {
        Deque<Step> queue = new ArrayDeque<>(List.of(actions));
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            try {
                if (queue.isEmpty()) { finished.countDown(); return; }
                Step step = queue.peek();
                if (step.ready().getAsBoolean()) { queue.remove(); step.action().run(); }
            } catch (Throwable failure) {
                error.set(failure);
                queue.clear();
                for (Window window : new ArrayList<>(Window.getWindows())) if (window != stage) window.hide();
                finished.countDown();
            }
        }), 100, 130, TimeUnit.MILLISECONDS);
        try {
            assertTrue("UI scenario timed out with " + queue.size() + " steps remaining", finished.await(25, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError("UI scenario failed", error.get());
        } finally { timer.shutdownNow(); }
    }

    @Test public void adminCanViewAndEditDatabaseProfilesAndCancelAnUnsavedEdit() throws Exception {
        assertNotNull("View action is required", fx(() -> stage.getScene().lookup("#view-user-buyer")));
        scenario(
            click("#view-user-buyer"),
            new Step(() -> dialog("User Details") != null, () -> {
                assertTrue(text(dialog("User Details")).contains("Hall 3"));
                assertTrue(text(dialog("User Details")).contains("Test buyer"));
                ((Button) dialog("User Details").lookupButton(ButtonType.CLOSE)).fire();
            }),
            click("#edit-user-buyer"),
            new Step(() -> dialog("Edit User") != null, () -> {
                ((TextField) dialog("Edit User").lookup("#user-full-name")).setText("Unsaved buyer");
                ((Button) dialog("Edit User").lookupButton(ButtonType.CANCEL)).fire();
            }),
            new Step(() -> dialog("Edit User") == null, () -> assertEquals("Test buyer", account("buyer").fullName())),
            click("#edit-user-buyer"),
            new Step(() -> dialog("Edit User") != null, () -> {
                DialogPane pane = dialog("Edit User");
                ((TextField) pane.lookup("#user-full-name")).setText("Edited buyer");
                @SuppressWarnings("unchecked") ComboBox<String> hall = (ComboBox<String>) pane.lookup("#user-residence-hall");
                hall.setValue("Hall 5");
                ((Button) pane.lookup("#save-user")).fire();
            }),
            new Step(() -> dialog("Edit User") == null, () -> {
                assertEquals("Edited buyer", account("buyer").fullName());
                assertEquals("Hall 5", account("buyer").residenceHall());
                assertTrue(text(stage.getScene().getRoot()).contains("updated"));
            })
        );
    }
    @Test public void destructiveActionsRequireConfirmationAndDeletedAccountsRemainReadOnlyInHistory() throws Exception {
        assertNotNull("A confirmable activation action is required", fx(() -> stage.getScene().lookup("#active-user-buyer")));
        scenario(
            click("#active-user-buyer"),
            new Step(() -> dialog("Confirm Deactivation") != null, () -> {
                assertTrue(text(dialog("Confirm Deactivation")).contains("buyer"));
                ((Button) dialog("Confirm Deactivation").lookupButton(ButtonType.CANCEL)).fire();
            }),
            new Step(() -> dialog("Confirm Deactivation") == null, () -> {
                assertTrue(account("buyer").active());
                assertTrue(AdminUserService.getHistory("buyer").isEmpty());
            }),
            click("#delete-user-buyer"),
            new Step(() -> dialog("Permanently Delete User") != null, () -> {
                String policy = text(dialog("Permanently Delete User")).toLowerCase(Locale.ROOT);
                for (String word : List.of("listings", "deals", "chats", "audit", "cannot be undone")) assertTrue(word, policy.contains(word));
                ((Button) dialog("Permanently Delete User").lookupButton(ButtonType.CANCEL)).fire();
            }),
            new Step(() -> dialog("Permanently Delete User") == null, () -> {
                assertNull(account("buyer").deletedAt());
                assertTrue(AdminUserService.getHistory("buyer").isEmpty());
            }),
            click("#active-user-buyer"), press("Confirm Deactivation", ButtonType.OK),
            new Step(() -> dialog("Confirm Deactivation") == null, () -> assertFalse(account("buyer").active())),
            click("#active-user-buyer"), press("Confirm Activation", ButtonType.OK),
            new Step(() -> dialog("Confirm Activation") == null, () -> assertTrue(account("buyer").active())),
            click("#delete-user-buyer"), press("Permanently Delete User", ButtonType.OK),
            new Step(() -> dialog("Permanently Delete User") == null, () -> {
                assertTrue(AdminUserService.getAllUsers(false).stream().noneMatch(user -> user.username().equals("buyer")));
                assertNull(DataService.authenticate("buyer", "TestPassword123", UserRole.BUYER));
                assertTrue(text(stage.getScene().getRoot()).contains("permanently deleted"));
            }),
            new Step(() -> stage.getScene().lookup("#show-deleted-users") != null,
                    () -> ((CheckBox) stage.getScene().lookup("#show-deleted-users")).fire()),
            new Step(() -> stage.getScene().lookup("#view-user-buyer") != null, () -> {
                for (String action : List.of("edit", "active", "delete")) {
                    assertTrue(stage.getScene().lookup("#" + action + "-user-buyer").isDisabled());
                }
            }),
            click("#history-user-buyer"),
            new Step(() -> dialog("Account History") != null, () -> {
                assertTrue(text(dialog("Account History")).contains("DELETE"));
                assertTrue(text(dialog("Account History")).contains("admin"));
                ((Button) dialog("Account History").lookupButton(ButtonType.CLOSE)).fire();
            })
        );
    }
    @Test public void sellerProfileValidationKeepsTheFormOpenAndStaleSaveDoesNotOverwriteChanges() throws Exception {
        scenario(
            click("#edit-user-seller"),
            new Step(() -> dialog("Edit User") != null, () -> {
                ((TextField) dialog("Edit User").lookup("#user-full-name")).clear();
                ((Button) dialog("Edit User").lookup("#save-user")).fire();
            }),
            new Step(() -> dialog("Edit User") != null, () -> {
                assertTrue(text(dialog("Edit User")).contains("Full name is required"));
                assertEquals("Test seller", account("seller").fullName());
                DialogPane pane = dialog("Edit User");
                ((TextField) pane.lookup("#user-full-name")).setText("Edited seller");
                ((TextArea) pane.lookup("#user-bio")).setText("Updated seller bio");
                @SuppressWarnings("unchecked") ComboBox<String> meetup = (ComboBox<String>) pane.lookup("#user-preferred-meetup");
                meetup.setValue("Student Centre");
                ((Button) pane.lookup("#save-user")).fire();
            }),
            new Step(() -> dialog("Edit User") == null, () -> {
                assertEquals("Updated seller bio", account("seller").bio());
                assertEquals("Student Centre", account("seller").preferredMeetup());
            }),
            click("#edit-user-seller"),
            new Step(() -> dialog("Edit User") != null, () -> {
                AdminUserService.setUserActive(account("seller"), false);
                ((TextField) dialog("Edit User").lookup("#user-full-name")).setText("Stale overwrite");
                ((Button) dialog("Edit User").lookup("#save-user")).fire();
            }),
            new Step(() -> dialog("User Change Not Saved") != null, () -> {
                assertTrue(text(dialog("User Change Not Saved")).contains("changed"));
                ((Button) dialog("User Change Not Saved").lookupButton(ButtonType.OK)).fire();
            }),
            new Step(() -> dialog("Edit User") == null, () -> {
                assertEquals("Edited seller", account("seller").fullName());
                assertFalse(account("seller").active());
                assertEquals(2, AdminUserService.getHistory("seller").size());
            })
        );
    }
}
