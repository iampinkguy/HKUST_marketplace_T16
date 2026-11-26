package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.controllers.ListingDescriptionControls;
import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DialogStyle;
import com.hkust.marketplace.utils.ListingDescriptionService;
import com.hkust.marketplace.utils.PhotoViewer;
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

/**
 * Task 3 admin portal. There is no registration screen.
 * Review, users, deals, notifications, all listings, ratings, and profile are in this dashboard.
 */
public class AdminDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnReview, btnAllListings, btnUsers, btnDeals, btnRatings, btnNotifications, btnProfile;
    @FXML private Circle notifUnreadDot;

    private final ListingDescriptionService descriptionService;
    private Button currentActiveBtn;
    private String userActionMessage;
    private boolean includeDeletedUsers;
    private String notificationCategory = "All";
    private String notificationState = "All";
    private String listingActionMessage;
    private Timeline notificationTicker;

    public AdminDashboardController() {
        this(new ListingDescriptionService());
    }

    AdminDashboardController(ListingDescriptionService descriptionService) {
        this.descriptionService = descriptionService;
    }

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
        review.setOnAction(event -> showListingDetails(l, false));
        card.getChildren().addAll(title, meta, submitted, createPhotoGallery(l.getPhotoPaths(), true), review);
        return card;
    }

    private void showListingDetails(Listing queued) {
        showListingDetails(queued, false);
    }

    private void showListingDetails(Listing queued, boolean fromNotification) {
        Listing listing;
        try {
            listing = DataService.getListingForReview(queued.getId(), queued.getReviewVersion());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showReviewFailure(queued, ex);
            if (fromNotification) showNotifications();
            else showReview();
            return;
        }
        renderListingDetail(listing, fromNotification, true, null);
    }

    private void renderListingDetail(Listing listing, boolean fromNotification, boolean reviewActions, String contextMessage) {
        setActive(fromNotification ? btnNotifications : btnReview, "Listing Detail");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));
        root.setId("admin-listing-detail");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("X");
        close.setId("close-listing-detail");
        close.getStyleClass().addAll("btn-outline", "btn-sm");
        close.setOnAction(event -> {
            if (fromNotification) showNotifications();
            else showReview();
        });
        header.getChildren().addAll(
                styled(listing.getTitle() + " (#" + listing.getId() + ")",
                        "-fx-font-size: 22px; -fx-font-weight: bold;"),
                spacer,
                close);

        FlowPane photos = createPhotoGallery(listing.getPhotoPaths(), false);

        VBox facts = new VBox(6);
        facts.getChildren().addAll(
                fact("Description", listing.getDescription()),
                fact("Seller", sellerDisplay(listing)),
                fact("Category", listing.getCategory()),
                fact("Type", typeDisplay(listing)),
                fact("Price", listing.getPriceDisplay()),
                fact("Condition", listing.getCondition()),
                fact("Meet-up location", listing.getMeetupLocation()),
                fact("Submitted", formatDate(listing.getSubmittedAt())),
                fact("Posted", formatDate(listing.getPostedAt())),
                fact("Availability", listing.getAvailabilityStatus() + " · "
                        + listing.getStatus().name().replace('_', ' ')),
                fact("Views", Integer.toString(listing.getViewCount()))
        );
        if (listing.getType() == ListingType.LEND) {
            String period = listing.getLendingDays() == null
                    ? "Not recorded"
                    : listing.getLendingDays() + (listing.getLendingDays() == 1 ? " day" : " days");
            facts.getChildren().add(4, fact("Lending period", period));
        }
        if (listing.getRejectionReason() != null && !listing.getRejectionReason().isBlank()) {
            facts.getChildren().add(fact("Rejection reason", listing.getRejectionReason()));
        }

        root.getChildren().add(header);
        if (contextMessage != null && !contextMessage.isBlank()) {
            root.getChildren().add(styled(contextMessage, "-fx-text-fill: #8a5a00;"));
        }
        root.getChildren().add(photos);
        root.getChildren().add(facts);

        if (reviewActions) {
            HBox actions = new HBox(8);
            Button approve = new Button("Approve");
            approve.setId("approve-listing");
            approve.getStyleClass().add("btn-primary");
            approve.setOnAction(event -> confirmListingDecision(listing, true, fromNotification));
            Button reject = new Button("Reject");
            reject.setId("reject-listing");
            reject.getStyleClass().addAll("btn-outline");
            reject.setOnAction(event -> confirmListingDecision(listing, false, fromNotification));
            actions.getChildren().addAll(approve, reject);
            root.getChildren().add(actions);
        }

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        contentPane.getChildren().add(scroll);
    }

    private void confirmListingDecision(Listing listing, boolean approve, boolean fromNotification) {
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
            Optional<String> entered = DialogStyle.showAndWait(reasonDialog);
            if (entered.isEmpty()) return;
            reason = entered.get().trim();
        }
        String decision = approve ? "Approve" : "Reject";
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Listing Decision");
        confirmation.setHeaderText(decision + " '" + listing.getTitle() + "' (#" + listing.getId() + ")?");
        confirmation.setContentText("Seller: " + sellerDisplay(listing) + "\nType: " + typeDisplay(listing)
                + "\nDecision: " + decision + (approve ? " — publish listing" : "\nReason: " + reason));
        if (DialogStyle.showAndWait(confirmation).orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            if (approve) DataService.approveListing(listing.getId(), listing.getReviewVersion());
            else DataService.rejectListing(listing.getId(), reason, listing.getReviewVersion());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showReviewFailure(listing, ex);
            if (fromNotification) showNotifications();
            else showReview();
            return;
        }
        showReview();
        Alert success = new Alert(Alert.AlertType.INFORMATION, approve
                ? "Listing approved and published. An approval notification has been saved for the seller."
                : "Listing rejected. A notification containing the reason has been saved for the seller.");
        success.setTitle("Listing Decision Saved");
        DialogStyle.showAndWait(success);
    }

    private Label fact(String name, String value) {
        Label label = new Label(name + ": " + (value == null || value.isBlank() ? "—" : value));
        label.setWrapText(true);
        return label;
    }

    private Label styled(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(style);
        return label;
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
        DialogStyle.showAndWait(alert);
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
        return date == null
                ? "Not set"
                : date.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.ENGLISH));
    }

    private FlowPane createPhotoGallery(List<String> paths, boolean thumbnails) {
        FlowPane gallery = new FlowPane(10, 10);
        gallery.setPrefWrapLength(thumbnails ? 600 : 680);
        if (paths.isEmpty()) {
            gallery.getChildren().add(noPhotoPlaceholder(thumbnails ? 110 : 300, thumbnails ? 85 : 160));
            return gallery;
        }
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
                int number = index + 1;
                photo.setOnAction(event -> PhotoViewer.show(image, "Photo " + number));
                gallery.getChildren().add(photo);
            } catch (IllegalArgumentException ex) {
                gallery.getChildren().add(new Label("Photo " + (index + 1) + " unavailable."));
            }
        }
        return gallery;
    }

    private StackPane noPhotoPlaceholder(double width, double height) {
        Label label = new Label("No photo available");
        label.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 13px;");
        StackPane placeholder = new StackPane(label);
        placeholder.setPrefSize(width, height);
        placeholder.setMinSize(width, height);
        placeholder.setMaxWidth(width);
        placeholder.setStyle("-fx-background-color: #f0f2f7; -fx-background-radius: 6;");
        return placeholder;
    }

    @FXML
    private void showAllListings() {
        setActive(btnAllListings, "Manage All Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        Button addBtn = new Button("+ Add Listing (on behalf of seller)");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setId("admin-listing-add");
        addBtn.setOnAction(e -> showManagedListingForm(null));
        root.getChildren().add(addBtn);
        TextField search = new TextField();
        search.setId("admin-listings-search");
        search.setPromptText("Search title, seller or listing ID");
        search.setPrefColumnCount(24);
        ComboBox<String> status = new ComboBox<>();
        status.setId("admin-listings-status");
        status.getItems().add("All statuses");
        for (ListingStatus value : ListingStatus.values()) status.getItems().add(value.name());
        status.setValue("All statuses");
        status.setAccessibleText("Listing status");
        Button refresh = new Button("Refresh");
        refresh.setId("admin-listings-refresh");
        FlowPane filters = new FlowPane(8, 8, search, status, refresh);
        VBox list = new VBox(10);
        list.setId("admin-listings-list");
        if (listingActionMessage != null) {
            Label success = new Label(listingActionMessage);
            success.setStyle("-fx-text-fill: #2e7d32;"); success.setWrapText(true);
            root.getChildren().add(success); listingActionMessage = null;
        }
        Runnable render = () -> renderManagedListings(list, search.getText(), status.getValue());
        search.textProperty().addListener((o, old, value) -> render.run());
        status.setOnAction(e -> render.run()); refresh.setOnAction(e -> render.run());
        root.getChildren().addAll(filters, list);
        render.run();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
    }

    private void renderManagedListings(VBox list, String query, String status) {
        list.getChildren().clear();
        try {
            String term = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
            List<Listing> listings = DataService.getAllListings().stream()
                    .filter(l -> "All statuses".equals(status) || l.getStatus().name().equals(status))
                    .filter(l -> (l.getId() + " " + l.getTitle() + " " + l.getSellerUsername() + " " + l.getSellerFullName())
                            .toLowerCase(java.util.Locale.ROOT).contains(term)).toList();
            if (listings.isEmpty()) list.getChildren().add(new Label("No listings match these filters."));
            for (Listing l : listings) {
                VBox info = new VBox(4, new Label(l.getTitle() + " (#" + l.getId() + ")"),
                        new Label("@" + l.getSellerUsername() + " · " + l.getSellerFullName() + " · " + l.getTypeDisplay() + " · " + l.getStatus()));
                Button edit = new Button("Edit"); edit.setId("admin-listing-edit-" + l.getId());
                edit.getStyleClass().add("btn-outline");
                edit.setOnAction(e -> showManagedListingForm(l));
                Button remove = new Button("Take down"); remove.setId("admin-listing-takedown-" + l.getId());
                remove.getStyleClass().add("btn-danger"); remove.setDisable(l.getStatus() != ListingStatus.LIVE);
                remove.setOnAction(e -> showListingTakedown(l));
                VBox card = new VBox(10, info, new HBox(8, edit, remove));
                for (DataService.ListingManagementEntry entry : DataService.getListingManagementHistory(l.getId())) {
                    Label history = new Label("Taken down by @" + entry.adminUsername() + " · "
                            + entry.occurredAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH))
                            + "\nReason: " + entry.reason());
                    history.setWrapText(true); card.getChildren().add(history);
                }
                card.getStyleClass().add("card"); card.setPadding(new Insets(12));
                card.setId("admin-listing-" + l.getId());
                list.getChildren().add(card);
            }
        } catch (IllegalStateException | SecurityException ex) {
            list.getChildren().add(new Label("Could not load listings: " + ex.getMessage()));
        }
    }

    private void showManagedListingForm(Listing snapshot) {
        Listing existing;
        try {
            existing = snapshot == null ? null : DataService.getListingForAdmin(snapshot.getId());
            if (snapshot != null && (existing == null || existing.getReviewVersion() != snapshot.getReviewVersion())) {
                throw new IllegalStateException("Listing has changed. Refresh and try again.");
            }
            // Recheck authorization even when opening an empty form.
            List<User> sellers = DataService.getAllUsers();
            Dialog<Void> dialog = new Dialog<>();
            boolean editing = existing != null;
            dialog.setTitle(editing ? "Edit Listing" : "Add Listing");
            dialog.initOwner(contentPane.getScene().getWindow()); dialog.setResizable(true);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            save.setText(editing ? "Save Changes" : "Publish Listing");
            ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("Sell", "Lend", "Give Away"));
            type.setId("admin-listing-type"); type.setPromptText("Choose listing type first *");
            ComboBox<String> seller = new ComboBox<>(); seller.setId("admin-listing-seller");
            sellers.stream().filter(u -> u.getRole() == UserRole.SELLER && u.isActive()).forEach(u -> seller.getItems().add(u.getUsername()));
            seller.setPromptText("Seller username *");
            TextField name = new TextField(); name.setId("admin-listing-name");
            ComboBox<String> category = new ComboBox<>(FXCollections.observableArrayList(DataService.CATEGORIES)); category.setId("admin-listing-category");
            ComboBox<String> condition = new ComboBox<>(FXCollections.observableArrayList(DataService.CONDITIONS)); condition.setId("admin-listing-condition");
            TextArea description = new TextArea(); description.setId("admin-listing-description"); description.setPrefRowCount(3); description.setWrapText(true);
            TextField price = new TextField(); price.setId("admin-listing-price");
            TextField days = new TextField(); days.setId("admin-listing-days");
            VBox priceGroup = new VBox(4, new Label("Price (HK$, Sell only) *"), price);
            VBox daysGroup = new VBox(4, new Label("Lending duration (days, Lend only) *"), days);
            ComboBox<String> meetup = new ComboBox<>(FXCollections.observableArrayList(DataService.HKUST_LOCATIONS)); meetup.setId("admin-listing-meetup");
            java.util.ArrayList<Path> photos = new java.util.ArrayList<>();
            VBox photoList = new VBox(6);
            Label photoCount = new Label("Photos: 0 / 6 (optional)");
            CheckBox replace = new CheckBox("Replace current photos (leave unchecked to keep them)");
            replace.setId("admin-listing-replace-photos"); replace.setVisible(editing); replace.setManaged(editing);
            Button choose = new Button("Choose Photos (up to 6)"); choose.setId("admin-listing-photos"); choose.setDisable(editing);
            replace.selectedProperty().addListener((o, old, value) -> choose.setDisable(!value));
            Label error = new Label(); error.setId("admin-listing-error"); error.setWrapText(true); error.setStyle("-fx-text-fill: #c62828;");
            error.visibleProperty().bind(error.textProperty().isNotEmpty());
            error.managedProperty().bind(error.visibleProperty());
            choose.setOnAction(e -> {
                javafx.stage.FileChooser chooser = new javafx.stage.FileChooser(); chooser.setTitle("Choose item photos");
                chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Image files", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
                List<java.io.File> files = chooser.showOpenMultipleDialog(dialog.getDialogPane().getScene().getWindow());
                if (files == null) return;
                List<Path> additions = files.stream().map(java.io.File::toPath).distinct().filter(p -> !photos.contains(p)).toList();
                if (photos.size() + additions.size() > 6) { error.setText("A listing cannot have more than 6 photos."); return; }
                photos.addAll(additions); refreshManagedPhotos(photos, photoList, photoCount);
            });
            var descriptionControls = new ListingDescriptionControls(descriptionService, name, category, condition, description, save);
            VBox fields = new VBox(8, new Label("Seller username *"), seller, new Label("Item name *"), name,
                    new Label("Category *"), category, new Label("Condition *"), condition,
                    descriptionControls, description, priceGroup, daysGroup, new Label("Meet-up location *"), meetup);
            fields.setId("admin-listing-fields");
            if (editing) {
                fields.getChildren().add(createPhotoGallery(existing.getPhotoPaths(), false));
                photoCount.setText("Current photos: " + existing.getPhotoPaths().size() + ". Replacement photos: 0 / 6.");
            }
            fields.getChildren().addAll(replace, choose, photoCount, photoList);
            Runnable adapt = () -> {
                fields.setDisable(type.getValue() == null);
                priceGroup.setVisible("Sell".equals(type.getValue())); priceGroup.setManaged(priceGroup.isVisible());
                daysGroup.setVisible("Lend".equals(type.getValue())); daysGroup.setManaged(daysGroup.isVisible());
            };
            type.valueProperty().addListener((o, old, value) -> adapt.run());
            if (editing) {
                seller.setValue(existing.getSellerUsername()); seller.setDisable(true);
                type.setValue(switch (existing.getType()) { case SELL -> "Sell"; case LEND -> "Lend"; case GIVEAWAY -> "Give Away"; });
                name.setText(existing.getTitle()); category.setValue(existing.getCategory()); condition.setValue(existing.getCondition());
                description.setText(existing.getDescription()); meetup.setValue(existing.getMeetupLocation());
                if (existing.getPrice() != null) price.setText(existing.getPrice().toString());
                if (existing.getLendingDays() != null) days.setText(existing.getLendingDays().toString());
            }
            adapt.run();
            VBox form = new VBox(10, new Label(editing ? "Status: " + existing.getStatus() + " (unchanged by editing)" : "Publishes immediately for the selected seller."),
                    new Label("Listing type *"), type, fields);
            form.setPadding(new Insets(12));
            ScrollPane scroll = new ScrollPane(form); scroll.setFitToWidth(true); scroll.setPrefViewportWidth(600); scroll.setPrefViewportHeight(520);
            VBox dialogContent = new VBox(8, error, scroll);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            dialog.getDialogPane().setContent(dialogContent);
            save.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume(); error.setText("");
                if (type.getValue() == null || seller.getValue() == null || name.getText().isBlank() || category.getValue() == null
                        || condition.getValue() == null || description.getText().isBlank() || meetup.getValue() == null) {
                    error.setText("Complete all required listing details."); return;
                }
                Listing draft = new Listing();
                draft.setTitle(name.getText()); draft.setDescription(description.getText()); draft.setCategory(category.getValue());
                draft.setCondition(condition.getValue()); draft.setMeetupLocation(meetup.getValue());
                try {
                    switch (type.getValue()) {
                        case "Sell" -> {
                            double value = Double.parseDouble(price.getText().trim());
                            if (!Double.isFinite(value) || value < 0) throw new NumberFormatException();
                            draft.setType(ListingType.SELL); draft.setPrice(value);
                        }
                        case "Lend" -> {
                            int value = Integer.parseInt(days.getText().trim()); if (value <= 0) throw new NumberFormatException();
                            draft.setType(ListingType.LEND); draft.setLendingDays(value);
                        }
                        default -> draft.setType(ListingType.GIVEAWAY);
                    }
                } catch (NumberFormatException ex) { error.setText("Enter a non-negative price for Sell or a positive whole number of days for Lend."); return; }
                if (editing) { draft.setId(existing.getId()); draft.setReviewVersion(existing.getReviewVersion()); }
                String descriptionConfirmation = descriptionControls.saveConfirmation();
                List<Path> selectedPhotos = List.copyOf(photos); boolean replacePhotos = replace.isSelected(); String selectedSeller = seller.getValue();
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Confirm Listing Changes"); confirm.setHeaderText((editing ? "Save changes to " : "Publish ") + draft.getTitle().trim() + "?");
                confirm.setContentText("Seller: @" + selectedSeller + "\nType: " + type.getValue() + "\nStatus: " + (editing ? existing.getStatus() : "LIVE"));
                confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
                save.setDisable(true);
                confirm.setOnHidden(e -> {
                    if (confirm.getResult() != ButtonType.OK) {
                        save.setDisable(false);
                        return;
                    }
                    fields.setDisable(true); type.setDisable(true);
                    ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setDisable(true);
                    dialog.setOnCloseRequest(close -> close.consume());
                    javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
                        @Override protected Void call() {
                            if (editing) DataService.updateListingAsAdmin(draft, selectedPhotos, replacePhotos);
                            else DataService.addListingForSeller(selectedSeller, draft, selectedPhotos);
                            return null;
                        }
                    };
                    task.setOnSucceeded(done -> {
                        dialog.setOnCloseRequest(null); dialog.close();
                        listingActionMessage = (editing ? "Listing changes saved. Status unchanged." : "Listing published. Seller notified.")
                                + descriptionConfirmation;
                        showAllListings();
                    });
                    task.setOnFailed(done -> {
                        error.setText("Could not save listing: " + task.getException().getMessage());
                        save.setDisable(false); fields.setDisable(false); type.setDisable(false);
                        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setDisable(false); dialog.setOnCloseRequest(null);
                    });
                    Thread worker = new Thread(task, "admin-listing-save"); worker.setDaemon(true); worker.start();
                }); DialogStyle.show(confirm);
            });
            DialogStyle.show(dialog);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            Alert error = new Alert(Alert.AlertType.ERROR, ex.getMessage()); error.initOwner(contentPane.getScene().getWindow()); DialogStyle.show(error);
            showAllListings();
        }
    }

    private void refreshManagedPhotos(java.util.List<Path> photos, VBox list, Label count) {
        list.getChildren().clear(); count.setText("Selected photos: " + photos.size() + " / 6");
        for (Path photo : List.copyOf(photos)) {
            ImageView preview = new ImageView(new Image(photo.toUri().toString(), 72, 72, true, true));
            Button remove = new Button("Remove photo");
            remove.setOnAction(e -> { photos.remove(photo); refreshManagedPhotos(photos, list, count); });
            Label file = new Label(photo.getFileName().toString()); file.setWrapText(true);
            list.getChildren().add(new HBox(8, preview, file, remove));
        }
    }

    private void showListingTakedown(Listing listing) {
        Dialog<Void> dialog = new Dialog<>(); dialog.setTitle("Take Down Listing");
        dialog.setHeaderText(listing.getTitle() + " (#" + listing.getId() + ")");
        dialog.initOwner(contentPane.getScene().getWindow()); dialog.setResizable(true);
        TextArea reason = new TextArea(); reason.setId("admin-listing-takedown-reason"); reason.setWrapText(true); reason.setPrefRowCount(3);
        Label policy = new Label("The listing will be retained as Removed. The seller and buyers with pending requests will be notified. Pending requests will be declined. Accepted deals and chats are retained.");
        policy.setWrapText(true); policy.setMaxWidth(480);
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill: #c62828;");
        dialog.getDialogPane().setContent(new VBox(10, policy, new Label("Reason *"), reason, error));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button proceed = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); proceed.setText("Continue");
        proceed.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); error.setText("");
            if (reason.getText().isBlank()) { error.setText("A takedown reason is required."); return; }
            String writtenReason = reason.getText().trim();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Listing Takedown"); confirm.setHeaderText("Take down " + listing.getTitle() + "?");
            confirm.setContentText("Listing #" + listing.getId() + "\nReason: " + writtenReason);
            confirm.initOwner(dialog.getDialogPane().getScene().getWindow());
            confirm.setOnHidden(e -> {
                if (confirm.getResult() != ButtonType.OK) return;
                try {
                    DataService.takeDownListing(listing.getId(), writtenReason, listing.getReviewVersion());
                    dialog.close(); listingActionMessage = "Listing taken down. Seller and pending buyers notified.";
                    showAllListings();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    error.setText("Could not take down listing: " + ex.getMessage());
                }
            }); DialogStyle.show(confirm);
        });
        DialogStyle.show(dialog);
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
            DialogStyle.showAndWait(details);
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
            DialogStyle.showAndWait(edit);
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
        if (DialogStyle.showAndWait(confirmation).orElse(ButtonType.CANCEL) != ButtonType.OK) return;
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
            DialogStyle.showAndWait(dialog);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            showUserManagementError(ex);
        }
    }

    private void showUserManagementError(RuntimeException failure) {
        Alert error = new Alert(Alert.AlertType.ERROR, failure.getMessage());
        error.setTitle("User Change Not Saved");
        DialogStyle.showAndWait(error);
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
            DialogStyle.show(dialog);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            Alert error = new Alert(Alert.AlertType.ERROR, ex.getMessage(), ButtonType.CLOSE);
            error.initOwner(contentPane.getScene().getWindow());
            error.setTitle("Deal Unavailable");
            error.setHeaderText("Could not load deal details");
            DialogStyle.show(error);
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

        TextField userFilter = new TextField();
        userFilter.setId("admin-ratings-user");
        userFilter.setPromptText("Buyer or seller username");
        userFilter.setPrefColumnCount(18);
        ComboBox<String> scoreFilter = new ComboBox<>();
        scoreFilter.setId("admin-ratings-score");
        scoreFilter.getItems().add("All scores");
        for (int half = 0; half <= 10; half++) scoreFilter.getItems().add(Double.toString(half / 2.0));
        scoreFilter.setEditable(true);
        scoreFilter.setValue("All scores");
        scoreFilter.getEditor().setStyle("-fx-padding: 0; -fx-border-width: 0; -fx-background-color: transparent; -fx-effect: null;");
        scoreFilter.setPrefWidth(145);
        scoreFilter.setAccessibleText("Exact rating score from zero to five, or All scores");
        DatePicker from = new DatePicker(); from.setId("admin-ratings-from"); from.setEditable(false);
        DatePicker to = new DatePicker(); to.setId("admin-ratings-to"); to.setEditable(false);
        from.setPromptText("From date"); to.setPromptText("To date");
        from.setPrefWidth(150); to.setPrefWidth(150);
        Label count = new Label(); count.setId("admin-ratings-count");
        Label feedback = new Label(); feedback.setId("admin-ratings-feedback");
        feedback.setWrapText(true);
        feedback.visibleProperty().bind(feedback.textProperty().isNotEmpty());
        feedback.managedProperty().bind(feedback.visibleProperty());
        VBox list = new VBox(10); list.setId("admin-ratings-list");
        Runnable render = () -> renderRatings(list, count, feedback, userFilter.getText(),
                scoreFilter.getEditor().getText(), from.getValue(), to.getValue());
        userFilter.textProperty().addListener((obs, old, value) -> render.run());
        scoreFilter.getEditor().textProperty().addListener((obs, old, value) -> render.run());
        from.valueProperty().addListener((obs, old, value) -> render.run());
        to.valueProperty().addListener((obs, old, value) -> render.run());
        Button clear = new Button("Clear filters"); clear.setId("admin-ratings-clear");
        clear.getStyleClass().add("btn-outline");
        clear.setOnAction(event -> {
            userFilter.clear(); scoreFilter.setValue("All scores"); scoreFilter.getEditor().setText("All scores");
            from.setValue(null); to.setValue(null); render.run();
        });
        Button refresh = new Button("Refresh"); refresh.setId("admin-ratings-refresh");
        refresh.getStyleClass().add("btn-outline"); refresh.setOnAction(event -> render.run());
        FlowPane filters = new FlowPane(12, 8,
                new VBox(4, new Label("User"), userFilter), new VBox(4, new Label("Score (0–5)"), scoreFilter),
                new VBox(4, new Label("From (inclusive)"), from), new VBox(4, new Label("To (inclusive)"), to));
        filters.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(filters, new HBox(10, clear, refresh), feedback, count, list);
        ScrollPane scroll = new ScrollPane(root); scroll.setFitToWidth(true);
        contentPane.getChildren().add(scroll);
        render.run();
    }

    private void renderRatings(VBox list, Label count, Label feedback, String user, String scoreText,
                               java.time.LocalDate from, java.time.LocalDate to) {
        list.getChildren().clear(); count.setText(""); feedback.setText("");
        List<Review> reviews;
        try {
            Double score = null;
            if (scoreText != null && !scoreText.isBlank() && !"All scores".equals(scoreText.trim())) {
                try { score = Double.valueOf(scoreText.trim()); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Enter a rating between 0.0 and 5.0, or select All scores."); }
            }
            reviews = DataService.getAllReviews(user, score, from, to);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            feedback.setText("Could not load ratings: " + ex.getMessage());
            feedback.setStyle("-fx-text-fill: #c0392b;");
            return;
        }
        count.setText(reviews.size() + (reviews.size() == 1 ? " rating" : " ratings"));
        if (reviews.isEmpty()) list.getChildren().add(new Label("No ratings match these filters."));
        for (Review r : reviews) {
            HBox row = new HBox(12);
            row.getStyleClass().add("card");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12));

            VBox info = new VBox(4);
            info.setMinWidth(0);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label stars = new Label(r.getScore() + " / 5  ·  @" + r.getSellerUsername() + " by @" + r.getBuyerUsername());
            stars.setWrapText(true);
            stars.setStyle("-fx-font-weight: bold;");
            Label comment = new Label(r.getComment() == null || r.getComment().isBlank()
                    ? "(No written feedback)" : r.getComment());
            comment.setWrapText(true);
            comment.setStyle("-fx-text-fill: #5a5a7a;");
            Label details = new Label(r.getListingTitle() + "  ·  "
                    + (r.getCreatedAt() == null ? "Date unavailable" : r.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                    + "  ·  " + (r.isHidden() ? "Hidden" : "Visible"));
            details.setWrapText(true);
            info.getChildren().addAll(stars, details, comment);
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

            Button hide = new Button(r.isHidden() ? "Restore" : "Hide");
            hide.setMinWidth(Region.USE_PREF_SIZE);
            hide.setId("admin-rating-hide-" + r.getId() + "-" + r.getBuyerUsername());
            hide.getStyleClass().addAll("btn-outline", "btn-sm");
            hide.setOnAction(e -> {
                boolean hidden = !r.isHidden();
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        (hidden ? "Hide" : "Restore") + " the rating for '" + r.getListingTitle()
                                + "' by @" + r.getBuyerUsername() + " for @" + r.getSellerUsername() + "?\n\n"
                                + (hidden ? "It will be excluded from public reviews and the seller's rating totals. Admins can restore it later."
                                : "It will appear in public reviews and count toward the seller's rating totals again."),
                        ButtonType.OK, ButtonType.CANCEL);
                confirm.initOwner(contentPane.getScene().getWindow());
                confirm.setTitle("Confirm Rating Visibility");
                confirm.setHeaderText(hidden ? "Hide rating" : "Restore rating");
                confirm.setOnHidden(event -> {
                    if (confirm.getResult() != ButtonType.OK) return;
                    try {
                        DataService.setReviewHidden(r.getBuyerUsername(), r.getId(), hidden);
                        renderRatings(list, count, feedback, user, scoreText, from, to);
                        feedback.setText(hidden ? "Rating hidden successfully." : "Rating restored successfully.");
                        feedback.setStyle("-fx-text-fill: #16804a;");
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                        renderRatings(list, count, feedback, user, scoreText, from, to);
                        feedback.setText("Could not update rating: " + ex.getMessage());
                        feedback.setStyle("-fx-text-fill: #c0392b;");
                    }
                });
                DialogStyle.show(confirm);
            });
            row.getChildren().addAll(info, hide);
            list.getChildren().add(row);
        }
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notification Board");
        contentPane.getChildren().clear();
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        VBox list = new VBox(8);
        list.setId("admin-notification-list");
        ComboBox<String> categories = new ComboBox<>(FXCollections.observableArrayList(
                "All", "Listing Review", "Listing Report", "Account Update", "Announcement", "System", "Offer", "Chat", "Reminder", "Rating", "Deal"));
        categories.setId("admin-notification-category");
        categories.setValue(notificationCategory);
        categories.setAccessibleText("Notification category");
        ComboBox<String> state = new ComboBox<>(FXCollections.observableArrayList("All", "Unread", "Read"));
        state.setId("admin-notification-state");
        state.setValue(notificationState);
        state.setAccessibleText("Notification read status");
        categories.valueProperty().addListener((obs, old, value) -> { notificationCategory = value; renderAdminNotifications(list); });
        state.valueProperty().addListener((obs, old, value) -> { notificationState = value; renderAdminNotifications(list); });
        Button clear = new Button("Clear filters");
        clear.setId("admin-notification-clear");
        clear.getStyleClass().add("btn-outline");
        clear.setOnAction(event -> { categories.setValue("All"); state.setValue("All"); });
        Button refresh = new Button("Refresh");
        refresh.setId("admin-notification-refresh");
        refresh.getStyleClass().add("btn-outline");
        refresh.setOnAction(event -> renderAdminNotifications(list));
        Button readAll = new Button("Read All Notifications");
        readAll.setId("admin-notification-read-all");
        readAll.getStyleClass().add("btn-outline");
        readAll.setOnAction(event -> {
            try {
                DataService.markAllCurrentUserNotificationsRead();
                renderAdminNotifications(list);
                refreshNotificationDot();
            } catch (IllegalStateException | SecurityException ex) {
                list.getChildren().removeIf(node -> "admin-notification-error".equals(node.getId()));
                Label error = new Label("Could not mark notifications as read: " + ex.getMessage());
                error.setId("admin-notification-error");
                error.setWrapText(true);
                error.setStyle("-fx-text-fill: #c62828;");
                list.getChildren().add(0, error);
            }
        });
        FlowPane filters = new FlowPane(10, 8, new Label("Category:"), categories, new Label("Status:"), state, clear, refresh, readAll);
        filters.setAlignment(Pos.CENTER_LEFT);
        renderAdminNotifications(list);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().addAll(filters, scroll);
        contentPane.getChildren().add(root);
        refreshNotificationDot();
    }

    private void renderAdminNotifications(VBox list) {
        list.getChildren().clear();
        try {
            List<Notification> all = DataService.getAdminNotifications();
            List<Notification> notifications = all.stream()
                    .filter(n -> "All".equals(notificationCategory) || notificationCategory.equalsIgnoreCase(n.getCategory()))
                    .filter(n -> "All".equals(notificationState) || n.isRead() == "Read".equals(notificationState)).toList();
            if (notifications.isEmpty()) list.getChildren().add(new Label(all.isEmpty()
                    ? "No notifications." : "No notifications match these filters."));
            for (Notification n : notifications) {
                VBox item = new VBox(6);
                item.setId("admin-notification-" + n.getId());
                item.getStyleClass().add("notif-item");
                if (!n.isRead()) item.setStyle("-fx-border-color: #003974; -fx-border-width: 0 0 0 3;");
                Label title = new Label(n.getTitle());
                title.setWrapText(true);
                title.setStyle("-fx-font-weight: bold;");
                Label message = new Label(n.getMessage());
                message.setWrapText(true);
                message.setStyle("-fx-text-fill: #5a5a7a;");
                Label meta = new Label(n.getCategory() + " · " + (n.getTimestamp() == null ? "Time not recorded"
                        : n.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH)))
                        + " · " + (n.isRead() ? "Read" : "Unread"));
                meta.setWrapText(true);
                meta.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 11px;");
                Button open = new Button("Go ->");
                open.setId("open-admin-notification-" + n.getId());
                open.getStyleClass().addAll("btn-outline", "btn-sm");
                open.setOnAction(event -> openAdminNotification(n, list));
                // Consume the button's mouse event so it does not also activate its card.
                open.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.scene.input.MouseEvent::consume);
                item.setCursor(javafx.scene.Cursor.HAND);
                item.setFocusTraversable(true);
                item.setAccessibleText(n.getTitle() + ". " + n.getCategory() + ". " + (n.isRead() ? "Read" : "Unread"));
                item.setOnKeyPressed(event -> {
                    if (event.getTarget() == item && (event.getCode() == javafx.scene.input.KeyCode.ENTER
                            || event.getCode() == javafx.scene.input.KeyCode.SPACE)) {
                        event.consume();
                        openAdminNotification(n, list);
                    }
                });
                item.setOnMouseClicked(event -> {
                    if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) openAdminNotification(n, list);
                });
                item.getChildren().addAll(title, message, meta, open);
                list.getChildren().add(item);
            }
        } catch (IllegalStateException | SecurityException ex) {
            Label error = new Label("Could not load notifications: " + ex.getMessage());
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #c62828;");
            list.getChildren().add(error);
        }
    }

    private void openAdminNotification(Notification notification, VBox list) {
        try {
            String id = notification.getId();
            notification = DataService.getAdminNotifications().stream().filter(n -> id.equals(n.getId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Notification is no longer available. Refresh the board."));
            String title = notification.getTitle() == null ? "" : notification.getTitle().toLowerCase();
            if (title.contains("flagged") || title.contains("moderation")) {
                DataService.markAdminNotificationRead(notification.getId());
                refreshNotificationDot();
                showRatings();
                return;
            }
            if (notification.getListingId() != null) {
                Listing listing = DataService.getListingForAdmin(notification.getListingId().toString());
                if (listing == null) throw new IllegalStateException("This listing no longer exists. Refresh the notification board.");
                DataService.markAdminNotificationRead(notification.getId());
                refreshNotificationDot();
                if ("Listing Report".equals(notification.getCategory()) || listing.getStatus() != ListingStatus.PENDING_REVIEW) {
                    showNotificationListing(notification, listing);
                } else {
                    showListingDetails(listing, true);
                }
            } else if (notification.getTargetUsername() != null) {
                String username = notification.getTargetUsername();
                if (username.equalsIgnoreCase(SessionManager.getCurrentUser().getUsername())) {
                    DataService.markAdminNotificationRead(notification.getId());
                    showProfile();
                } else {
                    AdminUserService.Account account = AdminUserService.getAllUsers(true).stream()
                            .filter(user -> username.equalsIgnoreCase(user.username())).findFirst().orElse(null);
                    DataService.markAdminNotificationRead(notification.getId());
                    if (account != null) {
                        includeDeletedUsers = account.deletedAt() != null;
                        showUsers();
                        viewManagedUser(account);
                    } else {
                        renderAdminNotifications(list);
                        showNotificationMessage(notification);
                    }
                }
            } else {
                DataService.markAdminNotificationRead(notification.getId());
                renderAdminNotifications(list);
                showNotificationMessage(notification);
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            if (ex instanceof SecurityException) list.getChildren().clear();
            list.getChildren().removeIf(node -> "admin-notification-error".equals(node.getId()));
            Label error = new Label("Could not open notification: " + ex.getMessage());
            error.setId("admin-notification-error");
            error.setWrapText(true);
            error.setStyle("-fx-text-fill: #c62828;");
            list.getChildren().add(0, error);
        }
    }

    private void showNotificationMessage(Notification notification) {
        Dialog<Void> details = new Dialog<>();
        details.setTitle("Notification");
        details.setHeaderText(notification.getTitle());
        details.initOwner(contentPane.getScene().getWindow());
        Label message = new Label(notification.getMessage());
        message.setWrapText(true);
        Label meta = new Label(notification.getCategory() + " · " + (notification.getTimestamp() == null
                ? "Time not recorded" : notification.getTimestamp().format(
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH))));
        VBox body = new VBox(12, meta, message);
        body.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(580);
        scroll.setPrefViewportHeight(300);
        details.getDialogPane().setContent(scroll);
        details.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        details.setResizable(true);
        DialogStyle.show(details);
    }

    private void showNotificationListing(Notification notification, Listing listing) {
        boolean pendingReview = listing.getStatus() == ListingStatus.PENDING_REVIEW
                && !"Listing Report".equals(notification.getCategory());
        renderListingDetail(listing, true, pendingReview, notification.getMessage());
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
        Button clearDatabase = new Button("Clear Database");
        clearDatabase.setId("profile-clear-database");
        clearDatabase.getStyleClass().add("btn-danger");
        clearDatabase.setOnAction(event -> confirmClearDatabase());
        Label clearHint = new Label("Deletes every listing, deal, review, notification, other account, and chat_history.json. Your admin login is kept.");
        clearHint.setWrapText(true);
        clearHint.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        form.getChildren().addAll(new Label("Username"), username, new Label("Full Name"), name,
                new Label("New Password"), pass, rules, new Label("Confirm Password"), confirm, error, success, save,
                clearDatabase, clearHint);
        contentPane.getChildren().add(form);
    }

    /**
     * Asks for confirmation, then clears {@code data.db} and {@code chat_history.json}.
     * Cancel leaves both files unchanged. A failure is shown in an error dialog.
     */
    private void confirmClearDatabase() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Clear Database");
        confirmation.setHeaderText("Clear all marketplace data?");
        confirmation.setContentText("This removes users, listings, deals, reviews, notifications, and chat_history.json. Your admin account is kept. This cannot be undone.");
        if (DialogStyle.showAndWait(confirmation).orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            DatabaseManager.clearDatabase();
            refreshNotificationDot();
            Alert success = new Alert(Alert.AlertType.INFORMATION,
                    "The database and chat history were cleared.");
            success.setTitle("Database Cleared");
            success.setHeaderText(null);
            DialogStyle.showAndWait(success);
            showProfile();
        } catch (IllegalStateException ex) {
            Alert error = new Alert(Alert.AlertType.ERROR, DialogStyle.userFacing(ex, "Could not clear the database."));
            error.setTitle("Database Not Cleared");
            error.setHeaderText(null);
            DialogStyle.showAndWait(error);
        }
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
