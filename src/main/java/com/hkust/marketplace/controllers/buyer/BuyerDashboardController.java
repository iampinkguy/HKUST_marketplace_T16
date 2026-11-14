package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingStatus;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Message;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BuyerDashboardController {

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
        currentActiveBtn = btnBrowse;
        showBrowse();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) {
            currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        }
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showBrowse() {
        setActive(btnBrowse, "Browse Marketplace");
        contentPane.getChildren().clear();

        VBox root = new VBox(16);
        root.setPadding(new Insets(4));

        // Filter bar
        HBox filterBar = new HBox(12);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> categoryFilter = new ComboBox<>(FXCollections.observableArrayList("All Categories"));
        categoryFilter.getItems().addAll(DataService.CATEGORIES);
        categoryFilter.getSelectionModel().selectFirst();
        ComboBox<String> typeFilter = new ComboBox<>(FXCollections.observableArrayList("All Types", "Sell", "Lend", "Giveaway"));
        typeFilter.getSelectionModel().selectFirst();
        TextField searchField = new TextField();
        searchField.setPromptText("Search listings...");
        searchField.setPrefWidth(220);
        Button searchBtn = new Button("Search");
        searchBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        filterBar.getChildren().addAll(
                new Label("Category:"), categoryFilter,
                new Label("Type:"), typeFilter,
                searchField, searchBtn
        );
        root.getChildren().add(filterBar);

        // Listing grid
        FlowPane grid = new FlowPane(16, 16);
        grid.setPrefWrapLength(900);

        List<Listing> live = DataService.getLiveListings();

        if (live.isEmpty()) {
            Label empty = new Label("No live listings at the moment.");
            empty.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 15px;");
            root.getChildren().add(empty);
        } else {
            for (Listing listing : live) {
                grid.getChildren().add(createListingCard(listing));
            }
            root.getChildren().add(grid);
        }

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        contentPane.getChildren().add(scroll);
    }

    private VBox createListingCard(Listing listing) {
        VBox card = new VBox(8);
        card.getStyleClass().add("listing-card");
        card.setPrefWidth(260);
        card.setMinHeight(200);

        // Type badge + price
        HBox top = new HBox();
        top.setAlignment(Pos.CENTER_LEFT);
        Label typeBadge = new Label(listing.getTypeDisplay());
        typeBadge.getStyleClass().add("badge");
        if (listing.getType() == ListingType.SELL) typeBadge.getStyleClass().add("badge-sold");
        else if (listing.getType() == ListingType.LEND) typeBadge.getStyleClass().add("badge-pending");
        else typeBadge.getStyleClass().add("badge-live");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label price = new Label(listing.getPriceDisplay());
        price.setStyle("-fx-font-weight: bold; -fx-text-fill: #003974; -fx-font-size: 16px;");
        top.getChildren().addAll(typeBadge, spacer, price);

        Label title = new Label(listing.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #1a1a2e;");
        title.setWrapText(true);

        Label meta = new Label(listing.getCategory() + " · " + listing.getCondition());
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        Label seller = new Label("Seller: " + listing.getSellerFullName());
        seller.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        Label location = new Label("📍 " + listing.getMeetupLocation());
        location.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        String postedDate = listing.getPostedAt() == null
                ? "Date unavailable"
                : listing.getPostedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        Label date = new Label("Posted: " + postedDate);
        date.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");

        Button actionBtn = new Button();
        actionBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        switch (listing.getType()) {
            case SELL -> {
                actionBtn.setText("Make Offer");
                actionBtn.setOnAction(e -> showOfferDialog(listing));
            }
            case LEND -> {
                actionBtn.setText("Request Borrow");
                actionBtn.setOnAction(e -> showBorrowDialog(listing));
            }
            case GIVEAWAY -> {
                actionBtn.setText("Claim Item");
                actionBtn.setOnAction(e -> claimItem(listing));
            }
        }

        Button chatboxBtn = new Button();
        chatboxBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        chatboxBtn.setText("Chatbox");
        chatboxBtn.setOnAction(e -> showChatbox(listing));

        card.getChildren().addAll(top, title, meta, seller, location, date, actionBtn, chatboxBtn);
        return card;
    }

    private void showChatbox(Listing listing) {
        User user = SessionManager.getCurrentUser();
        if (user == null) {
            new Alert(Alert.AlertType.ERROR, "You must be logged in to use the chatbox.").showAndWait();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Chatbox");
        dialog.setHeaderText("Chat with " + listing.getSellerFullName()
                + " about " + listing.getTitle());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<Message> messageList = new ListView<>();
        messageList.setPrefHeight(320);
        messageList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                } else {
                    String timestamp = message.getTimestamp() == null
                            ? ""
                            : message.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));
                    setText(message.getSender_id() + " (" + timestamp + ")\n" + message.getContent());
                    setWrapText(true);
                }
            }
        });

        TextArea input = new TextArea();
        input.setPromptText("Type a message...");
        input.setPrefRowCount(3);
        Button send = new Button("Send");
        send.getStyleClass().add("btn-primary");

        Runnable refreshMessages = () -> {
            try {
                ChatHistory history = DataService.getBuyerListingChat(listing.getId());
                messageList.getItems().setAll(history == null || history.getMessages() == null
                        ? List.of()
                        : history.getMessages());
                if (!messageList.getItems().isEmpty()) {
                    messageList.scrollTo(messageList.getItems().size() - 1);
                }
            } catch (IllegalStateException | SecurityException ex) {
                new Alert(Alert.AlertType.ERROR, "Could not load chat: " + ex.getMessage()).showAndWait();
            }
        };

        send.setOnAction(event -> {
            String content = input.getText().trim();
            if (content.isEmpty()) {
                return;
            }
            try {
                DataService.sendBuyerListingChatMessage(listing.getId(), content);
                input.clear();
                refreshMessages.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                new Alert(Alert.AlertType.ERROR, "Could not send message: " + ex.getMessage()).showAndWait();
            }
        });

        VBox content = new VBox(8, messageList, input, send);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);
        refreshMessages.run();
        dialog.showAndWait();
    }

    private void showOfferDialog(Listing listing) {
        TextInputDialog dialog = new TextInputDialog(String.valueOf(listing.getPrice() != null ? listing.getPrice() : 0));
        dialog.setTitle("Make an Offer");
        dialog.setHeaderText("Offer for: " + listing.getTitle());
        dialog.setContentText("Your offer price (HK$):");
        dialog.showAndWait().ifPresent(priceStr -> {
            try {
                double price = Double.parseDouble(priceStr);
                DataService.submitBuyerRequest(listing.getId(), "OFFER", price, null);
                new Alert(Alert.AlertType.INFORMATION,
                        "Your offer has been sent to " + listing.getSellerFullName() + ".").showAndWait();
            } catch (NumberFormatException ex) {
                new Alert(Alert.AlertType.ERROR, "Invalid price.").showAndWait();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            }
        });
    }

    private void showBorrowDialog(Listing listing) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Borrow Request");
        alert.setHeaderText("Request to borrow: " + listing.getTitle());
        alert.setContentText("Proposed duration: " + (listing.getLendingDays() != null ? listing.getLendingDays() + " days" : "N/A") +
                "\n\nSend borrow request to seller?");
        alert.showAndWait().ifPresent(resp -> {
            if (resp == ButtonType.OK) {
                TextInputDialog duration = new TextInputDialog(String.valueOf(
                        listing.getLendingDays() == null ? 1 : listing.getLendingDays()));
                duration.setTitle("Borrow Request");
                duration.setHeaderText("Proposed borrowing duration");
                duration.setContentText("Number of days:");
                duration.showAndWait().ifPresent(days -> {
                    try {
                        DataService.submitBuyerRequest(listing.getId(), "BORROW", null, Integer.parseInt(days));
                        new Alert(Alert.AlertType.INFORMATION, "Borrow request sent to the seller.").showAndWait();
                    } catch (NumberFormatException ex) {
                        new Alert(Alert.AlertType.ERROR, "Enter a valid borrowing duration.").showAndWait();
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                        new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                    }
                });
            }
        });
    }

    private void claimItem(Listing listing) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Claim Item");
        alert.setHeaderText("Claim: " + listing.getTitle());
        alert.setContentText("Are you sure you want to claim this giveaway item?");
        alert.showAndWait().ifPresent(resp -> {
            if (resp == ButtonType.OK) {
                try {
                    DataService.submitBuyerRequest(listing.getId(), "CLAIM", null, null);
                    new Alert(Alert.AlertType.INFORMATION, "Claim request sent to the seller.").showAndWait();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                }
            }
        });
    }

    @FXML
    private void showMyDeals() {
        setActive(btnDeals, "My Deals");
        contentPane.getChildren().clear();

        VBox root = new VBox(16);
        root.setPadding(new Insets(4));

        Label info = new Label("Active offers, borrow requests and claims");
        info.setStyle("-fx-text-fill: #5a5a7a;");
        root.getChildren().add(info);

        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<String[], String> colTitle = new TableColumn<>("Item");
        colTitle.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[0]));
        TableColumn<String[], String> colSeller = new TableColumn<>("Seller");
        colSeller.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[1]));
        TableColumn<String[], String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[2]));
        TableColumn<String[], String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[3]));
        TableColumn<String[], String> colAction = new TableColumn<>("Action");
        colAction.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[4]));

        table.getColumns().addAll(colTitle, colSeller, colType, colStatus, colAction);

        User user = SessionManager.getCurrentUser();
        List<Map<String, Object>> offers;
        try {
            offers = user == null ? List.of() : DataService.getBuyerRequests();
        } catch (IllegalStateException | SecurityException e) {
            new Alert(Alert.AlertType.ERROR, "Could not load your requests: " + e.getMessage()).showAndWait();
            offers = List.of();
        }
        List<Map<String, Object>> requestRows = offers;
        List<String[]> rows = new java.util.ArrayList<>();
        for (Map<String, Object> offer : requestRows) {
            String requestType = String.valueOf(offer.get("request_type"));
            String details = switch (requestType) {
                case "OFFER" -> "Sell · HK$" + offer.get("offer_price");
                case "BORROW" -> "Borrow · " + offer.get("proposed_days") + " days";
                case "CLAIM" -> "Giveaway claim";
                default -> requestType;
            };
            String status = String.valueOf(offer.get("status")).replace('_', ' ');
            if (offer.get("counter_price") != null) status += " · Counter: HK$" + offer.get("counter_price");
            rows.add(new String[]{
                    String.valueOf(offer.get("item_name")),
                    String.valueOf(offer.get("seller_name")),
                    details,
                    status,
                    "Open Chat"
            });
        }
        table.setItems(FXCollections.observableArrayList(rows));

        Button openChat = new Button("Open selected request chat");
        openChat.getStyleClass().add("btn-outline");
        openChat.setDisable(true);
        table.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) ->
                openChat.setDisable(selected.intValue() < 0));
        openChat.setOnAction(event -> {
            int selected = table.getSelectionModel().getSelectedIndex();
            if (selected >= 0 && selected < requestRows.size()) {
                showRequestChat(((Number) requestRows.get(selected).get("request_id")).intValue());
            }
        });
        root.getChildren().add(table);
        root.getChildren().add(openChat);
        Label note = new Label("💡 Self-return for borrowed items and mark-complete are available here in the full version.");
        note.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
        root.getChildren().add(note);

        contentPane.getChildren().add(root);
    }

    private void showRequestChat(int requestId) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Request Chat");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox messages = new VBox(8);
        messages.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(messages);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(300);
        TextField input = new TextField();
        input.setPromptText("Write a message...");
        Button send = new Button("Send");
        send.getStyleClass().add("btn-gold");
        Runnable refresh = () -> {
            messages.getChildren().clear();
            try {
                for (Map<String, Object> message : DataService.getRequestMessages(requestId)) {
                    Label line = new Label(message.get("sender_username") + ": " + message.get("content"));
                    line.setWrapText(true);
                    messages.getChildren().add(line);
                }
                scroll.setVvalue(1);
            } catch (IllegalStateException | SecurityException e) {
                new Alert(Alert.AlertType.ERROR, "Could not load request chat: " + e.getMessage()).showAndWait();
            }
        };
        send.setOnAction(event -> {
            try {
                DataService.sendRequestMessage(requestId, input.getText());
                input.clear();
                refresh.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                new Alert(Alert.AlertType.ERROR, "Could not send message: " + e.getMessage()).showAndWait();
            }
        });
        dialog.getDialogPane().setContent(new VBox(8, scroll, new HBox(8, input, send)));
        refresh.run();
        dialog.showAndWait();
    }

    @FXML
    private void showHistory() {
        setActive(btnHistory, "Deal History");
        contentPane.getChildren().clear();

        VBox root = new VBox(16);
        root.setPadding(new Insets(4));

        HBox filters = new HBox(12);
        filters.setAlignment(Pos.CENTER_LEFT);
        DatePicker from = new DatePicker();
        from.setPromptText("From");
        DatePicker to = new DatePicker();
        to.setPromptText("To");
        TextField search = new TextField();
        search.setPromptText("Search by title / seller...");
        filters.getChildren().addAll(new Label("Date range:"), from, new Label("–"), to, search);
        root.getChildren().add(filters);

        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<String[], String> c1 = new TableColumn<>("Item Title");
        c1.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[0]));
        TableColumn<String[], String> c2 = new TableColumn<>("Seller");
        c2.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[1]));
        TableColumn<String[], String> c3 = new TableColumn<>("Deal Date");
        c3.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[2]));
        TableColumn<String[], String> c4 = new TableColumn<>("Type");
        c4.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[3]));
        TableColumn<String[], String> c5 = new TableColumn<>("Outcome");
        c5.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue()[4]));
        table.getColumns().addAll(c1, c2, c3, c4, c5);
        User user = SessionManager.getCurrentUser();
        List<Map<String, Object>> offers = user == null
                ? List.of()
                : DatabaseManager.fetchBuyerOffers(user.getUsername());
        List<String[]> rows = new java.util.ArrayList<>();
        for (Map<String, Object> offer : offers) {
            rows.add(new String[]{
                    String.valueOf(offer.get("item_name")),
                    String.valueOf(offer.get("seller_name")),
                    String.valueOf(offer.get("posted_date")),
                    String.valueOf(offer.get("listing_type")),
                    "Submitted"
            });
        }
        table.setItems(FXCollections.observableArrayList(rows));
        root.getChildren().add(table);
        contentPane.getChildren().add(root);
    }

    @FXML
    private void showFavourites() {
        setActive(btnFavourites, "Favourites & Following");
        contentPane.getChildren().clear();
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));
        FlowPane grid = new FlowPane(16, 16);
        grid.setPrefWrapLength(900);
        User user = SessionManager.getCurrentUser();
        List<Listing> favourites = user == null
                ? List.of()
                : DatabaseManager.fetchFavouriteListings(user.getUsername());
        if (favourites.isEmpty()) {
            root.getChildren().add(new Label("No favourite listings yet."));
        } else {
            for (Listing listing : favourites) {
                grid.getChildren().add(createListingCard(listing));
            }
            root.getChildren().add(grid);
        }
        contentPane.getChildren().add(root);
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notification Board");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        User user = SessionManager.getCurrentUser();
        List<Notification> notifs = user == null
                ? List.of()
                : DataService.getNotificationsFor(user.getUsername());

        if (notifs.isEmpty()) {
            Label empty = new Label("No notifications yet.");
            empty.setStyle("-fx-text-fill: #5a5a7a;");
            root.getChildren().add(empty);
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                Label title = new Label(n.getTitle());
                title.setStyle("-fx-font-weight: bold; -fx-text-fill: #1a1a2e;");
                Label msg = new Label(n.getMessage());
                msg.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 13px;");
                msg.setWrapText(true);
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                item.getChildren().addAll(title, msg, meta);
                root.getChildren().add(item);
            }
        }

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "Manage Profile");
        contentPane.getChildren().clear();

        User user = SessionManager.getCurrentUser();
        VBox form = new VBox(14);
        form.setPadding(new Insets(8));
        form.setMaxWidth(420);
        form.getStyleClass().add("card");

        Label title = new Label("Edit your profile");
        title.getStyleClass().add("section-title");

        TextField nameField = new TextField(user != null ? user.getFullName() : "");
        nameField.setPromptText("Full Name");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("New password (leave blank to keep)");
        PasswordField confirmPass = new PasswordField();
        confirmPass.setPromptText("Confirm new password");
        ComboBox<String> residence = new ComboBox<>(FXCollections.observableArrayList(DataService.RESIDENCES));
        if (user != null && user.getResidenceHall() != null) {
            residence.setValue(user.getResidenceHall());
        }

        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {

            boolean changePassword = !newPass.getText().isBlank();
            if (changePassword) {
                String password = newPass.getText();
                if (!password.equals(confirmPass.getText())) {
                    new Alert(Alert.AlertType.ERROR, "Passwords do not match!").showAndWait();
                    return;
                }
                
                String pwdCheck = DataService.checkPasswordStrength(password);
                if (!"OK".equals(pwdCheck)) {
                    new Alert(Alert.AlertType.ERROR, "Password is too weak! Must be at least 8 characters, include uppercase, lowercase, and a number.").showAndWait();
                    return;
                }

            }

            DatabaseManager.updateUserProfile(user.getUsername(), nameField.getText().trim(),
                    residence.getValue(), user.getBio(), user.getPreferredMeetup(),
                    changePassword ? DataService.hashPassword(newPass.getText()) : null);
            user.setFullName(nameField.getText().trim());
            user.setResidenceHall(residence.getValue());

            new Alert(Alert.AlertType.INFORMATION, "Profile updated successfully.").showAndWait();
        });

        form.getChildren().addAll(title,
                new Label("Full Name"), nameField,
                new Label("New Password"), newPass,
                new Label("Confirm Password"), confirmPass,
                new Label("Residence"), residence,
                saveBtn);

        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
