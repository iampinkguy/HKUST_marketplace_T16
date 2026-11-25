package com.hkust.marketplace.controllers;

import com.hkust.marketplace.utils.ListingDescriptionService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.IOException;

/** Adds description assistance to the listing form's description label row. */
public final class ListingDescriptionControls extends HBox {
    private static final String DRAFT_BUTTON_STYLE = "-fx-padding: 1 10;";
    private static final String LOADING_DRAFT_BUTTON_STYLE = DRAFT_BUTTON_STYLE
            + " -fx-opacity: 1; -fx-text-fill: #5a5a7a; -fx-border-color: #d0d7e2;";

    private final Label title = new Label("Description *");
    private boolean draftApplied;

    public ListingDescriptionControls(ListingDescriptionService service, TextField name, ComboBox<String> category,
                                      ComboBox<String> condition, TextArea description, Button save) {
        super(8);
        title.setLabelFor(description);
        Label feedback = new Label();
        feedback.setId("listing-draft-feedback");
        Region spacer = new Region();
        setHgrow(spacer, Priority.ALWAYS);

        var length = new ComboBox<>(FXCollections.observableArrayList(ListingDescriptionService.Length.values()));
        length.setId("listing-draft-length");
        length.setAccessibleText("Description length");
        length.setValue(ListingDescriptionService.Length.MEDIUM);
        length.setPrefSize(120, 28);
        length.setMinHeight(28);
        length.setMaxHeight(28);
        length.setStyle("-fx-padding: 0; -fx-opacity: 1;");

        Button generate = new Button(descriptionAction(description.getText()));
        generate.setId("listing-draft-generate");
        generate.getStyleClass().add("btn-outline");
        generate.setPrefSize(159, 28);
        generate.setMinHeight(28);
        generate.setMaxHeight(28);
        generate.setStyle(DRAFT_BUTTON_STYLE);
        generate.setTooltip(new Tooltip("Uses the item details and any text in the description box."));
        getChildren().addAll(title, feedback, spacer, length, generate);
        setAlignment(Pos.CENTER_LEFT);

        BooleanProperty generating = new SimpleBooleanProperty();
        BooleanProperty inputsChanged = new SimpleBooleanProperty();
        ChangeListener<String> recordEdit = (observable, before, after) -> inputsChanged.set(true);
        name.textProperty().addListener(recordEdit);
        category.valueProperty().addListener(recordEdit);
        condition.valueProperty().addListener(recordEdit);
        description.textProperty().addListener((observable, before, after) -> {
            inputsChanged.set(true);
            if (!generating.get()) generate.setText(descriptionAction(after));
        });
        generate.disableProperty().bind(generating.or(save.disableProperty()));
        length.disableProperty().bind(generate.disableProperty());
        generate.setOnAction(event -> {
            String itemName = name.getText();
            String itemCategory = category.getValue();
            String itemCondition = condition.getValue();
            if (itemName.isBlank() || itemCategory == null || itemCondition == null) {
                showFeedback(feedback, "Add item details", "#a32020", "Enter the item name, category, and condition first.");
                return;
            }

            String sellerNotes = description.getText();
            ListingDescriptionService.Length selectedLength = length.getValue();
            inputsChanged.set(false);
            showFeedback(feedback, "", "#5a5a7a", null);
            generating.set(true);
            generate.setText(sellerNotes.isBlank() ? "Generating…" : "Polishing…");
            generate.setStyle(LOADING_DRAFT_BUTTON_STYLE);
            Task<String> task = new Task<>() {
                @Override
                protected String call() throws Exception {
                    return service.generate(itemName, itemCategory, itemCondition, sellerNotes, selectedLength);
                }
            };
            task.setOnSucceeded(result -> {
                generating.set(false);
                generate.setText(descriptionAction(description.getText()));
                generate.setStyle(DRAFT_BUTTON_STYLE);
                if (!canUpdateForm(save)) return;
                if (inputsChanged.get()) {
                    showFeedback(feedback, "Edits kept", "#5a5a7a",
                            "The listing changed while DeepSeek was working. Try again to use the current details.");
                    return;
                }
                description.setText(task.getValue());
                draftApplied = true;
                showFeedback(feedback, "Draft ready", "#246b39", null);
            });
            task.setOnFailed(result -> {
                generating.set(false);
                generate.setText(descriptionAction(description.getText()));
                generate.setStyle(DRAFT_BUTTON_STYLE);
                if (!canUpdateForm(save)) return;
                generate.setText("Try Again");
                String detail = task.getException() instanceof IOException failure
                        ? failure.getMessage() : "Could not generate a description.";
                showFeedback(feedback, "Generation failed", "#a32020", detail);
            });
            Thread worker = new Thread(task, "listing-description-draft");
            worker.setDaemon(true);
            worker.start();
        });
    }

    // Center the compact controls in the existing label row using the spacing above and below it.
    @Override protected double computeMinHeight(double width) { return title.minHeight(width); }

    @Override protected double computePrefHeight(double width) { return title.prefHeight(width); }

    public String saveConfirmation() {
        return draftApplied ? "\nYour final description was saved." : "";
    }

    private boolean canUpdateForm(Button save) {
        Scene scene = getScene();
        return !save.isDisabled() && scene != null && scene.getWindow() != null && scene.getWindow().isShowing();
    }

    private static String descriptionAction(String text) {
        return text.isBlank() ? "Generate Description" : "Polish Description";
    }

    private static void showFeedback(Label label, String text, String color, String detail) {
        label.setText(text);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + color + ";");
        label.setTooltip(detail == null ? null : new Tooltip(detail));
    }
}
