package com.hkust.marketplace.controllers.admin;

import com.hkust.marketplace.models.*;
import com.hkust.marketplace.utils.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Reviews through the real queue, details, reason and confirmation controls. */
public class AdminListingReviewScreenTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String oldUrl;
    private Stage stage;
    private String listingId;
    private List<Path> uploaded = List.of();
    private record Step(BooleanSupplier ready, Runnable action) {}
    @BeforeClass public static void toolkit() throws Exception {
        Assume.assumeTrue("Desktop tests are opt-in", Boolean.getBoolean("marketplace.uiTests")); AdminFxTestSupport.startToolkit();
    }
    @Before public void open() throws Exception {
        oldUrl = System.getProperty("marketplace.database.url");
        System.setProperty("marketplace.database.url", "jdbc:sqlite:" + folder.getRoot().toPath().resolve("review.db"));
        DatabaseManager.initialize();
        for (UserRole role : UserRole.values()) DatabaseManager.createUser(role.name().toLowerCase(), "Test " + role.name(), "unused", role, null, null, null);
        Path photo = folder.getRoot().toPath().resolve("book.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 3, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
        Listing listing = new Listing(); listing.setTitle("Review calculus book"); listing.setDescription("Clean pages");
        listing.setType(ListingType.SELL); listing.setPrice(80.0); listing.setCategory("Textbooks"); listing.setCondition("Used"); listing.setMeetupLocation("Library");
        SessionManager.login(new User("seller", "Test SELLER", "unused", UserRole.SELLER));
        DataService.submitListing(listing, List.of(photo, photo)); listingId = listing.getId();
        uploaded = listing.getPhotoPaths().stream().map(Path::of).toList(); admin();
        fx(() -> {
            Parent root = FXMLLoader.load(Path.of("src/main/resources/fxml/admin/AdminDashboardView.fxml").toUri().toURL());
            stage = new Stage(); stage.setScene(new Scene(root)); stage.show(); return null;
        });
    }
    @After public void close() throws Exception {
        try { fx(() -> { for (Window w : List.copyOf(Window.getWindows())) w.hide(); return null; }); }
        finally {
            SessionManager.logout();
            if (oldUrl == null) System.clearProperty("marketplace.database.url"); else System.setProperty("marketplace.database.url", oldUrl);
            for (Path photo : uploaded) Files.deleteIfExists(photo);
        }
    }
    private void admin() { SessionManager.login(new User("admin", "Admin", "unused", UserRole.ADMIN)); }
    private <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(() -> { layout(); return action.call(); });
        Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    private void layout() { if (stage != null) { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); } }
    private Node node(String id) { layout(); return stage.getScene().lookup("#" + id); }
    private Button button(String id) { return (Button) node(id); }
    private DialogPane dialog(String title) {
        for (Window w : List.copyOf(Window.getWindows())) if (w.isShowing() && w instanceof Stage s && title.equals(s.getTitle())
                && s.getScene().getRoot() instanceof DialogPane pane) return pane;
        return null;
    }
    private static String text(Node n) {
        String value = n instanceof Labeled label ? label.getText() + "\n" : "";
        if (n instanceof TextInputControl input) value += input.getText() + "\n";
        if (n instanceof ScrollPane scroll && scroll.getContent() != null) value += text(scroll.getContent());
        if (n instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) value += text(child);
        return value;
    }
    private Step click(String id) { return new Step(() -> button(id) != null, () -> button(id).fire()); }
    private Step press(String title, ButtonType type) { return new Step(() -> dialog(title) != null, () -> ((Button) dialog(title).lookupButton(type)).fire()); }
    private void scenario(Step... steps) throws Exception {
        Deque<Step> queue = new ArrayDeque<>(List.of(steps)); AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1); ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(() -> Platform.runLater(() -> {
            try {
                layout(); if (queue.isEmpty()) { done.countDown(); return; }
                Step step = queue.peek(); if (step.ready().getAsBoolean()) { queue.remove(); step.action().run(); }
            } catch (Throwable failure) {
                error.set(failure); queue.clear(); for (Window w : List.copyOf(Window.getWindows())) if (w != stage) w.hide(); done.countDown();
            }
        }), 50, 100, TimeUnit.MILLISECONDS);
        try {
            assertTrue("Review scenario timed out with " + queue.size() + " steps remaining", done.await(25, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError("Review scenario failed", error.get());
        } finally { timer.shutdownNow(); }
    }
    @Test public void approvalRequiresConfirmationAndOnlyConfirmedApprovalPublishesAndNotifiesSeller() throws Exception {
        scenario(click("review-listing-" + listingId), click("approve-listing"),
            new Step(() -> dialog("Confirm Listing Decision") != null, () -> {
                assertTrue(dialog("Confirm Listing Decision").getHeaderText().contains("Review calculus book"));
                assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(listingId).getStatus());
                ((Button) dialog("Confirm Listing Decision").lookupButton(ButtonType.CANCEL)).fire();
            }),
            new Step(() -> dialog("Confirm Listing Decision") == null, () -> {
                assertTrue(DataService.getNotificationsFor("seller").isEmpty());
                assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(listingId).getStatus());
            }), click("approve-listing"), press("Confirm Listing Decision", ButtonType.OK),
            new Step(() -> dialog("Listing Decision Saved") != null, () -> {
                Listing saved = DataService.getListingForAdmin(listingId);
                assertEquals(ListingStatus.LIVE, saved.getStatus()); assertNotNull(saved.getPostedAt());
                assertNotNull(saved.getReviewedAt());
                assertEquals("Listing Approved", DataService.getNotificationsFor("seller").get(0).getTitle());
                ((Button) dialog("Listing Decision Saved").lookupButton(ButtonType.OK)).fire();
            }),
            new Step(() -> node("review-listing-" + listingId) == null, () -> assertTrue(text(node("contentPane")).contains("No listings awaiting review"))));
    }
    @Test public void rejectionRequiresWrittenReasonAndConfirmationAndCancellationDiscardsTheDraft() throws Exception {
        scenario(click("review-listing-" + listingId), click("reject-listing"),
            new Step(() -> dialog("Rejection Reason") != null, () -> {
                ((TextField) dialog("Rejection Reason").lookup("#rejection-reason")).setText(" \t ");
                ((Button) dialog("Rejection Reason").lookupButton(ButtonType.OK)).fire();
                assertNotNull(dialog("Rejection Reason")); assertNull(dialog("Confirm Listing Decision"));
                assertTrue(dialog("Rejection Reason").getHeaderText().contains("nonblank"));
                assertNull(DataService.getListingForAdmin(listingId).getRejectionReason());
                ((TextField) dialog("Rejection Reason").lookup("#rejection-reason")).setText("  Missing item details  ");
                ((Button) dialog("Rejection Reason").lookupButton(ButtonType.OK)).fire();
            }),
            new Step(() -> dialog("Confirm Listing Decision") != null, () -> {
                assertTrue(text(dialog("Confirm Listing Decision")).contains("Missing item details"));
                ((Button) dialog("Confirm Listing Decision").lookupButton(ButtonType.CANCEL)).fire();
            }),
            new Step(() -> dialog("Confirm Listing Decision") == null, () -> {
                assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(listingId).getStatus());
                assertNull(DataService.getListingForAdmin(listingId).getRejectionReason());
                assertTrue(DataService.getNotificationsFor("seller").isEmpty());
            }), click("reject-listing"),
            new Step(() -> dialog("Rejection Reason") != null, () -> {
                assertEquals("", ((TextField) dialog("Rejection Reason").lookup("#rejection-reason")).getText());
                ((Button) dialog("Rejection Reason").lookupButton(ButtonType.CANCEL)).fire();
            }), click("reject-listing"),
            new Step(() -> dialog("Rejection Reason") != null, () -> {
                ((TextField) dialog("Rejection Reason").lookup("#rejection-reason")).setText("  Missing item details  ");
                ((Button) dialog("Rejection Reason").lookupButton(ButtonType.OK)).fire();
            }), press("Confirm Listing Decision", ButtonType.OK),
            new Step(() -> dialog("Listing Decision Saved") != null, () -> {
                Listing saved = DataService.getListingForAdmin(listingId);
                assertEquals(ListingStatus.REJECTED, saved.getStatus()); assertNull(saved.getPostedAt());
                assertEquals("Missing item details", saved.getRejectionReason());
                Notification notice = DataService.getNotificationsFor("seller").get(0);
                assertEquals("Listing Rejected", notice.getTitle()); assertTrue(notice.getMessage().contains("Missing item details"));
                ((Button) dialog("Listing Decision Saved").lookupButton(ButtonType.OK)).fire();
            }));
    }
    @Test public void aListingChangedDuringConfirmationIsNotApprovedAndTheQueueRefreshes() throws Exception {
        scenario(click("review-listing-" + listingId), click("approve-listing"),
            new Step(() -> dialog("Confirm Listing Decision") != null, () -> {
                Listing changed = DataService.getListingForAdmin(listingId); changed.setTitle("Corrected title");
                DataService.updateListingAsAdmin(changed, List.of(), false);
                ((Button) dialog("Confirm Listing Decision").lookupButton(ButtonType.OK)).fire();
            }),
            new Step(() -> dialog("Listing Decision Not Saved") != null, () -> {
                assertTrue(text(dialog("Listing Decision Not Saved")).contains("changed"));
                assertTrue(text(dialog("Listing Decision Not Saved")).contains("Corrected title"));
                ((Button) dialog("Listing Decision Not Saved").lookupButton(ButtonType.OK)).fire();
            }),
            new Step(() -> node("review-listing-" + listingId) != null, () -> {
                assertTrue(text(node("contentPane")).contains("Corrected title"));
                assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(listingId).getStatus());
                assertTrue(DataService.getNotificationsFor("seller").stream().noneMatch(n -> "Listing Approved".equals(n.getTitle())));
            }));
    }
    @Test public void queueAndDetailsShowRequiredFieldsAndPhotosCanBeOpenedWithoutSavingADecision() throws Exception {
        fx(() -> {
            String queue = text(node("contentPane"));
            for (String required : List.of("Review calculus book", "seller", "Test SELLER", "Textbooks", "Sell", "Library", "Submitted:", "Pending Review")) {
                assertTrue("Queue must display " + required, queue.contains(required));
            }
            return null;
        });
        scenario(click("review-listing-" + listingId),
            new Step(() -> node("admin-listing-detail") != null, () -> {
                Node detail = node("admin-listing-detail");
                for (String required : List.of("Clean pages", "Condition: Used", "Availability:", "80", "Library", "Photo 1", "Photo 2")) {
                    assertTrue("Details must display " + required, text(detail).contains(required));
                }
                Button photo = detail.lookupAll(".button").stream().filter(n -> n instanceof Button b && "Photo 2".equals(b.getText()))
                        .map(n -> (Button) n).findFirst().orElseThrow();
                assertTrue(photo.getGraphic() instanceof javafx.scene.image.ImageView);
                assertFalse(((javafx.scene.image.ImageView) photo.getGraphic()).getImage().isError()); photo.fire();
            }),
            new Step(() -> dialog("Photo 2") != null, () -> {
                DialogPane photo = dialog("Photo 2"); photo.applyCss(); photo.layout();
                javafx.scene.image.ImageView image = photo.lookupAll("ImageView").stream().map(n -> (javafx.scene.image.ImageView) n).findFirst().orElseThrow();
                assertEquals(3.0, image.getImage().getWidth(), 0.001);
                assertFalse(image.getImage().isError());
                Window window = photo.getScene().getWindow(); window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
            }), click("close-listing-detail"),
            new Step(() -> node("review-listing-" + listingId) != null, () -> {
                assertEquals(ListingStatus.PENDING_REVIEW, DataService.getListingForAdmin(listingId).getStatus());
                assertTrue(DataService.getNotificationsFor("seller").isEmpty());
            }));
    }
}
