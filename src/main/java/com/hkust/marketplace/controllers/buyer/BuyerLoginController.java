package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * Task 1.2 buyer sign-in. Enter submits the form. Show and Hide reveal the same password.
 * Only an active buyer account is accepted.
 */
public class BuyerLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordVisibleField;
    @FXML private Button togglePasswordButton;
    @FXML private Label errorLabel;

    @FXML
    private void initialize() {
        passwordVisibleField.textProperty().bindBidirectional(passwordField.textProperty());
    }

    @FXML
    private void togglePassword() {
        boolean show = !passwordVisibleField.isVisible();
        passwordVisibleField.setVisible(show);
        passwordVisibleField.setManaged(show);
        passwordField.setVisible(!show);
        passwordField.setManaged(!show);
        togglePasswordButton.setText(show ? "Hide" : "Show");
        if (show) {
            passwordVisibleField.requestFocus();
            passwordVisibleField.positionCaret(passwordVisibleField.getText().length());
        } else {
            passwordField.requestFocus();
            passwordField.positionCaret(passwordField.getText().length());
        }
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter both username and password.");
            return;
        }

        var account = DatabaseManager.fetchRow("users", "username", username);
        if (account != null && !"BUYER".equals(account.get("role"))) {
            showError("This account is not a buyer account. Please use the correct portal.");
            return;
        }
        if (account != null && ((Number) account.get("active")).intValue() == 0) {
            showError("This account has been deactivated. Contact admin.");
            return;
        }

        User user = DataService.authenticate(username, password, UserRole.BUYER);
        if (user == null) {
            showError("The credentials are invalid.");
            return;
        }

        SessionManager.login(user);
        SceneManager.showBuyerDashboard();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML
    private void goRegister() {
        SceneManager.showBuyerRegister();
    }

    @FXML
    private void goWelcome() {
        SceneManager.showWelcome();
    }
}
