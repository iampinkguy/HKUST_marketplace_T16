package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;

public class BuyerRegisterController {

    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> residenceCombo;
    @FXML private Label errorLabel;
    @FXML private Label successLabel;


    @FXML
    public void initialize() {
        residenceCombo.setItems(FXCollections.observableArrayList(
            com.hkust.marketplace.utils.DataService.RESIDENCES
        ));
    }

    @FXML
    private void handleRegister() {
        hideMessages();

        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();
        String residence = residenceCombo.getValue();

        // Validation
        if (username.isEmpty()) {
            showError("Username is required.");
            return;
        }
        if (DatabaseManager.usernameExists(username)) {
            showError("Username already taken. Please choose another.");
            return;
        }
        if (fullName.isEmpty()) {
            showError("Full Name cannot be empty.");
            return;
        }
        String pwdCheck = DataService.checkPasswordStrength(password);
        if (!"OK".equals(pwdCheck)) {
            showError(pwdCheck);
            return;
        }
        if (!password.equals(confirm)) {
            showError("Passwords do not match.");
            confirmPasswordField.clear();
            return;
        }

        String passwordHash = DataService.hashPassword(password);
        User user = new User(username.toLowerCase(), fullName, passwordHash, UserRole.BUYER);
        if (residence != null) {
            user.setResidenceHall(residence);
        }
        try {
            DatabaseManager.createUser(username, fullName, passwordHash, UserRole.BUYER, residence, null, null);
            showSuccess("Registration successful! You can now login.");
        } catch (IllegalStateException e) {
            showError("Registration failed. Please try again.");
        }
        // Clear form
        usernameField.clear();
        fullNameField.clear();
        passwordField.clear();
        confirmPasswordField.clear();
        residenceCombo.getSelectionModel().clearSelection();
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void showSuccess(String msg) {
        successLabel.setText(msg);
        successLabel.setVisible(true);
        successLabel.setManaged(true);
    }

    private void hideMessages() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        successLabel.setVisible(false);
        successLabel.setManaged(false);
    }

    @FXML private void goLogin() { SceneManager.showBuyerLogin(); }
    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
