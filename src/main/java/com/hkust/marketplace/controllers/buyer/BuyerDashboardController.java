package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BuyerDashboardController {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnBrowse, btnDeals, btnHistory, btnFavourites, btnNotifications, btnProfile;
    private Button currentActiveBtn;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) {
            welcomeLabel.setText("Hello, " + user.getFullName());
        }
        showBrowse();
    }

    private void setActive(Button button, String title) {
        if (currentActiveBtn != null) {
            currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        }
        button.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = button;
        pageTitle.setText(title);
    }

    @FXML
    private void showBrowse() {
        setActive(btnBrowse, "Browse Marketplace");
        renderBrowse("All Categories", "All Types", "");
    }

    private void renderBrowse(String category, String type, String search) {
        contentPane.getChildren().clear();
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));

        ComboBox<String> categoryFilter = new ComboBox<>(
                FXCollections.observableArrayList("All Categories"));
        categoryFilter.getItems().addAll(DataService.CATEGORIES);
        categoryFilter.setValue(category);
        ComboBox<String> typeFilter = new ComboBox<>(
                FXCollections.observableArrayList("All Types", "Sell", "Lend", "Giveaway"));
        typeFilter.setValue(type);
        TextField searchField = new TextField(search);
        searchField.setPromptText("Search listings...");
        searchField.setPrefWidth(220);
        Button searchButton = new Button("Search");
        searchButton.getStyleClass().addAll("btn-primary", "btn-sm");
        searchButton.setOnAction(event -> renderBrowse(
                categoryFilter.getValue(), typeFilter.getValue(), searchField.getText().trim()));

        HBox filters = new HBox(12, new Label("Category:"), categoryFilter,
                new Label("Type:"), typeFilter, searchField, searchButton);
        filters.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(filters);

        Map<String, Object> dbFilters = new java.util.LinkedHashMap<>();
        dbFilters.put("listing_status", ListingStatus.LIVE.name());
        if (!"All Categories".equals(category)) {
            dbFilters.put("category", category);
        }
        if (!"All Types".equals(type)) {
            dbFilters.put("listing_type", ListingType.valueOf(type.toUpperCase()).name());
        }

        List<Listing> listings = DatabaseManager.fetchListings(dbFilters);
        String query = search.toLowerCase();
        FlowPane grid = new FlowPane(16, 16);
        grid.setPrefWrapLength(900);
        for (Listing listing : listings) {
            if (query.isEmpty()
                    || listing.getItemName().toLowerCase().contains(query)
                    || listing.getDescription() != null
                    && listing.getDescription().toLowerCase().contains(query)
                    || listing.getSellerFullName() != null
                    && listing.getSellerFullName().toLowerCase().contains(query)) {
                grid.getChildren().add(createListingCard(listing));
            }
        }
        if (grid.getChildren().isEmpty()) {
            Label empty = new Label("No matching live listings.");
            empty.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 15px;");
            root.getChildren().add(empty);
        } else {
            root.getChildren().add(grid);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox createListingCard(Listing listing) {
        VBox card = new VBox(8);
        card.getStyleClass().add("listing-card");
        card.setPrefWidth(260);
        Label type = new Label(listing.getTypeDisplay());
        type.getStyleClass().add("badge");
        Label price = new Label(listing.getPriceDisplay());
        price.setStyle("-fx-font-weight: bold; -fx-text-fill: #003974; -fx-font-size: 16px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(type, spacer, price);
        top.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(listing.getItemName());
        title.setWrapText(true);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #1a1a2e;");
        String sellerName = listing.getSellerFullName() == null ? "Unknown seller"
                : listing.getSellerFullName();
        Label details = new Label(listing.getCategory() + " · " + listing.getCondition()
                + "\nSeller: " + sellerName + "\nMeetup: " + listing.getMeetupLocation()
                + "\nPosted: " + listing.getPostedAt().format(DATE_FORMAT));
        details.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        Button action = new Button();
        action.getStyleClass().addAll("btn-primary", "btn-sm");
        if (listing.getType() == ListingType.SELL) {
            action.setText("Make Offer");
            action.setOnAction(event -> showOfferDialog(listing));
        } else if (listing.getType() == ListingType.LEND) {
            action.setText("Request Borrow");
            action.setOnAction(event -> showBorrowDialog(listing));
        } else {
            action.setText("Claim Item");
            action.setOnAction(event -> claimItem(listing));
        }
        Button favourite = new Button("Favourite");
        favourite.getStyleClass().add("btn-sm");
        favourite.setOnAction(event -> {
            DatabaseManager.setFavourite(currentUser().getUsername(),
                    Integer.parseInt(listing.getId()), true);
            showInfo("Listing added to favourites.");
        });
        card.getChildren().addAll(top, title, details, action, favourite);
        return card;
    }

    private User currentUser() {
        User user = SessionManager.getCurrentUser();
        if (user == null) {
            throw new IllegalStateException("No buyer is logged in.");
        }
        return user;
    }

    private void showOfferDialog(Listing listing) {
        TextInputDialog dialog = new TextInputDialog(
                listing.getPrice() == null ? "" : String.valueOf(listing.getPrice()));
        dialog.setTitle("Make an Offer");
        dialog.setHeaderText("Offer for: " + listing.getItemName());
        dialog.setContentText("Your offer price (HK$):");
        dialog.showAndWait().ifPresent(value -> {
            try {
                double price = Double.parseDouble(value.trim());
                if (price < 0) throw new NumberFormatException();
                DatabaseManager.createOffer(currentUser().getUsername(),
                        Integer.parseInt(listing.getId()), price, "offer");
                DatabaseManager.createNotification(listing.getSellerUsername(),
                        currentUser().getUsername(), "New offer received",
                        currentUser().getFullName() + " offered HK$" + price
                                + " for " + listing.getItemName(), "Offer");
                showInfo("Offer submitted successfully.");
            } catch (NumberFormatException exception) {
                showError("Please enter a valid non-negative price.");
            }
        });
    }

    private void showBorrowDialog(Listing listing) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "Proposed duration: " + listing.getLendingDays() + " days\n\nSend request?",
                ButtonType.OK, ButtonType.CANCEL);
        dialog.setTitle("Borrow Request");
        dialog.setHeaderText("Request to borrow: " + listing.getItemName());
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            DatabaseManager.createOffer(currentUser().getUsername(),
                    Integer.parseInt(listing.getId()), 0, "borrow");
            notifySeller(listing, "Borrow request received",
                    currentUser().getFullName() + " requested to borrow " + listing.getItemName());
            showInfo("Borrow request submitted.");
        });
    }

    private void claimItem(Listing listing) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to claim this item?", ButtonType.OK, ButtonType.CANCEL);
        dialog.setTitle("Claim Item");
        dialog.setHeaderText("Claim: " + listing.getItemName());
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(button -> {
            DatabaseManager.createOffer(currentUser().getUsername(),
                    Integer.parseInt(listing.getId()), 0, "giveaway");
            notifySeller(listing, "Giveaway claimed",
                    currentUser().getFullName() + " claimed " + listing.getItemName());
            showInfo("Claim request submitted.");
        });
    }

    private void notifySeller(Listing listing, String title, String message) {
        DatabaseManager.createNotification(listing.getSellerUsername(),
                currentUser().getUsername(), title, message, "Deal");
    }

    @FXML
    private void showMyDeals() {
        setActive(btnDeals, "My Deals");
        renderOfferTable(false);
    }

    @FXML
    private void showHistory() {
        setActive(btnHistory, "Deal History");
        renderOfferTable(true);
    }

    private void renderOfferTable(boolean history) {
        contentPane.getChildren().clear();
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));
        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        addColumn(table, "Item", 0);
        addColumn(table, "Seller", 1);
        addColumn(table, "Type", 2);
        addColumn(table, "Status", 3);
        addColumn(table, history ? "Date" : "Action", 4);

        List<String[]> rows = new ArrayList<>();
        for (Map<String, Object> offer : DatabaseManager.fetchBuyerOffers(currentUser().getUsername())) {
            String status = String.valueOf(offer.get("listing_status"));
            boolean completed = List.of("SOLD", "LENT", "GIVEN").contains(status);
            if (history != completed) continue;
            String action = history ? String.valueOf(offer.get("posted_at"))
                    : "Offer: HK$" + offer.get("price");
            rows.add(new String[]{String.valueOf(offer.get("item_name")),
                    String.valueOf(offer.get("seller_name")),
                    String.valueOf(offer.get("listing_type")), status, action});
        }
        table.setItems(FXCollections.observableArrayList(rows));
        root.getChildren().add(table);
        if (rows.isEmpty()) {
            root.getChildren().add(new Label(history ? "No completed deals yet." : "No active deals yet."));
        }
        contentPane.getChildren().add(root);
    }

    private void addColumn(TableView<String[]> table, String title, int index) {
        TableColumn<String[], String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue()[index]));
        table.getColumns().add(column);
    }

    @FXML
    private void showFavourites() {
        setActive(btnFavourites, "Favourites & Following");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(8));
        FlowPane grid = new FlowPane(16, 16);
        for (Listing listing : DatabaseManager.fetchFavouriteListings(currentUser().getUsername())) {
            grid.getChildren().add(createListingCard(listing));
        }
        root.getChildren().add(grid);
        if (grid.getChildren().isEmpty()) {
            root.getChildren().add(new Label("You have no favourite listings."));
        }
        contentPane.getChildren().add(root);
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notification Board");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        List<Notification> notifications = DatabaseManager.fetchNotifications(currentUser().getUsername());
        for (Notification notification : notifications) {
            Label title = new Label(notification.getTitle());
            title.setStyle("-fx-font-weight: bold;");
            Label message = new Label(notification.getMessage());
            message.setWrapText(true);
            Label meta = new Label(notification.getCategory() + " · "
                    + notification.getTimestamp().format(DATE_TIME_FORMAT));
            root.getChildren().add(new VBox(4, title, message, meta));
        }
        if (notifications.isEmpty()) root.getChildren().add(new Label("No notifications yet."));
        contentPane.getChildren().add(new ScrollPane(root));
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "Manage Profile");
        contentPane.getChildren().clear();
        User user = currentUser();
        TextField name = new TextField(user.getFullName());
        ComboBox<String> residence = new ComboBox<>(
                FXCollections.observableArrayList(DataService.RESIDENCES));
        residence.setValue(user.getResidenceHall());
        PasswordField password = new PasswordField();
        PasswordField confirmation = new PasswordField();
        Button save = new Button("Save Changes");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(event -> saveProfile(user, name, residence, password, confirmation));
        VBox form = new VBox(10, new Label("Full Name"), name, new Label("Residence"),
                residence, new Label("New Password"), password,
                new Label("Confirm Password"), confirmation, save);
        form.setPadding(new Insets(8));
        form.setMaxWidth(420);
        form.getStyleClass().add("card");
        contentPane.getChildren().add(form);
    }

    private void saveProfile(User user, TextField name, ComboBox<String> residence,
                             PasswordField password, PasswordField confirmation) {
        String newName = name.getText().trim();
        if (newName.isEmpty()) {
            showError("Full name cannot be empty.");
            return;
        }
        String hash = null;
        if (!password.getText().isBlank()) {
            if (!password.getText().equals(confirmation.getText())) {
                showError("Passwords do not match.");
                return;
            }
            String result = DataService.checkPasswordStrength(password.getText());
            if (!"OK".equals(result)) {
                showError(result);
                return;
            }
            hash = DataService.hashPassword(password.getText());
        }
        DatabaseManager.updateUserProfile(user.getUsername(), newName, residence.getValue(),
                user.getBio(), user.getPreferredMeetup(), hash);
        user.setFullName(newName);
        user.setResidenceHall(residence.getValue());
        if (hash != null) user.setPasswordHash(hash);
        welcomeLabel.setText("Hello, " + newName);
        showInfo("Profile updated successfully.");
    }

    private void showInfo(String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
