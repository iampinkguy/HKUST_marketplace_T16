package com.hkust.marketplace.controllers.buyer;

import com.hkust.marketplace.models.ChatHistory;
import com.hkust.marketplace.models.Listing;
import com.hkust.marketplace.models.ListingType;
import com.hkust.marketplace.models.Message;
import com.hkust.marketplace.models.Notification;
import com.hkust.marketplace.models.User;
import com.hkust.marketplace.utils.DataService;
import com.hkust.marketplace.utils.DatabaseManager;
import com.hkust.marketplace.utils.SceneManager;
import com.hkust.marketplace.utils.SessionManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuyerDashboardController {

    private enum Page { BROWSE, DETAIL, DEALS, HISTORY, FAVOURITES, NOTIFICATIONS, PROFILE, CLOSED }

    private enum DetailMode { MARKET, DEAL, FINISHED, HISTORY, NOTICE, NOTICE_FINISHED }

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnBrowse, btnDeals, btnHistory, btnFavourites, btnNotifications, btnProfile;
    @FXML private Circle notifUnreadDot;

    private Button currentActiveBtn;
    private Page currentPage = Page.BROWSE;
    private Timeline returnTicker;
    private Timeline detailTicker;

    private String keyword = "";
    private String category = "All Categories";
    private String listingType = "All Types";
    private String condition = "All Conditions";
    private Double minPrice;
    private Double maxPrice;
    private String sort = "Newest";

    private String dealKeyword = "";
    private String dealCategory = "All Categories";
    private String dealType = "All Types";
    private String dealSeller = "";
    private String dealSort = "Newest";

    private LocalDate historyFrom;
    private LocalDate historyTo;
    private String historyCategory = "All Categories";
    private String historySeller = "";
    private String historyKeyword = "";

    private long announcedVersion = -1;
    private Page afterDetail = Page.BROWSE;
    private static final Pattern REQUEST_MARKER = Pattern.compile("\\(request (\\d+)\\)");

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnBrowse;
        ensureNotificationDot();
        returnTicker = new Timeline(new KeyFrame(Duration.seconds(1), event -> settleReturns()));
        returnTicker.setCycleCount(Timeline.INDEFINITE);
        returnTicker.play();
        refreshNotificationDot();
        showBrowse();
    }

    private void settleReturns() {
        if (!SessionManager.isBuyer()) return;
        refreshNotificationDot();
        try {
            int returned = DataService.settleExpiredBorrows();
            if (returned > 0 && (currentPage == Page.DEALS || currentPage == Page.HISTORY || currentPage == Page.BROWSE)) {
                refreshCurrentPage();
            }
        } catch (IllegalStateException | SecurityException ignored) {
            // The next user action reports a real session problem.
        }
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

    private void refreshCurrentPage() {
        switch (currentPage) {
            case BROWSE -> showBrowse();
            case DEALS -> showMyDeals();
            case HISTORY -> showHistory();
            case FAVOURITES -> showFavourites();
            case NOTIFICATIONS -> showNotifications();
            default -> { }
        }
    }

    private void setActive(Button btn, String title) {
        stopDetailWatch();
        if (currentActiveBtn != null) currentActiveBtn.getStyleClass().remove("sidebar-btn-active");
        btn.getStyleClass().add("sidebar-btn-active");
        currentActiveBtn = btn;
        pageTitle.setText(title);
    }

    private void stopDetailWatch() {
        if (detailTicker != null) {
            detailTicker.stop();
            detailTicker = null;
        }
    }

    @FXML
    private void showBrowse() {
        setActive(btnBrowse, "Browse Marketplace");
        currentPage = Page.BROWSE;
        contentPane.getChildren().setAll(marketplaceView());
    }

    private ScrollPane marketplaceView() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));

        HBox tools = new HBox(10);
        tools.setAlignment(Pos.CENTER_LEFT);
        TextField searchField = new TextField(keyword);
        searchField.setPromptText("Search by title or keyword");
        searchField.setPrefWidth(240);
        Button searchBtn = new Button("Search");
        searchBtn.getStyleClass().addAll("btn-primary", "btn-sm");
        Button filterBtn = new Button("Filter");
        filterBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        Button sortBtn = new Button("Sort");
        sortBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        Button closeBtn = new Button("X");
        closeBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        tools.getChildren().addAll(searchField, searchBtn, filterBtn, sortBtn, spacer, closeBtn);

        Label summary = new Label(filterSummary());
        summary.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px;");
        searchBtn.setOnAction(event -> {
            keyword = searchField.getText().trim();
            showBrowse();
        });
        filterBtn.setOnAction(event -> openMarketplaceFilter());
        sortBtn.setOnAction(event -> openMarketplaceSort());
        closeBtn.setOnAction(event -> closeScreen("Marketplace closed."));

        FlowPane grid = new FlowPane(16, 16);
        grid.setPrefWrapLength(900);
        List<Listing> live;
        try {
            live = DataService.searchBuyerMarketplace(keyword, category, listingType, condition, minPrice, maxPrice, sort);
        } catch (IllegalStateException | SecurityException ex) {
            live = List.of();
            root.getChildren().add(errorLabel(ex.getMessage()));
        }
        Set<Integer> favouriteIds;
        try {
            favouriteIds = new HashSet<>(DataService.getBuyerFavouriteIds());
        } catch (IllegalStateException | SecurityException ex) {
            favouriteIds = Set.of();
        }
        if (live.isEmpty()) {
            Label empty = new Label("No live listings match the current search.");
            empty.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 15px;");
            root.getChildren().addAll(tools, summary, empty);
        } else {
            for (Listing listing : live) {
                grid.getChildren().add(createListingCard(listing, favouriteIds.contains(Integer.parseInt(listing.getId()))));
            }
            root.getChildren().addAll(tools, summary, grid);
        }
        return scroll(root);
    }

    private String filterSummary() {
        String price = (minPrice == null && maxPrice == null) ? "any price"
                : (minPrice == null ? "0" : trimPrice(minPrice)) + " – " + (maxPrice == null ? "any" : trimPrice(maxPrice));
        return "Showing live listings · " + category + " · " + listingType + " · " + condition
                + " · " + price + " · sorted by " + sort;
    }

    private void openMarketplaceFilter() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit filter");
        dialog.setHeaderText("Choose which live listings to show");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Apply");

        ComboBox<String> categories = combo(DataService.CATEGORIES, "All Categories", category);
        ComboBox<String> types = combo(List.of("Sell", "Lend", "Giveaway"), "All Types", listingType);
        ComboBox<String> conditions = combo(DataService.CONDITIONS, "All Conditions", condition);
        TextField min = new TextField(minPrice == null ? "" : trimPrice(minPrice));
        min.setPromptText("Min price");
        TextField max = new TextField(maxPrice == null ? "" : trimPrice(maxPrice));
        max.setPromptText("Max price");
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Category"), categories,
                new Label("Listing type"), types,
                new Label("Condition"), conditions,
                new Label("Price range (HK$)"), new HBox(8, min, max)));
        dialog.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                minPrice = parseOptionalPrice(min.getText(), "Minimum price");
                maxPrice = parseOptionalPrice(max.getText(), "Maximum price");
                if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                    throw new IllegalArgumentException("Minimum price cannot be higher than maximum price.");
                }
                category = categories.getValue();
                listingType = types.getValue();
                condition = conditions.getValue();
                showBrowse();
            } catch (IllegalArgumentException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private void openMarketplaceSort() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit sorter");
        dialog.setHeaderText("Choose a sorting method");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Sort");
        ComboBox<String> sorter = new ComboBox<>(FXCollections.observableArrayList(
                "Newest", "Price: Low to High", "Price: High to Low"));
        sorter.setValue(sort);
        dialog.getDialogPane().setContent(sorter);
        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK && sorter.getValue() != null) {
                sort = sorter.getValue();
                showBrowse();
            }
        });
    }

    private VBox createListingCard(Listing listing, boolean favourited) {
        VBox card = new VBox(8);
        card.getStyleClass().add("listing-card");
        card.setPrefWidth(260);
        card.setMinHeight(220);
        card.setOnMouseClicked(event -> showListingDetail(listing.getId(), DetailMode.MARKET));

        ImageView thumb = new ImageView();
        thumb.setFitWidth(236);
        thumb.setFitHeight(120);
        thumb.setPreserveRatio(true);
        if (!listing.getPhotoPaths().isEmpty()) {
            Image image = loadPhoto(listing.getPhotoPaths().get(0), 236, 120);
            if (image != null) thumb.setImage(image);
        }

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label typeBadge = new Label(listing.getTypeDisplay());
        typeBadge.getStyleClass().add("badge");
        if (listing.getType() == ListingType.SELL) typeBadge.getStyleClass().add("badge-sold");
        else if (listing.getType() == ListingType.LEND) typeBadge.getStyleClass().add("badge-pending");
        else typeBadge.getStyleClass().add("badge-live");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button heart = heartButton(listing, favourited);
        heart.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        Label price = new Label(listing.getPriceDisplay());
        price.setStyle("-fx-font-weight: bold; -fx-text-fill: #003974; -fx-font-size: 16px;");
        top.getChildren().addAll(typeBadge, spacer, heart, price);

        card.getChildren().addAll(thumb, top,
                styled(listing.getTitle(), "-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #1a1a2e;"),
                styled(listing.getCategory() + " · " + listing.getCondition(), muted()),
                styled("Seller: " + text(listing.getSellerFullName()), muted()),
                styled("Meet-up: " + text(listing.getMeetupLocation()), muted()),
                styled("Posted: " + formatDate(listing.getPostedAt()), faint()),
                styled("Status: " + text(listing.getAvailabilityStatus()), muted()));
        if (listing.getType() == ListingType.LEND) {
            card.getChildren().add(card.getChildren().size() - 1,
                    styled("Lending period: " + lendingPeriodText(listing.getLendingDays()), muted()));
        }
        return card;
    }

    private void showListingDetail(String listingId, DetailMode mode) {
        Listing listing;
        try {
            listing = DataService.openBuyerListing(listingId);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            return;
        }
        if (listing == null) {
            alert(Alert.AlertType.ERROR, "This listing is no longer available.");
            return;
        }
        announcedVersion = listing.getReviewVersion();
        renderListingDetail(listing, mode);
    }

    private void renderListingDetail(Listing listing, DetailMode mode) {
        stopDetailWatch();
        currentPage = Page.DETAIL;
        pageTitle.setText("Listing Detail");

        VBox root = new VBox(14);
        root.setPadding(new Insets(4));
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label notice = new Label();
        notice.setStyle("-fx-text-fill: #8a5a00;");
        notice.setWrapText(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("X");
        close.getStyleClass().addAll("btn-outline", "btn-sm");
        close.setOnAction(event -> {
            stopDetailWatch();
            switch (afterDetail) {
                case HISTORY -> showHistory();
                case DEALS -> showMyDeals();
                case NOTIFICATIONS -> showNotifications();
                default -> showBrowse();
            }
        });
        header.getChildren().addAll(
                styled(listing.getTitle(), "-fx-font-size: 22px; -fx-font-weight: bold;"),
                heartButton(listing, isFavourited(listing)), spacer, close);

        FlowPane photos = new FlowPane(10, 10);
        photos.setPrefWrapLength(880);
        if (listing.getPhotoPaths().isEmpty()) {
            photos.getChildren().add(new Label("No photos supplied."));
        } else {
            int shown = Math.min(6, listing.getPhotoPaths().size());
            for (int i = 0; i < shown; i++) {
                Image image = loadPhoto(listing.getPhotoPaths().get(i), 220, 160);
                if (image == null) {
                    photos.getChildren().add(new Label("Photo " + (i + 1) + " unavailable."));
                    continue;
                }
                ImageView preview = new ImageView(image);
                preview.setFitWidth(220);
                preview.setFitHeight(160);
                preview.setPreserveRatio(true);
                Button photo = new Button("Photo " + (i + 1), preview);
                photo.setContentDisplay(ContentDisplay.TOP);
                int index = i;
                photo.setOnAction(event -> enlargePhoto(image, index + 1));
                photos.getChildren().add(photo);
            }
        }

        Map<String, Object> seller = DataService.getSellerRating(listing.getSellerUsername());
        double average = seller.get("average_rating") instanceof Number number ? number.doubleValue() : 0;
        int count = seller.get("rating_count") instanceof Number number ? number.intValue() : 0;
        VBox facts = new VBox(6);
        facts.getChildren().addAll(
                fact("Description", text(listing.getDescription())),
                fact("Category", listing.getCategory()),
                fact("Type", listing.getTypeDisplay()),
                fact("Price", listing.getPriceDisplay()),
                fact("Condition", listing.getCondition()),
                fact("Meet-up location", listing.getMeetupLocation()),
                fact("Posted", formatDate(listing.getPostedAt())),
                fact("Availability", listing.getAvailabilityStatus() + " · " + listing.getStatus()),
                fact("Views", Integer.toString(listing.getViewCount())),
                fact("Seller", text(listing.getSellerFullName()) + " · "
                        + String.format(Locale.ROOT, "%.1f", average) + " / 5 (" + count + " ratings)")
        );
        if (listing.getType() == ListingType.LEND) {
            facts.getChildren().add(3, fact("Lending period", lendingPeriodText(listing.getLendingDays())));
        }

        VBox reviews = new VBox(6);
        reviews.getChildren().add(styled("Seller reviews", "-fx-font-weight: bold;"));
        List<Map<String, Object>> sellerReviews = DataService.getSellerReviews(listing.getSellerUsername());
        if (sellerReviews.isEmpty()) reviews.getChildren().add(styled("No reviews yet.", muted()));
        for (Map<String, Object> review : sellerReviews) {
            String reply = review.get("seller_reply") == null ? "" : "\nSeller reply: " + review.get("seller_reply");
            reviews.getChildren().add(styled(review.get("rating") + " / 5 · " + textValue(review.get("item_name"))
                    + "\n" + textValue(review.get("text")) + reply, muted()));
        }

        boolean finishedView = mode == DetailMode.FINISHED || mode == DetailMode.HISTORY
                || mode == DetailMode.NOTICE_FINISHED;
        afterDetail = switch (mode) {
            case HISTORY -> Page.HISTORY;
            case DEAL, FINISHED -> Page.DEALS;
            case NOTICE, NOTICE_FINISHED -> Page.NOTIFICATIONS;
            default -> Page.BROWSE;
        };
        boolean available = "LIVE".equals(listing.getStatus().name()) && "Available".equals(listing.getAvailabilityStatus());
        boolean ownsDeal = buyerOwnsActiveDeal(listing.getId());
        HBox actions = new HBox(8);
        List<Button> actionButtons = new ArrayList<>();
        if (!finishedView && available) {
            Button primary = new Button(switch (listing.getType()) {
                case SELL -> "Make Offer";
                case LEND -> "Request Borrow";
                case GIVEAWAY -> "Claim Item";
            });
            primary.getStyleClass().add("btn-primary");
            primary.setOnAction(event -> actOnListing(listing));
            Button report = new Button("Report Listing");
            report.getStyleClass().addAll("btn-outline");
            report.setOnAction(event -> reportListing(listing));
            actionButtons.add(primary);
            actionButtons.add(report);
            actions.getChildren().addAll(primary, report);
        }
        Button chat = new Button("Chat");
        chat.getStyleClass().add("btn-primary");
        chat.setOnAction(event -> showChatbox(listing, !finishedView && available));
        if (!finishedView) actionButtons.add(chat);
        actions.getChildren().add(chat);
        if (!available && !ownsDeal) {
            notice.setText("This listing is no longer available. Only closing this screen is enabled.");
            actionButtons.forEach(button -> button.setDisable(true));
        }

        root.getChildren().addAll(header, notice, photos, facts, reviews, actions);
        contentPane.getChildren().setAll(scroll(root));
        watchListing(listing.getId(), mode, notice, actionButtons);
    }

    private void watchListing(String listingId, DetailMode mode, Label notice, List<Button> actionButtons) {
        detailTicker = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (currentPage != Page.DETAIL) return;
            try {
                Listing latest = DataService.peekBuyerListing(listingId);
                boolean ownsDeal = buyerOwnsActiveDeal(listingId);
                boolean available = latest != null && "LIVE".equals(latest.getStatus().name())
                        && "Available".equals(latest.getAvailabilityStatus());
                if (latest == null || (!available && !ownsDeal)) {
                    notice.setText(latest == null
                            ? "This listing was removed. Only closing this screen is enabled."
                            : "This listing was accepted for another buyer or is no longer available.");
                    actionButtons.forEach(button -> button.setDisable(true));
                }
                if (latest != null && latest.getReviewVersion() != announcedVersion) {
                    announcedVersion = latest.getReviewVersion();
                    alert(Alert.AlertType.INFORMATION, "Some details of this listing have changed.");
                    renderListingDetail(latest, mode);
                }
            } catch (IllegalStateException | SecurityException ex) {
                notice.setText(ex.getMessage());
                actionButtons.forEach(button -> button.setDisable(true));
            }
        }));
        detailTicker.setCycleCount(Timeline.INDEFINITE);
        detailTicker.play();
    }

    private boolean buyerOwnsActiveDeal(String listingId) {
        try {
            return DataService.getBuyerDeals().stream().anyMatch(deal ->
                    listingId.equals(String.valueOf(deal.get("listing_id")))
                            && "ACCEPTED".equals(deal.get("status"))
                            && !DataService.isBuyerDealFinished(deal));
        } catch (IllegalStateException | SecurityException ex) {
            return false;
        }
    }

    private void actOnListing(Listing listing) {
        try {
            switch (listing.getType()) {
                case SELL -> offerPrice(listing, null);
                case LEND -> borrowPeriod(listing, null);
                case GIVEAWAY -> claimItem(listing, null);
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
        }
    }

    private void offerPrice(Listing listing, Runnable afterSuccess) {
        TextInputDialog dialog = new TextInputDialog(listing.getPrice() == null ? "" : trimPrice(listing.getPrice()));
        dialog.setTitle("Make an Offer");
        dialog.setHeaderText("Offer for: " + listing.getTitle());
        dialog.setContentText("Your offer price (HK$):");
        dialog.showAndWait().ifPresent(priceStr -> {
            try {
                double price = Double.parseDouble(priceStr.trim());
                DataService.submitBuyerRequest(listing.getId(), "OFFER", price, null);
                DataService.sendBuyerListingChatMessage(listing.getId(),
                        "[Formal offer] Proposed price: HK$" + trimPrice(price));
                alert(Alert.AlertType.INFORMATION, "Your offer was sent to " + listing.getSellerFullName() + ".");
                if (afterSuccess != null) afterSuccess.run();
            } catch (NumberFormatException ex) {
                alert(Alert.AlertType.ERROR, "Enter a valid offer price.");
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private void borrowPeriod(Listing listing, Runnable afterSuccess) {
        Integer lendingDays = listing.getLendingDays();
        if (lendingDays == null || lendingDays <= 0) {
            alert(Alert.AlertType.ERROR, "This listing does not have a lending period.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Borrow Request");
        dialog.setHeaderText("Request to borrow: " + listing.getTitle());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        LocalDate today = LocalDate.now();
        DatePicker startDate = new DatePicker(today);
        DatePicker endDate = new DatePicker(today.plusDays(1));
        Label lengthLabel = new Label();
        lengthLabel.setWrapText(true);
        Runnable showLength = () -> {
            String error = borrowDateError(startDate.getValue(), endDate.getValue(), lendingDays);
            if (error != null) {
                lengthLabel.setText(error);
                return;
            }
            long days = ChronoUnit.DAYS.between(startDate.getValue(), endDate.getValue());
            lengthLabel.setText(lendingPeriodText((int) days) + " selected. You can borrow for 1 to "
                    + lendingPeriodText(lendingDays) + ".");
        };
        startDate.valueProperty().addListener((obs, oldValue, newValue) -> showLength.run());
        endDate.valueProperty().addListener((obs, oldValue, newValue) -> showLength.run());
        showLength.run();
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("The seller lends this item for up to " + lendingPeriodText(lendingDays) + "."),
                new Label("Start date"), startDate,
                new Label("End date"), endDate,
                lengthLabel));
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            String error = borrowDateError(startDate.getValue(), endDate.getValue(), lendingDays);
            if (error == null) return;
            event.consume();
            alert(Alert.AlertType.ERROR, error);
        });
        dialog.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                LocalDate start = startDate.getValue();
                LocalDate end = endDate.getValue();
                long days = ChronoUnit.DAYS.between(start, end);
                DataService.submitBorrowRequest(listing.getId(), start.atStartOfDay(), end.atStartOfDay());
                DataService.sendBuyerListingChatMessage(listing.getId(),
                        "[Formal borrow request] " + start + " to " + end
                                + " (" + lendingPeriodText((int) days) + ")");
                alert(Alert.AlertType.INFORMATION, "Your borrow request was sent to " + listing.getSellerFullName() + ".");
                if (afterSuccess != null) afterSuccess.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private String borrowDateError(LocalDate start, LocalDate end, int lendingDays) {
        if (start == null || end == null) return "Choose a start date and an end date.";
        if (start.isBefore(LocalDate.now())) return "Borrow start date cannot be in the past.";
        long days = ChronoUnit.DAYS.between(start, end);
        if (days < 1) return "Borrow at least 1 day.";
        if (days > lendingDays) {
            return "You can borrow this item for at most " + lendingPeriodText(lendingDays) + ".";
        }
        return null;
    }

    private String lendingPeriodText(Integer days) {
        if (days == null || days <= 0) return "Not set";
        return days == 1 ? "1 day" : days + " days";
    }

    private void claimItem(Listing listing, Runnable afterSuccess) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Claim Item");
        alert.setHeaderText("Claim: " + listing.getTitle());
        alert.setContentText("Claim this giveaway? Only the first successful claim is accepted.");
        alert.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                DataService.submitBuyerRequest(listing.getId(), "CLAIM", null, null);
                DataService.sendBuyerListingChatMessage(listing.getId(), "[Formal claim] Item claimed.");
                this.alert(Alert.AlertType.INFORMATION, "You claimed " + listing.getTitle() + ".");
                if (afterSuccess != null) afterSuccess.run();
                if (currentPage == Page.BROWSE || currentPage == Page.DETAIL) showBrowse();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                this.alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private void reportListing(Listing listing) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Report this listing? It will be hidden from your marketplace screens.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Report " + listing.getTitle());
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                DataService.reportBuyerListing(listing.getId());
                alert(Alert.AlertType.INFORMATION, "The listing was reported and hidden from your screens.");
                showBrowse();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private void showChatbox(Listing listing, boolean allowFormalAction) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Chat");
        dialog.setHeaderText("Chat with " + listing.getSellerFullName() + " about " + listing.getTitle());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).setText("X");

        ListView<Message> messageList = new ListView<>();
        messageList.setPrefHeight(280);
        messageList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                } else {
                    String timestamp = message.getTimestamp() == null ? ""
                            : message.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.ENGLISH));
                    setText(message.getSender_id() + " (" + timestamp + ")\n" + message.getContent());
                    setWrapText(true);
                }
            }
        });
        TextField search = new TextField();
        search.setPromptText("Search this chat history");
        TextArea input = new TextArea();
        input.setPromptText("Type a message...");
        input.setPrefRowCount(3);
        Button send = new Button("Send");
        send.getStyleClass().add("btn-primary");

        Runnable refreshMessages = () -> {
            try {
                ChatHistory history = DataService.getBuyerListingChat(listing.getId());
                List<Message> messages = history == null || history.getMessages() == null
                        ? List.of() : history.getMessages();
                String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
                if (!needle.isEmpty()) {
                    messages = messages.stream()
                            .filter(message -> message.getContent() != null
                                    && message.getContent().toLowerCase(Locale.ROOT).contains(needle))
                            .toList();
                }
                messageList.getItems().setAll(messages);
                if (!messageList.getItems().isEmpty()) messageList.scrollTo(messageList.getItems().size() - 1);
            } catch (IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, "Could not load chat: " + ex.getMessage());
            }
        };
        search.textProperty().addListener((obs, old, value) -> refreshMessages.run());
        send.setOnAction(event -> {
            try {
                DataService.sendBuyerListingChatMessage(listing.getId(), input.getText());
                input.clear();
                refreshMessages.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });

        VBox content = new VBox(8, new Label("Chat history is kept. Search does not delete messages."),
                search, messageList, input, send);
        if (allowFormalAction) {
            Button formal = new Button(switch (listing.getType()) {
                case SELL -> "Make Offer";
                case LEND -> "Request Borrow";
                case GIVEAWAY -> "Claim Item";
            });
            formal.getStyleClass().add("btn-gold");
            formal.setOnAction(event -> {
                Runnable refresh = refreshMessages::run;
                switch (listing.getType()) {
                    case SELL -> offerPrice(listing, refresh);
                    case LEND -> borrowPeriod(listing, refresh);
                    case GIVEAWAY -> claimItem(listing, refresh);
                }
            });
            content.getChildren().add(formal);
        }
        content.setPrefWidth(480);
        dialog.getDialogPane().setContent(content);
        refreshMessages.run();
        dialog.showAndWait();
    }

    @FXML
    private void showMyDeals() {
        setActive(btnDeals, "My Deals");
        currentPage = Page.DEALS;
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        HBox tools = new HBox(8);
        tools.setAlignment(Pos.CENTER_LEFT);
        Button filterBtn = new Button("Filter");
        filterBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        Button sortBtn = new Button("Sort");
        sortBtn.getStyleClass().addAll("btn-outline", "btn-sm");
        Button close = new Button("X");
        close.getStyleClass().addAll("btn-outline", "btn-sm");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        filterBtn.setOnAction(event -> openDealFilter());
        sortBtn.setOnAction(event -> openDealSort());
        close.setOnAction(event -> closeScreen("Deals screen closed."));
        tools.getChildren().addAll(filterBtn, sortBtn, styled(dealSummary(), muted()), spacer, close);

        List<Map<String, Object>> deals;
        try {
            deals = filteredDeals(DataService.getBuyerDeals());
        } catch (IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            deals = List.of();
        }
        VBox ongoing = new VBox(8);
        VBox completed = new VBox(8);
        ongoing.getChildren().add(styled("Ongoing deals", "-fx-font-size: 16px; -fx-font-weight: bold;"));
        completed.getChildren().add(styled("Completed deals", "-fx-font-size: 16px; -fx-font-weight: bold;"));
        boolean anyOngoing = false;
        boolean anyCompleted = false;
        for (Map<String, Object> deal : deals) {
            VBox card = dealCard(deal);
            if (isOngoing(deal)) {
                ongoing.getChildren().add(card);
                anyOngoing = true;
            } else {
                completed.getChildren().add(card);
                anyCompleted = true;
            }
        }
        if (!anyOngoing) ongoing.getChildren().add(styled("No ongoing deals.", muted()));
        if (!anyCompleted) completed.getChildren().add(styled("No completed deals.", muted()));
        root.getChildren().addAll(tools, ongoing, new Separator(), completed);
        contentPane.getChildren().setAll(scroll(root));
    }

    private VBox dealCard(Map<String, Object> deal) {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(12));
        String type = String.valueOf(deal.get("request_type"));
        String details = switch (type) {
            case "OFFER" -> "Sell offer · HK$" + trimNumber(deal.get("offer_price"));
            case "BORROW" -> "Borrow · " + formatRange(deal.get("borrow_start"), deal.get("borrow_end"));
            case "CLAIM" -> "Giveaway claim";
            default -> type;
        };
        String status = String.valueOf(deal.get("status")).replace('_', ' ');
        if (deal.get("returned_at") != null) status = "Returned";
        else if (deal.get("completed_at") != null) status = "Completed";
        if (deal.get("counter_price") != null) status += " · Counter HK$" + trimNumber(deal.get("counter_price"));
        card.getChildren().addAll(
                styled(textValue(deal.get("item_name")), "-fx-font-weight: bold; -fx-font-size: 15px;"),
                styled("Seller: " + textValue(deal.get("seller_name")) + " · " + details, muted()),
                styled("Status: " + status + (deal.get("due_at") == null ? "" : " · Due " + dateOnly(deal.get("due_at"))), muted())
        );
        HBox actions = new HBox(8);
        if (deal.get("listing_id") != null) {
            Button open = new Button("View listing");
            open.getStyleClass().addAll("btn-outline", "btn-sm");
            DetailMode mode = isOngoing(deal) ? DetailMode.DEAL : DetailMode.FINISHED;
            open.setOnAction(event -> showListingDetail(String.valueOf(deal.get("listing_id")), mode));
            actions.getChildren().add(open);
            Button chat = new Button("Chat");
            chat.getStyleClass().addAll("btn-primary", "btn-sm");
            chat.setOnAction(event -> openDealChat(deal, isOngoing(deal)));
            actions.getChildren().add(chat);
        }
        int requestId = ((Number) deal.get("request_id")).intValue();
        if ("BORROW".equals(type) && "ACCEPTED".equals(deal.get("status")) && deal.get("returned_at") == null) {
            Button ret = new Button("Self-return");
            ret.getStyleClass().addAll("btn-outline", "btn-sm");
            ret.setOnAction(event -> {
                try {
                    DataService.selfReturnBorrow(requestId);
                    alert(Alert.AlertType.INFORMATION, "The item was returned. You can rate the seller from completed deals.");
                    showMyDeals();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    alert(Alert.AlertType.ERROR, ex.getMessage());
                    showMyDeals();
                }
            });
            actions.getChildren().add(ret);
        }
        if (("OFFER".equals(type) || "CLAIM".equals(type)) && "ACCEPTED".equals(deal.get("status"))
                && deal.get("completed_at") == null) {
            Button complete = new Button("Mark complete");
            complete.getStyleClass().add("btn-primary");
            complete.setOnAction(event -> {
                try {
                    DataService.completeBuyerDeal(requestId);
                    alert(Alert.AlertType.INFORMATION, "The deal is complete.");
                    askForRating(requestId, textValue(deal.get("item_name")));
                    showMyDeals();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                    alert(Alert.AlertType.ERROR, ex.getMessage());
                }
            });
            actions.getChildren().add(complete);
        }
        if (!isOngoing(deal) && !"DECLINED".equals(deal.get("status")) && !isRated(deal)
                && (deal.get("completed_at") != null || deal.get("returned_at") != null)) {
            Button rate = new Button("Rate seller");
            rate.getStyleClass().add("btn-gold");
            rate.setOnAction(event -> {
                askForRating(requestId, textValue(deal.get("item_name")));
                showMyDeals();
            });
            actions.getChildren().add(rate);
        }
        card.getChildren().add(actions);
        return card;
    }

    private void openDealChat(Map<String, Object> deal, boolean allowFormalAction) {
        try {
            Listing listing = DataService.peekBuyerListing(String.valueOf(deal.get("listing_id")));
            if (listing == null) {
                alert(Alert.AlertType.ERROR, "The listing for this chat is no longer available.");
                return;
            }
            showChatbox(listing, allowFormalAction && "Available".equals(listing.getAvailabilityStatus()));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
        }
    }

    private void openDealFilter() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit filter");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Apply");
        TextField keywordField = new TextField(dealKeyword);
        keywordField.setPromptText("Title or seller");
        ComboBox<String> categories = combo(DataService.CATEGORIES, "All Categories", dealCategory);
        ComboBox<String> types = combo(List.of("OFFER", "BORROW", "CLAIM"), "All Types", dealType);
        TextField seller = new TextField(dealSeller);
        seller.setPromptText("Seller name");
        dialog.getDialogPane().setContent(new VBox(8, keywordField, categories, types, seller));
        dialog.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            dealKeyword = keywordField.getText().trim();
            dealCategory = categories.getValue();
            dealType = types.getValue();
            dealSeller = seller.getText().trim();
            showMyDeals();
        });
    }

    private void openDealSort() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit sorter");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Sort");
        ComboBox<String> sorter = new ComboBox<>(FXCollections.observableArrayList("Newest", "Oldest", "Title"));
        sorter.setValue(dealSort);
        dialog.getDialogPane().setContent(sorter);
        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK && sorter.getValue() != null) {
                dealSort = sorter.getValue();
                showMyDeals();
            }
        });
    }

    private List<Map<String, Object>> filteredDeals(List<Map<String, Object>> deals) {
        String needle = dealKeyword.toLowerCase(Locale.ROOT);
        String sellerNeedle = dealSeller.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> deal : deals) {
            if (!"All Categories".equals(dealCategory) && !dealCategory.equals(deal.get("category"))) continue;
            if (!"All Types".equals(dealType) && !dealType.equals(deal.get("request_type"))) continue;
            String title = textValue(deal.get("item_name")).toLowerCase(Locale.ROOT);
            String seller = textValue(deal.get("seller_name")).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !title.contains(needle) && !seller.contains(needle)) continue;
            if (!sellerNeedle.isEmpty() && !seller.contains(sellerNeedle)) continue;
            filtered.add(deal);
        }
        Comparator<Map<String, Object>> comparator = switch (dealSort) {
            case "Oldest" -> Comparator.comparing(deal -> textValue(deal.get("created_at")));
            case "Title" -> Comparator.comparing(deal -> textValue(deal.get("item_name")), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing((Map<String, Object> deal) -> textValue(
                    deal.get("updated_at") == null ? deal.get("created_at") : deal.get("updated_at"))).reversed();
        };
        filtered.sort(comparator);
        return filtered;
    }

    private String dealSummary() {
        return "Filter: " + dealCategory + " · " + dealType
                + (dealKeyword.isBlank() ? "" : " · \"" + dealKeyword + "\"")
                + " · " + dealSort;
    }

    @FXML
    private void showHistory() {
        setActive(btnHistory, "Deal History");
        currentPage = Page.HISTORY;
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        DatePicker from = new DatePicker(historyFrom);
        DatePicker to = new DatePicker(historyTo);
        ComboBox<String> categories = combo(DataService.CATEGORIES, "All Categories", historyCategory);
        TextField seller = new TextField(historySeller);
        seller.setPromptText("Seller");
        TextField search = new TextField(historyKeyword);
        search.setPromptText("Search title");
        Button apply = new Button("Apply");
        apply.getStyleClass().addAll("btn-primary", "btn-sm");
        apply.setOnAction(event -> {
            historyFrom = from.getValue();
            historyTo = to.getValue();
            historyCategory = categories.getValue();
            historySeller = seller.getText().trim();
            historyKeyword = search.getText().trim();
            showHistory();
        });
        VBox list = new VBox(12);
        int bought = 0;
        int borrowed = 0;
        int received = 0;
        try {
            for (Map<String, Object> deal : DataService.getBuyerDeals()) {
                if (deal.get("completed_at") == null && deal.get("returned_at") == null) continue;
                if ("DECLINED".equals(deal.get("status"))) continue;
                LocalDate dealDate = parseDealDate(deal);
                if (historyFrom != null && (dealDate == null || dealDate.isBefore(historyFrom))) continue;
                if (historyTo != null && (dealDate == null || dealDate.isAfter(historyTo))) continue;
                if (!"All Categories".equals(historyCategory) && !historyCategory.equals(deal.get("category"))) continue;
                if (!historySeller.isBlank() && !textValue(deal.get("seller_name")).toLowerCase(Locale.ROOT)
                        .contains(historySeller.toLowerCase(Locale.ROOT))) continue;
                if (!historyKeyword.isBlank() && !textValue(deal.get("item_name")).toLowerCase(Locale.ROOT)
                        .contains(historyKeyword.toLowerCase(Locale.ROOT))) continue;
                String requestType = String.valueOf(deal.get("request_type"));
                String dealTypeName = switch (requestType) {
                    case "BORROW" -> "Borrowed";
                    case "CLAIM" -> "Received";
                    default -> "Bought";
                };
                switch (dealTypeName) {
                    case "Borrowed" -> borrowed++;
                    case "Received" -> received++;
                    default -> bought++;
                }
                String detail = switch (requestType) {
                    case "OFFER" -> deal.get("offer_price") == null ? "" : "HK$" + trimNumber(deal.get("offer_price"));
                    case "BORROW" -> formatRange(deal.get("borrow_start"), deal.get("borrow_end"));
                    case "CLAIM" -> "Giveaway";
                    default -> "";
                };
                list.getChildren().add(historyCard(
                        textValue(deal.get("item_name")),
                        textValue(deal.get("seller_name")),
                        textValue(deal.get("category")),
                        dealDate == null ? "Date unavailable" : dealDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                        dealTypeName,
                        deal.get("returned_at") != null ? "Returned" : "Completed",
                        detail,
                        deal.get("listing_id") == null ? "" : String.valueOf(deal.get("listing_id"))));
            }
        } catch (IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
        }
        if (list.getChildren().isEmpty()) {
            list.getChildren().add(styled("No completed deals match these filters.", muted()));
        }
        HBox summary = new HBox(8, badge(bought + " bought", "badge-bought"),
                badge(borrowed + " borrowed", "badge-borrowed"),
                badge(received + " received", "badge-received"),
                styled("Click a deal to open its listing.", faint()));
        summary.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroller = scroll(list);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        root.getChildren().addAll(
                new HBox(8, new Label("From"), from, new Label("To"), to, categories, seller, search, apply),
                summary,
                scroller);
        contentPane.getChildren().setAll(root);
    }

    private VBox historyCard(String title, String seller, String category, String date, String typeName,
                             String outcome, String detail, String listingId) {
        String accent = switch (typeName) {
            case "Borrowed" -> "history-borrowed";
            case "Received" -> "history-received";
            default -> "history-bought";
        };
        String typeStyle = switch (typeName) {
            case "Borrowed" -> "badge-borrowed";
            case "Received" -> "badge-received";
            default -> "badge-bought";
        };
        VBox card = new VBox(8);
        card.getStyleClass().addAll("card", "card-hover", "history-card", accent);
        card.setPadding(new Insets(14, 16, 14, 18));
        card.setCursor(Cursor.HAND);
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(badge(typeName, typeStyle), spacer,
                badge(outcome, "Returned".equals(outcome) ? "badge-returned" : "badge-sold"));
        HBox bottom = new HBox(8);
        bottom.setAlignment(Pos.CENTER_LEFT);
        Region footSpace = new Region();
        HBox.setHgrow(footSpace, Priority.ALWAYS);
        String meta = date + (detail == null || detail.isBlank() ? "" : " · " + detail);
        bottom.getChildren().addAll(styled(meta, faint()), footSpace,
                styled("View listing", "-fx-text-fill: #003974; -fx-font-size: 12px; -fx-font-weight: bold;"));
        card.getChildren().addAll(top,
                styled(title, "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a1a2e;"),
                styled(seller + " · " + (category == null || category.isBlank() ? "Uncategorised" : category), muted()),
                bottom);
        card.setOnMouseClicked(event -> {
            if (listingId == null || listingId.isBlank()) {
                alert(Alert.AlertType.ERROR, "This listing is no longer available.");
                return;
            }
            showListingDetail(listingId, DetailMode.HISTORY);
        });
        return card;
    }

    private Button heartButton(Listing listing, boolean favourited) {
        Button heart = new Button();
        heart.setFocusTraversable(false);
        styleHeart(heart, favourited);
        heart.setOnAction(event -> toggleFavourite(listing, heart));
        return heart;
    }

    private void styleHeart(Button heart, boolean favourited) {
        heart.setText(favourited ? "♥" : "♡");
        heart.setStyle(favourited
                ? "-fx-background-color: transparent; -fx-text-fill: #c62828; -fx-font-size: 18px; -fx-cursor: hand;"
                : "-fx-background-color: transparent; -fx-text-fill: #9aa0b0; -fx-font-size: 18px; -fx-cursor: hand;");
    }

    private boolean isFavourited(Listing listing) {
        try {
            return DataService.getBuyerFavouriteIds().contains(Integer.parseInt(listing.getId()));
        } catch (IllegalStateException | SecurityException | NumberFormatException ex) {
            return false;
        }
    }

    private void toggleFavourite(Listing listing, Button heart) {
        boolean favourite = "♡".equals(heart.getText());
        try {
            DataService.setBuyerFavourite(listing.getId(), favourite);
            styleHeart(heart, favourite);
            if (currentPage == Page.BROWSE) showBrowse();
            else if (currentPage == Page.FAVOURITES) showFavourites();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
        }
    }

    private Label badge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", styleClass);
        return label;
    }

    @FXML
    private void showFavourites() {
        setActive(btnFavourites, "Favourites & Following");
        currentPage = Page.FAVOURITES;
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));
        FlowPane grid = new FlowPane(16, 16);
        List<Listing> favourites;
        try {
            favourites = DataService.getBuyerFavouriteListings();
        } catch (IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            favourites = List.of();
        }
        if (favourites.isEmpty()) root.getChildren().add(new Label("No favourite listings yet."));
        else {
            for (Listing listing : favourites) grid.getChildren().add(createListingCard(listing, true));
            root.getChildren().add(grid);
        }
        contentPane.getChildren().setAll(scroll(root));
    }

    @FXML
    private void showNotifications() {
        setActive(btnNotifications, "Notification Board");
        currentPage = Page.NOTIFICATIONS;
        VBox root = new VBox(12);
        root.setPadding(new Insets(4));
        ComboBox<String> categories = new ComboBox<>(FXCollections.observableArrayList(
                "All", "Offer", "Chat", "Reminder", "Rating", "System", "Announcement"));
        categories.getSelectionModel().selectFirst();
        VBox list = new VBox(8);
        Runnable render = () -> {
            list.getChildren().clear();
            List<Notification> notifications;
            try {
                notifications = DataService.getBuyerNotifications();
            } catch (IllegalStateException | SecurityException ex) {
                list.getChildren().add(errorLabel(ex.getMessage()));
                return;
            }
            String selected = categories.getValue();
            List<Notification> shown = notifications.stream()
                    .filter(notification -> "All".equals(selected) || selected.equalsIgnoreCase(notification.getCategory()))
                    .toList();
            List<Map<String, Object>> deals;
            try {
                deals = DataService.getBuyerDeals();
            } catch (IllegalStateException | SecurityException ex) {
                deals = List.of();
            }
            if (shown.isEmpty()) list.getChildren().add(styled("No notifications in this category.", muted()));
            for (Notification notification : shown) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                if (!notification.isRead()) item.setStyle("-fx-border-color: #003974; -fx-border-width: 0 0 0 3;");
                Map<String, Object> deal = dealForNotification(notification, deals);
                String listingId = listingIdForNotification(notification, deal);
                HBox footer = new HBox(8);
                footer.setAlignment(Pos.CENTER_LEFT);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                footer.getChildren().add(styled(notification.getCategory() + " · " + (notification.getTimestamp() == null ? ""
                        : notification.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"))), faint()));
                footer.getChildren().add(spacer);
                if (listingId != null) {
                    Button go = new Button("Go ->");
                    go.getStyleClass().addAll("btn-outline", "btn-sm");
                    go.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
                    go.setOnAction(event -> openNotificationDeal(notification, deal, listingId));
                    footer.getChildren().add(go);
                }
                item.getChildren().addAll(
                        styled(notification.getTitle(), "-fx-font-weight: bold; -fx-text-fill: #1a1a2e;"),
                        styled(notification.getMessage(), "-fx-text-fill: #5a5a7a; -fx-font-size: 13px;"),
                        footer
                );
                item.setOnMouseClicked(event -> {
                    if (notification.isRead()) return;
                    try {
                        DataService.markBuyerNotificationRead(notification.getId());
                        notification.setRead(true);
                        item.setStyle(null);
                        refreshNotificationDot();
                    } catch (IllegalStateException | SecurityException ex) {
                        alert(Alert.AlertType.ERROR, ex.getMessage());
                    }
                });
                list.getChildren().add(item);
            }
            refreshNotificationDot();
        };
        categories.setOnAction(event -> render.run());
        root.getChildren().addAll(new HBox(8, new Label("Category"), categories), list);
        render.run();
        contentPane.getChildren().setAll(scroll(root));
    }

    private void openNotificationDeal(Notification notification, Map<String, Object> deal, String listingId) {
        try {
            DataService.markBuyerNotificationRead(notification.getId());
            refreshNotificationDot();
        } catch (IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
            return;
        }
        DetailMode mode = deal == null ? DetailMode.NOTICE
                : DataService.isBuyerDealFinished(deal) ? DetailMode.NOTICE_FINISHED : DetailMode.NOTICE;
        showListingDetail(listingId, mode);
    }

    private Map<String, Object> dealForNotification(Notification notification, List<Map<String, Object>> deals) {
        Integer requestId = notification.getRequestId();
        if (requestId == null && notification.getMessage() != null) {
            Matcher marker = REQUEST_MARKER.matcher(notification.getMessage());
            if (marker.find()) requestId = Integer.parseInt(marker.group(1));
        }
        if (requestId != null) {
            for (Map<String, Object> deal : deals) {
                if (deal.get("request_id") instanceof Number id && id.intValue() == requestId) return deal;
            }
        }
        if (notification.getListingId() != null) {
            for (Map<String, Object> deal : deals) {
                if (deal.get("listing_id") instanceof Number id
                        && id.intValue() == notification.getListingId()) return deal;
            }
        }
        String message = notification.getMessage() == null ? "" : notification.getMessage();
        Map<String, Object> best = null;
        int bestLength = 0;
        for (Map<String, Object> deal : deals) {
            String name = textValue(deal.get("item_name"));
            if (name.isBlank() || !message.contains(name) || name.length() < bestLength) continue;
            boolean betterStatus = name.length() == bestLength
                    && statusMatchesNotification(notification, deal)
                    && (best == null || !statusMatchesNotification(notification, best));
            if (name.length() > bestLength || betterStatus) {
                best = deal;
                bestLength = name.length();
            }
        }
        return best;
    }

    private boolean statusMatchesNotification(Notification notification, Map<String, Object> deal) {
        String title = notification.getTitle() == null ? "" : notification.getTitle().toLowerCase(Locale.ROOT);
        String status = String.valueOf(deal.get("status"));
        if (title.contains("counter")) return "COUNTERED".equals(status);
        if (title.contains("declin") || title.contains("no longer available")) return "DECLINED".equals(status);
        if (title.contains("accepted")) return "ACCEPTED".equals(status);
        if (title.contains("ended") || title.contains("returned")) return deal.get("returned_at") != null;
        if (title.contains("reminder") || title.contains("due")) return deal.get("returned_at") == null;
        return false;
    }

    private String listingIdForNotification(Notification notification, Map<String, Object> deal) {
        if (deal != null && deal.get("listing_id") != null) return String.valueOf(deal.get("listing_id"));
        return notification.getListingId() == null ? null : notification.getListingId().toString();
    }

    @FXML
    private void showProfile() {
        setActive(btnProfile, "Manage Profile");
        currentPage = Page.PROFILE;
        User user = SessionManager.getCurrentUser();
        VBox form = new VBox(14);
        form.setPadding(new Insets(8));
        form.setMaxWidth(460);
        form.getStyleClass().add("card");
        TextField nameField = new TextField(user != null ? user.getFullName() : "");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("Leave blank to keep the current password");
        PasswordField confirmPass = new PasswordField();
        ComboBox<String> residence = new ComboBox<>(FXCollections.observableArrayList(DataService.RESIDENCES));
        if (user != null && user.getResidenceHall() != null) residence.setValue(user.getResidenceHall());
        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(event -> {
            try {
                DataService.updateBuyerProfile(nameField.getText(), residence.getValue(),
                        newPass.getText(), confirmPass.getText());
                welcomeLabel.setText("Hello, " + SessionManager.getCurrentUser().getFullName());
                newPass.clear();
                confirmPass.clear();
                alert(Alert.AlertType.INFORMATION, "Your profile was updated successfully.");
            } catch (DataService.BuyerInputException ex) {
                if (ex.isClearConfirmPassword()) confirmPass.clear();
                alert(Alert.AlertType.ERROR, ex.getMessage());
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
        form.getChildren().addAll(
                styled("Edit your profile", "-fx-font-size: 18px; -fx-font-weight: bold;"),
                new Label("Full name"), nameField,
                new Label("New password"), newPass,
                styled("At least 8 characters, with an uppercase letter, a lowercase letter, and a digit.", faint()),
                new Label("Confirm password"), confirmPass,
                new Label("Residence (optional)"), residence,
                saveBtn);
        contentPane.getChildren().setAll(form);
    }

    private void askForRating(int requestId, String itemName) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Rate seller");
        dialog.setHeaderText("Rate the seller for " + itemName);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Spinner<Double> stars = new Spinner<>(0.0, 5.0, 5.0, 0.5);
        stars.setEditable(true);
        TextArea comment = new TextArea();
        comment.setPromptText("Optional short review");
        comment.setPrefRowCount(3);
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("Rating from 0.0 to 5.0"), stars, comment));
        dialog.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                DataService.rateCompletedDeal(requestId, stars.getValue(), comment.getText());
                alert(Alert.AlertType.INFORMATION, "Your rating was saved.");
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
    }

    private void closeScreen(String message) {
        stopDetailWatch();
        currentPage = Page.CLOSED;
        pageTitle.setText("Closed");
        contentPane.getChildren().setAll(styled(message + " Choose a section from the menu.", muted()));
    }

    private void enlargePhoto(Image image, int number) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Photo " + number);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(640);
        view.setFitHeight(480);
        dialog.getDialogPane().setContent(view);
        dialog.showAndWait();
    }

    private LocalDate parseDealDate(Map<String, Object> deal) {
        String raw = deal.get("completed_at") != null ? deal.get("completed_at").toString()
                : deal.get("returned_at") == null ? null : deal.get("returned_at").toString();
        if (raw == null || raw.isBlank()) return null;
        return LocalDateTime.parse(raw.replace(' ', 'T')).toLocalDate();
    }

    private boolean isOngoing(Map<String, Object> deal) {
        if (deal.get("completed_at") != null || deal.get("returned_at") != null) return false;
        String status = String.valueOf(deal.get("status"));
        return "PENDING".equals(status) || "COUNTERED".equals(status) || "ACCEPTED".equals(status);
    }

    private boolean isRated(Map<String, Object> deal) {
        return deal.get("rated") instanceof Number number && number.intValue() != 0;
    }

    private Image loadPhoto(String path, double width, double height) {
        try {
            String uri = path.startsWith("file:") ? path : Path.of(path).toAbsolutePath().toUri().toString();
            Image image = new Image(uri, width, height, true, true, false);
            return image.isError() ? null : image;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ComboBox<String> combo(List<String> values, String allLabel, String selected) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(allLabel);
        box.getItems().addAll(values);
        box.setValue(box.getItems().contains(selected) ? selected : allLabel);
        return box;
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

    private Label errorLabel(String message) {
        return styled(message, "-fx-text-fill: #c62828;");
    }

    private String muted() { return "-fx-text-fill: #5a5a7a; -fx-font-size: 12px;"; }
    private String faint() { return "-fx-text-fill: #9aa0b0; -fx-font-size: 11px;"; }

    private ScrollPane scroll(javafx.scene.Node node) {
        ScrollPane pane = new ScrollPane(node);
        pane.setFitToWidth(true);
        pane.setStyle("-fx-background-color: transparent;");
        return pane;
    }

    private void alert(Alert.AlertType type, String message) {
        new Alert(type, message).showAndWait();
    }

    private String formatDate(LocalDateTime value) {
        return value == null ? "Date unavailable" : value.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
    }

    private String formatRange(Object start, Object end) {
        if (start == null || end == null) return "dates to be confirmed";
        return dateOnly(start) + " → " + dateOnly(end);
    }

    private String dateOnly(Object value) {
        String text = value.toString();
        int split = text.indexOf('T');
        if (split < 0) split = text.indexOf(' ');
        return split > 0 ? text.substring(0, split) : text;
    }

    private Double parseOptionalPrice(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a positive number.");
        }
    }

    private String trimPrice(double price) {
        return price == Math.rint(price) ? String.format(Locale.ROOT, "%.0f", price)
                : String.format(Locale.ROOT, "%.2f", price);
    }

    private String trimNumber(Object value) {
        return value instanceof Number number ? trimPrice(number.doubleValue()) : textValue(value);
    }

    private String text(String value) { return value == null || value.isBlank() ? "—" : value; }
    private String textValue(Object value) { return value == null ? "" : value.toString(); }

    @FXML
    private void logout() {
        stopDetailWatch();
        if (returnTicker != null) returnTicker.stop();
        SessionManager.logout();
        SceneManager.showWelcome();
    }
}
