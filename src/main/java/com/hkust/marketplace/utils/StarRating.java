package com.hkust.marketplace.utils;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.Locale;

/**
 * Formats 0.0–5.0 ratings (including half stars like 3.5) for display.
 */
public final class StarRating {

    private static final String FULL = "★";
    private static final String EMPTY = "☆";
    private static final String STAR_STYLE = "-fx-text-fill: #A68137; -fx-font-size: 16px;";

    private StarRating() {
    }

    /** Five-star bar as a node; supports exact half-stars (e.g. 3.5). */
    public static Node graphic(double rating) {
        double value = Math.max(0.0, Math.min(5.0, rating));
        HBox row = new HBox(1);
        row.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < 5; i++) {
            double fill = value - i;
            if (fill >= 0.75) {
                row.getChildren().add(starLabel(FULL));
            } else if (fill >= 0.25) {
                row.getChildren().add(halfStar());
            } else {
                row.getChildren().add(starLabel(EMPTY));
            }
        }
        Label score = new Label(String.format(Locale.ROOT, "  %.1f", value));
        score.setStyle("-fx-text-fill: #A68137; -fx-font-size: 13px;");
        row.getChildren().add(score);
        return row;
    }

    /** Plain-text fallback when a Node is awkward (full ★ / half ◐ / empty ☆). */
    public static String bar(double rating) {
        double value = Math.max(0.0, Math.min(5.0, rating));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            double fill = value - i;
            if (fill >= 0.75) out.append(FULL);
            else if (fill >= 0.25) out.append('◐');
            else out.append(EMPTY);
        }
        return out.toString();
    }

    public static String barWithScore(double rating) {
        double value = Math.max(0.0, Math.min(5.0, rating));
        return bar(value) + "  " + String.format(Locale.ROOT, "%.1f", value);
    }

    private static Label starLabel(String glyph) {
        Label label = new Label(glyph);
        label.setStyle(STAR_STYLE);
        return label;
    }

    private static Node halfStar() {
        Label empty = starLabel(EMPTY);
        Label full = starLabel(FULL);
        StackPane stack = new StackPane(empty, full);
        stack.setAlignment(Pos.CENTER_LEFT);
        Rectangle clip = new Rectangle(0, 0, 8, 18);
        full.setClip(clip);
        // Clip the filled star to the left half so 3.5 reads as three full + one half.
        Runnable syncClip = () -> {
            double width = full.getLayoutBounds().getWidth();
            double height = full.getLayoutBounds().getHeight();
            if (width <= 0) width = full.prefWidth(-1);
            if (height <= 0) height = full.prefHeight(-1);
            clip.setWidth(Math.max(1, width / 2.0));
            clip.setHeight(Math.max(1, height));
        };
        full.layoutBoundsProperty().addListener((obs, oldBounds, bounds) -> syncClip.run());
        stack.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) javafx.application.Platform.runLater(syncClip);
        });
        return stack;
    }
}
