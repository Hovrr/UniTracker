package com.unitracker.controller;

import com.unitracker.model.ProgressPreset;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Add/edit dialog for a single mastery curve (v2.0).
 *
 * <p>Deliberately built in code rather than in FXML. It is a small, entirely
 * data-shaped form whose field count is driven by {@link ProgressPreset}, and
 * keeping it here means the milestone list, the target, and the persistence
 * layer cannot drift apart - there is no separate declarative copy to forget to
 * update.
 *
 * <p>The one genuinely interesting behaviour is {@link #syncMilestones}, which
 * keeps the milestone preview honest as the user edits the target. That matters
 * because the milestones are stored as RATIOS, not absolute points: change the
 * target from 400 to 20000 and every threshold changes with it. A form that
 * showed stale numbers would be actively misleading.
 */
final class CurveEditor {

    private CurveEditor() {
    }

    /**
     * Shows the editor.
     *
     * @param existing the preset to edit, or null to create a new one
     * @return the edited preset, or null if the user cancelled
     */
    static ProgressPreset edit(ProgressPreset existing) {
        boolean creating = existing == null;
        ProgressPreset working = creating ? new ProgressPreset() : copyOf(existing);
        working.setKind(ProgressPreset.KIND_CURVE);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(creating ? "New Mastery Curve" : "Edit Mastery Curve");
        dialog.setHeaderText(creating
                ? "Define a target and the levels that lead up to it."
                : "Changes apply to new skills. Existing skills keep the milestones they have reached.");

        TextField name = new TextField(working.getName());
        name.setPromptText("e.g. 500-Hour Working Competence");
        name.setPrefColumnCount(28);

        TextArea description = new TextArea(working.getDescription());
        description.setPromptText("What does reaching this goal actually mean?");
        description.setPrefRowCount(3);
        description.setWrapText(true);

        Spinner<Double> target = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1, 10_000_000, working.getTargetPoints() == null ? 1000.0 : working.getTargetPoints(),
                100.0));
        target.setEditable(true);
        target.setPrefWidth(160);

        Label milestonePreview = new Label();
        milestonePreview.setWrapText(true);
        milestonePreview.getStyleClass().add("settings-hint");
        milestonePreview.setMaxWidth(520);
        milestonePreview.setMinHeight(58);

        // Recomputes the preview whenever the target changes. This is the
        // behaviour that makes the ratio-based storage understandable: the user
        // sees the ladder re-derive as they type rather than after saving.
        Runnable rebuild = () -> {
            double t = target.getValue() == null ? 0 : target.getValue();
            List<ProgressPreset.Milestone> milestones = working.buildMilestones(t);
            StringBuilder sb = new StringBuilder("Levels at this target: ");
            if (milestones.isEmpty()) {
                sb.append("none - the target is too small for the standard ladder.");
            } else {
                for (int i = 0; i < milestones.size(); i++) {
                    if (i > 0) {
                        sb.append(",  ");
                    }
                    sb.append(milestones.get(i).label()).append(" @ ")
                      .append(ProgressPreset.trimPoints(milestones.get(i).threshold()));
                }
            }
            milestonePreview.setText(sb.toString());
        };
        target.valueProperty().addListener((obs, old, value) -> rebuild.run());

        ButtonType saveType = new ButtonType(creating ? "Create" : "Save",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, new Label("Name"), name);
        grid.addRow(1, new Label("Description"), description);
        grid.addRow(2, new Label("Target points"), target);

        VBox content = new VBox(10, grid, milestonePreview);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(
                CurveEditor.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        // Rebuild once now, so the preview is populated before the dialog shows
        // rather than appearing a frame later.
        rebuild.run();

        dialog.getDialogPane().lookupButton(saveType).disableProperty()
                .bind(name.textProperty().isEmpty());

        return dialog.showAndWait()
                .filter(bt -> bt == saveType)
                .map(bt -> {
                    working.setName(name.getText().trim());
                    working.setDescription(description.getText() == null ? "" : description.getText().trim());
                    working.setTargetPoints(target.getValue());
                    // The ladder is regenerated from the FINAL target, so what
                    // is stored always matches what the preview showed.
                    working.setMilestonesJson(
                            working.serializeMilestones(working.buildMilestones(target.getValue())));
                    return working;
                })
                .orElse(null);
    }

    /**
     * A detached copy, so cancelling cannot leave a half-edited object in the
     * list the dialog was opened from.
     *
     * <p>A shallow field copy is sufficient and correct here: the only
     * collection is {@code milestonesJson}, which is an immutable String.
     */
    private static ProgressPreset copyOf(ProgressPreset source) {
        ProgressPreset copy = new ProgressPreset();
        copy.setId(source.getId());
        copy.setKind(source.getKind());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setMinutes(source.getMinutes());
        copy.setPoints(source.getPoints());
        copy.setTargetPoints(source.getTargetPoints());
        copy.setCurveRatio(source.getCurveRatio());
        copy.setMilestonesJson(source.milestonesJson());
        copy.setBuiltIn(source.isBuiltIn());
        copy.setSortOrder(source.getSortOrder());
        return copy;
    }
}
