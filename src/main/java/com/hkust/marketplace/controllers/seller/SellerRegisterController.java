package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;

public class SellerRegisterController {

    @FXML private TextField usernameField;
    @FXML private TextField fullNameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> meetupCombo;
    @FXML private TextArea bioArea;
    @FXML private Label errorLabel;
    @FXML private Label successLabel;

    @FXML
    public void initialize() {
        meetupCombo.setItems(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
    }

    @FXML
    private void handleRegister() {
        hideMessages();
        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();

        if (username.isEmpty()) { showError("Username is required."); return; }
        if (DummyDataService.getInstance().usernameExists(username)) {
            showError("Username already taken."); return;
        }
        if (fullName.isEmpty()) { showError("Full Name cannot be empty."); return; }
        String pwdCheck = DummyDataService.checkPasswordStrength(password);
        if (!"OK".equals(pwdCheck)) { showError(pwdCheck); return; }
        if (!password.equals(confirm)) { showError("Passwords do not match."); return; }

        User user = new User(username.toLowerCase(), fullName, password, UserRole.SELLER);
        if (meetupCombo.getValue() != null) user.setPreferredMeetup(meetupCombo.getValue());
        if (bioArea.getText() != null && !bioArea.getText().isBlank()) user.setBio(bioArea.getText().trim());
        DummyDataService.getInstance().registerUser(user);

        showSuccess("Seller account created! You can now login.");
        usernameField.clear(); fullNameField.clear(); passwordField.clear();
        confirmPasswordField.clear(); meetupCombo.getSelectionModel().clearSelection(); bioArea.clear();
    }

    private void showError(String msg) {
        errorLabel.setText(msg); errorLabel.setVisible(true); errorLabel.setManaged(true);
    }
    private void showSuccess(String msg) {
        successLabel.setText(msg); successLabel.setVisible(true); successLabel.setManaged(true);
    }
    private void hideMessages() {
        errorLabel.setVisible(false); errorLabel.setManaged(false);
        successLabel.setVisible(false); successLabel.setManaged(false);
    }

    @FXML private void goLogin() { SceneManager.showSellerLogin(); }
    @FXML private void goWelcome() { SceneManager.showWelcome(); }
}
