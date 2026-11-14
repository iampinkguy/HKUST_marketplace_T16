package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SellerDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnListings, btnCreate, btnStats, btnReviews, btnNotifications, btnProfile;

    private Button currentActiveBtn;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnListings;
        showMyListings();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showMyListings() {
        setActive(btnListings, "My Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        List<Listing> myListings;
        try {
            myListings = DataService.getSellerListings();
        } catch (IllegalStateException | SecurityException e) {
            showProfileError("Could not load your listings: " + e.getMessage());
            return;
        }

        if (myListings.isEmpty()) {
            Label empty = new Label("You have not posted any listings yet.");
            empty.setStyle("-fx-text-fill: #5a5a7a;");
            root.getChildren().add(empty);
        } else {
            for (Listing l : myListings) {
                root.getChildren().add(createListingRow(l));
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private String requestDescription(Map<String, Object> request) {
        return switch ((String) request.get("request_type")) {
            case "OFFER" -> "Offer: HK$" + request.get("offer_price");
            case "BORROW" -> "Borrow request: " + request.get("proposed_days") + " days";
            case "CLAIM" -> "Giveaway claim";
            default -> "Request";
        };
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
                showProfileError("Could not load request chat: " + e.getMessage());
            }
        };
        send.setOnAction(event -> {
            try {
                DataService.sendRequestMessage(requestId, input.getText());
                input.clear();
                refresh.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                showProfileError("Could not send message: " + e.getMessage());
            }
        });
        dialog.getDialogPane().setContent(new VBox(8, scroll, new HBox(8, input, send)));
        refresh.run();
        dialog.showAndWait();
    }

    private HBox createListingRow(Listing l) {
        HBox row = new HBox(16);
        row.getStyleClass().add("card");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14));

        VBox info = new VBox(4);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label title = new Label(l.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label meta = new Label(l.getCategory() + " · " + l.getTypeDisplay() + " · " + l.getMeetupLocation());
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        info.getChildren().addAll(title, meta);

        Label status = new Label(l.getStatus().name().replace('_', ' '));
        status.getStyleClass().add("badge");
        switch (l.getStatus()) {
            case LIVE -> status.getStyleClass().add("badge-live");
            case PENDING_REVIEW -> status.getStyleClass().add("badge-pending");
            case REJECTED -> status.getStyleClass().add("badge-rejected");
            default -> status.getStyleClass().add("badge-sold");
        }

        Button editBtn = new Button("Edit");
        editBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        editBtn.setOnAction(e -> showListingForm(l));

        Button delBtn = new Button("Delete");
        delBtn.getStyleClass().addAll("btn-danger", "btn-sm");
        delBtn.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                    "Permanently delete '" + l.getTitle() + "'?", ButtonType.CANCEL, ButtonType.OK);
            conf.setHeaderText("Delete listing");
            conf.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    try {
                        DataService.deleteSellerListing(l.getId(), l.getReviewVersion());
                        new Alert(Alert.AlertType.INFORMATION, "Listing deleted successfully.").showAndWait();
                        showMyListings();
                    } catch (IllegalStateException | SecurityException ex) {
                        showProfileError("Could not delete listing: " + ex.getMessage());
                    }
                }
            });
        });

        Button viewBtn = new Button("View");
        viewBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        viewBtn.setOnAction(e -> showListingDetails(l));

        row.getChildren().addAll(info, status, viewBtn, editBtn, delBtn);
        return row;
    }

    @FXML
    private void showCreateListing() {
        showListingForm(null);
    }

    private void showListingForm(Listing existing) {
        boolean editing = existing != null;
        setActive(editing ? btnListings : btnCreate,
                editing ? "Edit Listing" : "Create New Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label(editing ? "Edit listing details" : "Post a new item");
        title.getStyleClass().add("section-title");

        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList("Sell", "Lend", "Give Away"));
        typeCombo.setPromptText("Listing type *");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        TextField nameField = new TextField();
        nameField.setPromptText("Item name *");
        ComboBox<String> catCombo = new ComboBox<>(FXCollections.observableArrayList(DataService.CATEGORIES));
        catCombo.setPromptText("Category *");
        catCombo.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> condCombo = new ComboBox<>(FXCollections.observableArrayList(DataService.CONDITIONS));
        condCombo.setPromptText("Condition *");
        condCombo.setMaxWidth(Double.MAX_VALUE);
        TextArea descArea = new TextArea();
        descArea.setPromptText("Description *");
        descArea.setPrefRowCount(3);

        TextField priceField = new TextField();
        priceField.setPromptText("Price (HK$) *");
        TextField daysField = new TextField();
        daysField.setPromptText("Lending duration (days) *");
        VBox priceGroup = new VBox(4, new Label("Price (Sell only) *"), priceField);
        VBox daysGroup = new VBox(4, new Label("Lending Days (Lend only) *"), daysField);
        priceGroup.setVisible(false);
        priceGroup.setManaged(false);
        daysGroup.setVisible(false);
        daysGroup.setManaged(false);
        typeCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            boolean isSell = "Sell".equals(newValue);
            boolean isLend = "Lend".equals(newValue);
            priceGroup.setVisible(isSell);
            priceGroup.setManaged(isSell);
            daysGroup.setVisible(isLend);
            daysGroup.setManaged(isLend);
            priceField.setStyle("");
            daysField.setStyle("");
        });

        ComboBox<String> meetupCombo = new ComboBox<>(FXCollections.observableArrayList(DataService.HKUST_LOCATIONS));
        meetupCombo.setPromptText("Meet-up location *");
        meetupCombo.setMaxWidth(Double.MAX_VALUE);

        List<Path> selectedPhotos = new ArrayList<>();
        VBox photoList = new VBox(6);
        Button choosePhotosBtn = new Button("Choose Photos (up to 6)");
        choosePhotosBtn.getStyleClass().add("btn-outline");
        Label photoNote = new Label(editing
                ? "Current photos: " + existing.getPhotoPaths().size() + ". Leave replacement unchecked to keep them."
                : "Photos: 0 / 6");
        photoNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
        CheckBox replacePhotos = new CheckBox("Replace current photos");
        replacePhotos.setVisible(editing);
        replacePhotos.setManaged(editing);
        choosePhotosBtn.setDisable(editing);
        replacePhotos.selectedProperty().addListener((observable, oldValue, selected) ->
                choosePhotosBtn.setDisable(!selected));
        choosePhotosBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose item photos");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    "Image files", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
            List<java.io.File> files = chooser.showOpenMultipleDialog(contentPane.getScene().getWindow());
            if (files == null || files.isEmpty()) return;
            List<Path> newPhotos = files.stream().map(java.io.File::toPath)
                    .filter(path -> !selectedPhotos.contains(path))
                    .distinct()
                    .toList();
            if (selectedPhotos.size() + newPhotos.size() > 6) {
                new Alert(Alert.AlertType.WARNING, "You can upload a maximum of 6 photos.").showAndWait();
                return;
            }
            selectedPhotos.addAll(newPhotos);
            refreshPhotoSelection(selectedPhotos, photoList, photoNote, editing);
        });

        if (editing) {
            typeCombo.setValue(switch (existing.getType()) {
                case SELL -> "Sell";
                case LEND -> "Lend";
                case GIVEAWAY -> "Give Away";
            });
            nameField.setText(existing.getTitle());
            catCombo.setValue(existing.getCategory());
            condCombo.setValue(existing.getCondition());
            descArea.setText(existing.getDescription());
            meetupCombo.setValue(existing.getMeetupLocation());
            if (existing.getPrice() != null) priceField.setText(existing.getPrice().toString());
            if (existing.getLendingDays() != null) daysField.setText(existing.getLendingDays().toString());
        }

        Button submitBtn = new Button(editing ? "Save Changes for Review" : "Submit for Review");
        submitBtn.getStyleClass().add("btn-gold");
        submitBtn.setOnAction(e -> {
            clearInvalidFields(typeCombo, nameField, catCombo, condCombo, descArea, priceField, daysField, meetupCombo);
            boolean valid = true;
            if (typeCombo.getValue() == null) { markInvalid(typeCombo); valid = false; }
            if (nameField.getText() == null || nameField.getText().isBlank()) { markInvalid(nameField); valid = false; }
            if (catCombo.getValue() == null) { markInvalid(catCombo); valid = false; }
            if (condCombo.getValue() == null) { markInvalid(condCombo); valid = false; }
            if (descArea.getText() == null || descArea.getText().isBlank()) { markInvalid(descArea); valid = false; }
            if (meetupCombo.getValue() == null) { markInvalid(meetupCombo); valid = false; }

            Double price = null;
            Integer lendingDays = null;
            if ("Sell".equals(typeCombo.getValue())) {
                try {
                    price = Double.parseDouble(priceField.getText().trim());
                    if (!Double.isFinite(price) || price < 0) throw new NumberFormatException();
                } catch (NumberFormatException | NullPointerException ex) {
                    markInvalid(priceField);
                    valid = false;
                }
            } else if ("Lend".equals(typeCombo.getValue())) {
                try {
                    lendingDays = Integer.parseInt(daysField.getText().trim());
                    if (lendingDays <= 0) throw new NumberFormatException();
                } catch (NumberFormatException | NullPointerException ex) {
                    markInvalid(daysField);
                    valid = false;
                }
            }
            if (!valid) {
                new Alert(Alert.AlertType.WARNING,
                        "Complete all required fields. Enter a non-negative price for Sell or a positive duration for Lend.").showAndWait();
                return;
            }

            Listing listing = new Listing();
            if (editing) {
                listing.setId(existing.getId());
                listing.setReviewVersion(existing.getReviewVersion());
            }
            listing.setTitle(nameField.getText().trim());
            listing.setDescription(descArea.getText().trim());
            listing.setCategory(catCombo.getValue());
            listing.setCondition(condCombo.getValue());
            listing.setMeetupLocation(meetupCombo.getValue());
            switch (typeCombo.getValue()) {
                case "Sell" -> { listing.setType(ListingType.SELL); listing.setPrice(price); }
                case "Lend" -> { listing.setType(ListingType.LEND); listing.setLendingDays(lendingDays); }
                case "Give Away" -> listing.setType(ListingType.GIVEAWAY);
            }

            List<Path> photosToSave = List.copyOf(selectedPhotos);
            boolean shouldReplacePhotos = replacePhotos.isSelected();
            submitBtn.setDisable(true);
            submitBtn.setText("Saving...");
            Task<Void> saveTask = new Task<>() {
                @Override
                protected Void call() {
                    if (editing) {
                        DataService.updateSellerListing(listing, photosToSave, shouldReplacePhotos);
                    } else {
                        DataService.submitListing(listing, photosToSave);
                    }
                    return null;
                }
            };
            saveTask.setOnSucceeded(event -> {
                if (editing) {
                    new Alert(Alert.AlertType.INFORMATION,
                            "Changes saved. Your listing is Pending Review until an admin re-approves it.").showAndWait();
                } else {
                    new Alert(Alert.AlertType.INFORMATION,
                            "Listing submitted successfully. Status: Pending Review.\nAn admin has been notified.").showAndWait();
                }
                showMyListings();
            });
            saveTask.setOnFailed(event -> {
                submitBtn.setDisable(false);
                submitBtn.setText(editing ? "Save Changes for Review" : "Submit for Review");
                Throwable error = saveTask.getException();
                String message = error == null || error.getMessage() == null
                        ? "An unexpected error occurred."
                        : error.getMessage();
                new Alert(Alert.AlertType.ERROR, "Could not save listing: " + message).showAndWait();
            });
            Thread saveThread = new Thread(saveTask, "seller-listing-save");
            saveThread.setDaemon(true);
            saveThread.start();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-outline");
        cancelBtn.setOnAction(e -> showMyListings());

        form.getChildren().addAll(title,
                new Label("Listing Type *"), typeCombo,
                new Label("Item Name *"), nameField,
                new Label("Category *"), catCombo,
                new Label("Condition *"), condCombo,
                new Label("Description *"), descArea,
                priceGroup, daysGroup,
                new Label("Meet-up Location *"), meetupCombo,
                replacePhotos, choosePhotosBtn, photoNote, photoList,
                new HBox(8, submitBtn, cancelBtn));

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void showListingDetails(Listing listing) {
        String priceOrDuration = switch (listing.getType()) {
            case SELL -> "Price: HK$ " + listing.getPrice();
            case LEND -> "Lending duration: " + listing.getLendingDays() + " days";
            case GIVEAWAY -> "Free";
        };
        String details = "Status: " + listing.getStatus().name().replace('_', ' ')
                + "\nType: " + listing.getTypeDisplay()
                + "\nCategory: " + listing.getCategory()
                + "\nCondition: " + listing.getCondition()
                + "\n" + priceOrDuration
                + "\nMeet-up: " + listing.getMeetupLocation()
                + "\nPhotos: " + listing.getPhotoPaths().size()
                + "\n\n" + listing.getDescription()
                + (listing.getRejectionReason() == null ? "" : "\n\nAdmin feedback: " + listing.getRejectionReason());
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Listing Details");
        dialog.setHeaderText(listing.getTitle());
        dialog.setResizable(true);

        VBox body = new VBox(12);
        body.setPadding(new Insets(12));
        Label fields = new Label(details);
        fields.setWrapText(true);
        body.getChildren().addAll(fields,
                new Label("Photos — select a photo to view it at full size"),
                createPhotoGallery(listing.getPhotoPaths()));

        Label requestsTitle = new Label("Incoming offers & requests");
        requestsTitle.getStyleClass().add("section-title");
        VBox requests = new VBox(10);
        Label requestFeedback = new Label();
        requestFeedback.setWrapText(true);
        body.getChildren().addAll(new Separator(), requestsTitle, requestFeedback, requests);

        Label chatTitle = new Label("Chat room");
        chatTitle.getStyleClass().add("section-title");
        body.getChildren().addAll(new Separator(), chatTitle, createSellerListingChat(listing));

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(720);
        scroll.setPrefViewportHeight(650);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        refreshListingRequests(listing, requests, requestFeedback);
        dialog.showAndWait();
    }

    private VBox createSellerListingChat(Listing listing) {
        VBox chat = new VBox(8);
        ComboBox<String> buyerSelector = new ComboBox<>();
        buyerSelector.setPromptText("Select buyer conversation");
        buyerSelector.setMaxWidth(Double.MAX_VALUE);
        ListView<Message> messages = new ListView<>();
        messages.setPrefHeight(220);
        messages.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                } else {
                    String timestamp = message.getTimestamp() == null ? ""
                            : message.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"));
                    setText(message.getSender_id() + " (" + timestamp + ")\n" + message.getContent());
                    setWrapText(true);
                }
            }
        });
        Label feedback = new Label();
        feedback.setWrapText(true);
        TextArea input = new TextArea();
        input.setPromptText("Reply to the buyer...");
        input.setPrefRowCount(2);
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn-outline");
        Button send = new Button("Send");
        send.getStyleClass().add("btn-gold");

        Runnable reloadMessages = () -> {
            try {
                String buyer = buyerSelector.getValue();
                if (buyer == null) {
                    messages.getItems().clear();
                    feedback.setText("Select a buyer to view their chat.");
                    return;
                }
                ChatHistory history = DataService.getSellerListingChat(listing.getId(), buyer);
                messages.getItems().setAll(history == null || history.getMessages() == null
                        ? List.of() : history.getMessages());
                if (!messages.getItems().isEmpty()) {
                    messages.scrollTo(messages.getItems().size() - 1);
                }
                feedback.setText(messages.getItems().isEmpty()
                        ? "No messages in this conversation yet." : "");
            } catch (IllegalStateException | SecurityException e) {
                feedback.setText("Could not load chat: " + e.getMessage());
            }
        };
        Runnable reloadBuyers = () -> {
            try {
                String selected = buyerSelector.getValue();
                List<String> buyers = DataService.getSellerListingChatBuyers(listing.getId());
                buyerSelector.getItems().setAll(buyers);
                if (selected != null && buyers.contains(selected)) {
                    buyerSelector.setValue(selected);
                } else if (!buyers.isEmpty()) {
                    buyerSelector.setValue(buyers.get(0));
                } else {
                    buyerSelector.setValue(null);
                }
                reloadMessages.run();
            } catch (IllegalStateException | SecurityException e) {
                feedback.setText("Could not load chat participants: " + e.getMessage());
            }
        };
        buyerSelector.valueProperty().addListener((observable, oldValue, newValue) ->
                reloadMessages.run());
        refresh.setOnAction(event -> reloadBuyers.run());
        send.setOnAction(event -> {
            try {
                String buyer = buyerSelector.getValue();
                if (buyer == null) {
                    feedback.setText("Select a buyer conversation before sending a message.");
                    return;
                }
                DataService.sendSellerListingChatMessage(listing.getId(), buyer, input.getText());
                input.clear();
                reloadMessages.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                feedback.setText("Could not send message: " + e.getMessage());
            }
        });
        chat.getChildren().addAll(new Label("Buyer"), buyerSelector, messages, feedback,
                input, new HBox(8, refresh, send));
        reloadBuyers.run();
        return chat;
    }

    private void refreshListingRequests(Listing listing, VBox requests, Label feedback) {
        requests.getChildren().clear();
        try {
            List<Map<String, Object>> incoming = DataService.getPendingSellerRequests().stream()
                    .filter(request -> listing.getId().equals(
                            String.valueOf(request.get("listing_id"))))
                    .toList();
            if (incoming.isEmpty()) {
                requests.getChildren().add(new Label("No pending offers or requests for this listing."));
                return;
            }
            for (Map<String, Object> request : incoming) {
                VBox card = new VBox(8);
                card.getStyleClass().add("card");
                card.setPadding(new Insets(12));
                Label buyer = new Label("Buyer: " + request.get("buyer_name")
                        + " (" + request.get("buyer_username") + ")");
                buyer.setStyle("-fx-font-weight: bold;");
                Label summary = new Label(requestDescription(request));
                Button accept = new Button("Accept");
                accept.getStyleClass().add("btn-gold");
                Button decline = new Button("Decline");
                decline.getStyleClass().add("btn-danger");
                Button counter = new Button("Counter Offer");
                counter.getStyleClass().add("btn-outline");
                counter.setDisable(!"OFFER".equals(request.get("request_type")));
                Button chat = new Button("Open Chat Room");
                chat.getStyleClass().add("btn-outline");
                int requestId = ((Number) request.get("request_id")).intValue();
                accept.setOnAction(event -> resolveListingRequest(
                        listing, requestId, "ACCEPT", null, requests, feedback));
                decline.setOnAction(event -> resolveListingRequest(
                        listing, requestId, "DECLINE", null, requests, feedback));
                counter.setOnAction(event -> {
                    TextInputDialog prompt = new TextInputDialog();
                    prompt.setTitle("Counter Offer");
                    prompt.setHeaderText("Counteroffer for " + listing.getTitle());
                    prompt.setContentText("Your price (HK$):");
                    prompt.showAndWait().ifPresent(value -> {
                        try {
                            resolveListingRequest(listing, requestId, "COUNTER",
                                    Double.parseDouble(value), requests, feedback);
                        } catch (NumberFormatException e) {
                            feedback.setText("Enter a valid counteroffer amount.");
                        }
                    });
                });
                chat.setOnAction(event -> showRequestChat(requestId));
                card.getChildren().addAll(buyer, summary, new HBox(8, accept, decline, counter, chat));
                requests.getChildren().add(card);
            }
        } catch (IllegalStateException | SecurityException e) {
            feedback.setText("Could not load incoming requests: " + e.getMessage());
        }
    }

    private void resolveListingRequest(Listing listing, int requestId, String action, Double price,
                                       VBox requests, Label feedback) {
        try {
            DataService.resolveSellerRequest(requestId, action, price);
            feedback.setText(switch (action) {
                case "ACCEPT" -> "Request accepted. The buyer has been notified.";
                case "DECLINE" -> "Request declined. The buyer has been notified.";
                default -> "Counteroffer sent to the buyer.";
            });
            refreshListingRequests(listing, requests, feedback);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            feedback.setText("Could not update request: " + e.getMessage());
        }
    }

    private FlowPane createPhotoGallery(List<String> paths) {
        FlowPane gallery = new FlowPane(10, 10);
        gallery.setPrefWrapLength(680);
        if (paths.isEmpty()) gallery.getChildren().add(new Label("No photos supplied."));
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            try {
                String uri = path.startsWith("file:")
                        ? path : Path.of(path).toAbsolutePath().toUri().toString();
                Image image = new Image(uri, false);
                if (image.isError()) throw new IllegalArgumentException("Photo could not be opened.");
                ImageView preview = new ImageView(image);
                preview.setPreserveRatio(true);
                preview.setFitWidth(300);
                preview.setFitHeight(230);
                Button photo = new Button("Photo " + (index + 1), preview);
                photo.setContentDisplay(ContentDisplay.TOP);
                photo.setOnAction(event -> {
                    Dialog<ButtonType> fullPhoto = new Dialog<>();
                    fullPhoto.setTitle("Listing Photo");
                    fullPhoto.setResizable(true);
                    ImageView enlargedImage = new ImageView(image);
                    enlargedImage.setPreserveRatio(true);
                    enlargedImage.setSmooth(true);
                    enlargedImage.setFitWidth(900);
                    enlargedImage.setFitHeight(650);
                    fullPhoto.getDialogPane().setContent(enlargedImage);
                    fullPhoto.getDialogPane().setPrefSize(940, 720);
                    fullPhoto.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    fullPhoto.showAndWait();
                });
                gallery.getChildren().add(photo);
            } catch (IllegalArgumentException ex) {
                gallery.getChildren().add(new Label("Photo " + (index + 1) + " unavailable."));
            }
        }
        return gallery;
    }

    private void refreshPhotoSelection(List<Path> selectedPhotos, VBox photoList, Label photoNote, boolean editing) {
        photoNote.setText((editing ? "Replacement photos: " : "Photos: ")
                + selectedPhotos.size() + " / 6");
        photoList.getChildren().clear();
        for (Path photo : selectedPhotos) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Label fileName = new Label(photo.getFileName().toString());
            HBox.setHgrow(fileName, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-outline", "btn-sm");
            remove.setOnAction(e -> {
                selectedPhotos.remove(photo);
                refreshPhotoSelection(selectedPhotos, photoList, photoNote, editing);
            });
            row.getChildren().addAll(fileName, remove);
            photoList.getChildren().add(row);
        }
    }

    private void clearInvalidFields(Control... controls) {
        for (Control control : controls) control.setStyle("");
    }

    private void markInvalid(Control control) {
        control.setStyle("-fx-border-color: #c62828; -fx-border-width: 1.5;");
    }

    @FXML
    private void showStats() {
        setActive(btnStats, "Seller Stats");
        contentPane.getChildren().clear();

        VBox root = new VBox(20);
        root.setPadding(new Insets(4));

        User user = SessionManager.getCurrentUser();
        List<Listing> myListings;
        try {
            myListings = DataService.getSellerListings();
        } catch (IllegalStateException | SecurityException e) {
            showProfileError("Could not load your listing stats: " + e.getMessage());
            return;
        }

        // Metric cards
        HBox metrics = new HBox(16);
        metrics.getChildren().addAll(
                metricCard("Listings", String.valueOf(myListings.size()), "#003974"),
                metricCard("Avg Rating", String.format("%.1f", user != null ? user.getAverageRating() : 0), "#A68137"),
                metricCard("Reviews", String.valueOf(user != null ? user.getRatingCount() : 0), "#2e7d32"),
                metricCard("Views (demo)", "128", "#1565c0")
        );
        root.getChildren().add(metrics);

        // Bar chart
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle("Listings by Category (demo data)");
        barChart.setLegendVisible(false);
        barChart.setPrefHeight(280);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.getData().add(new XYChart.Data<>("Textbooks", 3));
        series.getData().add(new XYChart.Data<>("Electronics", 5));
        series.getData().add(new XYChart.Data<>("Furniture", 2));
        series.getData().add(new XYChart.Data<>("Others", 1));
        barChart.getData().add(series);

        // Pie chart
        PieChart pie = new PieChart();
        pie.setTitle("Listing Status Distribution");
        pie.setPrefHeight(280);
        pie.getData().add(new PieChart.Data("Live", 4));
        pie.getData().add(new PieChart.Data("Pending", 1));
        pie.getData().add(new PieChart.Data("Sold", 2));

        HBox charts = new HBox(20, barChart, pie);
        root.getChildren().add(charts);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox metricCard(String label, String value, String color) {
        VBox card = new VBox(4);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(140);
        card.setPadding(new Insets(16));
        Label val = new Label(value);
        val.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        card.getChildren().addAll(val, lbl);
        return card;
    }

    @FXML
    private void showReviews() {
        setActive(btnReviews, "Reviews & Feedback");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        User user = SessionManager.getCurrentUser();
        List<Review> reviews = DummyDataService.getInstance()
                .getReviewsForSeller(user != null ? user.getUsername() : "");

        if (reviews.isEmpty()) {
            root.getChildren().add(new Label("No reviews yet."));
        } else {
            for (Review r : reviews) {
                VBox item = new VBox(6);
                item.getStyleClass().add("card");
                Label stars = new Label("★".repeat(r.getRating()) + "☆".repeat(5 - r.getRating()));
                stars.setStyle("-fx-text-fill: #A68137; -fx-font-size: 16px;");
                Label comment = new Label(r.getComment());
                comment.setWrapText(true);
                Label meta = new Label("by @" + r.getBuyerUsername() + " · " + r.getListingTitle());
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
                HBox actions = new HBox(8);
                Button reply = new Button("Reply");
                reply.getStyleClass().addAll("btn-outline", "btn-sm");
                reply.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Reply sent to buyer (demo).").showAndWait());
                Button flag = new Button("Flag");
                flag.getStyleClass().addAll("btn-danger", "btn-sm");
                flag.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Review flagged for admin (demo).").showAndWait());
                actions.getChildren().addAll(reply, flag);
                item.getChildren().addAll(stars, comment, meta, actions);
                root.getChildren().add(item);
            }
        }
        contentPane.getChildren().add(new ScrollPane(root));
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notifications");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        User user = SessionManager.getCurrentUser();
        if (user == null) {
            showProfileError("Could not load notifications because you are not logged in.");
            return;
        }

        List<Notification> notifs = DatabaseManager.fetchNotifications(user.getUsername());
        if (notifs.isEmpty()) {
            root.getChildren().add(new Label("No notifications found."));
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                Label t = new Label(n.getTitle());
                t.setStyle("-fx-font-weight: bold;");
                Label status = new Label(n.isRead() ? "Read" : "Unread");
                status.setStyle(n.isRead()
                        ? "-fx-text-fill: #9aa0b0; -fx-font-size: 11px;"
                        : "-fx-text-fill: #A68137; -fx-font-size: 11px; -fx-font-weight: bold;");
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                Button view = new Button("View details");
                view.getStyleClass().addAll("btn-outline", "btn-sm");
                view.setOnAction(e -> viewNotification(user, n));
                item.getChildren().addAll(t, status, meta, view);
                root.getChildren().add(item);
            }
        }
        contentPane.getChildren().add(new ScrollPane(root));
    }

    private void viewNotification(User user, Notification notification) {
        Alert details = new Alert(Alert.AlertType.INFORMATION, notification.getMessage(), ButtonType.CLOSE);
        details.setTitle("Notification Details");
        details.setHeaderText(notification.getTitle() + " · " + notification.getCategory()
                + " · " + notification.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")));
        details.showAndWait();

        if (notification.isRead()) return;
        try {
            DatabaseManager.markNotificationRead(user.getUsername(), notification.getId());
        } catch (IllegalStateException e) {
            showProfileError("Could not mark notification as read: " + e.getMessage());
            return;
        }
        notification.setRead(true);
        showNotifications();
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User user = SessionManager.getCurrentUser();
        VBox form = new VBox(12);
        form.setMaxWidth(420);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        form.getChildren().add(new Label("Edit Seller Profile") {{ getStyleClass().add("section-title"); }});

        TextField name = new TextField(user != null ? user.getFullName() : "");
        PasswordField pass = new PasswordField();
        pass.setPromptText("New password (optional)");
        PasswordField confirmation = new PasswordField();
        confirmation.setPromptText("Confirm new password");
        TextArea bio = new TextArea(user != null && user.getBio() != null ? user.getBio() : "");
        bio.setPrefRowCount(3);
        ComboBox<String> meetup = new ComboBox<>(FXCollections.observableArrayList(DummyDataService.HKUST_LOCATIONS));
        if (user != null && user.getPreferredMeetup() != null) meetup.setValue(user.getPreferredMeetup());
        meetup.setMaxWidth(Double.MAX_VALUE);

        Button save = new Button("Save Changes");
        save.getStyleClass().add("btn-gold");
        save.setOnAction(e -> saveProfile(user, name, pass, confirmation, bio, meetup));
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-outline");
        cancel.setOnAction(e -> showProfile());

        form.getChildren().addAll(
                new Label("Full Name"), name,
                new Label("New Password"), pass,
                new Label("Confirm New Password"), confirmation,
                new Label("Bio"), bio,
                new Label("Preferred Meet-up"), meetup,
                new HBox(8, save, cancel));
        contentPane.getChildren().add(form);
    }

    private void saveProfile(User user, TextField name, PasswordField password,
                             PasswordField confirmation, TextArea bio, ComboBox<String> meetup) {
        if (user == null) {
            showProfileError("Could not save your profile because you are not logged in.");
            return;
        }

        String fullName = name.getText().trim();
        if (fullName.isEmpty()) {
            showProfileError("Full name cannot be empty.");
            return;
        }

        String passwordHash = null;
        String newPassword = password.getText();
        if (!newPassword.isEmpty()) {
            if (!newPassword.equals(confirmation.getText())) {
                showProfileError("Passwords do not match.");
                return;
            }
            String passwordCheck = DataService.checkPasswordStrength(newPassword);
            if (!"OK".equals(passwordCheck)) {
                showProfileError(passwordCheck);
                return;
            }
            passwordHash = DataService.hashPassword(newPassword);
        }

        String updatedBio = bio.getText() == null || bio.getText().isBlank()
                ? null : bio.getText().trim();
        String updatedMeetup = meetup.getValue();
        try {
            DatabaseManager.updateUserProfile(user.getUsername(), fullName, user.getResidenceHall(),
                    updatedBio, updatedMeetup, passwordHash);
        } catch (IllegalStateException e) {
            showProfileError("Could not save your profile: " + e.getMessage());
            return;
        }

        user.setFullName(fullName);
        user.setBio(updatedBio);
        user.setPreferredMeetup(updatedMeetup);
        if (passwordHash != null) user.setPasswordHash(passwordHash);
        welcomeLabel.setText("Hello, " + fullName);
        new Alert(Alert.AlertType.INFORMATION, "Profile updated successfully.").showAndWait();
    }

    private void showProfileError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
