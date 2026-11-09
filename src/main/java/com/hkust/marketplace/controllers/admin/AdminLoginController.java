package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class AdminLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        if (username.isEmpty() || password.isEmpty()) {
            showError("Please enter credentials.");
            return;
        }
        User user;
        try {
            user = DataService.authenticate(username, password, UserRole.ADMIN);
        } catch (IllegalStateException e) {
            showError("Could not access the database. Please try again.");
            return;
        }
        if (user == null) {
            showError("Invalid admin credentials.");
            return;
        }
        passwordField.clear();
        SessionManager.login(user);
        SceneManager.showAdminDashboard();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
