package com.hkust.marketplace.utils;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * Full-screen listing photo dialog that fits the whole image first and supports zoom.
 */
public final class PhotoViewer {

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 5.0;
    private static final double ZOOM_STEP = 1.2;
    private static final double VIEWPORT_WIDTH = 720;
    private static final double VIEWPORT_HEIGHT = 500;

    private PhotoViewer() {
    }

    public static void show(Image image, String title) {
        if (image == null) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title == null || title.isBlank() ? "Listing Photo" : title);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(true);

        double imageWidth = image.getWidth() > 0 ? image.getWidth() : VIEWPORT_WIDTH;
        double imageHeight = image.getHeight() > 0 ? image.getHeight() : VIEWPORT_HEIGHT;
        double fitScale = Math.min(VIEWPORT_WIDTH / imageWidth, VIEWPORT_HEIGHT / imageHeight);
        if (fitScale <= 0 || Double.isNaN(fitScale) || Double.isInfinite(fitScale)) {
            fitScale = 1.0;
        }
        // Start at a size that shows the whole picture at once.
        double baseWidth = imageWidth * Math.min(1.0, fitScale);
        double[] zoom = {1.0};
        applyZoom(view, baseWidth, zoom[0]);

        StackPane imageHolder = new StackPane(view);
        imageHolder.setAlignment(Pos.CENTER);
        imageHolder.setPadding(new Insets(8));

        ScrollPane scroll = new ScrollPane(imageHolder);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.setPrefViewportWidth(VIEWPORT_WIDTH);
        scroll.setPrefViewportHeight(VIEWPORT_HEIGHT);
        scroll.setStyle("-fx-background-color: transparent;");

        Label zoomLabel = new Label("100%");
        zoomLabel.setStyle("-fx-text-fill: #5a5a7a; -fx-font-size: 12px; -fx-min-width: 48px;");

        Runnable refreshZoomLabel = () -> zoomLabel.setText(Math.round(zoom[0] * 100) + "%");

        Button zoomOut = new Button("Zoom −");
        zoomOut.getStyleClass().addAll("btn-outline", "btn-sm");
        zoomOut.setOnAction(event -> {
            zoom[0] = clamp(zoom[0] / ZOOM_STEP);
            applyZoom(view, baseWidth, zoom[0]);
            refreshZoomLabel.run();
        });

        Button zoomIn = new Button("Zoom +");
        zoomIn.getStyleClass().addAll("btn-outline", "btn-sm");
        zoomIn.setOnAction(event -> {
            zoom[0] = clamp(zoom[0] * ZOOM_STEP);
            applyZoom(view, baseWidth, zoom[0]);
            refreshZoomLabel.run();
        });

        Button reset = new Button("Fit");
        reset.getStyleClass().addAll("btn-outline", "btn-sm");
        reset.setOnAction(event -> {
            zoom[0] = 1.0;
            applyZoom(view, baseWidth, zoom[0]);
            refreshZoomLabel.run();
        });

        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) return;
            double factor = event.getDeltaY() > 0 ? ZOOM_STEP : 1.0 / ZOOM_STEP;
            zoom[0] = clamp(zoom[0] * factor);
            applyZoom(view, baseWidth, zoom[0]);
            refreshZoomLabel.run();
            event.consume();
        });

        HBox controls = new HBox(10, zoomOut, zoomLabel, zoomIn, reset);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(8, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(controls);
        dialog.getDialogPane().setContent(root);
        DialogStyle.showAndWait(dialog);
    }

    private static void applyZoom(ImageView view, double baseWidth, double zoom) {
        view.setFitWidth(Math.max(40, baseWidth * zoom));
        view.setFitHeight(-1);
    }

    private static double clamp(double zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }
}
