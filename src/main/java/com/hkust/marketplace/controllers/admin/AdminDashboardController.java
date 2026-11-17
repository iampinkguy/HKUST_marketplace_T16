package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

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
    @FXML private Circle notifUnreadDot;

    private Button currentActiveBtn;
    private String userActionMessage;
    private boolean includeDeletedUsers;
    private Timeline notificationTicker;

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Admin: " + user.getFullName());
        currentActiveBtn = btnReview;
        ensureNotificationDot();
        notificationTicker = new Timeline(new KeyFrame(Duration.seconds(2), event -> refreshNotificationDot()));
        notificationTicker.setCycleCount(Timeline.INDEFINITE);
        notificationTicker.play();
        refreshNotificationDot();
        showReview();
    }

    private void ensureNotificationDot() {
        if (notifUnreadDot != null) {
            notifUnreadDot.toFront();
            return;
        }
        if (btnNotifications == null || !(btnNotifications.getParent() instanceof StackPane host)) return;
        notifUnreadDot = new Circle(6, Color.web("#e53935"));
        notifUnreadDot.setStroke(Color.WHITE);
        notifUnreadDot.setStrokeWidth(1.5);
        notifUnreadDot.setMouseTransparent(true);
        notifUnreadDot.setVisible(false);
        StackPane.setAlignment(notifUnreadDot, Pos.TOP_RIGHT);
        notifUnreadDot.setTranslateX(-14);
        notifUnreadDot.setTranslateY(10);
        host.getChildren().add(notifUnreadDot);
    }

    private void refreshNotificationDot() {
        ensureNotificationDot();
        if (notifUnreadDot == null) return;
        boolean unread = false;
        try {
            unread = DataService.hasUnreadNotifications();
        } catch (IllegalStateException | SecurityException ignored) {
            unread = false;
        }
        notifUnreadDot.setVisible(unread);
        if (unread) notifUnreadDot.toFront();
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

        CheckBox showDeleted = new CheckBox("Include permanently deleted accounts (read-only history)");
        showDeleted.setId("show-deleted-users");
        showDeleted.setSelected(includeDeletedUsers);
        showDeleted.setOnAction(event -> { includeDeletedUsers = showDeleted.isSelected(); showUsers(); });
        root.getChildren().add(showDeleted);
        if (userActionMessage != null) {
            Label message = new Label(userActionMessage);
            message.setStyle("-fx-text-fill: #2e7d32;");
            root.getChildren().add(message);
            userActionMessage = null;
        }
        List<AdminUserService.Account> users;
        try {
            users = AdminUserService.getAllUsers(includeDeletedUsers);
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load users: " + ex.getMessage()));
            contentPane.getChildren().add(root);
            return;
        }
        if (users.isEmpty()) root.getChildren().add(new Label("No buyer or seller accounts found."));
        for (AdminUserService.Account u : users) {

            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(3);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label name = new Label(u.fullName() + " (@" + u.username() + ")");
            name.setStyle("-fx-font-weight: bold;");
            Label role = new Label(u.role().name() + (u.deletedAt() != null ? " · Permanently deleted"
                    : u.active() ? " · Active" : " · Deactivated"));
            role.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
            info.getChildren().addAll(name, role);

            Button view = new Button("View");
            view.setId("view-user-" + u.username());
            view.getStyleClass().addAll("btn-outline", "btn-sm");
            view.setOnAction(event -> viewManagedUser(u));

            Button edit = new Button("Edit");
            edit.setId("edit-user-" + u.username());
            edit.getStyleClass().addAll("btn-outline", "btn-sm");
            edit.setOnAction(event -> editManagedUser(u));
            edit.setDisable(u.deletedAt() != null);

            Button toggle = new Button(u.active() ? "Deactivate" : "Activate");
            toggle.setId("active-user-" + u.username());
            toggle.getStyleClass().addAll(u.active() ? "btn-danger" : "btn-success", "btn-sm");
            toggle.setDisable(u.deletedAt() != null);
            toggle.setOnAction(event -> confirmManagedUserChange(u, false));

            Button delete = new Button("Permanently delete");
            delete.setId("delete-user-" + u.username());
            delete.getStyleClass().addAll("btn-danger", "btn-sm");
            delete.setDisable(u.deletedAt() != null);
            delete.setOnAction(event -> confirmManagedUserChange(u, true));
            Button history = new Button("History");
            history.setId("history-user-" + u.username());
            history.getStyleClass().addAll("btn-outline", "btn-sm");
            history.setOnAction(event -> showManagedUserHistory(u));

            row.getChildren().addAll(info, view, edit, toggle, delete, history);
            root.getChildren().add(row);
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private AdminUserService.Account currentManagedUser(AdminUserService.Account displayed) {
        AdminUserService.Account current = AdminUserService.getAllUsers(true).stream()
                .filter(user -> user.username().equals(displayed.username())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Account no longer exists. Refresh the user list."));
        if (current.version() != displayed.version()) {
            throw new IllegalStateException("Account changed since it was displayed. Refresh and review it again.");
        }
        return current;
    }

    private void viewManagedUser(AdminUserService.Account displayed) {
        try {
            AdminUserService.Account user = currentManagedUser(displayed);
            Dialog<ButtonType> details = new Dialog<>();
            details.setTitle("User Details");
            details.setHeaderText(user.fullName() + " (@" + user.username() + ")");
            Label fields = new Label("Username: " + user.username() + "\nRole: " + user.role()
                    + "\nStatus: " + (user.deletedAt() != null ? "Permanently deleted" : user.active() ? "Active" : "Deactivated")
                    + "\nCreated: " + formatDate(user.createdAt())
                    + "\nResidence hall: " + optionalUserField(user.residenceHall())
                    + "\nBio: " + optionalUserField(user.bio())
                    + "\nPreferred meetup: " + optionalUserField(user.preferredMeetup())
                    + (user.deletedAt() == null ? "" : "\nDeleted: " + formatDate(user.deletedAt())));
            fields.setWrapText(true);
            VBox body = new VBox(fields);
            body.setPadding(new Insets(16));
            body.setPrefWidth(450);
            details.getDialogPane().setContent(body);
            details.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            details.setResizable(true);
            details.showAndWait();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showUserManagementError(ex);
            showUsers();
        }
    }

    private void editManagedUser(AdminUserService.Account displayed) {
        try {
            AdminUserService.Account user = currentManagedUser(displayed);
            if (user.deletedAt() != null) throw new IllegalStateException("Permanently deleted accounts cannot be edited.");
            Dialog<ButtonType> edit = new Dialog<>();
            edit.setTitle("Edit User");
            edit.setHeaderText("Username: " + user.username() + " · Role: " + user.role());
            TextField fullName = new TextField(user.fullName());
            fullName.setId("user-full-name");
            ComboBox<String> hall = new ComboBox<>(FXCollections.observableArrayList(DataService.RESIDENCES));
            hall.getItems().add(0, "Not specified");
            hall.setValue(user.residenceHall() == null ? "Not specified" : user.residenceHall());
            hall.setId("user-residence-hall");
            TextArea bio = new TextArea(user.bio());
            bio.setWrapText(true);
            bio.setPrefRowCount(4);
            bio.setId("user-bio");
            ComboBox<String> meetup = new ComboBox<>(FXCollections.observableArrayList(DataService.HKUST_LOCATIONS));
            meetup.getItems().add(0, "Not specified");
            meetup.setValue(user.preferredMeetup() == null ? "Not specified" : user.preferredMeetup());
            meetup.setId("user-preferred-meetup");
            Label error = new Label();
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #c62828;");
            VBox form = new VBox(10, new Label("Full name"), fullName);
            form.setPadding(new Insets(16));
            form.setPrefWidth(460);
            if (user.role() == UserRole.BUYER) form.getChildren().addAll(new Label("Residence hall"), hall);
            else form.getChildren().addAll(new Label("Bio"), bio, new Label("Preferred meetup"), meetup);
            form.getChildren().add(error);
            edit.getDialogPane().setContent(form);
            ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
            edit.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            edit.getDialogPane().lookupButton(save).setId("save-user");
            edit.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                try {
                    AdminUserService.updateUser(user, fullName.getText(),
                            "Not specified".equals(hall.getValue()) ? null : hall.getValue(), bio.getText(),
                            "Not specified".equals(meetup.getValue()) ? null : meetup.getValue());
                    edit.close();
                    userActionMessage = "Account updated.";
                    showUsers();
                } catch (IllegalArgumentException ex) {
                    error.setText(ex.getMessage());
                } catch (IllegalStateException | SecurityException ex) {
                    showUserManagementError(ex);
                    edit.close();
                    showUsers();
                }
            });
            edit.showAndWait();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showUserManagementError(ex);
            showUsers();
        }
    }

    private String optionalUserField(String value) { return value == null || value.isBlank() ? "Not specified" : value; }

    private void confirmManagedUserChange(AdminUserService.Account displayed, boolean delete) {
        String action = delete ? "Permanently delete" : displayed.active() ? "Deactivate" : "Activate";
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(delete ? "Permanently Delete User" : displayed.active() ? "Confirm Deactivation" : "Confirm Activation");
        confirmation.setHeaderText(action + " " + displayed.fullName() + " (@" + displayed.username() + ")?");
        confirmation.setContentText(delete
                ? "Login credentials and profile details will be erased. This cannot be undone.\n\n"
                    + "The username remains as a historical account identifier. Published and pending listings are withdrawn. "
                    + "Listings, deals, chats and the audit log are retained. Existing deals are not automatically cancelled."
                : displayed.active()
                    ? "Sign-in will be blocked. Profile, listings, deals and chats are retained unchanged. This account can be reactivated."
                    : "Sign-in will be restored using the existing profile and credentials.");
        confirmation.getDialogPane().setPrefWidth(540);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            if (delete) AdminUserService.deleteUser(displayed);
            else AdminUserService.setUserActive(displayed, !displayed.active());
            userActionMessage = delete ? "Account permanently deleted. History retained."
                    : displayed.active() ? "Account deactivated." : "Account activated.";
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showUserManagementError(ex);
        }
        showUsers();
    }

    private void showManagedUserHistory(AdminUserService.Account user) {
        try {
            List<AdminUserService.Event> events = AdminUserService.getHistory(user.username());
            StringBuilder text = new StringBuilder("Account: @" + user.username() + "\n");
            if (events.isEmpty()) text.append("No saved management actions.");
            for (AdminUserService.Event event : events) {
                text.append("\n").append(formatDate(event.at())).append(" | ").append(event.action())
                        .append(" | Admin: ").append(event.admin());
            }
            TextArea history = new TextArea(text.toString());
            history.setEditable(false);
            history.setWrapText(true);
            history.setPrefRowCount(12);
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Account History");
            dialog.setHeaderText("Saved management actions for @" + user.username());
            dialog.getDialogPane().setContent(history);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.setResizable(true);
            dialog.showAndWait();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showUserManagementError(ex);
        }
    }

    private void showUserManagementError(RuntimeException failure) {
        Alert error = new Alert(Alert.AlertType.ERROR, failure.getMessage());
        error.setTitle("User Change Not Saved");
        error.showAndWait();
    }

    @FXML
    private void showDeals() {
        setActive(btnDeals, "All Deals Record");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        FlowPane filters = new FlowPane(10, 8);
        filters.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setId("deals-search");
        search.setPromptText("Search item title / buyer / seller...");
        search.setPrefWidth(260);
        ComboBox<String> typeFilter = new ComboBox<>(FXCollections.observableArrayList("All Types", "Sell", "Lend", "Giveaway"));
        typeFilter.setId("deals-type");
        typeFilter.getSelectionModel().selectFirst();
        ComboBox<String> statusFilter = new ComboBox<>(FXCollections.observableArrayList("All Statuses", "Accepted", "Completed", "Returned"));
        statusFilter.setId("deals-status");
        statusFilter.getSelectionModel().selectFirst();
        Button clear = new Button("Clear");
        clear.setId("deals-clear");
        clear.getStyleClass().add("btn-outline");
        Button refresh = new Button("Refresh");
        refresh.setId("deals-refresh");
        refresh.getStyleClass().add("btn-outline");
        filters.getChildren().addAll(search, new Label("Type"), typeFilter, new Label("Status"), statusFilter, clear, refresh);
        root.getChildren().add(filters);
        Label count = new Label();
        count.setId("deals-count");
        root.getChildren().add(count);
        Label error = new Label();
        error.setId("deals-error");
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #c62828;");
        error.setVisible(false);
        error.setManaged(false);
        root.getChildren().add(error);

        TableView<DataService.DealRecord> table = new TableView<>();
        table.setId("deals-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(dealColumn("Item Title", DataService.DealRecord::itemTitle, 180));
        table.getColumns().add(dealColumn("Buyer Username", DataService.DealRecord::buyerUsername, 125));
        table.getColumns().add(dealColumn("Seller Username", DataService.DealRecord::sellerUsername, 125));
        table.getColumns().add(dealColumn("Deal Type", d -> switch (d.dealType()) {
            case SELL -> "Sell"; case LEND -> "Lend"; case GIVEAWAY -> "Giveaway";
        }, 95));
        table.getColumns().add(dealColumn("Start Date", d -> formatDealDate(d.startDate()), 155));
        table.getColumns().add(dealColumn("Return/Complete Date", this::formatDealEndDate, 175));
        table.getColumns().add(dealColumn("Status", DataService.DealRecord::status, 100));
        table.setRowFactory(view -> {
            TableRow<DataService.DealRecord> row = new TableRow<>();
            row.cursorProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then(javafx.scene.Cursor.DEFAULT).otherwise(javafx.scene.Cursor.HAND));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 1
                        && !row.isEmpty()) showDealDetails(row.getItem().requestId());
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) {
                showDealDetails(table.getSelectionModel().getSelectedItem().requestId());
                event.consume();
            }
        });
        Runnable load = () -> {
            ListingType type = switch (typeFilter.getValue()) {
                case "Sell" -> ListingType.SELL;
                case "Lend" -> ListingType.LEND;
                case "Giveaway" -> ListingType.GIVEAWAY;
                default -> null;
            };
            String status = "All Statuses".equals(statusFilter.getValue()) ? null : statusFilter.getValue();
            error.setVisible(false);
            error.setManaged(false);
            try {
                table.setItems(FXCollections.observableArrayList(DataService.getAllDeals(search.getText(), type, status)));
                boolean filtered = !search.getText().isBlank() || type != null || status != null;
                table.setPlaceholder(new Label(filtered ? "No deals match these filters." : "No deals recorded yet."));
                int size = table.getItems().size();
                count.setText(size + (size == 1 ? " deal" : " deals"));
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                table.getItems().clear();
                table.setPlaceholder(new Label("Could not load deals. Use Refresh to try again."));
                count.setText("Deals unavailable");
                error.setText("Could not load deals. " + ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        };
        search.textProperty().addListener((observable, previous, next) -> load.run());
        typeFilter.valueProperty().addListener((observable, previous, next) -> load.run());
        statusFilter.valueProperty().addListener((observable, previous, next) -> load.run());
        clear.setOnAction(event -> {
            search.clear();
            typeFilter.getSelectionModel().selectFirst();
            statusFilter.getSelectionModel().selectFirst();
        });
        refresh.setOnAction(event -> load.run());
        load.run();
        VBox.setVgrow(table, Priority.ALWAYS);
        root.getChildren().add(table);
        Label dateNote = new Label("Start Date: borrow start, otherwise request creation. Return/Complete Date: scheduled return (due) for ongoing loans, actual return/completion for finished deals; — means not recorded.");
        dateNote.setId("deals-date-note");
        dateNote.setWrapText(true);
        dateNote.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        root.getChildren().add(dateNote);
        Label detailsHint = new Label("Click a deal or select it and press Enter to view details.");
        detailsHint.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        root.getChildren().add(detailsHint);
        contentPane.getChildren().add(root);
    }

    private void showDealDetails(int requestId) {
        try {
            DataService.DealDetails details = DataService.getDealDetails(requestId);
            DataService.DealRecord deal = details.deal();
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Deal Details");
            dialog.setHeaderText(deal.itemTitle());
            dialog.initOwner(contentPane.getScene().getWindow());
            dialog.setResizable(true);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            GridPane facts = new GridPane();
            facts.setHgap(18);
            facts.setVgap(12);
            facts.setPadding(new Insets(12));
            ColumnConstraints values = new ColumnConstraints();
            values.setHgrow(Priority.ALWAYS);
            facts.getColumnConstraints().addAll(new ColumnConstraints(145), values);
            dealFact(facts, "id", "Deal ID", Integer.toString(deal.requestId()));
            dealFact(facts, "item", "Item Title", deal.itemTitle());
            dealFact(facts, "buyer", "Buyer Username", deal.buyerUsername());
            dealFact(facts, "seller", "Seller Username", deal.sellerUsername());
            dealFact(facts, "type", "Deal Type", switch (deal.dealType()) {
                case SELL -> "Sell"; case LEND -> "Lend"; case GIVEAWAY -> "Giveaway";
            });
            dealFact(facts, "status", "Status", deal.status());
            dealFact(facts, "category", "Category", details.category());
            dealFact(facts, "condition", "Condition", details.condition());
            dealFact(facts, "description", "Description", details.description());
            dealFact(facts, "meetup", "Meetup Location", details.meetupLocation());
            dealFact(facts, "requested", "Requested At", formatDealDate(details.requestedAt()));
            dealFact(facts, "start", "Start Date", formatDealDate(deal.startDate()));
            if (deal.dealType() == ListingType.SELL) {
                dealFact(facts, "price", "Agreed Price", details.offerPrice() == null ? null
                        : String.format(java.util.Locale.ROOT, "HK$%.2f", details.offerPrice()));
            } else if (deal.dealType() == ListingType.LEND) {
                dealFact(facts, "borrow-end", "Borrow End", formatDealDate(details.borrowEnd()));
                if (details.proposedDays() != null) dealFact(facts, "duration", "Lending Duration", details.proposedDays() + " days");
                dealFact(facts, "scheduled", "Scheduled Return", formatDealDate(deal.scheduledReturnDate()));
            }
            dealFact(facts, "completed", deal.dealType() == ListingType.LEND ? "Actual Return" : "Completed At",
                    formatDealDate(deal.completeDate()));
            ScrollPane scroll = new ScrollPane(facts);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportWidth(580);
            scroll.setPrefViewportHeight(460);
            dialog.getDialogPane().setContent(scroll);
            dialog.show();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            Alert error = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.CLOSE);
            error.initOwner(contentPane.getScene().getWindow());
            error.setTitle("Deal Unavailable");
            error.setHeaderText("Could not load deal details");
            error.show();
        }
    }

    private void dealFact(GridPane facts, String id, String title, String value) {
        Label caption = new Label(title);
        caption.setStyle("-fx-font-weight: bold;");
        Label detail = new Label(value == null || value.isBlank() ? "—" : value);
        detail.setId("deal-detail-" + id);
        detail.setWrapText(true);
        detail.setMaxWidth(Double.MAX_VALUE);
        int row = facts.getRowCount();
        facts.add(caption, 0, row);
        facts.add(detail, 1, row);
    }

    private TableColumn<DataService.DealRecord, String> dealColumn(String title,
            java.util.function.Function<DataService.DealRecord, String> value, double width) {
        TableColumn<DataService.DealRecord, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> {
            String text = value.apply(cell.getValue());
            return new javafx.beans.property.SimpleStringProperty(text == null ? "—" : text);
        });
        return column;
    }

    private String formatDealDate(String date) { return date == null ? "—" : date.replace('T', ' '); }

    private String formatDealEndDate(DataService.DealRecord deal) {
        if (deal.completeDate() != null) return formatDealDate(deal.completeDate());
        if (deal.scheduledReturnDate() == null) return "—";
        String due = formatDealDate(deal.scheduledReturnDate());
        return due.substring(0, Math.min(16, due.length())) + " (due)";
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
            Label comment = new Label(r.getComment() == null || r.getComment().isBlank()
                    ? "(No written feedback)" : r.getComment());
            comment.setWrapText(true);
            comment.setStyle("-fx-text-fill: #5a5a7a;");
            info.getChildren().addAll(stars, comment);
            if (r.getSellerReply() != null && !r.getSellerReply().isBlank()) {
                Label reply = new Label("Seller reply: " + r.getSellerReply());
                reply.setWrapText(true);
                reply.setStyle("-fx-text-fill: #003974;");
                info.getChildren().add(reply);
            }
            if (r.isFlagged()) {
                Label flagged = new Label("Flagged by seller for moderation");
                flagged.setStyle("-fx-text-fill: #c62828; -fx-font-weight: bold;");
                info.getChildren().add(flagged);
            }

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
            refreshNotificationDot();
            return;
        }
        if (notifs.isEmpty()) {
            root.getChildren().add(new Label("No notifications."));
        } else {
            for (Notification n : notifs) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                if (!n.isRead()) item.setStyle("-fx-border-color: #003974; -fx-border-width: 0 0 0 3;");
                Label t = new Label(n.getTitle());
                t.setStyle("-fx-font-weight: bold;");
                Label m = new Label(n.getMessage());
                m.setWrapText(true);
                m.setStyle("-fx-text-fill: #5a5a7a;");
                HBox footer = new HBox(8);
                footer.setAlignment(Pos.CENTER_LEFT);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label meta = new Label(n.getCategory() + " · " +
                        (n.getTimestamp() == null ? ""
                                : n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm",
                                java.util.Locale.ENGLISH))));
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 11px;");
                footer.getChildren().addAll(meta, spacer);
                if (canGoFromAdminNotification(n)) {
                    Button go = new Button("Go ->");
                    go.getStyleClass().addAll("btn-outline", "btn-sm");
                    go.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                    go.setOnAction(event -> openAdminNotification(n));
                    footer.getChildren().add(go);
                }
                item.getChildren().addAll(t, m, footer);
                item.setOnMouseClicked(event -> {
                    if (n.isRead()) return;
                    if (!markAdminNotificationRead(n)) return;
                    item.setStyle(null);
                });
                root.getChildren().add(item);
            }
        }
        refreshNotificationDot();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private boolean canGoFromAdminNotification(Notification notification) {
        if (notification.getListingId() != null) return true;
        String title = notification.getTitle() == null ? "" : notification.getTitle().toLowerCase();
        return title.contains("listing") || title.contains("flagged") || title.contains("moderation")
                || title.contains("review");
    }

    private void openAdminNotification(Notification notification) {
        if (!markAdminNotificationRead(notification)) return;
        String title = notification.getTitle() == null ? "" : notification.getTitle().toLowerCase();
        if (title.contains("flagged") || title.contains("moderation")) {
            showRatings();
            return;
        }
        if (notification.getListingId() != null) {
            String listingId = Integer.toString(notification.getListingId());
            try {
                Listing listing = DataService.getListingForAdmin(listingId);
                if (listing != null && listing.getStatus() == ListingStatus.PENDING_REVIEW) {
                    showListingDetails(listing);
                    return;
                }
                if (listing != null) {
                    showAllListings();
                    new Alert(Alert.AlertType.INFORMATION,
                            "Opened All Listings. \"" + listing.getTitle()
                                    + "\" is currently " + listing.getStatus().name().replace('_', ' ') + ".")
                            .showAndWait();
                    return;
                }
            } catch (IllegalStateException | SecurityException | IllegalArgumentException ex) {
                new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                showNotifications();
                return;
            }
        }
        if (title.contains("listing") || title.contains("review")) {
            showReview();
            return;
        }
        showNotifications();
    }

    private boolean markAdminNotificationRead(Notification notification) {
        if (notification.isRead()) return true;
        try {
            DataService.markCurrentUserNotificationRead(notification.getId());
            notification.setRead(true);
            refreshNotificationDot();
            return true;
        } catch (IllegalStateException | SecurityException ex) {
            new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
            return false;
        }
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "My Profile");
        contentPane.getChildren().clear();
        User user = SessionManager.getCurrentUser();
        VBox form = new VBox(12);
        form.setMaxWidth(460);
        form.getStyleClass().add("card");
        form.setPadding(new Insets(20));
        Label titleLbl = new Label("Admin Profile");
        titleLbl.getStyleClass().add("section-title");
        form.getChildren().add(titleLbl);

        TextField username = new TextField(user != null ? user.getUsername() : "");
        username.setId("profile-username");
        username.setEditable(false);
        TextField name = new TextField(user != null ? user.getFullName() : "");
        name.setId("profile-full-name");
        PasswordField pass = new PasswordField();
        pass.setId("profile-new-password");
        pass.setPromptText("Leave blank to keep the current password");
        Label rules = new Label("Password rules: at least 8 characters, with an uppercase letter, a lowercase letter, and a digit.");
        rules.setId("profile-password-rules");
        rules.setWrapText(true);
        rules.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        PasswordField confirm = new PasswordField();
        confirm.setId("profile-confirm-password");
        Label error = new Label();
        error.setId("profile-error");
        error.setStyle("-fx-text-fill: #c62828; -fx-font-size: 13px;");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        Label success = new Label();
        success.setId("profile-success");
        success.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 13px;");
        success.setWrapText(true);
        success.setVisible(false);
        success.setManaged(false);
        Button save = new Button("Save");
        save.setId("profile-save");
        save.getStyleClass().add("btn-success");
        save.setOnAction(e -> {
            error.setVisible(false);
            error.setManaged(false);
            success.setVisible(false);
            success.setManaged(false);
            try {
                AdminProfileService.updateOwnProfile(name.getText(), pass.getText(), confirm.getText());
                User updated = SessionManager.getCurrentUser();
                name.setText(updated.getFullName());
                welcomeLabel.setText("Admin: " + updated.getFullName());
                pass.clear();
                confirm.clear();
                success.setText("Your profile was updated successfully.");
                success.setVisible(true);
                success.setManaged(true);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                if (ex instanceof DataService.BuyerInputException input && input.isClearConfirmPassword()) confirm.clear();
                error.setText(ex.getMessage());
                error.setVisible(true);
                error.setManaged(true);
            }
        });
        form.getChildren().addAll(new Label("Username"), username, new Label("Full Name"), name,
                new Label("New Password"), pass, rules, new Label("Confirm Password"), confirm, error, success, save);
        contentPane.getChildren().add(form);
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
