package com.hkust.marketplace;

import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * HKUST Marketplace - Main Application Entry Point
 * JavaFX demo / skeleton for student development.
 *
 * This application provides a complete UI skeleton for the Buyer, Seller and Admin portals.
 * Admin login uses SQLite. Other backend integration points remain in the UI skeleton.
 */
public class MarketplaceApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        DatabaseManager.initialize();
        DataService.seedUserIfMissing();
        SceneManager.init(primaryStage);
        SceneManager.showWelcome();
        primaryStage.setTitle("HKUST Marketplace");
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(700);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
