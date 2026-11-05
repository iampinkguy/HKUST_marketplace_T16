package com.hkust.marketplace.utils;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * Central navigation helper.
 * Loads FXML scenes and keeps a reference to the primary stage.
 */
public class SceneManager {

    private static Stage primaryStage;
    private static final String FXML_BASE = "/fxml/";

    public static void init(Stage stage) {
        primaryStage = stage;
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    public static void switchScene(String fxmlName) {
        try {
            if (primaryStage == null) {
                throw new IllegalStateException("SceneManager not initialized – primaryStage is null");
            }

            URL resource = SceneManager.class.getResource(FXML_BASE + fxmlName);
            if (resource == null) {
                throw new IOException("FXML resource not found on classpath: " + FXML_BASE + fxmlName);
            }

            System.out.println("[SceneManager] Loading: " + resource);
            FXMLLoader loader = new FXMLLoader(resource);
            Parent root = loader.load();

            Scene scene = primaryStage.getScene();
            if (scene == null) {
                scene = new Scene(root, 1200, 780);
                URL css = SceneManager.class.getResource("/css/styles.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                primaryStage.setScene(scene);
            } else {
                // Keep stylesheets when swapping root
                scene.setRoot(root);
            }
            System.out.println("[SceneManager] Switched to: " + fxmlName);
        } catch (Exception e) {
            e.printStackTrace();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = "Failed to load: " + fxmlName + "\n\n" + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage();
            System.err.println("[SceneManager] " + msg);

            // Show a dialog so the error is visible without reading the console
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Navigation Error");
                alert.setHeaderText("Could not open screen");
                alert.setContentText(msg);
                alert.showAndWait();
            } catch (Exception ignored) {
                // If even the alert fails, at least we printed to console
            }
            throw new RuntimeException(msg, e);
        }
    }

    public static void showWelcome() {
        switchScene("WelcomeView.fxml");
    }

    public static void showBuyerLogin() {
        switchScene("buyer/BuyerLoginView.fxml");
    }

    public static void showBuyerRegister() {
        switchScene("buyer/BuyerRegisterView.fxml");
    }

    public static void showBuyerDashboard() {
        switchScene("buyer/BuyerDashboardView.fxml");
    }

    public static void showSellerLogin() {
        switchScene("seller/SellerLoginView.fxml");
    }

    public static void showSellerRegister() {
        switchScene("seller/SellerRegisterView.fxml");
    }

    public static void showSellerDashboard() {
        switchScene("seller/SellerDashboardView.fxml");
    }

    public static void showAdminLogin() {
        switchScene("admin/AdminLoginView.fxml");
    }

    public static void showAdminDashboard() {
        switchScene("admin/AdminDashboardView.fxml");
    }
}
