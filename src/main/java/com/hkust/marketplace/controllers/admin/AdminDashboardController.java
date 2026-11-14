package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class AdminDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnReview, btnAllListings, btnUsers, btnDeals, btnRatings, btnNotifications, btnProfile;

    private Button currentActiveBtn;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Admin: " + user.getFullName());
        currentActiveBtn = btnReview;
        showReview();
    }

    private void setActive(Button btn, String title) {
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    @FXML
    private void showReview() {
        setActive(btnReview, "Listing Review & Approval");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        List<Listing> pending;
        try {
            pending = DataService.getPendingListings();
        } catch (IllegalStateException | IllegalArgumentException | SecurityException e) {
            Label error = new Label("Could not load pending listings. " + e.getMessage());
            error.setWrapText(true);
            Button retry = new Button("Retry");
            retry.setOnAction(event -> showReview());
            root.getChildren().addAll(error, retry);
            contentPane.getChildren().add(root);
            return;
        }
        if (pending.isEmpty()) {
            Label empty = new Label("No listings awaiting review.");
            empty.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 15px;");
            root.getChildren().add(empty);
        } else {
            for (Listing l : pending) {
                root.getChildren().add(createPendingCard(l));
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private VBox createPendingCard(Listing l) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        Label title = new Label(l.getTitle());
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        Label meta = new Label(String.format("Seller: %s  ·  %s  ·  %s  ·  %s",
                sellerDisplay(l), l.getCategory(), typeDisplay(l), l.getMeetupLocation()));
        meta.setWrapText(true);
        meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");

        Label submitted = new Label("Submitted: " + formatDate(l.getSubmittedAt()) + "  ·  Status: Pending Review");
        submitted.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");

        Button review = new Button("Review details");
        review.setId("review-listing-" + l.getId());
        review.getStyleClass().addAll("btn-primary", "btn-sm");
        review.setOnAction(event -> showListingDetails(l));
        card.getChildren().addAll(title, meta, submitted, createPhotoGallery(l.getPhotoPaths(), true), review);
        return card;
    }

    private void showListingDetails(Listing queued) {
        Listing listing;
        try {
            listing = DataService.getListingForReview(queued.getId(), queued.getReviewVersion());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showReviewFailure(queued, ex);
            showReview();
            return;
        }
        Dialog<ButtonType> details = new Dialog<>();
        details.setTitle("Review Listing");
        details.setHeaderText(listing.getTitle() + " (#" + listing.getId() + ")");
        details.setResizable(true);
        VBox body = new VBox(12);
        body.setPadding(new Insets(12));
        Label fields = new Label(listingDetailsText(listing));
        fields.setWrapText(true);
        body.getChildren().addAll(fields, new Label("Photos — select a photo to view it at full size"),
                createPhotoGallery(listing.getPhotoPaths(), false));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(720);
        scroll.setPrefViewportHeight(520);
        details.getDialogPane().setContent(scroll);
        ButtonType approve = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        ButtonType reject = new ButtonType("Reject", ButtonBar.ButtonData.OTHER);
        details.getDialogPane().getButtonTypes().addAll(approve, reject, ButtonType.CANCEL);
        details.getDialogPane().lookupButton(approve).setId("approve-listing");
        details.getDialogPane().lookupButton(reject).setId("reject-listing");
        details.getDialogPane().lookupButton(approve).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            confirmListingDecision(details, listing, true);
        });
        details.getDialogPane().lookupButton(reject).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            confirmListingDecision(details, listing, false);
        });
        details.showAndWait();
    }

    private void confirmListingDecision(Dialog<?> details, Listing listing, boolean approve) {
        String reason = null;
        if (!approve) {
            TextInputDialog reasonDialog = new TextInputDialog();
            reasonDialog.setTitle("Rejection Reason");
            reasonDialog.setHeaderText("Enter a written reason for rejecting '" + listing.getTitle() + "'.");
            reasonDialog.setContentText("Reason:");
            reasonDialog.getEditor().setId("rejection-reason");
            reasonDialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
                if (reasonDialog.getEditor().getText().isBlank()) {
                    event.consume();
                    reasonDialog.setHeaderText("A nonblank rejection reason is required.");
                    reasonDialog.getEditor().requestFocus();
                }
            });
            Optional<String> entered = reasonDialog.showAndWait();
            if (entered.isEmpty()) {
                details.close();
                return;
            }
            reason = entered.get().trim();
        }
        String decision = approve ? "Approve" : "Reject";
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Listing Decision");
        confirmation.setHeaderText(decision + " '" + listing.getTitle() + "' (#" + listing.getId() + ")?");
        confirmation.setContentText("Seller: " + sellerDisplay(listing) + "\nType: " + typeDisplay(listing)
                + "\nDecision: " + decision + (approve ? " — publish listing" : "\nReason: " + reason));
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            details.close();
            return;
        }
        try {
            if (approve) DataService.approveListing(listing.getId(), listing.getReviewVersion());
            else DataService.rejectListing(listing.getId(), reason, listing.getReviewVersion());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showReviewFailure(listing, ex);
            details.close();
            showReview();
            return;
        }
        showReview();
        Alert success = new Alert(Alert.AlertType.INFORMATION, approve
                ? "Listing approved and published. An approval notification has been saved for the seller."
                : "Listing rejected. A notification containing the reason has been saved for the seller.");
        success.setTitle("Listing Decision Saved");
        success.showAndWait();
        details.close();
    }

    private void showReviewFailure(Listing displayed, RuntimeException failure) {
        String message = failure.getMessage();
        try {
            Listing current = DataService.getListingForAdmin(displayed.getId());
            message += current == null ? "\n\nCurrent state: listing no longer exists."
                    : "\n\nCurrent listing state:\n" + listingDetailsText(current);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            message += "\nCurrent listing state could not be loaded. " + ex.getMessage();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Listing Decision Not Saved");
        alert.setHeaderText("Refresh the queue and review the current submission.");
        TextArea text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(12);
        alert.getDialogPane().setContent(text);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private String listingDetailsText(Listing listing) {
        String text = "Title: " + listing.getTitle() + " (#" + listing.getId() + ")"
                + "\nSeller: " + sellerDisplay(listing)
                + "\nCategory: " + listing.getCategory()
                + "\nListing Type: " + typeDisplay(listing)
                + "\nStatus: " + listing.getStatus().name().replace('_', ' ')
                + "\nMeetup location: " + listing.getMeetupLocation()
                + "\nSubmitted: " + formatDate(listing.getSubmittedAt())
                + "\nFirst posted: " + formatDate(listing.getPostedAt())
                + "\nCondition: " + listing.getCondition()
                + "\nAvailability: " + listing.getAvailabilityStatus();
        if (listing.getType() == ListingType.SELL) text += "\nPrice: "
                + (listing.getPrice() == null ? "Not recorded" : String.format("HK$ %.2f", listing.getPrice()));
        if (listing.getType() == ListingType.LEND) text += "\nLending duration: "
                + (listing.getLendingDays() == null ? "Not recorded" : listing.getLendingDays() + " days");
        text += "\nDescription: " + (listing.getDescription() == null ? "" : listing.getDescription());
        if (listing.getRejectionReason() != null) text += "\nRejection reason: " + listing.getRejectionReason();
        return text;
    }

    private String sellerDisplay(Listing listing) {
        return listing.getSellerUsername() == null ? "Not recorded"
                : listing.getSellerFullName() + " (@" + listing.getSellerUsername() + ")";
    }

    private String typeDisplay(Listing listing) {
        return listing.getType() == null ? "Not recorded" : listing.getTypeDisplay();
    }

    private String formatDate(LocalDateTime date) {
        return date == null ? "Not set" : date.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
    }

    private FlowPane createPhotoGallery(List<String> paths, boolean thumbnails) {
        FlowPane gallery = new FlowPane(10, 10);
        gallery.setPrefWrapLength(thumbnails ? 600 : 680);
        if (paths.isEmpty()) gallery.getChildren().add(new Label("No photos supplied."));
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            try {
                String uri = path.startsWith("file:") ? path : Path.of(path).toAbsolutePath().toUri().toString();
                Image image = new Image(uri, false);
                if (image.isError()) throw new IllegalArgumentException("Photo could not be opened");
                ImageView preview = new ImageView(image);
                preview.setPreserveRatio(true);
                preview.setFitWidth(thumbnails ? 110 : 300);
                preview.setFitHeight(thumbnails ? 85 : 230);
                Button photo = new Button("Photo " + (index + 1), preview);
                photo.setContentDisplay(ContentDisplay.TOP);
                photo.setOnAction(event -> {
                    Dialog<ButtonType> fullPhoto = new Dialog<>();
                    fullPhoto.setTitle("Listing Photo");
                    fullPhoto.setResizable(true);
                    ScrollPane scroll = new ScrollPane(new ImageView(image));
                    scroll.setPrefViewportWidth(720);
                    scroll.setPrefViewportHeight(500);
                    fullPhoto.getDialogPane().setContent(scroll);
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

    @FXML
    private void showAllListings() {
        setActive(btnAllListings, "Manage All Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Admin can create a listing on behalf of a seller here.\nSame form fields as Seller Create Listing.\n(UI placeholder)").showAndWait());
        root.getChildren().add(addBtn);

        List<Listing> listings;
        try {
            listings = DataService.getAllListings();
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load listings: " + ex.getMessage()));
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }
        if (listings.isEmpty()) root.getChildren().add(new Label("No listings stored."));
        for (Listing l : listings) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label t = new Label(l.getTitle());
            t.setStyle("-fx-font-weight: bold;");
            Label m = new Label(l.getSellerFullName() + " · " + l.getTypeDisplay() + " · " + l.getStatus());
            m.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(t, m);

            Button edit = new Button("Edit");
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Edit listing dialog (demo).").showAndWait());

            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("btn-danger", "btn-sm");
            remove.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION, "Permanently remove this listing?");
                conf.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        try {
                            DataService.removeListing(l.getId());
                            showAllListings();
                        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    }
                });
            });

            row.getChildren().addAll(info, edit, remove);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showUsers() {
        setActive(btnUsers, "Manage All Users");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        List<User> users;
        try {
            users = DataService.getAllUsers();
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load users: " + ex.getMessage()));
            contentPane.getChildren().add(root);
            return;
        }
        for (User u : users) {
            if (u.getRole() == UserRole.ADMIN) continue;

            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label name = new Label(u.getFullName() + " (@" + u.getUsername() + ")");
            name.setStyle("-fx-font-weight: bold;");
            Label role = new Label(u.getRole().name() + (u.isActive() ? " · Active" : " · Deactivated"));
            role.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(name, role);

            Button edit = new Button("Edit");
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Edit user dialog (demo).").showAndWait());

            Button toggle = new Button(u.isActive() ? "Deactivate" : "Activate");
            toggle.getStyleClass().addAll(u.isActive() ? "btn-danger" : "btn-success", "btn-sm");
            toggle.setOnAction(e -> {
                try {
                    DataService.setUserActive(u.getUsername(), !u.isActive());
                    showUsers();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                }
            });

            Button delete = new Button("Delete");
            delete.getStyleClass().addAll("btn-danger", "btn-sm");
            delete.setOnAction(e -> {
                Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                        "Permanently delete user @" + u.getUsername() + "?");
                conf.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        try {
                            DataService.setUserActive(u.getUsername(), false);
                            new Alert(Alert.AlertType.INFORMATION, "User deactivated.").showAndWait();
                            showUsers();
                        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                        }
                    }
                });
            });

            row.getChildren().addAll(info, edit, toggle, delete);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showDeals() {
        setActive(btnDeals, "All Deals Record");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        HBox filters = new HBox(10);
        filters.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setPromptText("Search by item / user...");
        ComboBox<String> typeFilter = new ComboBox<>(FXCollections.observableArrayList("All Types", "Sell", "Lend", "Giveaway"));
        typeFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(search, typeFilter);
        root.getChildren().add(filters);

        TableView<String[]> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        String[] headers = {"Item", "Buyer", "Seller", "Type", "Status", "Date"};
        for (int i = 0; i < headers.length; i++) {
            final int idx = i;
            TableColumn<String[], String> col = new TableColumn<>(headers[i]);
            col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue()[idx]));
            table.getColumns().add(col);
        }
        table.setItems(FXCollections.observableArrayList(
                new String[]{"Calculus Textbook", "alice", "charlie", "Sell", "Accepted", "2026-01-15"},
                new String[]{"USB Hub", "bob", "diana", "Giveaway", "Completed", "2025-12-01"}
        ));
        root.getChildren().add(table);
        contentPane.getChildren().add(root);
    }

    @FXML
    private void showRatings() {
        setActive(btnRatings, "Seller Ratings Overview");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        HBox filters = new HBox(10);
        TextField userFilter = new TextField();
        userFilter.setPromptText("Filter by seller...");
        ComboBox<String> scoreFilter = new ComboBox<>(FXCollections.observableArrayList(
                "All scores", "5 stars", "4 stars", "3 stars", "2 stars", "1 star"));
        scoreFilter.getSelectionModel().selectFirst();
        filters.getChildren().addAll(userFilter, scoreFilter);
        root.getChildren().add(filters);

        List<Review> reviews;
        try {
            reviews = DataService.getAllReviews();
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load reviews: " + ex.getMessage()));
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }
        if (reviews.isEmpty()) root.getChildren().add(new Label("No reviews stored."));
        for (Review r : reviews) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(4);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label stars = new Label("★".repeat(r.getRating()) + "☆".repeat(5 - r.getRating()) +
                    "  @" + r.getSellerUsername() + " by @" + r.getBuyerUsername());
            stars.setStyle("-fx-font-weight: bold;");
            Label comment = new Label(r.getComment());
            comment.setWrapText(true);
            comment.setStyle("-fx-text-fill: #5a5a7a;");
            info.getChildren().addAll(stars, comment);

            Button hide = new Button(r.isHidden() ? "Unhide" : "Hide");
            hide.getStyleClass().addAll("btn-outline", "btn-sm");
            hide.setOnAction(e -> {
                try {
                    DataService.setReviewHidden(r.getBuyerUsername(), r.getId(), !r.isHidden());
                    showRatings();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                }
            });
            row.getChildren().addAll(info, hide);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Admin Notifications");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        List<Notification> notifs;
        try {
            User admin = SessionManager.getCurrentUser();
            notifs = admin == null ? List.of() : DataService.getNotificationsFor(admin.getUsername());
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load notifications: " + ex.getMessage()));
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }
        if (notifs.isEmpty()) {
            root.getChildren().add(new Label("No notifications."));
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                Label t = new Label(n.getTitle());
                t.setStyle("-fx-font-weight: bold;");
                Label m = new Label(n.getMessage());
                m.setWrapText(true);
                m.setStyle("-fx-text-fill: #5a5a7a;");
                Label meta = new Label(n.getCategory() + " · " +
                        n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm")));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                item.getChildren().addAll(t, m, meta);
                root.getChildren().add(item);
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User user = SessionManager.getCurrentUser();
        VBox form = new VBox(12);
        form.setMaxWidth(400);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label titleLbl = new Label("Admin Profile");
        titleLbl.getStyleClass().add("section-title");
        form.getChildren().add(titleLbl);

        TextField name = new TextField(user != null ? user.getFullName() : "");
        PasswordField pass = new PasswordField();
        pass.setPromptText("New password (optional)");
        Button save = new Button("Save");
        save.getStyleClass().add("btn-success");
        save.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION, "Profile updated (demo).").showAndWait());
        form.getChildren().addAll(new Label("Full Name"), name, new Label("New Password"), pass, save);
        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
