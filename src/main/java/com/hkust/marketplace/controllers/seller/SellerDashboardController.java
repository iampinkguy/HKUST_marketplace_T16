package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DummyDataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
        editBtn.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Edit form would open here.\n(Demo placeholder)").showAndWait());

        Button delBtn = new Button("Delete");
        delBtn.getStyleClass().addAll("btn-danger", "btn-sm");
        delBtn.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Delete this listing?");
            conf.showAndWait().ifPresent(r -> {
                if (r == ButtonType.OK) {
                    DummyDataService.getInstance().removeListing(l.getId());
                    showMyListings();
                }
            });
        });

        row.getChildren().addAll(info, status, editBtn, delBtn);
        return row;
    }

    @FXML
    private void showCreateListing() {
        setActive(btnCreate, "Create New Listing");
        contentPane.getChildren().clear();

        VBox form = new VBox(12);
        form.setMaxWidth(520);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));

        Label title = new Label("Post a new item");
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
        Label photoNote = new Label("Photos: 0 / 6");
        photoNote.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
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
            refreshPhotoSelection(selectedPhotos, photoList, photoNote);
        });

        Button submitBtn = new Button("Submit for Review");
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
            User user = SessionManager.getCurrentUser();
            Listing listing = new Listing();
            listing.setTitle(nameField.getText().trim());
            listing.setDescription(descArea.getText().trim());
            listing.setCategory(catCombo.getValue());
            listing.setCondition(condCombo.getValue());
            listing.setMeetupLocation(meetupCombo.getValue());
            listing.setSellerUsername(user == null ? null : user.getUsername());
            listing.setSellerFullName(user == null ? null : user.getFullName());
            listing.setStatus(ListingStatus.PENDING_REVIEW);
            switch (typeCombo.getValue()) {
                case "Sell" -> { listing.setType(ListingType.SELL); listing.setPrice(price); }
                case "Lend" -> { listing.setType(ListingType.LEND); listing.setLendingDays(lendingDays); }
                case "Give Away" -> listing.setType(ListingType.GIVEAWAY);
            }
            try {
                DataService.submitListing(listing, selectedPhotos);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                new Alert(Alert.AlertType.ERROR, "Could not submit listing: " + ex.getMessage()).showAndWait();
                return;
            }
            new Alert(Alert.AlertType.INFORMATION,
                    "Listing submitted successfully. Status: Pending Review.\nAn admin has been notified.").showAndWait();
            showMyListings();
        });

        form.getChildren().addAll(title,
                new Label("Listing Type *"), typeCombo,
                new Label("Item Name *"), nameField,
                new Label("Category *"), catCombo,
                new Label("Condition *"), condCombo,
                new Label("Description *"), descArea,
                priceGroup, daysGroup,
                new Label("Meet-up Location *"), meetupCombo,
                choosePhotosBtn, photoNote, photoList, submitBtn);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void refreshPhotoSelection(List<Path> selectedPhotos, VBox photoList, Label photoNote) {
        photoNote.setText("Photos: " + selectedPhotos.size() + " / 6");
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
                refreshPhotoSelection(selectedPhotos, photoList, photoNote);
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
