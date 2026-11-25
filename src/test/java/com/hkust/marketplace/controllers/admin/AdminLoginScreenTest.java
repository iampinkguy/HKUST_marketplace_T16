package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real login controls and navigation, backed by an isolated database. */
public class AdminLoginScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static String hash;
    private String oldUrl;
    private Stage stage;
    private Stage previousStage;
    @BeforeClass public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        AdminFxTestSupport.startToolkit(); hash = DataService.hashPassword("AdminPassword123");
    }
    @Before public void open() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("login.db"));
        DatabaseManager.initialize(); SessionManager.logout();
        for (UserRole role : UserRole.values()) DatabaseManager.createUser(role.name().toLowerCase(), "Test " + role.name(), hash, role, null, null, null);
        fx(() -> {
            previousStage = SceneManager.getPrimaryStage(); stage = new Stage(); SceneManager.init(stage);
            SceneManager.showAdminLogin(); stage.show(); return null;
        });
    }
    @After public void close() throws Exception {
        try { fx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); SceneManager.init(previousStage); return null; }); }
        finally {
            SessionManager.logout();
            if (oldUrl == null) System.clearProperty("marketplace.database.url"); else System.setProperty("marketplace.database.url", oldUrl);
        }
    }
    private <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> {
            if (stage != null && stage.getScene() != null) { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
            return action.call();
        }); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    private Node node(String id) { return stage.getScene().lookup("#" + id); }
    private TextField field(String id) { return (TextField) node(id); }
    private void submit(String username, String password) {
        field("usernameField").setText(username); field("passwordField").setText(password);
        field("passwordField").fireEvent(new ActionEvent());
    }
    private void errorContains(String expected) {
        Label error = (Label) node("errorLabel");
        assertTrue(error.isVisible()); assertTrue(error.isManaged()); assertTrue(error.getText(), error.getText().contains(expected));
        assertFalse(SessionManager.isLoggedIn()); assertNotNull(node("usernameField"));
    }
    @Test public void invalidCredentialsAndInputsShowFeedbackWithoutOpeningTheDashboard() throws Exception {
        fx(() -> {
            submit("", ""); errorContains("both username and password");
            submit("unknown", "AdminPassword123"); errorContains("credentials are invalid");
            submit("admin", "wrong"); errorContains("credentials are invalid");
            submit("buyer", "AdminPassword123"); errorContains("not an admin account");
            submit("seller", "AdminPassword123"); errorContains("not an admin account");
            DatabaseManager.setUserActive("admin", false);
            submit("admin", "AdminPassword123"); errorContains("deactivated");
            return null;
        });
    }
    @Test public void passwordVisibilityKeepsTheValueAndSuccessfulLoginClearsItAndNavigates() throws Exception {
        fx(() -> {
            submit("admin", "wrong"); errorContains("credentials are invalid");
            PasswordField password = (PasswordField) field("passwordField");
            TextField visible = field("passwordVisibleField");
            Button toggle = (Button) node("togglePasswordButton");
            toggle.fire(); assertTrue(visible.isVisible()); assertFalse(password.isVisible()); assertEquals("Hide", toggle.getText());
            visible.setText("AdminPassword123"); assertEquals("AdminPassword123", password.getText());
            toggle.fire(); assertFalse(visible.isVisible()); assertTrue(password.isVisible()); assertEquals("Show", toggle.getText());
            field("usernameField").setText(" ADMIN ");
            password.fireEvent(new ActionEvent());
            assertTrue(SessionManager.isLoggedIn());
            assertEquals("admin", SessionManager.getCurrentUser().getUsername());
            assertEquals(UserRole.ADMIN, SessionManager.getCurrentUser().getRole());
            assertEquals("", password.getText()); assertEquals("", visible.getText());
            assertNotNull(node("pageTitle"));
            assertEquals("Listing Review & Approval", ((Label) node("pageTitle")).getText());
            assertNull(node("usernameField"));
            return null;
        });
    }
    @Test public void unavailableDatabaseShowsRetryFeedbackAndDoesNotCreateASession() throws Exception {
        String workingUrl = System.getProperty("marketplace.database.url");
        try {
            System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("missing/login.db"));
            fx(() -> { submit("admin", "AdminPassword123"); errorContains("Could not access the database. Please try again."); return null; });
        } finally { System.setProperty("marketplace.database.url", workingUrl); }
        fx(() -> { submit("admin", "AdminPassword123"); assertEquals("admin", SessionManager.getCurrentUser().getUsername()); return null; });
    }
    @Test public void backAndLogoutReturnToWelcomeWithoutLeavingAnAuthenticatedSession() throws Exception {
        fx(() -> {
            Hyperlink back = (Hyperlink) stage.getScene().lookup(".hyperlink");
            assertTrue(back.getText().contains("Back to Welcome")); back.fire();
            assertNull(node("usernameField")); assertFalse(SessionManager.isLoggedIn());
            SceneManager.showAdminLogin(); stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            submit("admin", "AdminPassword123");
            stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
            Button logout = stage.getScene().getRoot().lookupAll(".button").stream().filter(n -> n instanceof Button b && "Logout".equals(b.getText().trim()))
                    .map(n -> (Button) n).findFirst().orElseThrow();
            logout.fire(); assertFalse(SessionManager.isLoggedIn()); assertNull(node("pageTitle"));
            return null;
        });
    }
}
