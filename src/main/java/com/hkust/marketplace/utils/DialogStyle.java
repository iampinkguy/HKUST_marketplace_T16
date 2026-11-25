package com.hkust.marketplace.utils;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import java.net.URL;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the shared HKUST Marketplace stylesheet and button look to pop-up dialogs.
 */
public final class DialogStyle {

    private static final String STYLE_MARKER = "app-dialog";
    private static final Object HANDLER_KEY = new Object();
    private static final String STYLESHEET;
    private static final String DEFAULT_RETRY = "Something went wrong. Please try again.";

    static {
        URL css = DialogStyle.class.getResource("/css/styles.css");
        STYLESHEET = css == null ? null : css.toExternalForm();
    }

    private DialogStyle() {
    }

    public static void apply(Dialog<?> dialog) {
        if (dialog == null) return;
        apply(dialog.getDialogPane());
        ensureShowingHandler(dialog);
        if (dialog.getOwner() == null) {
            Window owner = SceneManager.getPrimaryStage();
            if (owner != null && owner.isShowing()) {
                try {
                    dialog.initOwner(owner);
                } catch (IllegalStateException ignored) {
                    // Owner may already be locked after the dialog has been shown once.
                }
            }
        }
    }

    public static void apply(DialogPane pane) {
        if (pane == null) return;
        if (STYLESHEET != null && !pane.getStylesheets().contains(STYLESHEET)) {
            pane.getStylesheets().add(STYLESHEET);
        }
        if (!pane.getStyleClass().contains(STYLE_MARKER)) {
            pane.getStyleClass().add(STYLE_MARKER);
        }
        styleButtons(pane);
    }

    public static <R> Optional<R> showAndWait(Dialog<R> dialog) {
        apply(dialog);
        try {
            return dialog.showAndWait();
        } catch (IllegalStateException ex) {
            if (!isInternalUiError(ex)) throw ex;
            // showAndWait cannot run during a Timeline/layout pulse — defer one frame.
            AtomicReference<Optional<R>> result = new AtomicReference<>(Optional.empty());
            Object nestKey = new Object();
            Platform.runLater(() -> {
                try {
                    result.set(dialog.showAndWait());
                } finally {
                    Platform.exitNestedEventLoop(nestKey, null);
                }
            });
            Platform.enterNestedEventLoop(nestKey);
            return result.get();
        }
    }

    public static void show(Dialog<?> dialog) {
        apply(dialog);
        dialog.show();
    }

    public static Optional<ButtonType> alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message == null ? "" : message);
        return showAndWait(alert);
    }

    public static Optional<ButtonType> alert(Alert.AlertType type, String title, String header, String message) {
        Alert alert = new Alert(type);
        if (title != null) alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message == null ? "" : message);
        return showAndWait(alert);
    }

    /** True for JavaFX pulse/layout dialog errors that should never be shown to users. */
    public static boolean isInternalUiError(Throwable error) {
        if (error == null) return false;
        String message = error.getMessage();
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("showandwait is not allowed")
                || lower.contains("animation or layout processing")
                || lower.contains("not on fx application thread");
    }

    /**
     * Keeps useful business errors; replaces misleading JavaFX internals with a short fallback.
     * Pass {@code null} fallback to hide the message entirely when it is internal-only.
     */
    public static String userFacing(Throwable error, String fallback) {
        if (error == null) return fallback == null ? DEFAULT_RETRY : fallback;
        return userFacing(error.getMessage(), fallback);
    }

    public static String userFacing(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback == null ? DEFAULT_RETRY : fallback;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("showandwait is not allowed")
                || lower.contains("animation or layout processing")
                || lower.contains("not on fx application thread")) {
            return fallback;
        }
        return message;
    }

    private static void ensureShowingHandler(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.getProperties().containsKey(HANDLER_KEY)) return;
        EventHandler<DialogEvent> handler = event -> styleButtons(dialog.getDialogPane());
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, handler);
        pane.getProperties().put(HANDLER_KEY, handler);
    }

    private static void styleButtons(DialogPane pane) {
        boolean closeOnly = pane.getButtonTypes().size() == 1
                && pane.getButtonTypes().get(0) == ButtonType.CLOSE;
        for (ButtonType type : pane.getButtonTypes()) {
            Node node = pane.lookupButton(type);
            if (!(node instanceof Button button)) continue;
            button.getStyleClass().removeAll("btn-primary", "btn-outline", "btn-danger", "btn-gold", "btn-success");
            ButtonBar.ButtonData data = type.getButtonData();
            String text = button.getText() == null
                    ? (type.getText() == null ? "" : type.getText())
                    : button.getText();
            // Window chrome already has a close control — hide the duplicate dialog-bar X/Close.
            if (closeOnly || "X".equalsIgnoreCase(text.trim())) {
                button.setVisible(false);
                button.setManaged(false);
                continue;
            }
            if ("Reject".equalsIgnoreCase(text)
                    || "Delete".equalsIgnoreCase(text)
                    || "Take Down".equalsIgnoreCase(text)) {
                button.getStyleClass().add("btn-danger");
            } else if (data == ButtonBar.ButtonData.OK_DONE
                    || data == ButtonBar.ButtonData.APPLY
                    || data == ButtonBar.ButtonData.YES
                    || data == ButtonBar.ButtonData.FINISH) {
                button.getStyleClass().add("btn-primary");
            } else if (data == ButtonBar.ButtonData.CANCEL_CLOSE
                    || data == ButtonBar.ButtonData.NO
                    || data == ButtonBar.ButtonData.BACK_PREVIOUS
                    || data == ButtonBar.ButtonData.OTHER) {
                button.getStyleClass().add("btn-outline");
            } else {
                button.getStyleClass().add("btn-outline");
            }
        }
        if (closeOnly) {
            Node buttonBar = pane.lookup(".button-bar");
            if (buttonBar != null) {
                buttonBar.setVisible(false);
                buttonBar.setManaged(false);
            }
        }
    }
}
