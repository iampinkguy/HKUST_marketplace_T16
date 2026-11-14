package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.utils.DataService;
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

        String username = usernameField.getText();
        String fullName = fullNameField.getText();
        String password = passwordField.getText();
        String confirm = confirmPasswordField.getText();
        String residence = residenceCombo.getValue();

        try {
            DataService.registerBuyer(username, fullName, password, confirm, residence);
            showSuccess("Registration is successful. You can now login.");
            usernameField.clear();
            fullNameField.clear();
            passwordField.clear();
            confirmPasswordField.clear();
            residenceCombo.getSelectionModel().clearSelection();
        } catch (DataService.BuyerInputException ex) {
            if (ex.isClearConfirmPassword()) confirmPasswordField.clear();
            showError(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            showError(ex.getMessage());
        } catch (IllegalStateException ex) {
            showError("Registration failed. Please try again.");
        }
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
