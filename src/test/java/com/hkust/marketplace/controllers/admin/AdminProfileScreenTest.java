package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in desktop tests: run with -Dmarketplace.uiTests=true in a desktop session. */
public class AdminProfileScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static String passwordHash;
    private String oldUrl;
    private Stage stage;

    @BeforeClass public static void startToolkit() throws Exception {
        Assume.assumeTrue("Desktop UI tests are opt-in", Boolean.getBoolean("marketplace.uiTests"));
        passwordHash = DataService.hashPassword("OriginalPassword123");
        AdminFxTestSupport.startToolkit();
    }
    @Before public void openScreen() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("screen.db"));
        DatabaseManager.initialize();
        DatabaseManager.createUser("admin", "Original Admin", passwordHash, UserRole.ADMIN, null, null, null);
        SessionManager.login(DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN));
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage();
            stage.setTitle("Isolated Admin Profile Test");
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Path.of("src/main/resources/css/styles.css").toUri().toString());
            stage.setScene(scene);
            stage.show();
            button("btnProfile").fire();
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
    private TextField field(String id) { return (TextField) stage.getScene().lookup("#" + id); }
    private Label label(String id) { return (Label) stage.getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) stage.getScene().lookup("#" + id); }

    @Test public void savingNameShowsSuccessUpdatesTheWelcomeAndSurvivesReopeningAndLogin() throws Exception {
        fx(() -> {
            assertNotNull("Username must be displayed", field("profile-username"));
            assertFalse(field("profile-username").isEditable());
            assertEquals("admin", field("profile-username").getText());
            field("profile-full-name").setText(" Updated Admin ");
            button("profile-save").fire();
            assertTrue(label("profile-success").isVisible());
            assertEquals("Your profile was updated successfully.", label("profile-success").getText());
            assertFalse(label("profile-error").isVisible());
            assertEquals("Admin: Updated Admin", label("welcomeLabel").getText());
            button("btnReview").fire();
            button("btnProfile").fire();
            assertEquals("Updated Admin", field("profile-full-name").getText());
            return null;
        });
        SessionManager.logout();
        assertEquals("Updated Admin", DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN).getFullName());
    }

    @Test public void validationShowsRegistrationErrorsAndClearsOnlyMismatchedConfirmation() throws Exception {
        fx(() -> {
            field("profile-full-name").setText("  ");
            button("profile-save").fire();
            assertTrue(label("profile-error").isVisible());
            assertEquals("Full name cannot be empty.", label("profile-error").getText());
            assertFalse(label("profile-success").isVisible());
            field("profile-full-name").setText("Unsaved Admin");
            field("profile-new-password").setText("weak");
            field("profile-confirm-password").setText("weak");
            button("profile-save").fire();
            assertEquals("The password is too weak to be used.", label("profile-error").getText());
            assertEquals("weak", field("profile-confirm-password").getText());
            field("profile-new-password").setText("NewPassword456");
            field("profile-confirm-password").setText("Different123");
            button("profile-save").fire();
            assertEquals("The confirm-password was incorrect.", label("profile-error").getText());
            assertEquals("", field("profile-confirm-password").getText());
            assertEquals("NewPassword456", field("profile-new-password").getText());
            assertEquals("Unsaved Admin", field("profile-full-name").getText());
            assertEquals("Original Admin", SessionManager.getCurrentUser().getFullName());
            field("profile-confirm-password").setText("NewPassword456");
            button("profile-save").fire();
            assertFalse(label("profile-error").isVisible());
            assertTrue(label("profile-success").isVisible());
            assertEquals("", field("profile-new-password").getText());
            assertEquals("", field("profile-confirm-password").getText());
            // A new validation failure replaces previous success feedback.
            field("profile-full-name").clear();
            button("profile-save").fire();
            assertTrue(label("profile-error").isVisible());
            assertFalse(label("profile-success").isVisible());
            return null;
        });
        SessionManager.logout();
        assertNull(DataService.authenticate("admin", "OriginalPassword123", UserRole.ADMIN));
        assertEquals("Unsaved Admin", DataService.authenticate("admin", "NewPassword456", UserRole.ADMIN).getFullName());
    }

    @Test public void passwordRequirementsAreExplainedBeforeSaving() throws Exception {
        fx(() -> {
            assertNotNull("Password rules must be visible on the form", label("profile-password-rules"));
            assertTrue(label("profile-password-rules").isVisible());
            assertEquals("Password rules: at least 8 characters, with an uppercase letter, a lowercase letter, and a digit.",
                    label("profile-password-rules").getText());
            assertTrue(field("profile-new-password") instanceof PasswordField);
            assertTrue(field("profile-confirm-password") instanceof PasswordField);
            return null;
        });
    }
}
