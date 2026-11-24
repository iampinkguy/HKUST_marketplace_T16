package com.hkust.marketplace.controllers.seller;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.DialogStyle;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import com.hkust.marketplace.utils.StarRating;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SellerDashboardController {

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnListings, btnCreate, btnStats, btnReviews, btnNotifications, btnProfile;
    @FXML private Circle notifUnreadDot;

    private Button currentActiveBtn;
    private String notificationCategory = "All";
    private Timeline notificationTicker;

    private String listingKeyword = "";
    private String listingCategory = "All Categories";
    private String listingType = "All Types";
    private String listingStatus = "All Statuses";

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnListings;
        ensureNotificationDot();
        notificationTicker = new Timeline(new KeyFrame(Duration.seconds(2), event -> refreshNotificationDot()));
        notificationTicker.setCycleCount(Timeline.INDEFINITE);
        notificationTicker.play();
        refreshNotificationDot();
        showMyListings();
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
    private void showMyListings() {
        setActive(btnListings, "My Listings");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        HBox tools = new HBox(8);
        tools.setAlignment(Pos.CENTER_LEFT);
        Button filterBtn = new Button("Filter");
        filterBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        filterBtn.setOnAction(event -> openListingFilter());
        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        clearBtn.setDisable(!hasListingFilters());
        clearBtn.setOnAction(event -> {
            listingKeyword = "";
            listingCategory = "All Categories";
            listingType = "All Types";
            listingStatus = "All Statuses";
            showMyListings();
        });
        Label summary = new Label(listingFilterSummary());
        summary.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        tools.getChildren().addAll(filterBtn, clearBtn, summary);
        root.getChildren().add(tools);

        List<Listing> myListings;
        try {
            myListings = filteredSellerListings(DataService.getSellerListings());
        } catch (IllegalStateException | SecurityException e) {
            showProfileError("Could not load your listings: " + e.getMessage());
            return;
        }

        if (myListings.isEmpty()) {
            Label empty = new Label(hasListingFilters()
                    ? "No listings match the current filters."
                    : "You have not posted any listings yet.");
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

    private void openListingFilter() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Filter listings");
        dialog.setHeaderText("Narrow down your listings");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Apply");

        TextField keywordField = new TextField(listingKeyword);
        keywordField.setPromptText("Title keyword");
        ComboBox<String> categories = new ComboBox<>();
        categories.getItems().add("All Categories");
        categories.getItems().addAll(DataService.CATEGORIES);
        categories.setValue(categories.getItems().contains(listingCategory) ? listingCategory : "All Categories");
        categories.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> types = new ComboBox<>(FXCollections.observableArrayList(
                "All Types", "For Sale", "For Lending", "Giveaway"));
        types.setValue(types.getItems().contains(listingType) ? listingType : "All Types");
        types.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> statuses = new ComboBox<>(FXCollections.observableArrayList(
                "All Statuses",
                "Active (Live / Pending / Reserved / Rejected)",
                "Finished (Sold / Lent / Given / Removed)",
                "Pending Review", "Live", "Rejected", "Reserved",
                "Sold", "Lent", "Given", "Removed"));
        statuses.setValue(statuses.getItems().contains(listingStatus) ? listingStatus : "All Statuses");
        statuses.setMaxWidth(Double.MAX_VALUE);

        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Keyword"), keywordField,
                new Label("Category"), categories,
                new Label("Listing type"), types,
                new Label("Status"), statuses));
        DialogStyle.showAndWait(dialog).ifPresent(response -> {
            if (response != ButtonType.OK) return;
            listingKeyword = keywordField.getText() == null ? "" : keywordField.getText().trim();
            listingCategory = categories.getValue();
            listingType = types.getValue();
            listingStatus = statuses.getValue();
            showMyListings();
        });
    }

    private List<Listing> filteredSellerListings(List<Listing> listings) {
        String needle = listingKeyword.toLowerCase(Locale.ROOT);
        List<Listing> filtered = new ArrayList<>();
        for (Listing listing : listings) {
            if (!"All Categories".equals(listingCategory) && !listingCategory.equals(listing.getCategory())) {
                continue;
            }
            if (!"All Types".equals(listingType) && !listingType.equals(listing.getTypeDisplay())) {
                continue;
            }
            if (!matchesListingStatus(listing.getStatus(), listingStatus)) continue;
            if (!needle.isEmpty()) {
                String title = listing.getTitle() == null ? "" : listing.getTitle().toLowerCase(Locale.ROOT);
                String id = listing.getId() == null ? "" : listing.getId().toLowerCase(Locale.ROOT);
                if (!title.contains(needle) && !id.contains(needle)) continue;
            }
            filtered.add(listing);
        }
        return filtered;
    }

    private boolean matchesListingStatus(ListingStatus status, String filter) {
        if (filter == null || "All Statuses".equals(filter)) return true;
        return switch (filter) {
            case "Active (Live / Pending / Reserved / Rejected)" ->
                    status == ListingStatus.LIVE || status == ListingStatus.PENDING_REVIEW
                            || status == ListingStatus.RESERVED || status == ListingStatus.REJECTED;
            case "Finished (Sold / Lent / Given / Removed)" ->
                    status == ListingStatus.SOLD || status == ListingStatus.LENT
                            || status == ListingStatus.GIVEN || status == ListingStatus.REMOVED;
            case "Pending Review" -> status == ListingStatus.PENDING_REVIEW;
            case "Live" -> status == ListingStatus.LIVE;
            case "Rejected" -> status == ListingStatus.REJECTED;
            case "Reserved" -> status == ListingStatus.RESERVED;
            case "Sold" -> status == ListingStatus.SOLD;
            case "Lent" -> status == ListingStatus.LENT;
            case "Given" -> status == ListingStatus.GIVEN;
            case "Removed" -> status == ListingStatus.REMOVED;
            default -> true;
        };
    }

    private boolean hasListingFilters() {
        return !listingKeyword.isBlank()
                || !"All Categories".equals(listingCategory)
                || !"All Types".equals(listingType)
                || !"All Statuses".equals(listingStatus);
    }

    private String listingFilterSummary() {
        String search = listingKeyword.isBlank() ? "all keywords" : "\"" + listingKeyword + "\"";
        return "Filter: " + search + " · " + listingCategory + " · " + listingType + " · " + listingStatus;
    }

    private String requestDescription(Map<String, Object> request) {
        String type = (String) request.get("request_type");
        return switch (type == null ? "" : type) {
            case "OFFER" -> "Offer: HK$" + request.get("offer_price");
            case "BORROW" -> {
                Object days = request.get("proposed_days");
                yield "Borrow request: " + days + (Integer.valueOf(1).equals(days) ? " day" : " days");
            }
            case "CLAIM" -> "Giveaway claim";
            default -> "Request";
        };
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

        boolean mutable = false;
        try {
            mutable = DataService.canSellerModifyListing(l.getId());
        } catch (IllegalStateException | SecurityException ignored) {
            mutable = false;
        }
        if (!mutable) {
            String message = switch (l.getStatus()) {
                case LIVE, PENDING_REVIEW, REJECTED -> "Resolve open requests before editing or deleting this listing.";
                default -> "Edit and delete are unavailable while the listing is " + statusLabel(l.getStatus()) + ".";
            };
            Label locked = new Label(message);
            locked.setStyle("-fx-text-fill: #8a5a00; -fx-font-size: 11px;");
            locked.setWrapText(true);
            info.getChildren().add(locked);
        }

        Button editBtn = new Button("Edit");
        editBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        editBtn.setDisable(!mutable);
        editBtn.setOnAction(e -> showListingForm(l));

        Button delBtn = new Button("Delete");
        delBtn.getStyleClass().addAll("btn-danger", "btn-sm");
        delBtn.setDisable(!mutable);
        delBtn.setOnAction(e -> {
            Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                    "Remove '" + l.getTitle() + "' from the marketplace?", ButtonType.CANCEL, ButtonType.OK);
            conf.setHeaderText("Delete listing");
            DialogStyle.showAndWait(conf).ifPresent(r -> {
                if (r == ButtonType.OK) {
                    try {
                        DataService.deleteSellerListing(l.getId(), l.getReviewVersion());
                        DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION, "Listing deleted successfully."));
                        showMyListings();
                    } catch (IllegalStateException | SecurityException | IllegalArgumentException ex) {
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
                DialogStyle.showAndWait(new Alert(Alert.AlertType.WARNING, "You can upload a maximum of 6 photos."));
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
                DialogStyle.showAndWait(new Alert(Alert.AlertType.WARNING,
                        "Complete all required fields. Enter a non-negative price for Sell or a positive duration for Lend."));
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
                    DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION,
                            "Changes saved. Your listing is Pending Review until an admin re-approves it."));
                } else {
                    DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION,
                            "Listing submitted successfully. Status: Pending Review.\nAn admin has been notified."));
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
                DialogStyle.showAndWait(new Alert(Alert.AlertType.ERROR, "Could not save listing: " + message));
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
        showListingDetails(listing, false);
    }

    private void showListingDetails(Listing listing, boolean fromNotification) {
        setActive(fromNotification ? btnNotifications : btnListings, "Listing Detail");
        contentPane.getChildren().clear();

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("X");
        close.getStyleClass().addAll("btn-outline", "btn-sm");
        close.setOnAction(e -> {
            if (fromNotification) showNotifications();
            else showMyListings();
        });
        header.getChildren().addAll(
                styled(listing.getTitle(), "-fx-font-size: 22px; -fx-font-weight: bold;"),
                spacer,
                close);

        FlowPane photos = createPhotoGallery(listing.getPhotoPaths());

        VBox facts = new VBox(6);
        facts.getChildren().addAll(
                fact("Description", listing.getDescription()),
                fact("Category", listing.getCategory()),
                fact("Type", listing.getTypeDisplay()),
                fact("Price", listing.getPriceDisplay()),
                fact("Condition", listing.getCondition()),
                fact("Meet-up location", listing.getMeetupLocation()),
                fact("Posted", formatDate(listing.getPostedAt())),
                fact("Availability", listing.getAvailabilityStatus() + " · " + listing.getStatus()),
                fact("Views", Integer.toString(listing.getViewCount()))
        );
        if (listing.getType() == ListingType.LEND) {
            String period = listing.getLendingDays() == null || listing.getLendingDays() <= 0
                    ? "Not set"
                    : listing.getLendingDays() + (listing.getLendingDays() == 1 ? " day" : " days");
            facts.getChildren().add(3, fact("Lending period", period));
        }
        if (listing.getRejectionReason() != null && !listing.getRejectionReason().isBlank()) {
            facts.getChildren().add(fact("Admin feedback", listing.getRejectionReason()));
        }

        HBox actions = new HBox(8);
        Button edit = new Button("Edit Listing");
        edit.getStyleClass().add("btn-primary");
        boolean canEdit = false;
        try {
            canEdit = DataService.canSellerModifyListing(listing.getId());
        } catch (IllegalStateException | SecurityException ignored) {
            canEdit = false;
        }
        edit.setDisable(!canEdit);
        edit.setOnAction(e -> showListingForm(listing));
        Button openChat = new Button("Open Chat Room");
        openChat.getStyleClass().add("btn-primary");
        actions.getChildren().addAll(edit, openChat);
        Map<String, Object> completableDeal = null;
        try {
            completableDeal = DataService.getSellerCompletableDeal(listing.getId());
        } catch (IllegalStateException | SecurityException ignored) {
            completableDeal = null;
        }
        Label completeStatus = new Label();
        completeStatus.setWrapText(true);
        completeStatus.setStyle("-fx-text-fill: #8a5a00;");
        if (completableDeal != null) {
            if (completableDeal.get("seller_completed_at") != null) {
                completeStatus.setText("You marked complete. Waiting for the buyer to mark complete.");
            } else {
                Button complete = new Button("Mark Complete");
                complete.getStyleClass().add("btn-gold");
                complete.setOnAction(e -> markListingComplete(listing));
                actions.getChildren().add(complete);
                if (completableDeal.get("buyer_completed_at") != null) {
                    completeStatus.setText("Buyer already marked complete. Mark complete to finish the listing.");
                } else {
                    completeStatus.setText("Both buyer and seller must mark complete to finish this listing.");
                }
            }
        }

        Label requestsTitle = styled("Incoming offers & requests", "-fx-font-weight: bold;");
        VBox requests = new VBox(10);
        Label requestFeedback = new Label();
        requestFeedback.setWrapText(true);
        requestFeedback.setStyle("-fx-text-fill: #8a5a00;");
        openChat.setOnAction(e -> showSellerListingChat(listing, null, requests, requestFeedback));

        root.getChildren().add(header);
        root.getChildren().add(photos);
        root.getChildren().add(facts);
        root.getChildren().add(actions);
        if (completeStatus.getText() != null && !completeStatus.getText().isBlank()) {
            root.getChildren().add(completeStatus);
        }
        root.getChildren().addAll(requestsTitle, requestFeedback, requests);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        contentPane.getChildren().add(scroll);
        refreshListingRequests(listing, requests, requestFeedback);
    }

    private void markListingComplete(Listing listing) {
        Alert conf = new Alert(Alert.AlertType.CONFIRMATION,
                "Mark '" + listing.getTitle()
                        + "' as complete? The listing finishes only after both you and the buyer mark complete.",
                ButtonType.CANCEL, ButtonType.OK);
        conf.setHeaderText("Mark listing complete");
        DialogStyle.showAndWait(conf).ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                boolean fullyDone = DataService.completeSellerListing(listing.getId(), listing.getReviewVersion());
                DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION, fullyDone
                        ? "Both sides marked complete. The listing is finished."
                        : "Your completion is saved. Waiting for the buyer to mark complete."));
                if (fullyDone) showMyListings();
                else showListingDetails(listing, false);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                showProfileError("Could not complete listing: " + ex.getMessage());
            }
        });
    }

    private void showSellerListingChat(Listing listing, String buyerUsername, VBox requests, Label requestFeedback) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Chat room");
        dialog.setHeaderText(buyerUsername == null || buyerUsername.isBlank()
                ? "Chat about " + listing.getTitle()
                : "Chat with " + buyerUsername + " about " + listing.getTitle());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox chat = createSellerListingChat(listing, buyerUsername);
        chat.setPrefWidth(480);
        dialog.getDialogPane().setContent(chat);
        DialogStyle.showAndWait(dialog);
        refreshListingRequests(listing, requests, requestFeedback);
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

    private String formatDate(LocalDateTime value) {
        return value == null
                ? "Date unavailable"
                : value.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH));
    }

    private VBox createSellerListingChat(Listing listing, String preferredBuyer) {
        VBox chat = new VBox(8);
        ComboBox<String> buyerSelector = new ComboBox<>();
        buyerSelector.setPromptText("Select buyer conversation");
        buyerSelector.setMaxWidth(Double.MAX_VALUE);
        TextField search = new TextField();
        search.setPromptText("Search this chat history");
        ListView<Message> messages = new ListView<>();
        messages.setPrefHeight(220);
        messages.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                    setStyle("");
                } else {
                    String timestamp = message.getTimestamp() == null ? ""
                            : message.getTimestamp().format(
                            DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.ENGLISH));
                    boolean system = "system".equalsIgnoreCase(message.getSender_role());
                    String who = system ? "Announcement" : message.getSender_id();
                    setText(who + " (" + timestamp + ")\n" + message.getContent());
                    setWrapText(true);
                    setStyle(system ? "-fx-text-fill: #8a5a00; -fx-font-style: italic;" : "");
                }
            }
        });
        Label feedback = new Label();
        feedback.setWrapText(true);
        Label offerStatus = new Label();
        offerStatus.setWrapText(true);
        offerStatus.setStyle("-fx-text-fill: #8a5a00;");
        HBox offerActions = new HBox(8);
        TextArea input = new TextArea();
        input.setPromptText("Reply to the buyer...");
        input.setPrefRowCount(2);
        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn-outline");
        Button send = new Button("Send");
        send.getStyleClass().add("btn-gold");

        Runnable[] reloadOfferActions = new Runnable[1];
        Runnable reloadMessages = () -> {
            try {
                String buyer = buyerSelector.getValue();
                if (buyer == null) {
                    messages.getItems().clear();
                    feedback.setText("Select a buyer to view their chat.");
                    if (reloadOfferActions[0] != null) reloadOfferActions[0].run();
                    return;
                }
                ChatHistory history = DataService.getSellerListingChat(listing.getId(), buyer);
                List<Message> loaded = history == null || history.getMessages() == null
                        ? List.of() : history.getMessages();
                String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                if (!needle.isEmpty()) {
                    loaded = loaded.stream()
                            .filter(message -> message.getContent() != null
                                    && message.getContent().toLowerCase(Locale.ROOT).contains(needle))
                            .toList();
                }
                messages.getItems().setAll(loaded);
                if (!messages.getItems().isEmpty()) {
                    messages.scrollTo(messages.getItems().size() - 1);
                }
                if (loaded.isEmpty()) {
                    feedback.setText(needle.isEmpty()
                            ? "No messages in this conversation yet."
                            : "No messages match this search.");
                } else {
                    feedback.setText("");
                }
                if (reloadOfferActions[0] != null) reloadOfferActions[0].run();
            } catch (IllegalStateException | SecurityException e) {
                feedback.setText("Could not load chat: " + e.getMessage());
            }
        };
        reloadOfferActions[0] = () -> {
            offerActions.getChildren().clear();
            offerStatus.setText("");
            String buyer = buyerSelector.getValue();
            if (buyer == null) return;
            try {
                Map<String, Object> open = DataService.getOpenListingRequestForBuyer(listing.getId(), buyer);
                String status = open == null ? null : String.valueOf(open.get("status"));
                if (open == null || (!"PENDING".equals(status) && !"COUNTERED".equals(status))) {
                    offerStatus.setText("No pending offer from this buyer. Negotiate in chat — only the buyer can submit a formal price offer.");
                    return;
                }
                int requestId = ((Number) open.get("request_id")).intValue();
                offerStatus.setText("Pending " + requestDescription(open)
                        + " — Accept or Decline here. Negotiate price in chat; only the buyer updates the formal offer.");
                Button accept = new Button("Accept Offer");
                accept.getStyleClass().add("btn-gold");
                Button decline = new Button("Decline");
                decline.getStyleClass().add("btn-danger");
                accept.setOnAction(event -> {
                    try {
                        DataService.resolveSellerRequest(requestId, "ACCEPT", null, null);
                        DataService.sendSellerListingChatMessage(listing.getId(), buyer,
                                "[Formal] Offer accepted.");
                        feedback.setText("Offer accepted. The buyer has been notified.");
                        reloadMessages.run();
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                        feedback.setText("Could not accept: " + e.getMessage());
                    }
                });
                decline.setOnAction(event -> {
                    try {
                        DataService.resolveSellerRequest(requestId, "DECLINE", null, null);
                        DataService.sendSellerListingChatMessage(listing.getId(), buyer,
                                "[Formal] Offer declined.");
                        feedback.setText("Offer declined. The buyer has been notified.");
                        reloadMessages.run();
                    } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                        feedback.setText("Could not decline: " + e.getMessage());
                    }
                });
                offerActions.getChildren().addAll(accept, decline);
            } catch (IllegalStateException | SecurityException e) {
                offerStatus.setText("Could not load offer state: " + e.getMessage());
            }
        };
        Runnable reloadBuyers = () -> {
            try {
                String selected = buyerSelector.getValue();
                List<String> buyers = new ArrayList<>(DataService.getSellerListingChatBuyers(listing.getId()));
                if (preferredBuyer != null && !preferredBuyer.isBlank() && !buyers.contains(preferredBuyer)) {
                    buyers.add(0, preferredBuyer);
                }
                buyerSelector.getItems().setAll(buyers);
                if (preferredBuyer != null && !preferredBuyer.isBlank() && buyers.contains(preferredBuyer)) {
                    buyerSelector.setValue(preferredBuyer);
                } else if (selected != null && buyers.contains(selected)) {
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
        search.textProperty().addListener((obs, old, value) -> reloadMessages.run());
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
        chat.getChildren().addAll(
                new Label("Buyer"),
                buyerSelector,
                new Label("Chat history is kept. Search does not delete messages."),
                search,
                messages,
                offerStatus,
                offerActions,
                feedback,
                input,
                new HBox(8, refresh, send));
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
                Button chat = new Button("Open Chat Room");
                chat.getStyleClass().add("btn-outline");
                int requestId = ((Number) request.get("request_id")).intValue();
                String buyerUsername = String.valueOf(request.get("buyer_username"));
                accept.setOnAction(event -> resolveListingRequest(
                        listing, requestId, "ACCEPT", null, null, requests, feedback));
                decline.setOnAction(event -> resolveListingRequest(
                        listing, requestId, "DECLINE", null, null, requests, feedback));
                chat.setOnAction(event -> showSellerListingChat(listing, buyerUsername, requests, feedback));
                HBox actions = new HBox(8, accept, decline, chat);
                card.getChildren().addAll(buyer, summary, actions);
                requests.getChildren().add(card);
            }
        } catch (IllegalStateException | SecurityException e) {
            feedback.setText("Could not load incoming requests: " + e.getMessage());
        }
    }

    private void resolveListingRequest(Listing listing, int requestId, String action, Double price,
                                       Integer counterDays, VBox requests, Label feedback) {
        try {
            DataService.resolveSellerRequest(requestId, action, price, counterDays);
            feedback.setText(switch (action) {
                case "ACCEPT" -> "Request accepted. The buyer has been notified.";
                case "DECLINE" -> "Request declined. The buyer has been notified.";
                default -> "Request updated.";
            });
            refreshListingRequests(listing, requests, feedback);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            feedback.setText("Could not update request: " + e.getMessage());
        }
    }

    private FlowPane createPhotoGallery(List<String> paths) {
        FlowPane gallery = new FlowPane(10, 10);
        gallery.setPrefWrapLength(880);
        if (paths.isEmpty()) {
            gallery.getChildren().add(new Label("No photos supplied."));
            return gallery;
        }
        int shown = Math.min(6, paths.size());
        for (int index = 0; index < shown; index++) {
            String path = paths.get(index);
            try {
                String uri = path.startsWith("file:")
                        ? path : Path.of(path).toAbsolutePath().toUri().toString();
                Image image = new Image(uri, 220, 160, true, true, false);
                if (image.isError()) throw new IllegalArgumentException("Photo could not be opened.");
                ImageView preview = new ImageView(image);
                preview.setPreserveRatio(true);
                preview.setFitWidth(220);
                preview.setFitHeight(160);
                Button photo = new Button("Photo " + (index + 1), preview);
                photo.setContentDisplay(ContentDisplay.TOP);
                int number = index + 1;
                photo.setOnAction(event -> {
                    Dialog<ButtonType> fullPhoto = new Dialog<>();
                    fullPhoto.setTitle("Photo " + number);
                    fullPhoto.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    ImageView enlargedImage = new ImageView(image);
                    enlargedImage.setPreserveRatio(true);
                    enlargedImage.setFitWidth(640);
                    enlargedImage.setFitHeight(480);
                    fullPhoto.getDialogPane().setContent(enlargedImage);
                    DialogStyle.showAndWait(fullPhoto);
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

        int totalViews = myListings.stream().mapToInt(Listing::getViewCount).sum();
        int offersReceived = 0;
        int completedDeals = 0;
        try {
            Map<String, Integer> activity = DataService.getSellerActivityStats();
            offersReceived = activity.getOrDefault("offers", 0);
            completedDeals = activity.getOrDefault("completed", 0);
        } catch (IllegalStateException | SecurityException ignored) {
            // Keep zeros if stats cannot be loaded.
        }

        HBox metrics = new HBox(16);
        metrics.getChildren().addAll(
                metricCard("Listings", String.valueOf(myListings.size()), "#003974"),
                metricCard("Views", String.valueOf(totalViews), "#1565c0"),
                metricCard("Offers received", String.valueOf(offersReceived), "#6a1b9a"),
                metricCard("Completed deals", String.valueOf(completedDeals), "#00838f"),
                metricCard("Avg Rating", String.format("%.1f", user != null ? user.getAverageRating() : 0), "#A68137"),
                metricCard("Reviews", String.valueOf(user != null ? user.getRatingCount() : 0), "#2e7d32")
        );
        root.getChildren().add(metrics);

        if (myListings.isEmpty()) {
            root.getChildren().add(styled("No listings yet.", "-fx-text-fill: #9aa0b0; -fx-font-size: 13px;"));
        } else {
            CategoryAxis xAxis = new CategoryAxis();
            NumberAxis yAxis = new NumberAxis();
            yAxis.setLabel("Listings");
            yAxis.setForceZeroInRange(true);
            yAxis.setMinorTickVisible(false);
            BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
            barChart.setTitle("Listings by Category");
            barChart.setLegendVisible(false);
            barChart.setPrefHeight(280);
            barChart.setAnimated(false);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            int maxCategory = 0;
            Map<String, Integer> categoryCounts = new LinkedHashMap<>();
            for (String category : DataService.CATEGORIES) categoryCounts.put(category, 0);
            for (Listing listing : myListings) {
                String category = listing.getCategory() == null || listing.getCategory().isBlank()
                        ? "Uncategorised" : listing.getCategory();
                categoryCounts.merge(category, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
                if (entry.getValue() == 0) continue;
                series.getData().add(new XYChart.Data<>(entry.getKey(), entry.getValue()));
                maxCategory = Math.max(maxCategory, entry.getValue());
            }
            int tick = maxCategory <= 8 ? 1 : (int) Math.ceil(maxCategory / 8.0);
            yAxis.setAutoRanging(false);
            yAxis.setLowerBound(0);
            yAxis.setTickUnit(tick);
            yAxis.setUpperBound(Math.ceil(maxCategory / (double) tick) * tick);
            barChart.getData().add(series);

            PieChart pie = new PieChart();
            pie.setTitle("Listing Status");
            pie.setPrefHeight(280);
            pie.setAnimated(false);
            pie.setLabelsVisible(true);
            Map<ListingStatus, Integer> statusCounts = new EnumMap<>(ListingStatus.class);
            for (Listing listing : myListings) {
                if (listing.getStatus() != null) statusCounts.merge(listing.getStatus(), 1, Integer::sum);
            }
            for (ListingStatus status : ListingStatus.values()) {
                int count = statusCounts.getOrDefault(status, 0);
                if (count > 0) pie.getData().add(new PieChart.Data(statusLabel(status), count));
            }

            HBox.setHgrow(barChart, Priority.ALWAYS);
            HBox.setHgrow(pie, Priority.ALWAYS);
            barChart.setMaxWidth(Double.MAX_VALUE);
            pie.setMaxWidth(Double.MAX_VALUE);
            HBox charts = new HBox(20, barChart, pie);
            charts.setFillHeight(true);
            root.getChildren().add(charts);
        }

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

    private String statusLabel(ListingStatus status) {
        String[] words = status.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    @FXML
    private void showReviews() {
        showReviews(null);
    }

    private void showReviews(Notification ratingNotification) {
        setActive(btnReviews, "Reviews & Feedback");
        contentPane.getChildren().clear();

        VBox root = new VBox(12);
        root.setPadding(new Insets(4));

        List<Review> reviews;
        try {
            reviews = new ArrayList<>(DataService.getReviewsForCurrentSeller());
        } catch (IllegalStateException | SecurityException ex) {
            root.getChildren().add(new Label("Could not load reviews: " + ex.getMessage()));
            contentPane.getChildren().add(new ScrollPane(root));
            return;
        }

        root.getChildren().add(styled(
                "Select a review to respond or flag it for admin moderation.",
                "-fx-text-fill: #5a5a7a; -fx-font-size: 13px;"));

        if (ratingNotification != null && ratingNotification.getListingId() != null
                && ratingNotification.getSender() != null) {
            String listingId = ratingNotification.getListingId().toString();
            Review linkedReview = reviews.stream()
                    .filter(review -> listingId.equals(review.getId())
                            && ratingNotification.getSender().equalsIgnoreCase(review.getBuyerUsername()))
                    .findFirst().orElse(null);
            if (linkedReview == null) {
                root.getChildren().add(new Label("The review linked to this notification is no longer visible."));
            } else {
                reviews.remove(linkedReview);
                reviews.add(0, linkedReview);
            }
        }

        if (reviews.isEmpty()) {
            root.getChildren().add(new Label("No reviews yet."));
        } else {
            for (Review r : reviews) {
                VBox item = new VBox(6);
                item.getStyleClass().add("card");
                item.setPadding(new Insets(12));
                Label comment = new Label(r.getComment() == null || r.getComment().isBlank()
                        ? "(No written feedback)" : r.getComment());
                comment.setWrapText(true);
                Label meta = new Label("by @" + r.getBuyerUsername() + " · " + r.getListingTitle());
                meta.setStyle("-fx-text-fill: #9aa0b0; -fx-font-size: 12px;");
                Label replyLabel = styled(
                        r.getSellerReply() == null || r.getSellerReply().isBlank()
                                ? "No reply yet."
                                : "Your reply: " + r.getSellerReply(),
                        r.getSellerReply() == null || r.getSellerReply().isBlank()
                                ? "-fx-text-fill: #9aa0b0; -fx-font-size: 12px;"
                                : "-fx-text-fill: #003974; -fx-font-size: 13px;");
                HBox actions = new HBox(8);
                Button reply = new Button(r.getSellerReply() == null || r.getSellerReply().isBlank()
                        ? "Respond" : "Edit Reply");
                reply.getStyleClass().addAll("btn-outline", "btn-sm");
                reply.setOnAction(e -> respondToReview(r));
                Button flag = new Button(r.isFlagged() ? "Flagged" : "Flag");
                flag.getStyleClass().addAll("btn-danger", "btn-sm");
                flag.setDisable(r.isFlagged());
                flag.setOnAction(e -> flagReview(r));
                actions.getChildren().addAll(reply, flag);
                item.getChildren().addAll(StarRating.graphic(r.getScore()), comment, meta, replyLabel);
                if (r.isFlagged()) {
                    item.getChildren().add(styled("Flagged for admin moderation.",
                            "-fx-text-fill: #c62828; -fx-font-size: 12px; -fx-font-weight: bold;"));
                }
                item.getChildren().add(actions);
                root.getChildren().add(item);
            }
        }
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        contentPane.getChildren().add(scroll);
    }

    private void respondToReview(Review review) {
        String draft = review.getSellerReply() == null ? "" : review.getSellerReply();
        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Respond to Review");
            dialog.setHeaderText("Reply to @" + review.getBuyerUsername() + " about " + review.getListingTitle());
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            TextArea input = new TextArea(draft);
            input.setPromptText("Enter a written reply...");
            input.setPrefRowCount(4);
            input.setWrapText(true);
            Label hint = new Label("Your reply will be saved and the buyer will be notified.");
            hint.setWrapText(true);
            dialog.getDialogPane().setContent(new VBox(8, hint, input));
            dialog.setResultConverter(button -> button);
            var result = DialogStyle.showAndWait(dialog);
            if (result.isEmpty() || result.get() != ButtonType.OK) {
                showReviews();
                return;
            }
            draft = input.getText() == null ? "" : input.getText();
            try {
                DataService.replyToReview(review.getBuyerUsername(), review.getId(), draft);
                DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION,
                        "Reply submitted. The buyer has been notified."));
                showReviews();
                return;
            } catch (IllegalArgumentException ex) {
                DialogStyle.showAndWait(new Alert(Alert.AlertType.ERROR,
                        "A reply cannot be empty. Please enter a written reply."));
            } catch (IllegalStateException | SecurityException ex) {
                showProfileError(ex.getMessage());
                showReviews();
                return;
            }
        }
    }

    private void flagReview(Review review) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Flag this review by @" + review.getBuyerUsername()
                        + " as inappropriate? An admin will be notified for moderation.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Confirm flag");
        DialogStyle.showAndWait(confirm).ifPresent(response -> {
            if (response != ButtonType.OK) {
                showReviews();
                return;
            }
            try {
                DataService.flagReview(review.getBuyerUsername(), review.getId());
                DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION,
                        "Review flagged. The report was sent to admin for moderation."));
                showReviews();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                showProfileError(ex.getMessage());
                showReviews();
            }
        });
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

        ComboBox<String> categories = new ComboBox<>(FXCollections.observableArrayList(
                "All", "Offer", "Chat", "Reminder", "Rating", "System", "Announcement"));
        if (!categories.getItems().contains(notificationCategory)) notificationCategory = "All";
        categories.setValue(notificationCategory);
        VBox list = new VBox(8);
        Runnable render = () -> {
            list.getChildren().clear();
            List<Notification> notifications = DatabaseManager.fetchNotifications(user.getUsername());
            String selected = categories.getValue() == null ? "All" : categories.getValue();
            List<Notification> shown = notifications.stream()
                    .filter(notification -> "All".equals(selected)
                            || selected.equalsIgnoreCase(notification.getCategory()))
                    .toList();
            List<Listing> listings;
            try {
                listings = DataService.getSellerListings();
            } catch (IllegalStateException | SecurityException ex) {
                listings = List.of();
            }
            if (shown.isEmpty()) {
                list.getChildren().add(styled("No notifications in this category.",
                        "-fx-text-fill: #9aa0b0; -fx-font-size: 13px;"));
            }
            for (Notification notification : shown) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                if (!notification.isRead()) item.setStyle("-fx-border-color: #003974; -fx-border-width: 0 0 0 3;");
                boolean rating = "Rating".equalsIgnoreCase(notification.getCategory());
                String listingId = rating ? null : listingIdForNotification(notification, listings);
                HBox footer = new HBox(8);
                footer.setAlignment(Pos.CENTER_LEFT);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                String when = notification.getTimestamp() == null ? ""
                        : notification.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"));
                footer.getChildren().add(styled(notification.getCategory() + " · " + when,
                        "-fx-text-fill: #9aa0b0; -fx-font-size: 11px;"));
                footer.getChildren().add(spacer);
                if (rating || listingId != null) {
                    Button go = new Button(rating ? "View review" : "Go ->");
                    go.getStyleClass().addAll("btn-outline", "btn-sm");
                    go.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                    go.setOnAction(event -> {
                        if (rating) openRatingNotification(user, notification);
                        else openNotificationListing(user, notification, listingId);
                    });
                    footer.getChildren().add(go);
                }
                item.getChildren().addAll(
                        styled(notification.getTitle(), "-fx-font-weight: bold; -fx-text-fill: #1a1a2e;"),
                        styled(notification.getMessage(), "-fx-text-fill: #5a5a7a; -fx-font-size: 13px;"),
                        footer
                );
                item.setOnMouseClicked(event -> {
                    if (rating) {
                        openRatingNotification(user, notification);
                        return;
                    }
                    if (notification.isRead()) return;
                    if (!markNotificationRead(user, notification)) return;
                    item.setStyle(null);
                });
                list.getChildren().add(item);
            }
            refreshNotificationDot();
        };
        categories.setOnAction(event -> {
            notificationCategory = categories.getValue();
            render.run();
        });
        Button readAll = new Button("Read All Notifications");
        readAll.getStyleClass().addAll("btn-outline", "btn-sm");
        readAll.setOnAction(event -> {
            try {
                DataService.markAllCurrentUserNotificationsRead();
                render.run();
            } catch (IllegalStateException | SecurityException ex) {
                showProfileError(ex.getMessage());
            }
        });
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, new Label("Category"), categories, toolbarSpacer, readAll);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(toolbar, list);
        render.run();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        contentPane.getChildren().add(scroll);
    }

    private void openRatingNotification(User user, Notification notification) {
        if (markNotificationRead(user, notification)) showReviews(notification);
    }

    private void openNotificationListing(User user, Notification notification, String listingId) {
        if (!markNotificationRead(user, notification)) return;
        Listing listing;
        try {
            listing = DataService.getSellerListings().stream()
                    .filter(item -> listingId.equals(item.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (IllegalStateException | SecurityException ex) {
            showProfileError(ex.getMessage());
            return;
        }
        if (listing == null) {
            showProfileError("This listing is no longer available.");
            showNotifications();
            return;
        }
        showListingDetails(listing, true);
    }

    private String listingIdForNotification(Notification notification, List<Listing> listings) {
        if (notification.getListingId() != null) {
            String id = Integer.toString(notification.getListingId());
            for (Listing listing : listings) {
                if (id.equals(listing.getId())) return id;
            }
        }
        String message = notification.getMessage() == null ? "" : notification.getMessage();
        Listing best = null;
        int bestLength = 0;
        for (Listing listing : listings) {
            String name = listing.getTitle();
            if (name == null || name.isBlank() || !message.contains(name) || name.length() <= bestLength) continue;
            best = listing;
            bestLength = name.length();
        }
        return best == null ? null : best.getId();
    }

    private boolean markNotificationRead(User user, Notification notification) {
        if (notification.isRead()) return true;
        try {
            DatabaseManager.markNotificationRead(user.getUsername(), notification.getId());
            notification.setRead(true);
            refreshNotificationDot();
            return true;
        } catch (IllegalStateException e) {
            showProfileError("Could not mark notification as read: " + e.getMessage());
            return false;
        }
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
        ComboBox<String> meetup = new ComboBox<>(FXCollections.observableArrayList(DataService.HKUST_LOCATIONS));
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
        DialogStyle.showAndWait(new Alert(Alert.AlertType.INFORMATION, "Profile updated successfully."));
    }

    private void showProfileError(String message) {
        DialogStyle.showAndWait(new Alert(Alert.AlertType.ERROR, message));
    }

    @FXML
    private void logout() {
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
