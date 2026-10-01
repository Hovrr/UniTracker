package com.unitracker.controller;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressPreset;
import com.unitracker.util.Anim;
import com.unitracker.util.AppSettings;
import com.unitracker.util.FocusTimerState;
import com.unitracker.util.SoundPlayer;
import com.unitracker.util.UiScale;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The v2.0 Settings dialog.
 *
 * <h2>THE GOVERNING RULE: WRITE-THROUGH, NO DRAFT STATE</h2>
 * Every control here persists the instant it is committed, and the running
 * application picks the change up on its very next use. There is no Apply
 * button and no Cancel, and that is a deliberate design decision rather than an
 * omission:
 * <ul>
 *   <li>A Cancel that appeared to work but silently discarded a scale change
 *       the user had <em>already watched apply</em> is worse than having no
 *       such concept. Watching the UI resize and then have it snap back on
 *       Cancel reads as a bug in the preview, not as a discarded draft.</li>
 *   <li>A draft model needs a second copy of every setting, a "has anything
 *       changed" tracker, and a merge step. All three are places for the
 *       stored value and the live value to disagree - and the derived-points
 *       model in this codebase exists precisely because such a second copy was
 *       the original source of three permanent data-corruption bugs. The same
 *       reasoning applies to preferences.</li>
 * </ul>
 *
 * <h2>WHY NO RE-RESTART IS NEEDED</h2>
 * Each setting is read live rather than captured at startup:
 * <ul>
 *   <li><b>Animations</b> - {@link Anim} consults {@code AppSettings} on every
 *       call and short-circuits, so the next animation already honours it.</li>
 *   <li><b>UI scale</b> - {@link UiScale#apply} swaps the generated stylesheet
 *       on the live {@link Scene} and JavaFX re-resolves style immediately.</li>
 *   <li><b>Timer rewards</b> - {@code AppSettings} is the reader, and it caches
 *       with this class as the only writer, so the change is visible at once
 *       with no invalidation step.</li>
 *   <li><b>Presets</b> - read straight from SQLite on each list refresh.</li>
 * </ul>
 */
public class SettingsDialogController {

    // ---- Appearance ----
    @FXML private CheckBox animationsCheckBox;
    @FXML private Label animationsHint;
    @FXML private ComboBox<UiScale.Scale> scaleCombo;
    @FXML private Label scaleHint;
    @FXML private Button previewAnimationsButton;

    // ---- Focus Timer ----
    @FXML private Spinner<Double> pointsPerLevelSpinner;
    @FXML private Spinner<Double> baselinePointsSpinner;
    @FXML private VBox rewardGrid;
    @FXML private Button clearRewardsButton;

    // ---- Progress Presets ----
    @FXML private ComboBox<ProgressPreset> defaultCurveCombo;
    @FXML private Label curveHint;
    @FXML private ListView<ProgressPreset> presetList;
    @FXML private Label presetDetail;
    @FXML private Button addCurveButton;
    @FXML private Button editCurveButton;
    @FXML private Button deleteCurveButton;
    @FXML private Button resetPresetsButton;

    /**
     * Suppresses write-through while controls are being POPULATED.
     *
     * <p>Without it, loading the current values into the spinners would fire
     * their listeners and write the same values straight back - harmless in
     * effect, but it turns opening the dialog into a write transaction and, on
     * a corrupt stored value that the spinner clamps, into an actual
     * OVERWRITE of data the user never touched.
     */
    private boolean loading = false;

    /** The live scene, so a scale change can be applied immediately. */
    private Scene liveScene;

    /**
     * The dialog's own pane, so a motion change can be applied to it.
     *
     * <p>Separate from {@link #liveScene} on purpose. A Dialog is its own
     * {@code Scene} with its own stylesheet list and it does not inherit the
     * owner's, so the dashboard and this dialog each have to be addressed
     * explicitly. Null until {@link #show} builds the dialog, which is why
     * every use here is null-guarded rather than assumed.
     */
    private javafx.scene.control.DialogPane dialogPane;

    private final DatabaseHelper db = DatabaseHelper.getInstance();

    /** One spinner per duration, built in code from the same list the timer
     *  dropdown uses, so the two cannot fall out of step. */
    private final java.util.Map<Integer, Spinner<Double>> rewardSpinners = new java.util.LinkedHashMap<>();

    // -----------------------------------------------------------------
    //  Entry point
    // -----------------------------------------------------------------

    /**
     * Builds the dialog, wires it to the live scene, shows it, and returns
     * when the user closes it.
     *
     * <p>Static and self-contained rather than a method on DashboardController,
     * so the dialog can be opened from anywhere - including the floating
     * widget - without the controller having to grow another entry point.
     */
    public static void show(Scene liveScene) {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                SettingsDialogController.class.getResource("/com/unitracker/view/SettingsDialog.fxml"));
        javafx.scene.layout.Pane content;
        try {
            content = loader.load();
        } catch (Exception e) {
            // A broken settings dialog must never take the app down with it -
            // the user reached it by choice, not to fix something critical.
            System.err.println("[Settings] Could not load the settings dialog: " + e);
            e.printStackTrace();
            return;
        }
        SettingsDialogController controller = loader.getController();
        controller.liveScene = liveScene;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.initOwner(liveScene == null ? null : liveScene.getWindow());
        // The button bar is added here rather than declared in the FXML,
        // because a DialogPane cannot be an FXML root element, so there is no
        // DialogPane in the document to hang <buttonTypes> off.
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(
                SettingsDialogController.class.getResource("/com/unitracker/css/styles.css")
                        .toExternalForm());
        dialog.getDialogPane().getStyleClass().add("settings-pane");
        // Honor the motion preference at CONSTRUCTION, not only on the first
        // checkbox change. A user who disabled animations last session, or on
        // another machine via a synced profile, must not get a dialog that is
        // visibly animated while every window behind it is static - and that
        // user has no reason to think the checkbox is broken, because the one
        // control that would tell them is sitting in the animated dialog.
        controller.dialogPane = dialog.getDialogPane();
        UiScale.applyMotionTo(dialog.getDialogPane(), AppSettings.animationsEnabled());
        // Resizable, because the reward grid and the preset detail line both
        // wrap: a fixed-size dialog clips its own text on a small screen.
        dialog.setResizable(true);
        // Modal, and deliberately so: this is opened from a button, so a modal
        // dialog is what the user expects. The Focus Timer completion path
        // explicitly does NOT do this, because it runs inside an animation
        // pulse where a nested event loop is a real defect.
        dialog.showAndWait();
    }

    @FXML
    private void initialize() {
        loadAppearanceTab();
        loadTimerTab();
        loadPresetTab();
    }

    // -----------------------------------------------------------------
    //  Appearance
    // -----------------------------------------------------------------

    private void loadAppearanceTab() {
        loading = true;
        try {
            animationsCheckBox.setSelected(AppSettings.animationsEnabled());
            animationsCheckBox.setOnAction(e -> {
                if (loading) {
                    return;
                }
                boolean on = animationsCheckBox.isSelected();
                // Persist FIRST. Anim reads the setting on every call, so the
                // programmatic side needs no wiring at all - but the CSS side
                // does, and if attaching the override throws we would rather
                // have stored what the user asked for than silently disagree
                // with them on the next launch.
                AppSettings.set(AppSettings.KEY_ANIMATIONS_ENABLED, on);
                // The half that Anim cannot reach. Button hover and press
                // transforms, and the glow paired with them, are CSS - resolved
                // by the styling engine with no consultation of Anim. Applying
                // or removing an override sheet is the only way to change them
                // at runtime. See UiScale#applyMotion for why it cannot be
                // done in styles.css.
                applyMotionPreference(on);
                updateAnimationsHint(on);
                if (on) {
                    SoundPlayer.play(SoundPlayer.Sfx.CLICK);
                }
                statusHint("Animations " + (on ? "enabled." : "disabled."));
            });

            List<UiScale.Scale> tiers = List.of(UiScale.Scale.values());
            scaleCombo.setItems(FXCollections.observableArrayList(tiers));
            scaleCombo.setValue(AppSettings.uiScale());
            scaleCombo.valueProperty().addListener((obs, old, chosen) -> {
                if (loading || chosen == null) {
                    return;
                }
                // Persist FIRST, then apply. If applying throws, the stored
                // preference is still correct and the next launch will match
                // what the user asked for, rather than the two disagreeing.
                AppSettings.set(AppSettings.KEY_UI_SCALE, chosen.name());
                applyScale(chosen);
                statusHint("Interface size set to " + chosen.displayName() + ".");
            });

            updateAnimationsHint(AppSettings.animationsEnabled());
            updateScaleHint(AppSettings.uiScale());
        } finally {
            loading = false;
        }

        // The preview is the whole point of write-through being safe: the user
        // can see exactly what the setting does before navigating away.
        previewAnimationsButton.setOnAction(e -> {
            hideToastHint();
            if (!AppSettings.animationsEnabled()) {
                statusHint("Animations are off, so there is nothing to preview.");
                return;
            }
            Anim.pulse(previewAnimationsButton);
        });
    }

    /**
     * Propagates a change to the animation preference to every open scene.
     *
     * <p>Both the main window and the dialog are addressed, and the order is
     * deliberate: the dialog is fixed FIRST. Otherwise the user could uncheck
     * the box, watch the dashboard behind the modal go static, and then watch
     * this dialog stay fully animated until they closed it - which looks like
     * the setting applying inconsistently, and is far more confusing than the
     * original no-op.
     *
     * <p>Failures are contained per scene. One scene refusing a stylesheet must
     * not stop the others from being updated, and must not propagate out of a
     * checkbox handler and kill the dialog.
     */
    private void applyMotionPreference(boolean enabled) {
        // Every open window, for the same reason the scale does: the dashboard
        // behind this dialog and the floating widget both have their own Scene
        // and their own cascade, and leaving either animated makes the toggle
        // look like it only half worked.
        UiScale.applyToAllOpenWindows(AppSettings.uiScale());
        if (dialogPane != null) {
            UiScale.applyMotionTo(dialogPane, enabled);
        }
        if (liveScene != null) {
            try {
                UiScale.applyMotion(liveScene, enabled);
            } catch (RuntimeException e) {
                System.err.println("[Settings] Could not apply the motion preference: " + e.getMessage());
            }
        }
        // The floating widget owns a THIRD scene, created lazily. It re-reads
        // the preference itself when it is built, so nothing to do here - but
        // see FloatingTimerWindow, which is why it does not need notifying.
    }

    private void applyScale(UiScale.Scale chosen) {
        if (liveScene == null) {
            return;
        }
        try {
            // EVERY open window, not just the dashboard.
            //
            // This used to restyle the single scene handed in at construction,
            // which left the Settings dialog itself and the floating timer at the
            // old size - and those are the two the user is actually looking at
            // while they decide whether the setting works: the dialog is modal
            // and on top, and the widget is always-on-top. The dropdown looked
            // completely inert.
            //
            // apply() now clears and re-attaches the generated sheet on each
            // window's own ROOT (see UiScale#sheetHost for why the level
            // matters), re-applies CSS and layout recursively, and resizes
            // resizable windows to their content so a larger tier cannot leave
            // this dialog clipping its own buttons.
            UiScale.clear(liveScene);
            int touched = UiScale.applyToAllOpenWindows(chosen);
            if (touched == 0 && liveScene != null) {
                // The dialog has not been shown yet, so Window.getWindows()
                // may not list it. Fall back to the scene we were handed.
                UiScale.apply(liveScene, chosen);
            }
        } catch (RuntimeException e) {
            System.err.println("[Settings] Could not apply the UI scale: " + e.getMessage());
            statusHint("The new size could not be applied immediately; it will take effect on restart.");
        }
        updateScaleHint(chosen);
    }

    private void updateAnimationsHint(boolean on) {
        animationsHint.setText(on
                ? "Panels, charts and buttons animate in. Turn this off if you prefer the interface to be "
                  + "completely static - everything honours it, not just some of it."
                : "All animation is suppressed. Panels and charts appear instantly, and nothing fades, "
                  + "slides or pulses. Nothing is hidden or left half-drawn - the end state is applied "
                  + "directly, so the interface is static but completely usable.");
    }

    private void updateScaleHint(UiScale.Scale chosen) {
        double base = chosen.resolve().baseFontSize();
        String comparison;
        if (chosen == UiScale.Scale.AUTO) {
            comparison = "Chosen from your screen size.";
        } else if (base > AppSettings.DEFAULT_TIMER_POINTS + 12.5) {
            comparison = "Larger than the 1080p default.";
        } else if (base < 12.5) {
            comparison = "Smaller than the 1080p default - useful on a small panel or a heavily scaled display.";
        } else {
            comparison = "The 1080p baseline this layout was calibrated against.";
        }
        scaleHint.setText("Rescales the whole interface proportionally, text and spacing together, "
                + "rather than only a few hard-coded sizes. Base text is " + base + "px. " + comparison);
    }

    // -----------------------------------------------------------------
    //  Focus Timer
    // -----------------------------------------------------------------

    private void loadTimerTab() {
        loading = true;
        try {
            pointsPerLevelSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                    1, 100000, AppSettings.pointsPerLevel(), 10));
            pointsPerLevelSpinner.setEditable(true);
            // Committed by ENTER or focus loss, not per keystroke. A spinnner's
            // value property only changes on a committed edit, so typing "100"
            // does NOT write a transient 1 - which would otherwise re-level the
            // user mid-edit and fire a spurious level-up fanfare.
            pointsPerLevelSpinner.valueProperty().addListener((obs, old, value) -> {
                if (loading || value == null) return;
                AppSettings.set(AppSettings.KEY_POINTS_PER_LEVEL, value);
                statusHint("Points per level is now " + ProgressPreset.trimPoints(value) + ".");
            });

            baselinePointsSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                    0, 1000, AppSettings.getDouble(AppSettings.KEY_TIMER_DEFAULT_POINTS,
                            AppSettings.DEFAULT_TIMER_POINTS), 0.5));
            baselinePointsSpinner.setEditable(true);
            baselinePointsSpinner.valueProperty().addListener((obs, old, value) -> {
                if (loading || value == null) return;
                AppSettings.set(AppSettings.KEY_TIMER_DEFAULT_POINTS, value);
                statusHint("A 25-minute block is now worth "
                        + ProgressPreset.trimPoints(value) + " pts.");
            });

            buildRewardRows();
        } finally {
            loading = false;
        }

        clearRewardsButton.setOnAction(e -> {
            // Drop every per-duration override key from the cache and the table,
            // so each duration falls back to the pro-rata baseline. The set of
            // keys to KEEP is listed explicitly because a blanket "clear all
            // settings" would be catastrophic here.
            java.util.Set<String> keep = new java.util.HashSet<>(java.util.Set.of(
                    AppSettings.KEY_TIMER_MINUTES,
                    AppSettings.KEY_TIMER_DEFAULT_POINTS,
                    AppSettings.KEY_POINTS_PER_LEVEL,
                    AppSettings.KEY_DEFAULT_CURVE_ID,
                    AppSettings.KEY_MUTED,
                    AppSettings.KEY_UI_SCALE,
                    AppSettings.KEY_ANIMATIONS_ENABLED,
                    AppSettings.KEY_FLOATING_TIMER_OPEN,
                    AppSettings.KEY_TIMER_TOAST));
            for (int minutes : FocusTimerState.AVAILABLE_MINUTES) {
                String key = AppSettings.KEY_TIMER_POINTS_PREFIX + minutes;
                keep.remove(key);
                db.setSetting(key, "");
                AppSettings.invalidate(key);
            }
            AppSettings.retainOnly(keep);
            buildRewardRows();
            statusHint("All per-duration overrides cleared - every duration scales from the baseline again.");
        });
    }

    /**
     * Builds one row per duration from the timer's own preset list.
     *
     * <p>Generated rather than declared in FXML so the settings UI can never
     * disagree with the reward schedule the timer actually uses. A static grid
     * falls behind the moment a duration is added, and the symptom - a user
     * shorted points on a block they believed they had priced - is entirely
     * silent.
     */
    private void buildRewardRows() {
        rewardGrid.getChildren().clear();
        rewardSpinners.clear();
        boolean wasLoading = loading;
        loading = true;
        try {
            for (int minutes : FocusTimerState.AVAILABLE_MINUTES) {
                HBox row = new HBox(12);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

                Label name = new Label(FocusTimerState.formatDuration(minutes));
                name.getStyleClass().add("settings-label");
                name.setMinWidth(120);

                Spinner<Double> spinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                        0, 1000, AppSettings.pointsForDuration(minutes), 0.5));
                spinner.setEditable(true);
                spinner.setPrefWidth(120);
                spinner.setTooltip(new javafx.scene.control.Tooltip(
                        "Points for a completed " + FocusTimerState.formatDuration(minutes) + " session"));

                // NOTE this writes an explicit override for EVERY duration the
                // moment the grid is built, because the listener below fires
                // on the spinner construction... which is exactly why `loading`
                // is set across the whole loop rather than per control. The
                // spinner is only wired after its value factory is set, so no
                // write occurs here; the guard exists for the listeners added
                // below during a REBUILD, when the factory is re-created.
                spinner.valueProperty().addListener((obs, old, value) -> {
                    if (loading || value == null) return;
                    AppSettings.set(AppSettings.KEY_TIMER_POINTS_PREFIX + minutes, value);
                    statusHint(FocusTimerState.formatDuration(minutes) + " is now worth "
                            + ProgressPreset.trimPoints(value) + " pts.");
                });

                rewardSpinners.put(minutes, spinner);
                row.getChildren().addAll(name, spinner);
                rewardGrid.getChildren().add(row);
            }
        } finally {
            loading = wasLoading;
        }
    }

    // -----------------------------------------------------------------
    //  Progress presets
    // -----------------------------------------------------------------

    private void loadPresetTab() {
        refreshPresets();
        presetList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> updatePresetDetail(selected));
        // Delete is only offered for user presets; the database refuses anyway,
        // but a permanently live button that does nothing is worse than a
        // disabled one.
        presetList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) ->
                deleteCurveButton.setDisable(selected == null || selected.isBuiltIn()));
        editCurveButton.setDisable(false);
    }

    private void refreshPresets() {
        List<ProgressPreset> curves = db.getPresetsOfKind(ProgressPreset.KIND_CURVE);
        loading = true;
        try {
            presetList.setItems(FXCollections.observableArrayList(curves));
            presetList.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(ProgressPreset p, boolean empty) {
                    super.updateItem(p, empty);
                    if (empty || p == null) {
                        setText(null);
                        setStyle(null);
                        return;
                    }
                    setText(p.getName() + (p.isBuiltIn() ? "  (built-in)" : ""));
                    setStyle(p.isBuiltIn() ? "-fx-text-fill: -text-secondary;" : "");
                }
            });
            if (!curves.isEmpty()) {
                presetList.getSelectionModel().selectFirst();
            }
            updatePresetDetail(curves.isEmpty() ? null : curves.get(0));

            // The default-curve combo lists only mastery curves, since a
            // points-per-duration rate card cannot set a skill's target.
            List<ProgressPreset> options = new java.util.ArrayList<>();
            ProgressPreset none = new ProgressPreset(ProgressPreset.KIND_CURVE, "No preset", "");
            options.add(none);
            options.addAll(curves);
            defaultCurveCombo.setItems(FXCollections.observableArrayList(options));
            long wanted = AppSettings.defaultCurveId();
            ProgressPreset chosen = null;
            for (ProgressPreset p : options) {
                if (p.getId() == wanted) {
                    chosen = p;
                    break;
                }
            }
            defaultCurveCombo.setValue(chosen != null ? chosen : none);
            defaultCurveCombo.valueProperty().addListener((obs, old, selected) -> {
                if (loading || selected == null) return;
                // -1 is the "none" sentinel, stored as the synthetic preset's
                // default id.
                AppSettings.set(AppSettings.KEY_DEFAULT_CURVE_ID,
                        selected.getId() <= 0 ? -1 : (int) selected.getId());
                updateCurveHint(selected);
                statusHint(selected.getId() <= 0
                        ? "New skills will use a plain target."
                        : "New skills will start from the \"" + selected.getName() + "\" curve.");
            });
            updateCurveHint(defaultCurveCombo.getValue());
        } finally {
            loading = false;
        }
    }

    private void updateCurveHint(ProgressPreset selected) {
        if (selected == null || selected.getId() <= 0) {
            curveHint.setText("New skills start with a plain target-points spinner and no milestones.");
            return;
        }
        double target = selected.getTargetPoints() == null ? 0 : selected.getTargetPoints();
        List<ProgressPreset.Milestone> milestones = selected.getMilestones();
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(selected.getName()).append("\" sets a new skill's target to ")
          .append(ProgressPreset.trimPoints(target)).append(" points, with ")
          .append(milestones.size()).append(" milestone")
          .append(milestones.size() == 1 ? "" : "s").append(":");
        for (int i = 0; i < milestones.size(); i++) {
            sb.append(i == 0 ? " " : ", ")
              .append(milestones.get(i).label()).append(" at ")
              .append(ProgressPreset.trimPoints(milestones.get(i).threshold()));
        }
        sb.append(". Existing skills are not changed.");
        curveHint.setText(sb.toString());
    }

    private void updatePresetDetail(ProgressPreset selected) {
        if (selected == null) {
            presetDetail.setText("No preset selected.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(selected.getName());
        if (selected.getDescription() != null && !selected.getDescription().isBlank()) {
            sb.append(" - ").append(selected.getDescription());
        }
        List<ProgressPreset.Milestone> milestones = selected.getMilestones();
        if (!milestones.isEmpty()) {
            sb.append("  |  ");
            for (int i = 0; i < milestones.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(milestones.get(i).label()).append(" @ ")
                  .append(ProgressPreset.trimPoints(milestones.get(i).threshold()));
            }
        }
        if (selected.isBuiltIn()) {
            sb.append("  |  Built-in presets can be edited but not deleted.");
        }
        presetDetail.setText(sb.toString());
    }

    @FXML
    private void handleAddCurve() {
        ProgressPreset created = CurveEditor.edit(null);
        if (created == null) {
            return;
        }
        created.setKind(ProgressPreset.KIND_CURVE);
        if (db.insertPreset(created) > 0) {
            refreshPresets();
            presetList.getSelectionModel().select(created);
            statusHint("Created the \"" + created.getName() + "\" curve.");
        } else {
            statusHint("The curve could not be saved.");
        }
    }

    @FXML
    private void handleEditCurve() {
        ProgressPreset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ProgressPreset edited = CurveEditor.edit(selected);
        if (edited == null) {
            return;
        }
        if (db.updatePreset(edited)) {
            refreshPresets();
            presetList.getSelectionModel().select(edited);
            statusHint("Updated the \"" + edited.getName() + "\" curve.");
        } else {
            statusHint("The curve could not be saved.");
        }
    }

    @FXML
    private void handleDeleteCurve() {
        ProgressPreset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isBuiltIn()) {
            return;
        }
        if (db.deletePreset(selected.getId())) {
            refreshPresets();
            statusHint("Deleted the \"" + selected.getName() + "\" curve.");
        } else {
            statusHint("That curve could not be deleted.");
        }
    }

    @FXML
    private void handleResetPresets() {
        // Re-seeding is a no-op once any preset exists, so the built-ins are
        // restored by clearing the flag and re-running the seeder. A user's own
        // curves are left alone - deleting someone's work because they asked to
        // restore defaults would be a nasty surprise.
        db.resetBuiltinPresets();
        refreshPresets();
        statusHint("Built-in presets restored to their defaults. Your own curves were left alone.");
    }

    // -----------------------------------------------------------------
    //  Status line
    // -----------------------------------------------------------------

    /**
     * The dialog's own one-line status, shown under the tab content.
     *
     * <p>A settings dialog with no feedback is indistinguishable from one whose
     * changes silently failed - and the fact that these writes are invisible by
     * design makes a visible acknowledgement more important here, not less.
     */
    private void statusHint(String message) {
        if (presetDetail == null) {
            return;
        }
        presetDetail.setText(message);
        // Visible for a few seconds, then the selection detail returns.
        javafx.animation.PauseTransition clear = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(4));
        clear.setOnFinished(e -> updatePresetDetail(presetList.getSelectionModel().getSelectedItem()));
        clear.play();
    }

    private void hideToastHint() {
        // No-op kept for the preview button's symmetry with showToastHint.
    }
}
