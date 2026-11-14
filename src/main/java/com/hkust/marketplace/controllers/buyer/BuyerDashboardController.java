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
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BuyerDashboardController {

    private enum Page { BROWSE, DETAIL, DEALS, HISTORY, FAVOURITES, NOTIFICATIONS, PROFILE, CLOSED }

    private enum DetailMode { MARKET, DEAL, FINISHED }

    @FXML private StackPane contentPane;
    @FXML private Label pageTitle;
    @FXML private Label welcomeLabel;
    @FXML private Button btnBrowse, btnDeals, btnHistory, btnFavourites, btnNotifications, btnProfile;

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

    @FXML
    public void initialize() {
        User user = SessionManager.getCurrentUser();
        if (user != null) welcomeLabel.setText("Hello, " + user.getFullName());
        currentActiveBtn = btnBrowse;
        returnTicker = new Timeline(new KeyFrame(Duration.seconds(1), event -> settleReturns()));
        returnTicker.setCycleCount(Timeline.INDEFINITE);
        returnTicker.play();
        showBrowse();
    }

    private void settleReturns() {
        if (!SessionManager.isBuyer()) return;
        try {
            int returned = DataService.settleExpiredBorrows();
            if (returned > 0 && (currentPage == Page.DEALS || currentPage == Page.HISTORY || currentPage == Page.BROWSE)) {
                refreshCurrentPage();
            }
        } catch (IllegalStateException | SecurityException ignored) {
            // The next user action reports a real session problem.
        }
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
        if (live.isEmpty()) {
            Label empty = new Label("No live listings match the current search.");
            empty.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 15px;");
            root.getChildren().addAll(tools, summary, empty);
        } else {
            for (Listing listing : live) grid.getChildren().add(createListingCard(listing));
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

    private VBox createListingCard(Listing listing) {
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
        Label price = new Label(listing.getPriceDisplay());
        price.setStyle("-fx-font-weight: bold; -fx-text-fill: #003974; -fx-font-size: 16px;");
        top.getChildren().addAll(typeBadge, spacer, price);

        card.getChildren().addAll(thumb, top,
                styled(listing.getTitle(), "-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: #1a1a2e;"),
                styled(listing.getCategory() + " · " + listing.getCondition(), muted()),
                styled("Seller: " + text(listing.getSellerFullName()), muted()),
                styled("Meet-up: " + text(listing.getMeetupLocation()), muted()),
                styled("Posted: " + formatDate(listing.getPostedAt()), faint()),
                styled("Status: " + text(listing.getAvailabilityStatus()), muted()));
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
            if (mode == DetailMode.DEAL || mode == DetailMode.FINISHED) showMyDeals();
            else showBrowse();
        });
        header.getChildren().addAll(styled(listing.getTitle(), "-fx-font-size: 22px; -fx-font-weight: bold;"), spacer, close);

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

        VBox reviews = new VBox(6);
        reviews.getChildren().add(styled("Seller reviews", "-fx-font-weight: bold;"));
        List<Map<String, Object>> sellerReviews = DataService.getSellerReviews(listing.getSellerUsername());
        if (sellerReviews.isEmpty()) reviews.getChildren().add(styled("No reviews yet.", muted()));
        for (Map<String, Object> review : sellerReviews) {
            String reply = review.get("seller_reply") == null ? "" : "\nSeller reply: " + review.get("seller_reply");
            reviews.getChildren().add(styled(review.get("rating") + " / 5 · " + textValue(review.get("item_name"))
                    + "\n" + textValue(review.get("text")) + reply, muted()));
        }

        boolean finishedView = mode == DetailMode.FINISHED;
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
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Borrow Request");
        dialog.setHeaderText("Request to borrow: " + listing.getTitle());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        LocalDateTime startDefault = LocalDateTime.now().withNano(0);
        LocalDateTime endDefault = startDefault.plusSeconds(5);
        DatePicker startDate = new DatePicker(startDefault.toLocalDate());
        TextField startTime = new TextField(startDefault.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        DatePicker endDate = new DatePicker(endDefault.toLocalDate());
        TextField endTime = new TextField(endDefault.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("The shortest borrow period is 5 seconds."),
                new Label("Start date and time (HH:mm:ss)"), new HBox(8, startDate, startTime),
                new Label("End date and time (HH:mm:ss)"), new HBox(8, endDate, endTime)));
        dialog.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            try {
                LocalDateTime start = readDateTime(startDate, startTime, "Start");
                LocalDateTime end = readDateTime(endDate, endTime, "End");
                DataService.submitBorrowRequest(listing.getId(), start, end);
                DataService.sendBuyerListingChatMessage(listing.getId(),
                        "[Formal borrow request] " + start.toString().replace('T', ' ')
                                + " to " + end.toString().replace('T', ' '));
                alert(Alert.AlertType.INFORMATION, "Your borrow request was sent to " + listing.getSellerFullName() + ".");
                if (afterSuccess != null) afterSuccess.run();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
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
                            : message.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM HH:mm:ss"));
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
                styled("Status: " + status + (deal.get("due_at") == null ? "" : " · Due " + textValue(deal.get("due_at")).replace('T', ' ')), muted())
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
        root.getChildren().add(new HBox(8, new Label("From"), from, new Label("To"), to, categories, seller, search, apply));

        TableView<String[]> table = new TableView<>();
        table.getStyleClass().add("deal-history-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().addAll(column("Item Title", 0), column("Seller", 1), column("Deal Date", 2),
                column("Type", 3), column("Outcome", 4));
        List<String[]> rows = new ArrayList<>();
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
                String outcome = deal.get("returned_at") != null ? "Returned" : "Completed";
                rows.add(new String[]{
                        textValue(deal.get("item_name")),
                        textValue(deal.get("seller_name")),
                        dealDate == null ? "" : dealDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        dealTypeName,
                        outcome
                });
            }
        } catch (IllegalStateException | SecurityException ex) {
            alert(Alert.AlertType.ERROR, ex.getMessage());
        }
        table.setItems(FXCollections.observableArrayList(rows));
        root.getChildren().add(table);
        VBox.setVgrow(table, Priority.ALWAYS);
        contentPane.getChildren().add(root);
        contentPane.getChildren().setAll(root);
    }

    @FXML
    private void showFavourites() {
        setActive(btnFavourites, "Favourites & Following");
        currentPage = Page.FAVOURITES;
        VBox root = new VBox(16);
        root.setPadding(new Insets(4));
        FlowPane grid = new FlowPane(16, 16);
        User user = SessionManager.getCurrentUser();
        List<Listing> favourites = user == null ? List.of() : DatabaseManager.fetchFavouriteListings(user.getUsername());
        List<Integer> hidden = user == null ? List.of() : DatabaseManager.fetchReportedListingIds(user.getUsername());
        favourites = favourites.stream()
                .filter(listing -> !hidden.contains(Integer.parseInt(listing.getId())))
                .toList();
        if (favourites.isEmpty()) root.getChildren().add(new Label("No favourite listings yet."));
        else {
            for (Listing listing : favourites) grid.getChildren().add(createListingCard(listing));
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
            if (shown.isEmpty()) list.getChildren().add(styled("No notifications in this category.", muted()));
            for (Notification notification : shown) {
                VBox item = new VBox(4);
                item.getStyleClass().add("notif-item");
                if (!notification.isRead()) item.setStyle("-fx-border-color: #003974; -fx-border-width: 0 0 0 3;");
                item.getChildren().addAll(
                        styled(notification.getTitle(), "-fx-font-weight: bold; -fx-text-fill: #1a1a2e;"),
                        styled(notification.getMessage(), "-fx-text-fill: #5a5a7a; -fx-font-size: 13px;"),
                        styled(notification.getCategory() + " · " + (notification.getTimestamp() == null ? ""
                                : notification.getTimestamp().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"))), faint())
                );
                item.setOnMouseClicked(event -> {
                    try {
                        DataService.markBuyerNotificationRead(notification.getId());
                        showNotifications();
                        categories.setValue(selected);
                    } catch (IllegalStateException | SecurityException ex) {
                        alert(Alert.AlertType.ERROR, ex.getMessage());
                    }
                });
                list.getChildren().add(item);
            }
        };
        categories.setOnAction(event -> render.run());
        root.getChildren().addAll(new HBox(8, new Label("Category"), categories), list);
        render.run();
        contentPane.getChildren().setAll(scroll(root));
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

    private LocalDateTime readDateTime(DatePicker date, TextField time, String label) {
        if (date.getValue() == null) throw new IllegalArgumentException(label + " date is required.");
        String clock = time.getText() == null || time.getText().isBlank() ? "00:00:00" : time.getText().trim();
        try {
            return LocalDateTime.of(date.getValue(), LocalTime.parse(clock));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(label + " time must use HH:mm:ss.");
        }
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

    private TableColumn<String[], String> column(String title, int index) {
        TableColumn<String[], String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(cell.getValue()[index]));
        return column;
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
        return start.toString().replace('T', ' ') + " → " + end.toString().replace('T', ' ');
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
