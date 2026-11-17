package com.hkust.marketplace.utils;

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
import java.util.Optional;

/**
 * Applies the shared HKUST Marketplace stylesheet and button look to pop-up dialogs.
 */
public final class DialogStyle {

    private static final String STYLE_MARKER = "app-dialog";
    private static final Object HANDLER_KEY = new Object();
    private static final String STYLESHEET;

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
        return dialog.showAndWait();
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

    private static void ensureShowingHandler(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.getProperties().containsKey(HANDLER_KEY)) return;
        EventHandler<DialogEvent> handler = event -> styleButtons(dialog.getDialogPane());
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, handler);
        pane.getProperties().put(HANDLER_KEY, handler);
    }

    private static void styleButtons(DialogPane pane) {
        for (ButtonType type : pane.getButtonTypes()) {
            Node node = pane.lookupButton(type);
            if (!(node instanceof Button button)) continue;
            button.getStyleClass().removeAll("btn-primary", "btn-outline", "btn-danger", "btn-gold", "btn-success");
            ButtonBar.ButtonData data = type.getButtonData();
            String text = type.getText() == null ? "" : type.getText();
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
    }
}
