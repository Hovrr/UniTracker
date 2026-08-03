package com.unitracker.controller;

import com.unitracker.command.CommandManager;
import com.unitracker.command.BatchLogProgressCommand;
import com.unitracker.command.DeleteSkillCommand;
import com.unitracker.command.EditSkillCommand;
import com.unitracker.command.LogProgressCommand;
import com.unitracker.command.SkillSnapshot;
import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.CalendarNote;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;
import com.unitracker.util.MarkdownUtil;
import com.unitracker.util.PdfExportUtil;
import com.unitracker.util.SoundPlayer;
import com.unitracker.util.UtrackFileUtil;
import com.unitracker.util.VisualizationRenderer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;
import netscape.javascript.JSObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for Dashboard.fxml (v5).
 *
 * WHAT CHANGED FROM v4:
 *   - Sticky notes: Add/Edit dialogs now include a Markdown formatting
 *     toolbar (buildMarkdownToolbar) instead of a bare TextArea; rendered
 *     checkboxes in note cards are genuinely clickable and persist their
 *     state back to the database via a small Java-JS bridge
 *     (setupCheckboxInteraction / CheckboxBridge); note cards gained
 *     Up/Down buttons and drag-and-drop reordering (moveNote / reorderNotes).
 *   - breadthLabel now dynamically tracks the breadth bar's actual position
 *     within the (scrollable) canvas viewport via repositionBreadthLabel(),
 *     instead of sitting at a fixed StackPane-relative offset that drifted
 *     out of alignment whenever the canvas was panned or auto-centered.
 *   - rotateLabelsCheckBox toggles between horizontal-wrapped and rotated
 *     label styles for BOTH Comb-Shaped and Skill-Tree (Skill-Tree's
 *     default is now horizontal-wrapped too, matching Comb-Shaped).
 */
public class DashboardController {

    // ----- Root / top bar -----
    @FXML private BorderPane rootPane;
    @FXML private Button undoButton;
    @FXML private Button redoButton;
    @FXML private Label statusBarLabel;

    // ----- Calendar + heatmap streaks -----
    @FXML private Label monthYearLabel;
    @FXML private GridPane calendarGrid;
    @FXML private Label currentStreakLabel;
    @FXML private Label longestStreakLabel;
    @FXML private TextField noteSearchField;

    // ----- Sticky notes -----
    @FXML private VBox notesContainer;
    @FXML private VBox pinnedNotesContainer;
    @FXML private Label selectedDateLabel;

    // ----- Collapsible sidebar (PART 2) -----
    @FXML private TitledPane calendarPane;
    @FXML private TitledPane timerPane;
    @FXML private TitledPane notesPane;
    @FXML private Label compactNotesHint;

    // ----- Pomodoro -----
    @FXML private Label pomodoroTimeLabel;
    @FXML private Button pomodoroStartButton;
    @FXML private Button pomodoroResetButton;
    @FXML private ProgressBar pomodoroProgressBar;
    /** Duration preset picker. Holds display labels ("45 min", "2 hr"); the
     *  selected INDEX maps back into TIMER_PRESETS. */
    @FXML private ComboBox<String> timerDurationCombo;
    @FXML private Button timerSettingsButton;
    @FXML private Button tipsButton;
    @FXML private ToggleButton muteToggle;

    // ----- Level / badge (zero-budget maximizer #1) -----
    @FXML private Label levelBadgeLabel;
    @FXML private Label levelProgressLabel;

    // ----- Skill tracker header -----
    @FXML private ComboBox<Skill> skillComboBox;
    @FXML private Button editSkillButton;
    @FXML private Button deleteSkillButton;
    @FXML private Circle statusDot;
    @FXML private ToggleButton activeStatusToggle;
    @FXML private ToggleButton stalledStatusToggle;
    @FXML private HBox colorSwatchRow;
    @FXML private ColorPicker customColorPicker;

    // ----- Metrics + persistent progress bar -----
    @FXML private Label currentPointsLabel;
    @FXML private Label targetPointsLabel;
    @FXML private Label percentageHeaderLabel;
    @FXML private ProgressBar mainProgressBar;

    // ----- Real-time input -----
    @FXML private Spinner<Integer> minutesSpinner;
    @FXML private Spinner<Double> pointsSpinner;
    @FXML private Button logSessionButton;

    // ----- Visualization toggles -----
    @FXML private ToggleButton curveToggle;
    @FXML private ToggleButton iShapedToggle;
    @FXML private ToggleButton combShapedToggle;
    @FXML private ToggleButton skillTreeToggle;
    @FXML private ToggleButton radarToggle;
    @FXML private ToggleButton velocityToggle;
    @FXML private ToggleButton timePieToggle;

    // ----- Depth-level filter (B.2): Comb-Shaped/Radar depth picker,
    //       Curve's specific-descendant picker. Hidden for I-Shaped/Skill-Tree. -----
    @FXML private ComboBox<String> depthLevelComboBox;

    // ----- Zoom / spacing controls -----
    @FXML private HBox zoomControlsRow;
    @FXML private Label zoomLevelLabel;
    @FXML private Slider hSpacingSlider;
    @FXML private Slider vSpacingSlider;
    @FXML private CheckBox rotateLabelsCheckBox;

    // ----- Multi-skill filter -----
    @FXML private ScrollPane filterScrollPane;
    @FXML private VBox filterCategoriesBox;

    // ----- Visualization area -----
    @FXML private SplitPane mainSplitPane;
    @FXML private VBox chartControlsPane;
    @FXML private StackPane visualizationStack;
    @FXML private LineChart<Number, Number> curveChart;
    @FXML private ScrollPane structuralCanvasScroll;
    @FXML private Canvas structuralCanvas;
    @FXML private Label breadthLabel;

    // ----- State -----
    private final DatabaseHelper db = DatabaseHelper.getInstance();
    private final ObservableList<Skill> skills = FXCollections.observableArrayList();
    private final CommandManager commandManager = new CommandManager();
    private final Set<Integer> filteredSkillIds = new HashSet<>();
    private final Map<String, Boolean> categoryExpandedState = new HashMap<>();

    private YearMonth currentMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    /** Overridable "pretend this is today" date (right-click a calendar day
     *  to move it here). Starts equal to the real system date; drives ONLY
     *  the .calendar-day-today CSS placement in buildDayCell(). selectedDate
     *  above - updated by BOTH left- and right-click - is what actually
     *  governs new notes / Log Session's date, since that should track
     *  whatever the user is currently looking at, not a separately-tracked
     *  "today" concept that could silently diverge from it. */
    private LocalDate mockToday = LocalDate.now();
    private Skill selectedSkill;
    private ToggleGroup vizToggleGroup;
    private Toggle lastActiveToggle;
    private String breadthCategoryLabel;

    private Skill boundSkill;
    private ChangeListener<Number> pointsChangeListener;

    // ----- Depth-level combo backing state (B.2) -----
    // Only populated/consulted when curveToggle is active: index in this
    // list matches index in depthLevelComboBox.getItems() so a selection
    // can be resolved back to the actual Skill without string-parsing the
    // displayed breadcrumb text.
    private final List<Skill> depthComboSkillOptions = new ArrayList<>();
    private Skill lastCurveComboSkill;

    private double zoomLevel = 1.0;

    private double panStartMouseX;
    private double panStartMouseY;
    private double panStartHValue;
    private double panStartVValue;

    /**
     * True once the user has deliberately moved the view (dragged the canvas or
     * scroll-zoomed at a cursor anchor). Auto-centering is suppressed from then
     * on, so we never yank the viewport out from under someone who has just
     * scrolled to the corner of a big tree. Cleared by switching chart type or
     * hitting Reset, both of which mean "give me a fresh view".
     */
    private boolean viewPinnedByUser;

    /** Last controls-pane preferred height the divider was anchored to (items
     *  4/5). Sentinel -1 so the very first layout pass always anchors. */
    private double lastAnchoredControlsHeight = -1;

    private static final double BASE_CANVAS_WIDTH = 600;
    private static final double BASE_CANVAS_HEIGHT = 380;
    private static final double ZOOM_MIN = 0.5;
    private static final double ZOOM_MAX = 2.5;
    private static final double ZOOM_STEP = 0.25;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final DateTimeFormatter NOTE_DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");
    private static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] PRESET_COLORS = {
            "#A8EB12", "#008793", "#414F6C", "#004D7A", "#E85D75",
            "#9B5DE5", "#FF8552", "#FFD23F", "#4CC9F0", "#F72585"
    };
    private static final String SETTING_BREADTH_LABEL = "breadth_category_label";

    /** Skill Decay: no logged session in this many days => STALLED.
     *  Re-derived from the log data on every load, never stored as a
     *  separate piece of state - which is what makes "auto-revert to
     *  ACTIVE on the next session" free rather than another code path. */
    private static final int STALLED_AFTER_DAYS = 14;

    /** Durations offered in the Focus Timer dropdown, in minutes. */
    private static final int[] TIMER_PRESETS = {15, 25, 30, 40, 45, 50, 60, 90, 120};

    /** Fallbacks used the first time the app runs, before anything is saved.
     *  The live values are read from app_settings - see timerMinutes(),
     *  pointsForDuration() and pointsPerLevel(). */
    private static final int DEFAULT_TIMER_MINUTES = 25;
    private static final double DEFAULT_TIMER_POINTS = 5.0;
    private static final double DEFAULT_POINTS_PER_LEVEL = 100.0;

    /** app_settings keys. "timer.points.<minutes>" is per-duration, so a
     *  2-hour block can be worth more than four 30-minute ones if the user
     *  says so. */
    private static final String KEY_TIMER_MINUTES = "timer.minutes";
    private static final String KEY_TIMER_POINTS_PREFIX = "timer.points.";
    private static final String KEY_TIMER_DEFAULT_POINTS = "timer.defaultPoints";
    private static final String KEY_POINTS_PER_LEVEL = "level.pointsPerLevel";
    private static final String KEY_MUTED = "sound.muted";
    /** One app_settings row per collapsible sidebar pane; the fx:id is the suffix. */
    private static final String KEY_SIDEBAR_PREFIX = "sidebar.expanded.";
    /** Below this content height the notes list collapses to pinned-only (PART 2).
     *  ~2 note cards' worth - under that a scrollable list shows nothing useful. */
    private static final double NOTES_COMPACT_THRESHOLD = 170;
    /** The sidebar column's own clamp, mirrored from Dashboard.fxml. Keep these
     *  in step with the left VBox's minWidth/maxWidth or the clock will scale
     *  against the wrong range. */
    private static final double COLUMN_MIN_WIDTH = 300;
    private static final double COLUMN_MAX_WIDTH = 420;
    /** Focus-timer clock size at those two widths. The floor is what lets
     *  "01:30:00" render whole in a narrow window instead of ellipsising;
     *  see setupPomodoro for the calibration. */
    private static final double CLOCK_FONT_MIN = 35;
    private static final double CLOCK_FONT_MAX = 50;

    /** Ticks the countdown once per second. AnimationTimer runs on the JavaFX
     *  application thread, so no Platform.runLater is needed to touch the UI
     *  from inside it - and nothing else needs a background thread either. */
    private AnimationTimer pomodoroTimer;
    private long pomodoroSecondsLeft = DEFAULT_TIMER_MINUTES * 60L;
    private boolean pomodoroRunning;

    /** Level reached at the last refresh, so refreshLevelBadge() can tell a
     *  genuine level-UP from a routine repaint and only then play the fanfare. */
    private int lastKnownLevel = -1;

    /** skill id -> tree depth, from parent_id. Refreshed whenever the skill list
     *  is reloaded. Backs the indented ComboBox cells; see
     *  applyHierarchyCellFactory() for why Skill#getDepth() cannot be used. */
    private Map<Integer, Integer> skillDepths = new HashMap<>();

    /** Guards the timer-duration listener while it repopulates its own items. */
    private boolean updatingTimerCombo;
    private static final String[] BADGE_TITLES = {
            "Novice", "Apprentice", "Practitioner", "Adept", "Specialist",
            "Expert", "Veteran", "Master", "Grandmaster", "Polymath"
    };

    /** Set on the "Current Streak" label whenever the streak is unbroken, so
     *  styles.css can make a live streak glow without the controller knowing
     *  any colours. */
    private static final PseudoClass STREAK_ALIVE = PseudoClass.getPseudoClass("alive");

    // =================================================================
    //  INITIALIZATION
    // =================================================================

    @FXML
    private void initialize() {
        setupVizToggleGroup();
        setupSkillComboBox();
        setupSpinners();
        setupColorSwatches();
        setupColorPicker();
        setupStatusToggles();
        setupUndoRedoButtons();
        setupKeyboardShortcuts();
        setupBreadthLabel();
        setupSpacingSliders();
        setupRotateLabelsToggle();
        setupCanvasPanning();
        setupDepthLevelComboBox();
        setupAdvancedLogButton();
        setupPomodoro();
        setupNoteSearch();
        setupResponsiveDivider();
        setupCollapsibleSidebar();
        // Mute is persisted, so a muted install stays muted across restarts.
        SoundPlayer.setMuted("true".equals(db.getSetting(KEY_MUTED, "false")));
        setupClickSound();
        if (muteToggle != null) {
            muteToggle.setSelected(SoundPlayer.isMuted());
            // The glyph stays fixed; .circle-button:selected in CSS shows muted state.
        }

        loadSkillsFromDatabase();
        applyStalledStatuses();
        buildCalendar();
        refreshPinnedNotes();
        refreshNotesForSelectedDate();
        refreshVisualization();
        refreshLevelBadge();

        statusBarLabel.setText("Connected to local SQLite database.");
    }

    private void setupVizToggleGroup() {
        vizToggleGroup = new ToggleGroup();
        for (ToggleButton tb : List.of(curveToggle, iShapedToggle,
                combShapedToggle, skillTreeToggle, radarToggle, velocityToggle, timePieToggle)) {
            tb.setToggleGroup(vizToggleGroup);
        }
        curveToggle.setSelected(true);

        vizToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                oldToggle.setSelected(true);
            }
        });
    }

    private void setupSkillComboBox() {
        skillComboBox.setItems(skills);
        applyHierarchyCellFactory(skillComboBox);
        skillComboBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldSkill, newSkill) -> selectSkill(newSkill));
    }

    /**
     * Makes a skill ComboBox show the hierarchy instead of a flat list where a
     * Category, a Skill and a Subskill all look identical.
     *
     * <p>Three cues, because indentation alone disappears once the list scrolls:
     * an indent per level, a leading glyph, and bold for root Categories.
     *
     * <p>CRITICAL - WHY DEPTH COMES FROM THE DATABASE: these combos are fed
     * {@code db.getAllSkills()}, a flat list whose Skill objects have a null
     * parent - only getSkillTree() links them. So Skill#getDepth() returns 0 for
     * every row here and indenting by it would silently do nothing. Depth is
     * therefore looked up from the parent_id-derived map. The map is read into a
     * local so a rebuild mid-scroll can't have cells disagreeing with each other.
     *
     * <p>The BUTTON cell deliberately does NOT indent - the collapsed box shows
     * one item and leading whitespace there just looks like a layout bug.
     */
    private void applyHierarchyCellFactory(ComboBox<Skill> box) {
        box.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Skill s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                    setStyle(null);
                    return;
                }
                int depth = skillDepths.getOrDefault(s.getId(), 0);
                String glyph = switch (depth) {
                    case 0 -> "◆ ";   // filled diamond - Category
                    case 1 -> "▸ ";   // small triangle - Skill
                    default -> "• ";  // bullet - Subskill and deeper
                };
                setText("    ".repeat(depth) + glyph + s.getName());
                // Root categories bold and brighter; deeper levels progressively
                // dimmer, so the eye can find the top of a group at a glance.
                setStyle(depth == 0
                        ? "-fx-font-weight: bold; -fx-text-fill: -text-primary;"
                        : depth == 1
                        ? "-fx-text-fill: -text-primary;"
                        : "-fx-text-fill: -text-secondary;");
            }
        });
        box.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Skill s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.getName());
            }
        });
    }

    private void setupSpinners() {
        minutesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 600, 25, 5));
        pointsSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100, 5, 0.5));
    }

    // =================================================================
    //  POMODORO TIMER + AUTO-LOG
    // =================================================================

    /**
     * 25-minute countdown driven by an AnimationTimer rather than a
     * Timeline/Thread: it already ticks on the JavaFX application thread, so
     * the label updates need no Platform.runLater, and it stops cleanly when
     * the app closes without leaving a non-daemon thread hanging around.
     *
     * <p>The timer's handle() fires ~60x/second; we only touch the UI when the
     * whole second actually changes, so this costs a subtraction per frame.
     */
    private void setupPomodoro() {
        if (pomodoroTimeLabel == null) return; // older layout without the widget

        // RESIZE FIX, half 2 of 2. The FXML's minWidth="0" guarantees the clock
        // can never force the panel outside the sidebar again, but on its own it
        // buys that by ellipsising to "01:30..." - a truncated timer is worse
        // than a small one. The panel simply cannot fit 8 glyphs at 50px when
        // the column is at its 300px floor: ~150px is left after the badge
        // column and the glass-panel padding, against ~212px needed.
        //
        // So the size follows the width. Calibrated at the two ends of the
        // column's own clamp (minWidth=300 / maxWidth=420 in the FXML):
        //   300px column -> ~150px for the clock -> 35px font
        //   420px column -> ~259px for the clock -> 50px fits outright
        // Interpolated linearly between, clamped at both ends so a collapsed
        // pane (width 0) or an unexpectedly wide one cannot produce a nonsense
        // size. Tune CLOCK_FONT_MIN/MAX if the font or the badge width changes.
        if (timerPane != null) {
            timerPane.widthProperty().addListener((obs, old, w) ->
                    pomodoroTimeLabel.setStyle(
                            "-fx-font-size: " + Math.round(clockFontFor(w.doubleValue())) + "px;"));
        }

        pomodoroSecondsLeft = timerMinutes() * 60L;

        if (timerDurationCombo != null) {
            List<String> labels = new ArrayList<>();
            for (int m : TIMER_PRESETS) labels.add(formatDuration(m));
            timerDurationCombo.setItems(FXCollections.observableArrayList(labels));
            timerDurationCombo.setValue(formatDuration(timerMinutes()));
            // Changing the duration while a timer runs would make the progress
            // bar lie about how far along you are, so it resets the countdown.
            timerDurationCombo.valueProperty().addListener((obs, old, chosen) -> {
                if (chosen == null || updatingTimerCombo) return;
                int minutes = TIMER_PRESETS[timerDurationCombo.getSelectionModel().getSelectedIndex()];
                db.setSetting(KEY_TIMER_MINUTES, String.valueOf(minutes));
                handlePomodoroReset();
                statusBarLabel.setText("Focus timer set to " + formatDuration(minutes)
                        + " (worth " + trimNumber(pointsForDuration(minutes)) + " pts).");
            });
        }

        pomodoroTimer = new AnimationTimer() {
            private long startNanos = -1;
            private long lastWholeSecond = -1;

            @Override
            public void start() {
                startNanos = -1;
                super.start();
            }

            @Override
            public void handle(long now) {
                if (startNanos < 0) {
                    startNanos = now;
                    lastWholeSecond = -1;
                }
                long elapsedSeconds = (now - startNanos) / 1_000_000_000L;
                if (elapsedSeconds == lastWholeSecond) return;
                lastWholeSecond = elapsedSeconds;

                long remaining = pomodoroSecondsLeft - elapsedSeconds;
                if (remaining <= 0) {
                    stop();
                    pomodoroRunning = false;
                    pomodoroSecondsLeft = 0;
                    updatePomodoroDisplay();
                    onPomodoroComplete();
                } else {
                    updatePomodoroDisplay(remaining);
                }
            }
        };
        updatePomodoroDisplay();
    }

    @FXML
    private void handlePomodoroToggle() {
        if (pomodoroTimer == null) return;
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        if (pomodoroRunning) {
            // Pause: bank the remaining time so Start resumes instead of restarting.
            pomodoroTimer.stop();
            pomodoroSecondsLeft = remainingPomodoroSeconds();
            pomodoroRunning = false;
            pomodoroStartButton.setText("Resume");
            statusBarLabel.setText("Focus timer paused at " + formatMMSS(pomodoroSecondsLeft) + ".");
        } else {
            if (pomodoroSecondsLeft <= 0) pomodoroSecondsLeft = timerMinutes() * 60L;
            pomodoroTimer.start();
            pomodoroRunning = true;
            pomodoroStartButton.setText("Pause");
            statusBarLabel.setText("Focus timer started - " + formatDuration(timerMinutes())
                    + " worth " + trimNumber(pointsForDuration(timerMinutes())) + " pts.");
        }
    }

    @FXML
    private void handlePomodoroReset() {
        if (pomodoroTimer == null) return;
        pomodoroTimer.stop();
        pomodoroRunning = false;
        pomodoroSecondsLeft = timerMinutes() * 60L;
        pomodoroStartButton.setText("Start");
        updatePomodoroDisplay();
        statusBarLabel.setText("Focus timer reset to " + formatMMSS(pomodoroSecondsLeft) + ".");
    }

    /**
     * Item 2.2's configuration half: the reward for the CURRENT duration, the
     * baseline reward used to scale every other duration, and the points needed
     * per level.
     *
     * <p>NO SCHEMA CHANGE: all three land in the existing generic app_settings
     * key/value table, which keeps the user's historical log data untouched.
     *
     * <p>Only the selected duration gets an explicit override row here rather
     * than a nine-row grid of every preset. The baseline scales the rest
     * proportionally ({@link #pointsForDuration}), so the common case - "a
     * 25-minute block is worth 5, make the others make sense" - is one number,
     * and anyone wanting a bespoke value for 90 minutes just selects 90 and sets
     * it. Overrides already set stay set; this only ever writes what changed.
     */
    @FXML
    private void handleTimerSettings() {
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        int minutes = timerMinutes();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Timer & Level Settings");
        dialog.setHeaderText("Rewards for focus sessions, and how fast you level up.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Spinner<Double> thisDuration = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 1000, pointsForDuration(minutes), 0.5));
        thisDuration.setEditable(true);

        Spinner<Double> baseline = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 1000, db.getSettingDouble(KEY_TIMER_DEFAULT_POINTS, DEFAULT_TIMER_POINTS), 0.5));
        baseline.setEditable(true);

        Spinner<Double> perLevel = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1, 100000, pointsPerLevel(), 10));
        perLevel.setEditable(true);

        Label explain = new Label("Durations without their own reward are scaled from the baseline, "
                + "so " + DEFAULT_TIMER_MINUTES + " min = baseline.");
        explain.setWrapText(true);
        explain.getStyleClass().add("metric-label");
        explain.setMaxWidth(320);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Points for " + formatDuration(minutes)), thisDuration);
        form.addRow(1, new Label("Baseline (per " + DEFAULT_TIMER_MINUTES + " min)"), baseline);
        form.addRow(2, new Label("Points per level"), perLevel);
        form.add(explain, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets()
                .add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            db.setSetting(KEY_TIMER_POINTS_PREFIX + minutes, String.valueOf(thisDuration.getValue()));
            db.setSetting(KEY_TIMER_DEFAULT_POINTS, String.valueOf(baseline.getValue()));
            db.setSetting(KEY_POINTS_PER_LEVEL, String.valueOf(perLevel.getValue()));
            // The badge reads pointsPerLevel() live, so the level can legitimately
            // move here; suppress the fanfare because lowering the threshold is
            // not an achievement.
            lastKnownLevel = -1;
            refreshLevelBadge();
            statusBarLabel.setText(formatDuration(minutes) + " is now worth "
                    + trimNumber(thisDuration.getValue()) + " pts · "
                    + trimNumber(perLevel.getValue()) + " pts per level.");
        });
    }

    /** Global sound on/off, persisted so it survives a restart. */
    @FXML
    private void handleToggleMute() {
        boolean muted = muteToggle.isSelected();
        SoundPlayer.setMuted(muted);
        db.setSetting(KEY_MUTED, String.valueOf(muted));
        // The glyph no longer changes: .circle-button:selected in styles.css
        // carries the muted look, so swapping text here would only fight the
        // fixed 32px circle (a wider glyph forces an oval).
        // Plays only when UNmuting, which doubles as a confirmation that audio
        // actually works on this machine.
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        statusBarLabel.setText(muted ? "Sounds muted." : "Sounds on.");
    }

    /**
     * Item 2.3: the discoverability hints. Everything listed here is a real
     * interaction that exists in this controller - a tips panel that lies is
     * worse than no tips panel, so this is deliberately hand-maintained
     * alongside the handlers rather than generated.
     */
    @FXML
    private void handleShowTips() {
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        String[][] tips = {
                {"Log Session", "Right-click the button to log for a specific past date, or to batch-log several days at once."},
                {"Calendar", "Click any day to see and edit that day's note. Brighter tiles mean more points that day."},
                {"Notes", "Type #tags in a note, then search them in the box above the calendar. Pin a note to keep it on top."},
                {"Canvas charts", "Scroll the wheel over the chart to zoom at the cursor. Drag to pan. Reset re-centers everything."},
                {"Focus timer", "Pick a duration from the dropdown; the gear sets what it's worth and how many points a level costs."},
                {"Skill picker", "Indented entries are children - bold ◆ is a category, ▸ a skill, • a subskill."},
                {"Undo", "Ctrl+Z undoes the last log, edit or delete; Ctrl+Shift+Z redoes it. Rollups to parent skills undo too."},
                {"Layout", "Drag the divider between the charts and the panels below to rebalance the space."},
                {"Spacing", "Right-click or double-click either spacing slider to type an exact value."},
        };

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        int row = 0;
        for (String[] tip : tips) {
            Label what = new Label(tip[0]);
            what.getStyleClass().add("panel-title");
            what.setMinWidth(110);
            Label how = new Label(tip[1]);
            how.getStyleClass().add("metric-label");
            how.setWrapText(true);
            how.setMaxWidth(420);
            grid.addRow(row++, what, how);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        scroll.getStyleClass().add("canvas-scroll");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tips & Shortcuts");
        dialog.setHeaderText("Things that are easy to miss.");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getStylesheets()
                .add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private long remainingPomodoroSeconds() {
        // Parsed back off the label so pause/resume can't drift from what the
        // user is actually looking at. Handles both mm:ss and h:mm:ss.
        String[] parts = pomodoroTimeLabel.getText().split(":");
        try {
            long total = 0;
            for (String part : parts) total = total * 60 + Long.parseLong(part.trim());
            return total;
        } catch (RuntimeException malformed) {
            return pomodoroSecondsLeft;
        }
    }

    private void updatePomodoroDisplay() {
        updatePomodoroDisplay(pomodoroSecondsLeft);
    }

    private void updatePomodoroDisplay(long secondsLeft) {
        pomodoroTimeLabel.setText(formatMMSS(secondsLeft));
        double total = timerMinutes() * 60.0;
        pomodoroProgressBar.setProgress(Math.max(0, Math.min(1, (total - secondsLeft) / total)));
    }

    private static String formatMMSS(long totalSeconds) {
        long safe = Math.max(0, totalSeconds);
        // Presets now go up to 2 hours, where mm:ss would read "120:00".
        // %02d on the hour too: "01:30:00", never "1:30:00". The label is
        // left-aligned in the horizontal layout, so a variable-width hour
        // would shift every digit to its right by ~27px as the clock crosses
        // from 1:xx to 0:xx - a leading zero keeps the glyphs anchored.
        if (safe >= 3600) {
            return String.format("%02d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60);
        }
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    /** 45 -> "45 min", 60 -> "1 hr", 90 -> "1 hr 30 min". Used for the dropdown
     *  labels and the status bar, not the countdown itself. */
    private static String formatDuration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int hours = minutes / 60;
        int rest = minutes % 60;
        String h = hours + (hours == 1 ? " hr" : " hrs");
        return rest == 0 ? h : h + " " + rest + " min";
    }

    // =================================================================
    //  TIMER + LEVEL CONFIGURATION  (persisted in app_settings)
    // =================================================================

    /** Currently selected timer length. Clamped to a sane range so a
     *  hand-edited setting can't produce a zero-length or week-long timer. */
    private int timerMinutes() {
        int stored = db.getSettingInt(KEY_TIMER_MINUTES, DEFAULT_TIMER_MINUTES);
        return (int) clamp(stored, 1, 600);
    }

    /**
     * Points awarded for completing a given duration.
     *
     * <p>Two-level lookup on purpose: an explicit per-duration override wins,
     * otherwise the reward scales pro-rata from the configurable default so a
     * user who only ever sets "5 points for 25 minutes" still gets a sensible
     * 12 points for an hour instead of a flat 5.
     */
    private double pointsForDuration(int minutes) {
        double override = db.getSettingDouble(KEY_TIMER_POINTS_PREFIX + minutes, -1);
        if (override >= 0) return override;

        double perDefaultBlock = db.getSettingDouble(KEY_TIMER_DEFAULT_POINTS, DEFAULT_TIMER_POINTS);
        double scaled = perDefaultBlock * minutes / (double) DEFAULT_TIMER_MINUTES;
        return Math.round(scaled * 2) / 2.0; // nearest half point
    }

    /** Points per level. Floored at 1 so a zero can't divide by zero below. */
    private double pointsPerLevel() {
        return Math.max(1, db.getSettingDouble(KEY_POINTS_PER_LEVEL, DEFAULT_POINTS_PER_LEVEL));
    }

    /** Session finished: chime, then open the log dialog pre-filled with the
     *  minutes that were actually just spent. */
    private void onPomodoroComplete() {
        pomodoroStartButton.setText("Start");
        int justCompleted = timerMinutes();
        pomodoroSecondsLeft = justCompleted * 60L;
        SoundPlayer.play(SoundPlayer.Sfx.TIMER_FINISH);
        showPomodoroLogDialog(justCompleted);
    }

    /** Pre-filled confirmation: the duration just completed, its configured
     *  point reward, and today. The user can still change the skill or the
     *  points before confirming - a session spent on the wrong skill is worse
     *  than one that takes an extra click. */
    private void showPomodoroLogDialog(int minutesCompleted) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Focus Session Complete");
        dialog.setHeaderText("Nice work - " + formatDuration(minutesCompleted) + " of focus.");

        ComboBox<Skill> skillPicker = new ComboBox<>(skills);
        skillPicker.setPromptText("Select a skill");
        applyHierarchyCellFactory(skillPicker);
        if (selectedSkill != null) skillPicker.getSelectionModel().select(selectedSkill);
        else if (!skills.isEmpty()) skillPicker.getSelectionModel().selectFirst();
        skillPicker.setMaxWidth(Double.MAX_VALUE);

        // Max is generous rather than 100: a 2-hour block with a custom reward
        // can legitimately be worth more than the old fixed cap allowed.
        Spinner<Double> points = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 1000, pointsForDuration(minutesCompleted), 0.5));
        points.setEditable(true);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Skill"), skillPicker);
        form.addRow(1, new Label("Minutes"), new Label(String.valueOf(minutesCompleted)));
        form.addRow(2, new Label("Points"), points);
        form.addRow(3, new Label("Date"), new Label(mockToday.format(NOTE_DATE_FMT)));
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets()
                .add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            Skill target = skillPicker.getValue();
            if (target == null) {
                statusBarLabel.setText("Session finished, but no skill was selected - nothing logged.");
                return;
            }
            double pointsBefore = target.getCurrentPoints();
            ProgressLog log = new ProgressLog(target.getId(), mockToday, minutesCompleted, points.getValue());
            log.setNote("Focus session (" + formatDuration(minutesCompleted) + ")");
            commandManager.execute(new LogProgressCommand(db, target, skills, log));
            SoundPlayer.play(SoundPlayer.Sfx.LOG_SESSION);
            celebrateIfJustCompleted(pointsBefore, target);
            afterLogChanged();
            buildCalendar();
            statusBarLabel.setText("Focus session logged to " + target.getName() + ".");
        });
        updatePomodoroDisplay();
    }

    // =================================================================
    //  SKILL DECAY  ("Stalled" status)
    // =================================================================

    /**
     * Recomputes ACTIVE/STALLED for every skill from its last logged session.
     * <p>
     * DERIVED, NOT STORED-AND-FORGOTTEN: because the flag is recalculated
     * from the log data every time, "auto-revert to Active once a new session
     * is logged" needs no separate code path, no background thread, and no
     * scheduled job - the next call simply sees a fresh date. The result is
     * still written back to the DB so exports and the status dot agree.
     * <p>
     * Only writes rows whose status actually changed, so a normal refresh with
     * nothing stale does zero UPDATEs.
     */
    private void applyStalledStatuses() {
        Map<Integer, LocalDate> lastActivity = db.getLastActivityPerSkill();
        LocalDate cutoff = mockToday.minusDays(STALLED_AFTER_DAYS);
        int changed = 0;
        for (Skill s : skills) {
            LocalDate last = lastActivity.get(s.getId());
            boolean stale = last != null && last.isBefore(cutoff);
            String next = stale ? Skill.STATUS_STALLED : Skill.STATUS_ACTIVE;
            if (!next.equals(s.getStatus())) {
                s.setStatus(next);
                db.updateSkill(s);
                changed++;
            }
        }
        if (changed > 0) {
            statusBarLabel.setText(changed + " skill" + (changed == 1 ? "" : "s")
                    + " re-evaluated for inactivity (" + STALLED_AFTER_DAYS + "-day rule).");
        }
    }

    /** Everything that has to be recomputed after points change: decay status,
     *  streaks, level, and the charts that read aggregates rather than the
     *  selected skill. Called from every log/undo path so none of them can
     *  drift out of sync. */
    private void afterLogChanged() {
        applyStalledStatuses();
        refreshStreakLabels();
        refreshLevelBadge();
        refreshVisualization();
    }

    // =================================================================
    //  LEVEL / BADGE MILESTONES  (zero-budget maximizer #1)
    // =================================================================

    /** Total points across every logged session -> a level and a title.
     *  Purely a read of one SUM aggregate, so it costs nothing to recompute. */
    private void refreshLevelBadge() {
        if (levelBadgeLabel == null) return;
        double perLevel = pointsPerLevel();
        double total = db.getTotalLoggedPoints();
        int level = (int) Math.floor(total / perLevel) + 1;
        String title = BADGE_TITLES[Math.min(level - 1, BADGE_TITLES.length - 1)];
        double intoLevel = total % perLevel;

        // Hyphen, not "·": the middle dot is the same non-ASCII punctuation the
        // em-dash sweep removed, and devcheck-punct.py would flag it as UI text.
        levelBadgeLabel.setText("Lv." + level + " - " + title);
        levelProgressLabel.setText(trimNumber(intoLevel) + " / " + trimNumber(perLevel)
                + " pts to Lv." + (level + 1));

        // Fanfare only on a real level-UP. lastKnownLevel starts at -1 so the
        // first paint of the session establishes a baseline silently - otherwise
        // simply opening the app at level 7 would sound like you just earned it.
        // Guarded as > so lowering the threshold in settings (which recomputes a
        // higher level) is the one case that does celebrate, while an undo that
        // drops you a level stays quiet.
        if (lastKnownLevel > 0 && level > lastKnownLevel) {
            SoundPlayer.play(SoundPlayer.Sfx.LEVEL_UP);
            statusBarLabel.setText("Level up! You reached Lv." + level + " · " + title + ".");
        }
        lastKnownLevel = level;
    }

    /** 12.0 -> "12", 12.5 -> "12.5". Points are doubles but almost always
     *  whole, and "12.0 pts" everywhere reads like a rounding bug. */
    private static String trimNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    // =================================================================
    //  NOTE SEARCH  (#tags via SQLite LIKE)
    // =================================================================

    /** Live search over note titles + markdown bodies. Typing "#java" lists
     *  every date that tag appears on; clearing the box restores the normal
     *  selected-date view. */
    private void setupNoteSearch() {
        if (noteSearchField == null) return;
        noteSearchField.textProperty().addListener((obs, old, text) -> {
            if (text == null || text.isBlank()) {
                refreshNotesForSelectedDate();
            } else {
                showSearchResults(text);
            }
        });
    }

    private void showSearchResults(String query) {
        notesContainer.getChildren().clear();
        List<CalendarNote> hits = db.searchNotes(query);
        selectedDateLabel.setText("Search: \"" + query.trim() + "\"");

        if (hits.isEmpty()) {
            Label empty = new Label("No notes match \"" + query.trim() + "\".");
            empty.getStyleClass().add("empty-notes-label");
            notesContainer.getChildren().add(empty);
            return;
        }

        Label summary = new Label(hits.size() + " note" + (hits.size() == 1 ? "" : "s") + " found");
        summary.getStyleClass().add("search-summary-label");
        notesContainer.getChildren().add(summary);

        for (CalendarNote note : hits) {
            // Each hit is clickable: jumping to the note's date is the whole
            // point of searching a tag like #thesis.
            Label dateChip = new Label(note.getNoteDate().format(NOTE_DATE_FMT));
            dateChip.getStyleClass().add("search-date-chip");
            dateChip.setCursor(Cursor.HAND);
            dateChip.setOnMouseClicked(e -> {
                selectedDate = note.getNoteDate();
                currentMonth = YearMonth.from(note.getNoteDate());
                noteSearchField.clear(); // triggers refreshNotesForSelectedDate via the listener
                buildCalendar();
            });
            notesContainer.getChildren().add(dateChip);
            notesContainer.getChildren().add(buildNoteCard(note, hits));
        }
    }

    // =================================================================
    //  PINNED / UNIVERSAL NOTES
    // =================================================================

    /** Renders the always-visible pinned notes above the day list. Hides the
     *  whole container when nothing is pinned, so an empty section doesn't
     *  eat vertical space in the sidebar. */
    private void refreshPinnedNotes() {
        if (pinnedNotesContainer == null) return;
        pinnedNotesContainer.getChildren().clear();
        List<CalendarNote> pinned = db.getPinnedNotes();
        boolean any = !pinned.isEmpty();
        pinnedNotesContainer.setVisible(any);
        pinnedNotesContainer.setManaged(any); // managed=false so it takes no layout space
        for (CalendarNote note : pinned) {
            VBox card = buildNoteCard(note, pinned);
            card.getStyleClass().add("note-card-pinned");
            pinnedNotesContainer.getChildren().add(card);
        }
    }

    /** Toggles a note between "lives on its date" and "always visible". */
    private void togglePinned(CalendarNote note) {
        note.setPinned(!note.isPinned());
        db.updateNote(note);
        refreshPinnedNotes();
        refreshNotesForSelectedDate();
        statusBarLabel.setText(note.isPinned()
                ? "Note pinned - now visible on every date."
                : "Note unpinned - back on " + note.getNoteDate().format(NOTE_DATE_FMT) + ".");
    }

    private void setupColorSwatches() {
        colorSwatchRow.getChildren().clear();
        for (String hex : PRESET_COLORS) {
            Region swatch = new Region();
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color: " + hex + ";");
            swatch.setOnMouseClicked(e -> handleSetStatusColor(hex));
            colorSwatchRow.getChildren().add(swatch);
        }
    }

    private void setupColorPicker() {
        customColorPicker.setValue(Color.web(PRESET_COLORS[0]));
        customColorPicker.setOnAction(e -> {
            Color c = customColorPicker.getValue();
            String hex = String.format("#%02X%02X%02X",
                    (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255),
                    (int) Math.round(c.getBlue() * 255));
            handleSetStatusColor(hex);
        });
    }

    private void setupStatusToggles() {
        ToggleGroup statusGroup = new ToggleGroup();
        activeStatusToggle.setToggleGroup(statusGroup);
        stalledStatusToggle.setToggleGroup(statusGroup);
        activeStatusToggle.setOnAction(e -> setSkillStatus(Skill.STATUS_ACTIVE));
        stalledStatusToggle.setOnAction(e -> setSkillStatus(Skill.STATUS_STALLED));
    }

    private void setupUndoRedoButtons() {
        undoButton.disableProperty().bind(commandManager.canUndoProperty().not());
        redoButton.disableProperty().bind(commandManager.canRedoProperty().not());
    }

    /**
     * ITEM 1.3: keeps the chart area's top edge glued to the bottom of whatever
     * controls are currently visible.
     *
     * <p>THE PROBLEM: dividerPositions is a RATIO. The filter list and the
     * zoom/spacing row are shown or hidden per chart type, so the top pane's
     * real content height swings between roughly 0 and ~260px - but a fixed
     * 0.38 ratio hands it 38% of the window regardless. That is the gap above
     * the canvas on Curve/Velocity, and a squeezed chart on Comb-Shaped.
     *
     * <p>THE FIX: convert the top pane's actual preferred height into the ratio
     * that would produce it, and re-apply that whenever the content changes
     * height or the window resizes. Listening to the pane's own
     * heightProperty covers all three triggers - a filter appearing, a chart
     * type switching, and the window being maximized - so nothing has to
     * remember to call it.
     *
     * <p>The user can still drag the divider afterwards; this only re-anchors
     * when the content itself changes size.
     */
    private void setupResponsiveDivider() {
        if (chartControlsPane == null || mainSplitPane == null) return;

        // prefHeight(-1) = "how tall does this pane's visible content actually
        // want to be", which is 0 when both rows are hidden and grows as they
        // appear. Region.USE_COMPUTED_SIZE would give the same number here.
        Runnable anchor = () -> {
            double total = mainSplitPane.getHeight();
            if (total <= 0) return; // not laid out yet
            double wanted = chartControlsPane.prefHeight(-1);

            // ITEMS 4 + 5. Only re-anchor when the CONTENT's preferred height
            // actually changed. Without this guard the feature ate its own
            // javadoc promise, in two separate ways:
            //
            //  (4) Dragging the divider is precisely what changes this pane's
            //      actual height, so the listener fired mid-drag and slammed the
            //      divider back to the computed ratio. The cursor "changed for a
            //      split second" because the divider was being yanked out from
            //      under the pointer - no amount of extra hitbox padding fixes
            //      that, because the target was moving, not small.
            //
            //  (5) On window resize the same thing happened: SplitPane's own
            //      resizableWithParent="false" already keeps this pane at its
            //      preferred pixel height, and this listener re-applied a ratio
            //      on top of it. Two mechanisms writing one divider is why it
            //      held on 1080p (where the ratio happened to agree) and broke
            //      at 2K (where it did not). Preferred height does not change on
            //      a pure resize, so the guard now leaves resizing entirely to
            //      resizableWithParent, which is the thing designed for it.
            //
            // Epsilon rather than != because prefHeight is a computed double and
            // sub-pixel jitter would defeat an exact comparison.
            if (Math.abs(wanted - lastAnchoredControlsHeight) < 0.5) return;
            lastAnchoredControlsHeight = wanted;

            // Never let the controls take more than half: a long filter list
            // must not squeeze the chart out of existence.
            double ratio = clamp(wanted / total, 0.0, 0.5);
            mainSplitPane.setDividerPositions(ratio);
        };

        chartControlsPane.heightProperty().addListener((obs, o, n) -> Platform.runLater(anchor));
        mainSplitPane.heightProperty().addListener((obs, o, n) -> Platform.runLater(anchor));
        Platform.runLater(anchor);
    }

    /**
     * PART 2: the collapsible left sidebar. Three TitledPanes whose expanded
     * state survives a restart, plus the compact notes mode.
     *
     * <p>WHY TITLEDPANE AND NOT A NESTED SPLITPANE: a SplitPane can only ever
     * redistribute the height it already has, and each of these three panels has
     * a real minimum (the calendar grid alone needs ~260px). On 1080p the sum of
     * the three minimums exceeds the column, so dividers bottom out and the user
     * still cannot see their notes. Collapsing hands the space back completely -
     * a collapsed TitledPane costs only its ~28px title bar.
     *
     * <p>Only the notes pane gets vgrow: it is the one with unbounded content, so
     * it should absorb whatever the other two give up. Without this the freed
     * space would pool as dead air at the bottom of the column.
     */
    private void setupCollapsibleSidebar() {
        for (TitledPane pane : new TitledPane[]{calendarPane, timerPane, notesPane}) {
            if (pane == null) continue;
            String key = KEY_SIDEBAR_PREFIX + pane.getId();
            // Default "true": a first run shows everything, which is the only
            // state that reveals the feature exists.
            pane.setExpanded(!"false".equals(db.getSetting(key, "true")));
            pane.expandedProperty().addListener((obs, was, is) -> {
                db.setSetting(key, String.valueOf(is));
                // A collapsed pane must not keep claiming grow priority, or the
                // remaining panes cannot expand into the space it just released.
                VBox.setVgrow(pane, is && pane == notesPane ? Priority.ALWAYS : Priority.NEVER);
            });
            VBox.setVgrow(pane, pane.isExpanded() && pane == notesPane
                    ? Priority.ALWAYS : Priority.NEVER);
        }

        // Compact notes mode: driven off the ScrollPane's real viewport height
        // rather than the TitledPane's, so it reacts to the pinned section
        // growing too, not just to the window resizing.
        if (notesContainer != null && notesContainer.getParent() != null) {
            notesPane.heightProperty().addListener((obs, o, n) -> applyCompactNotes());
            Platform.runLater(this::applyCompactNotes);
        }
    }

    /**
     * PART 2: when the notes pane is too short for a scrollable list to be
     * useful, hide the day list and show only the pinned notes. A 3-row
     * scrollbar is worse than an honest "there is more, give me room" state.
     *
     * <p>Deliberately does NOT touch the pinned container's own visibility -
     * refreshPinnedNotes() owns that (it hides when nothing is pinned), and two
     * writers on one property is how you get a section that flickers.
     */
    private void applyCompactNotes() {
        if (notesPane == null || notesContainer == null) return;
        Node scroll = notesContainer.getParent();      // the notes ScrollPane
        if (scroll == null) return;

        boolean compact = notesPane.isExpanded()
                && notesPane.getHeight() > 0
                && notesPane.getHeight() < NOTES_COMPACT_THRESHOLD;

        scroll.setVisible(!compact);
        scroll.setManaged(!compact);
        if (compactNotesHint != null) {
            compactNotesHint.setVisible(compact);
            compactNotesHint.setManaged(compact);
        }
    }

    /**
     * Item 2.4: one click sound for every ordinary button, wired once at the
     * scene root instead of a SoundPlayer.play() line inside ~30 handlers.
     * That is not just less code - it also means a button added to the FXML
     * later is audible automatically, with no chance of someone forgetting.
     *
     * <p>An EVENT FILTER on the root, not a handler: filters run on the way
     * DOWN, so the sound fires even for buttons whose own handler consumes the
     * event or opens a modal dialog (a modal blocks the bubbling phase, which
     * would swallow a root-level handler entirely).
     *
     * <p>Handlers that play a MORE specific sound - logging, timer finish,
     * level up - deliberately still play their own on top; those are outcomes,
     * whereas this is the mechanical feedback of the press itself. The two
     * exceptions are the timer's own buttons, which already play CLICK
     * explicitly and would otherwise double up.
     */
    private void setupClickSound() {
        rootPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            Node target = event.getTarget() instanceof Node n ? n : null;
            // Walk up: the actual target is usually the Button's inner label.
            while (target != null && !(target instanceof ButtonBase)) {
                target = target.getParent();
            }
            if (target == null || target.isDisabled()) return;
            if (target == pomodoroStartButton || target == pomodoroResetButton
                    || target == timerSettingsButton || target == tipsButton) {
                return; // these play CLICK themselves
            }
            SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        });
    }

    private void setupKeyboardShortcuts() {
        rootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN),
                        this::handleUndo);
                newScene.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.Z, KeyCombination.SHIFT_DOWN, KeyCombination.CONTROL_DOWN),
                        this::handleRedo);
            }
        });
    }

    private void setupBreadthLabel() {
        breadthCategoryLabel = db.getSetting(SETTING_BREADTH_LABEL, "General Knowledge");
        breadthLabel.setText(breadthCategoryLabel);
        breadthLabel.setOnMouseClicked(e -> startEditingBreadthLabel());
        // C.7: re-track the bar's position any time the user scrolls/pans -
        // not just when refreshVisualization() runs. Both axes: vvalue for
        // the existing vertical tracking, hvalue for B.4's new horizontal
        // tracking (previously missing entirely - see repositionBreadthLabel).
        structuralCanvasScroll.vvalueProperty().addListener((obs, o, n) -> repositionBreadthLabel());
        structuralCanvasScroll.hvalueProperty().addListener((obs, o, n) -> repositionBreadthLabel());
    }

    private void setupSpacingSliders() {
        hSpacingSlider.valueProperty().addListener((obs, o, n) -> refreshVisualization());
        vSpacingSlider.valueProperty().addListener((obs, o, n) -> refreshVisualization());
        setupSpacingSliderManualInput(hSpacingSlider, "H-Spacing");
        setupSpacingSliderManualInput(vSpacingSlider, "V-Spacing");
    }

    private void setupSpacingSliderManualInput(Slider slider, String label) {
        slider.setOnMouseClicked(event -> {
            boolean doubleLeftClick = event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2;
            boolean rightClick = event.getButton() == MouseButton.SECONDARY;
            if (!doubleLeftClick && !rightClick) return;

            TextInputDialog input = new TextInputDialog(String.format("%.2f", slider.getValue()));
            input.setTitle(label);
            input.setHeaderText(null);
            input.setContentText(String.format("Enter %s (%.1f-%.1f):", label, slider.getMin(), slider.getMax()));
            input.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
            input.getDialogPane().getStyleClass().add("glass-panel");

            input.showAndWait().ifPresent(text -> {
                try {
                    double value = Double.parseDouble(text.trim().replace(",", "."));
                    slider.setValue(Math.max(slider.getMin(), Math.min(slider.getMax(), value)));
                } catch (NumberFormatException ex) {
                    showAlert(Alert.AlertType.WARNING, "Invalid number", "Please enter a valid number, e.g. 1.5");
                }
            });
        });
    }

    /** Item C.8: default OFF (horizontal + word-wrap) for both Comb-Shaped
     *  and Skill-Tree; ON switches both to the older rotated style. */
    private void setupRotateLabelsToggle() {
        rotateLabelsCheckBox.setSelected(false);
        rotateLabelsCheckBox.selectedProperty().addListener((obs, o, n) -> refreshVisualization());
    }

    private void setupCanvasPanning() {
        structuralCanvas.setCursor(Cursor.OPEN_HAND);

        structuralCanvas.setOnMousePressed(event -> {
            structuralCanvas.setCursor(Cursor.CLOSED_HAND);
            panStartMouseX = event.getSceneX();
            panStartMouseY = event.getSceneY();
            panStartHValue = structuralCanvasScroll.getHvalue();
            panStartVValue = structuralCanvasScroll.getVvalue();
        });

        structuralCanvas.setOnMouseDragged(event -> {
            double deltaX = event.getSceneX() - panStartMouseX;
            double deltaY = event.getSceneY() - panStartMouseY;

            double viewportW = structuralCanvasScroll.getViewportBounds().getWidth();
            double viewportH = structuralCanvasScroll.getViewportBounds().getHeight();
            double scrollableW = structuralCanvas.getWidth() - viewportW;
            double scrollableH = structuralCanvas.getHeight() - viewportH;

            if (scrollableW > 0) {
                double newH = panStartHValue - deltaX / scrollableW;
                structuralCanvasScroll.setHvalue(clamp(newH, 0, 1));
                viewPinnedByUser = true;
            }
            if (scrollableH > 0) {
                double newV = panStartVValue - deltaY / scrollableH;
                structuralCanvasScroll.setVvalue(clamp(newV, 0, 1));
                viewPinnedByUser = true;
            }
        });

        structuralCanvas.setOnMouseReleased(event -> structuralCanvas.setCursor(Cursor.OPEN_HAND));

        structuralCanvas.setOnScroll(this::handleCanvasScrollZoom);
        // Same gesture on the ScrollPane itself: past the canvas edges (the
        // letterboxed area when zoomed out) the Canvas gets no events, and a
        // wheel turn there would otherwise just scroll the pane.
        structuralCanvasScroll.setOnScroll(this::handleCanvasScrollZoom);

        // Auto-center (item 1.4). A brand-new chart has no meaningful viewport
        // size yet, so centering at render time lands on stale bounds; this
        // re-centers as the layout settles and on every window resize, but only
        // while the user has not taken manual control of the view.
        //
        // Items 1.4/1.5: the canvas base size now comes FROM this viewport, so a
        // viewport change has to re-render or the canvas keeps its old size until
        // the next toggle click - which is exactly the "chart shrinks when the
        // control bars hide" bug. The 2px threshold stops the feedback loop:
        // re-rendering can add/remove a scrollbar, which nudges the viewport,
        // which would re-render forever. Sub-pixel churn is ignored, so it
        // settles after at most one correction pass.
        structuralCanvasScroll.viewportBoundsProperty().addListener((obs, o, n) -> {
            boolean resized = o == null
                    || Math.abs(o.getWidth() - n.getWidth()) > 2
                    || Math.abs(o.getHeight() - n.getHeight()) > 2;
            if (resized && structuralCanvasScroll.isVisible()) {
                Platform.runLater(this::refreshVisualization);
            }
            if (!viewPinnedByUser) centerCanvasScroll();
        });
    }

    /**
     * Scroll-to-zoom (item 2.1), anchored at the pointer so the thing under the
     * cursor stays under the cursor - the behaviour every map and design tool
     * has trained users to expect. Plain "zoom and re-center" makes deep trees
     * unnavigable because the node you were inspecting flies off-screen.
     *
     * <p>Ctrl is NOT required: the canvas is a dedicated viewport, and requiring
     * a modifier here would just make the wheel scroll the pane instead, which
     * is the less useful action.
     *
     * <p>Trackpads deliver many small deltas rather than one 40px notch, so the
     * step is scaled by the delta instead of being a fixed ZOOM_STEP.
     */
    private void handleCanvasScrollZoom(javafx.scene.input.ScrollEvent event) {
        double delta = event.getDeltaY();
        if (delta == 0) return;

        double before = zoomLevel;
        double factor = delta > 0 ? 1.10 : 1 / 1.10;
        zoomLevel = clamp(zoomLevel * factor, ZOOM_MIN, ZOOM_MAX);
        if (zoomLevel == before) {   // already pinned at a limit
            event.consume();
            return;
        }

        // Where the pointer sits in CONTENT coordinates, as a 0..1 fraction.
        // Canvas-relative coords are used because the canvas IS the content, so
        // this stays correct no matter how the pane is scrolled.
        javafx.geometry.Point2D inCanvas = structuralCanvas.sceneToLocal(event.getSceneX(), event.getSceneY());
        double fracX = clamp(inCanvas.getX() / Math.max(1, structuralCanvas.getWidth()), 0, 1);
        double fracY = clamp(inCanvas.getY() / Math.max(1, structuralCanvas.getHeight()), 0, 1);

        viewPinnedByUser = true;
        refreshVisualization();      // resizes the canvas to the new zoom

        // Scroll so that same content fraction lands back under the pointer.
        // ScrollPane's h/vvalue is the fraction of the SCROLLABLE range, not of
        // the content, hence the viewport correction - without it the anchor
        // drifts steadily toward the edges.
        Platform.runLater(() -> {
            double viewW = structuralCanvasScroll.getViewportBounds().getWidth();
            double viewH = structuralCanvasScroll.getViewportBounds().getHeight();
            double scrollableW = structuralCanvas.getWidth() - viewW;
            double scrollableH = structuralCanvas.getHeight() - viewH;
            if (scrollableW > 0) {
                double targetX = fracX * structuralCanvas.getWidth() - viewW / 2.0;
                structuralCanvasScroll.setHvalue(clamp(targetX / scrollableW, 0, 1));
            }
            if (scrollableH > 0) {
                double targetY = fracY * structuralCanvas.getHeight() - viewH / 2.0;
                structuralCanvasScroll.setVvalue(clamp(targetY / scrollableH, 0, 1));
            }
        });
        event.consume();
    }

    /**
     * Focus-timer clock size for a given pane width, interpolated between the
     * sidebar column's own clamp (COLUMN_MIN_WIDTH..COLUMN_MAX_WIDTH in the
     * FXML) and flattened outside it.
     *
     * <p>Package-private and static purely so the self-check below can call it
     * without standing up a JavaFX scene.
     */
    static double clockFontFor(double paneWidth) {
        double span = COLUMN_MAX_WIDTH - COLUMN_MIN_WIDTH;
        double t = (paneWidth - COLUMN_MIN_WIDTH) / span;
        double size = CLOCK_FONT_MIN + t * (CLOCK_FONT_MAX - CLOCK_FONT_MIN);
        return Math.max(CLOCK_FONT_MIN, Math.min(CLOCK_FONT_MAX, size));
    }

    private double clamp(double value, double min, double max) {        return Math.max(min, Math.min(max, value));
    }

    private void centerCanvasScroll() {
        Platform.runLater(() -> {
            structuralCanvasScroll.setHvalue(0.5);
            structuralCanvasScroll.setVvalue(0.5);
        });
    }

    /**
     * Item 1.4: every chart type comes up centered, and STAYS centered through
     * re-renders and window resizes, right up until the user pans or
     * scroll-zooms. Previously this only fired when the chart type changed, so
     * a re-render (log a session, resize, toggle a filter) left the view stuck
     * wherever the last layout pass had dumped it - usually the top-left corner.
     */
    private void autoCenterIfUnpinned() {
        if (!viewPinnedByUser) centerCanvasScroll();
    }

    /**
     * Item C.7 (vertical) + B.4 (horizontal): repositions breadthLabel so it
     * tracks the breadth bar's ACTUAL on-screen position on BOTH axes,
     * accounting for the current scroll offset AND the current canvas width.
     * <p>
     * VERTICAL (pre-existing, unchanged): the label sat at a fixed
     * StackPane-relative top margin that drifted whenever the canvas was
     * panned or auto-centered.
     * <p>
     * HORIZONTAL (B.4 fix - this half was previously missing entirely):
     * {@code StackPane.alignment="TOP_CENTER"} in the FXML only centers the
     * label relative to the StackPane/viewport, not relative to the
     * canvas's actual content. renderBreadthAndDepth always draws the
     * breadth bar symmetrically (fillRoundRect(30, ..., w-60, ...)), so its
     * true midpoint in CANVAS-space is always exactly {@code canvasWidth/2}
     * - but that only lands in the viewport's visual center when the canvas
     * is exactly as wide as the viewport AND hvalue is exactly 0.5. Since
     * H-Spacing resizes the canvas (see refreshVisualization) and panning
     * changes hvalue, the label needs an explicit horizontal nudge away
     * from TOP_CENTER's default position, computed the same way as the
     * vertical one: canvas-space coordinate minus the current scroll offset.
     */
    private void repositionBreadthLabel() {
        if (!breadthLabel.isVisible()) return;

        double viewportHeight = structuralCanvasScroll.getViewportBounds().getHeight();
        double canvasHeight = structuralCanvas.getHeight();
        double scrollableHeight = Math.max(0, canvasHeight - viewportHeight);
        double scrollOffsetY = structuralCanvasScroll.getVvalue() * scrollableHeight;

        double barTopYInViewport = VisualizationRenderer.BREADTH_BAR_TOP_Y - scrollOffsetY;
        double labelMarginTop = barTopYInViewport - 18; // sit ~18px above the bar's top edge
        StackPane.setMargin(breadthLabel, new Insets(Math.max(4, labelMarginTop), 0, 0, 0));

        double viewportWidth = structuralCanvasScroll.getViewportBounds().getWidth();
        double canvasWidth = structuralCanvas.getWidth();
        double scrollableWidth = Math.max(0, canvasWidth - viewportWidth);
        double scrollOffsetX = structuralCanvasScroll.getHvalue() * scrollableWidth;

        double barMidXInCanvas = canvasWidth / 2.0; // true midpoint of the breadth bar - see javadoc above
        double barMidXInViewport = barMidXInCanvas - scrollOffsetX;
        double viewportCenterX = viewportWidth / 2.0;
        // TOP_CENTER already puts the label at viewportCenterX; translateX nudges
        // it from there to the bar's true midpoint, whatever that currently is.
        breadthLabel.setTranslateX(barMidXInViewport - viewportCenterX);
    }

    private void loadSkillsFromDatabase() {
        skills.setAll(db.getAllSkills());
        // getAllSkills() returns a FLAT list with null parents, so Skill#getDepth()
        // is always 0 there. One recursive-CTE round trip gives the real depths,
        // which is what the indented ComboBox cells render from.
        skillDepths = db.getSkillDepths();
        filteredSkillIds.clear();
        for (Skill s : skills) filteredSkillIds.add(s.getId());
        rebuildSkillFilterPanel();

        if (!skills.isEmpty()) {
            skillComboBox.getSelectionModel().selectFirst();
        } else {
            selectSkill(null);
        }
    }

    // =================================================================
    //  SKILL SELECTION + REAL-TIME METRIC BINDING
    // =================================================================

    private void selectSkill(Skill skill) {
        this.selectedSkill = skill;
        editSkillButton.setDisable(skill == null);
        deleteSkillButton.setDisable(skill == null);

        if (skill == null) {
            unbindMetrics();
            refreshVisualization();
            return;
        }
        bindMetricsToSkill(skill);
        activeStatusToggle.setSelected(skill.isActive());
        stalledStatusToggle.setSelected(!skill.isActive());
        buildCalendar();
        refreshVisualization();
    }

    private void bindMetricsToSkill(Skill skill) {
        unbindMetrics();
        boundSkill = skill;

        currentPointsLabel.textProperty().bind(Bindings.createStringBinding(
                () -> String.format("%.1f", skill.getCurrentPoints()), skill.currentPointsProperty()));
        targetPointsLabel.textProperty().bind(Bindings.createStringBinding(
                () -> String.format("%.1f", skill.getTargetPoints()), skill.targetPointsProperty()));
        percentageHeaderLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(skill.progressProperty().get() * 100) + "%", skill.progressProperty()));

        mainProgressBar.progressProperty().bind(skill.progressProperty());
        statusDot.setFill(Color.web(skill.getColorHex()));

        pointsChangeListener = (obs, oldVal, newVal) -> refreshVisualization();
        skill.currentPointsProperty().addListener(pointsChangeListener);
    }

    private void unbindMetrics() {
        if (boundSkill != null && pointsChangeListener != null) {
            boundSkill.currentPointsProperty().removeListener(pointsChangeListener);
        }
        currentPointsLabel.textProperty().unbind();
        targetPointsLabel.textProperty().unbind();
        percentageHeaderLabel.textProperty().unbind();
        mainProgressBar.progressProperty().unbind();

        currentPointsLabel.setText("--");
        targetPointsLabel.setText("--");
        percentageHeaderLabel.setText("0%");
        mainProgressBar.setProgress(0);
        boundSkill = null;
    }

    /**
     * A.1 REFACTOR: was a free-text category ComboBox backed by
     * getDistinctCategories(); now a real parent-picker over the actual
     * tree. The first item is a sentinel "no parent" Skill (id stays at its
     * default Skill.NO_PARENT) representing "make this a new Category" -
     * everything else is a real node from db.getSkillTree(), indented by
     * depth so the hierarchy reads clearly in the dropdown.
     */
    private ComboBox<Skill> buildParentComboBox(int initialParentId) {
        Skill noParent = new Skill();
        noParent.setName("(No parent - new Category)");

        List<Skill> options = new ArrayList<>();
        options.add(noParent);
        options.addAll(Skill.flatten(db.getSkillTree()));

        ComboBox<Skill> box = new ComboBox<>(FXCollections.observableArrayList(options));
        box.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Skill s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                } else {
                    setText(s.getId() == Skill.NO_PARENT ? s.getName() : "  ".repeat(s.getDepth()) + s.getName());
                }
            }
        });
        box.setButtonCell(box.getCellFactory().call(null));

        Skill initial = options.stream()
                .filter(s -> s.getId() == initialParentId)
                .findFirst()
                .orElse(noParent);
        box.setValue(initial);
        return box;
    }

    @FXML
    private void handleAddSkill() {
        Dialog<Skill> dialog = new Dialog<>();
        dialog.setTitle("New Skill");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        ButtonType addButtonType = new ButtonType("Add Skill", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Python, Spanish, Guitar");
        ComboBox<Skill> parentBox = buildParentComboBox(Skill.NO_PARENT);
        Spinner<Double> targetSpinner = new Spinner<>(10.0, 100000.0, 100.0, 10.0);
        targetSpinner.setEditable(true);
        ComboBox<String> structureBox = new ComboBox<>(FXCollections.observableArrayList(
                Skill.STRUCTURE_I, Skill.STRUCTURE_COMB));
        structureBox.getSelectionModel().selectFirst();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Parent"), parentBox);
        grid.addRow(2, new Label("Target Points"), targetSpinner);
        grid.addRow(3, new Label("Structure"), structureBox);
        dialog.getDialogPane().setContent(grid);

        Node addButton = dialog.getDialogPane().lookupButton(addButtonType);
        addButton.disableProperty().bind(nameField.textProperty().isEmpty());

        dialog.setResultConverter(bt -> {
            if (bt != addButtonType) return null;
            Skill parent = parentBox.getValue();
            int parentId = parent == null ? Skill.NO_PARENT : parent.getId();
            Skill s = new Skill(nameField.getText().trim(), parentId, targetSpinner.getValue());
            s.setStructureType(structureBox.getValue());
            return s;
        });

        dialog.showAndWait().ifPresent(skill -> {
            db.insertSkill(skill);
            skills.add(skill);
            filteredSkillIds.add(skill.getId());
            rebuildSkillFilterPanel();
            skillComboBox.getSelectionModel().select(skill);
        });
    }

    @FXML
    private void handleEditSkill() {
        if (selectedSkill == null) {
            showAlert(Alert.AlertType.WARNING, "No skill selected", "Select a skill to edit first.");
            return;
        }
        SkillSnapshot before = SkillSnapshot.of(selectedSkill);

        Dialog<SkillSnapshot> dialog = new Dialog<>();
        dialog.setTitle("Edit Skill - " + selectedSkill.getName());
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        ButtonType saveType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField nameField = new TextField(selectedSkill.getName());
        ComboBox<Skill> parentBox = buildParentComboBox(selectedSkill.getParentId());
        Spinner<Double> targetSpinner = new Spinner<>(1.0, 100000.0, selectedSkill.getTargetPoints(), 10.0);
        targetSpinner.setEditable(true);
        Spinner<Double> currentSpinner = new Spinner<>(0.0, 100000.0, selectedSkill.getCurrentPoints(), 5.0);
        currentSpinner.setEditable(true);
        ComboBox<String> structureBox = new ComboBox<>(FXCollections.observableArrayList(
                Skill.STRUCTURE_I, Skill.STRUCTURE_COMB));
        structureBox.setValue(selectedSkill.getStructureType());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Parent"), parentBox);
        grid.addRow(2, new Label("Target Points"), targetSpinner);
        grid.addRow(3, new Label("Current Points"), currentSpinner);
        grid.addRow(4, new Label("Structure"), structureBox);
        Label hint = new Label("Editing Current Points here is a manual correction, not a\nlogged session - use \"Log Session\" instead to track real study time.");
        hint.getStyleClass().add("empty-notes-label");
        grid.add(hint, 0, 5, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.disableProperty().bind(nameField.textProperty().isEmpty());

        dialog.setResultConverter(bt -> {
            if (bt != saveType) return null;
            Skill parent = parentBox.getValue();
            int parentId = parent == null ? Skill.NO_PARENT : parent.getId();
            return new SkillSnapshot(nameField.getText().trim(), parentId,
                    structureBox.getValue(), selectedSkill.getStatus(), selectedSkill.getColorHex(),
                    targetSpinner.getValue(), currentSpinner.getValue());
        });

        dialog.showAndWait().ifPresent(after -> {
            commandManager.execute(new EditSkillCommand(db, selectedSkill, before, after));
            refreshAfterHistoryChange();
            statusBarLabel.setText("Updated " + selectedSkill.getName() + ".");
        });
    }

    @FXML
    private void handleDeleteSkill() {
        if (selectedSkill == null) {
            showAlert(Alert.AlertType.WARNING, "No skill selected", "Select a skill to delete first.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Skill");
        confirm.setHeaderText(null);
        confirm.setContentText("Delete \"" + selectedSkill.getName()
                + "\" and all its logged sessions?\nYou can undo this with Ctrl+Z.");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        confirm.getDialogPane().getStyleClass().add("glass-panel");

        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            String deletedName = selectedSkill.getName();
            commandManager.execute(new DeleteSkillCommand(db, skills, selectedSkill));

            if (!skills.isEmpty()) {
                skillComboBox.getSelectionModel().selectFirst();
            } else {
                selectSkill(null);
            }
            refreshAfterHistoryChange();
            statusBarLabel.setText("Deleted " + deletedName + ". Press Ctrl+Z to undo.");
        });
    }

    // =================================================================
    //  DRAG-AND-DROP SKILL REORDERING
    // =================================================================

    @FXML
    private void handleReorderSkills() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Reorder Skills");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<Skill> listView = new ListView<>(skills);
        listView.setPrefSize(320, 320);
        listView.setCellFactory(lv -> buildDraggableSkillCell());

        VBox content = new VBox(8,
                new Label("Drag items to reorder. This order is used everywhere\n(dropdown, charts, PDF export)."),
                listView);
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private ListCell<Skill> buildDraggableSkillCell() {
        ListCell<Skill> cell = new ListCell<>() {
            @Override
            protected void updateItem(Skill skill, boolean empty) {
                super.updateItem(skill, empty);
                setText(empty || skill == null ? null : skill.getName());
                setGraphic(null);
            }
        };
        cell.getStyleClass().add("drag-cell");

        cell.setOnDragDetected(event -> {
            if (cell.getItem() == null) return;
            Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(cell.getIndex()));
            db.setContent(content);
            event.consume();
        });

        cell.setOnDragOver(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        cell.setOnDragEntered(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                cell.setOpacity(0.4);
            }
        });
        cell.setOnDragExited(event -> cell.setOpacity(1.0));

        cell.setOnDragDropped(event -> {
            if (cell.getItem() == null) {
                event.setDropCompleted(false);
                event.consume();
                return;
            }
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasString()) {
                int draggedIndex = Integer.parseInt(dragboard.getString());
                int dropIndex = cell.getIndex();
                Skill dragged = skills.remove(draggedIndex);
                skills.add(dropIndex, dragged);
                db.updateSkillOrder(skills);
                refreshVisualization();
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        cell.setOnDragDone(DragEvent::consume);
        return cell;
    }

    // =================================================================
    //  REAL-TIME PROGRESS LOGGING
    // =================================================================

    @FXML
    private void handleLogProgress() {
        if (selectedSkill == null) {
            showAlert(Alert.AlertType.WARNING, "No skill selected", "Please select or create a skill first.");
            return;
        }
        int minutes = minutesSpinner.getValue();
        double points = pointsSpinner.getValue();
        if (minutes <= 0 && points <= 0) {
            showAlert(Alert.AlertType.INFORMATION, "Nothing to log",
                    "Enter minutes and/or points before logging a session.");
            return;
        }

        double pointsBefore = selectedSkill.getCurrentPoints();
        ProgressLog log = new ProgressLog(selectedSkill.getId(), selectedDate, minutes, points);
        commandManager.execute(new LogProgressCommand(db, selectedSkill, skills, log));
        SoundPlayer.play(SoundPlayer.Sfx.LOG_SESSION);
        celebrateIfJustCompleted(pointsBefore, selectedSkill);
        afterLogChanged();

        buildCalendar();
        statusBarLabel.setText("Logged " + minutes + " min / +" + points + " pts to "
                + selectedSkill.getName() + " on " + selectedDate.format(NOTE_DATE_FMT) + ".");
    }

    /**
     * B.7: fires the confetti burst exactly once, on the frame a skill's
     * completion CROSSES 100% - not on every subsequent log once it's
     * already there, which would get old fast. Compares before/after
     * rather than just checking "is it >= target now" for that reason.
     */
    private void celebrateIfJustCompleted(double pointsBefore, Skill skill) {
        if (skill.getTargetPoints() <= 0) return;
        boolean wasComplete = pointsBefore >= skill.getTargetPoints();
        boolean isComplete = skill.getCurrentPoints() >= skill.getTargetPoints();
        if (isComplete && !wasComplete) {
            playCompletionCelebration();
        }
    }

    /**
     * Small celebratory confetti burst, anchored over the main ProgressBar.
     * <p>
     * ITEM 2 REVISI: was a {@link Popup}, switched to a borderless
     * {@link Stage} (StageStyle.TRANSPARENT). The earlier Popup version set
     * Color.TRANSPARENT on its Scene AFTER popup.show() - Popup only creates
     * its backing Scene lazily, so there's a real window (even if brief)
     * where the popup is already visible on screen with whatever its
     * PLATFORM DEFAULT fill is, before that override line ever runs. A
     * Stage lets the Scene be constructed directly with
     * {@code new Scene(root, w, h, Color.TRANSPARENT)} - transparent from
     * the fill's very first value, with no window for a default to flash
     * through before an override lands. Still just as lightweight/borderless
     * as Popup was (StageStyle.TRANSPARENT strips all window chrome), and
     * still doesn't touch rootPane's BorderPane layout at all.
     */
    private void playCompletionCelebration() {
        double w = 360, h = 220;
        Pane particleLayer = new Pane();
        particleLayer.setPrefSize(w, h);
        particleLayer.setMouseTransparent(true);
        particleLayer.setPickOnBounds(false);
        particleLayer.setStyle("-fx-background-color: transparent;");

        Bounds anchorBounds = mainProgressBar.localToScreen(mainProgressBar.getBoundsInLocal());
        double anchorX = anchorBounds.getMinX() + anchorBounds.getWidth() / 2.0;
        double anchorY = anchorBounds.getMinY() + anchorBounds.getHeight() / 2.0;

        Stage overlay = new Stage(StageStyle.TRANSPARENT);
        overlay.initOwner(mainProgressBar.getScene().getWindow());
        overlay.setAlwaysOnTop(true);
        overlay.setResizable(false);
        overlay.setX(anchorX - w / 2.0);
        overlay.setY(anchorY - h / 2.0);
        // Color.TRANSPARENT passed directly to the Scene constructor - not
        // set afterward - is what actually guarantees no opaque frame ever
        // renders, not even briefly.
        overlay.setScene(new Scene(particleLayer, w, h, Color.TRANSPARENT));
        overlay.show();

        Color[] palette = {
                Color.web("#A8EB12"), Color.web("#008793"), Color.web("#414F6C"),
                Color.web("#E7ECF3"), Color.web("#FFD166"),
        };
        Random rnd = new Random();
        int count = 60;
        List<Circle> dots = new ArrayList<>();
        List<double[]> velocity = new ArrayList<>(); // {vx, vy} in px/sec

        for (int i = 0; i < count; i++) {
            Circle dot = new Circle(2 + rnd.nextDouble() * 2.5, palette[rnd.nextInt(palette.length)]);
            dot.setLayoutX(w / 2.0);
            dot.setLayoutY(h / 2.0);
            particleLayer.getChildren().add(dot);
            dots.add(dot);

            double angle = rnd.nextDouble() * Math.PI * 2;
            double speed = 60 + rnd.nextDouble() * 140;
            velocity.add(new double[]{Math.cos(angle) * speed, Math.sin(angle) * speed - 60});
        }

        double durationSeconds = 1.6;
        double gravity = 260; // px/sec^2
        long startNanos = System.nanoTime();

        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = (now - startNanos) / 1_000_000_000.0;
                if (t > durationSeconds) {
                    stop();
                    overlay.close();
                    return;
                }
                for (int i = 0; i < dots.size(); i++) {
                    double[] v = velocity.get(i);
                    Circle dot = dots.get(i);
                    dot.setLayoutX(w / 2.0 + v[0] * t);
                    dot.setLayoutY(h / 2.0 + v[1] * t + 0.5 * gravity * t * t);
                    dot.setOpacity(Math.max(0, 1 - t / durationSeconds));
                }
            }
        };
        timer.start();
    }

    /** Right-click on "Log Session" opens the Advanced Log dialog, same
     *  double-duty-button convention already used for the H/V-Spacing
     *  sliders (see setupSpacingSliderManualInput) - the ordinary click
     *  keeps doing exactly what it always did. */
    private void setupAdvancedLogButton() {
        logSessionButton.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                openAdvancedLogDialog();
            }
        });
    }

    /**
     * ITEM 5 ROOT CAUSE FIX: DatePicker with NO explicit converter falls
     * back to a locale-dependent default (e.g. dd/MM/yyyy vs MM/dd/yyyy
     * depending on the JVM's default Locale). If the user ever TYPES a date
     * rather than picking it from the popup, and that text is ambiguous
     * under the active locale's assumed format, DatePicker#getValue() can
     * silently end up holding a DIFFERENT date than what's visibly printed
     * in the field - which reads exactly like "random wrong dates" once
     * that value gets used for a database insert. A fixed, explicit format
     * makes what's typed and what's parsed always agree, independent of
     * whatever locale the app happens to run under.
     */
    private StringConverter<LocalDate> unambiguousDateConverter() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : fmt.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) return null;
                return LocalDate.parse(text.trim(), fmt);
            }
        };
    }

    /**
     * Pure-Java dialog (no new .fxml) offering everything the plain "Log
     * Session" button can't: an explicit date (defaulting to the calendar's
     * mocked "today"), OR a whole inclusive date range for backfilling many
     * days at once with the same Minutes/Points. Built as one Dialog with a
     * RadioButton mode switch rather than two separate dialogs, so
     * switching your mind mid-entry doesn't lose what you already typed.
     */
    private void openAdvancedLogDialog() {
        if (selectedSkill == null) {
            showAlert(Alert.AlertType.WARNING, "No skill selected", "Please select or create a skill first.");
            return;
        }

        Dialog<List<ProgressLog>> dialog = new Dialog<>();
        dialog.setTitle("Advanced Log - " + selectedSkill.getName());
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");
        // Sized for the LARGER "Batch (Date Range)" mode (two DatePickers +
        // the wrapped count label) so toggling between modes never needs a
        // resize - it previously first laid out at the smaller "Specific
        // Date" size, then got visually cut off (Save/Cancel unreachable)
        // once switching to Batch added rows without the window growing to
        // match. USE_PREF_SIZE forces the pane to actually honor this
        // instead of shrinking back down to fit its initial content pass.
        dialog.getDialogPane().setPrefWidth(440);
        dialog.getDialogPane().setPrefHeight(400);
        dialog.getDialogPane().setMinWidth(Region.USE_PREF_SIZE);
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        // a) Minutes / Points - seeded from the main UI's spinners, same ranges.
        Spinner<Integer> minutesField = new Spinner<>(0, 600, minutesSpinner.getValue(), 5);
        minutesField.setEditable(true);
        Spinner<Double> pointsField = new Spinner<>(0.0, 100.0, pointsSpinner.getValue(), 0.5);
        pointsField.setEditable(true);

        // b) Specific Date vs Batch (Date Range) mode switch.
        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton singleModeBtn = new RadioButton("Specific Date");
        singleModeBtn.setToggleGroup(modeGroup);
        singleModeBtn.setSelected(true);
        RadioButton rangeModeBtn = new RadioButton("Batch (Date Range)");
        rangeModeBtn.setToggleGroup(modeGroup);
        HBox modeRow = new HBox(16, singleModeBtn, rangeModeBtn);

        DatePicker singleDatePicker = new DatePicker(mockToday);
        singleDatePicker.setConverter(unambiguousDateConverter());
        HBox singleDateRow = new HBox(8, new Label("Date:"), singleDatePicker);
        singleDateRow.setAlignment(Pos.CENTER_LEFT);

        DatePicker rangeStartPicker = new DatePicker(mockToday.minusDays(6));
        rangeStartPicker.setConverter(unambiguousDateConverter());
        DatePicker rangeEndPicker = new DatePicker(mockToday);
        rangeEndPicker.setConverter(unambiguousDateConverter());
        HBox rangeRow = new HBox(8, new Label("From:"), rangeStartPicker, new Label("To:"), rangeEndPicker);
        rangeRow.setAlignment(Pos.CENTER_LEFT);
        rangeRow.setVisible(false);
        rangeRow.setManaged(false);

        Label rangeCountLabel = new Label();
        // -text-secondary, not the .empty-notes-label class (-text-muted) -
        // that class is meant for subtle "nothing here yet" placeholders,
        // too low-contrast for this dialog's active info text. wrapText +
        // an explicit max width fix the other half of the same visual bug:
        // this was also getting cut off ("same Minut...") rather than
        // wrapping, since a Label has no wrap behavior by default.
        rangeCountLabel.setStyle("-fx-text-fill: -text-secondary; -fx-font-family: 'Poppins'; -fx-font-style: italic;");
        rangeCountLabel.setWrapText(true);
        rangeCountLabel.setMaxWidth(380);
        rangeCountLabel.setVisible(false);
        rangeCountLabel.setManaged(false);

        Node saveButton = dialog.getDialogPane().lookupButton(saveType);

        Runnable revalidate = () -> {
            boolean isRange = modeGroup.getSelectedToggle() == rangeModeBtn;
            boolean dateValid;
            if (isRange) {
                LocalDate start = rangeStartPicker.getValue();
                LocalDate end = rangeEndPicker.getValue();
                dateValid = start != null && end != null && !end.isBefore(start);
                rangeCountLabel.setText(dateValid
                        ? "Will insert " + (ChronoUnit.DAYS.between(start, end) + 1)
                                + " session(s) - one per day, same Minutes/Points each."
                        : "Pick a valid range (end date on or after start date).");
            } else {
                dateValid = singleDatePicker.getValue() != null;
            }
            boolean nothingToLog = minutesField.getValue() <= 0 && pointsField.getValue() <= 0;
            saveButton.setDisable(!dateValid || nothingToLog);
        };

        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            boolean isRange = newT == rangeModeBtn;
            singleDateRow.setVisible(!isRange);
            singleDateRow.setManaged(!isRange);
            rangeRow.setVisible(isRange);
            rangeRow.setManaged(isRange);
            rangeCountLabel.setVisible(isRange);
            rangeCountLabel.setManaged(isRange);
            revalidate.run();
        });
        rangeStartPicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        rangeEndPicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        singleDatePicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        minutesField.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        pointsField.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        revalidate.run();

        VBox content = new VBox(12,
                new HBox(16,
                        new VBox(4, new Label("Minutes"), minutesField),
                        new VBox(4, new Label("Points"), pointsField)),
                new Separator(),
                modeRow,
                singleDateRow,
                rangeRow,
                rangeCountLabel);
        content.setPadding(new Insets(4, 8, 4, 8));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt != saveType) return null;
            int minutes = minutesField.getValue();
            double points = pointsField.getValue();
            boolean isRange = modeGroup.getSelectedToggle() == rangeModeBtn;

            List<ProgressLog> logs = new ArrayList<>();
            if (isRange) {
                for (LocalDate d = rangeStartPicker.getValue(); !d.isAfter(rangeEndPicker.getValue()); d = d.plusDays(1)) {
                    logs.add(new ProgressLog(selectedSkill.getId(), d, minutes, points));
                }
            } else {
                logs.add(new ProgressLog(selectedSkill.getId(), singleDatePicker.getValue(), minutes, points));
            }
            return logs;
        });

        dialog.showAndWait().ifPresent(logs -> {
            double pointsBefore = selectedSkill.getCurrentPoints();
            commandManager.execute(new BatchLogProgressCommand(db, selectedSkill, skills, logs));
            celebrateIfJustCompleted(pointsBefore, selectedSkill);
            afterLogChanged();
            buildCalendar();
            refreshVisualization();
            statusBarLabel.setText("Logged " + logs.size() + " session" + (logs.size() == 1 ? "" : "s")
                    + " to " + selectedSkill.getName() + ".");
        });
    }

    // =================================================================
    //  UNDO / REDO / CLEAR CACHE
    // =================================================================

    @FXML
    private void handleUndo() {
        if (commandManager.undo()) {
            refreshAfterHistoryChange();
            statusBarLabel.setText("Undone.");
        } else {
            statusBarLabel.setText("Nothing to undo.");
        }
    }

    @FXML
    private void handleRedo() {
        if (commandManager.redo()) {
            refreshAfterHistoryChange();
            statusBarLabel.setText("Redone.");
        } else {
            statusBarLabel.setText("Nothing to redo.");
        }
    }

    /**
     * "Refresh" (was "Clear Cache"): re-reads everything from SQLite and
     * re-renders, with no restart and no confirmation prompt - there's nothing
     * destructive left to confirm.
     * <p>
     * Undo/redo history is deliberately KEPT now. The old version wiped it,
     * which is why it needed a scary dialog; but the history holds Command
     * objects referencing Skill instances that loadSkillsFromDatabase()
     * replaces, so the real requirement is that undo still resolves correctly
     * afterwards - refreshAfterHistoryChange() already re-selects by identity
     * and falls back to the first skill when the old instance is gone.
     * <p>
     * NO LEAK: unbindMetrics() detaches the currentPoints listener before
     * rebinding, and every node built here (calendar cells, note cards) is
     * dropped by clearing its parent's children list, so the old ones become
     * unreachable rather than accumulating one set per refresh.
     */
    @FXML
    private void handleRefreshAndClearCache() {
        unbindMetrics();
        loadSkillsFromDatabase();
        applyStalledStatuses();
        rebuildSkillFilterPanel();
        buildCalendar();
        refreshPinnedNotes();
        refreshNotesForSelectedDate();
        refreshVisualization();
        refreshLevelBadge();
        statusBarLabel.setText("Refreshed from database at " + LocalTime.now().withNano(0) + ".");
    }

    private void refreshAfterHistoryChange() {
        if (selectedSkill != null && !skills.contains(selectedSkill)) {
            if (!skills.isEmpty()) {
                skillComboBox.getSelectionModel().selectFirst();
            } else {
                selectSkill(null);
            }
        } else if (selectedSkill != null) {
            bindMetricsToSkill(selectedSkill);
        }
        rebuildSkillFilterPanel();
        afterLogChanged();
        buildCalendar();
        refreshNotesForSelectedDate();
        refreshVisualization();
    }

    // =================================================================
    //  CALENDAR
    // =================================================================

    private void buildCalendar() {
        calendarGrid.getChildren().clear();
        monthYearLabel.setText(currentMonth.format(MONTH_FMT));

        for (int i = 0; i < WEEKDAYS.length; i++) {
            Label header = new Label(WEEKDAYS[i]);
            header.getStyleClass().add("calendar-weekday");
            calendarGrid.add(header, i, 0);
        }

        LocalDate firstOfMonth = currentMonth.atDay(1);
        int firstDayCol = firstOfMonth.getDayOfWeek().getValue() - 1;
        int daysInMonth = currentMonth.lengthOfMonth();

        Map<LocalDate, List<CalendarNote>> notesByDate = db.getNotesForMonth(currentMonth).stream()
                .collect(Collectors.groupingBy(CalendarNote::getNoteDate));
        Map<LocalDate, Double> pointsByDate = db.getPointsPerDay();

        int row = 1;
        int col = firstDayCol;
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = currentMonth.atDay(day);
            double pointsThatDay = pointsByDate.getOrDefault(date, 0.0);
            calendarGrid.add(buildDayCell(date, notesByDate.getOrDefault(date, List.of()), pointsThatDay), col, row);
            col++;
            if (col > 6) {
                col = 0;
                row++;
            }
        }
        refreshStreakLabels();
    }

    /**
     * GitHub-style heatmap tier for a day's total points. Returning a style
     * CLASS rather than an inline colour keeps every shade in styles.css, so
     * the palette can be retuned without touching Java.
     *
     * <p>Thresholds are the ones requested: 0 / 1-5 / 6-15 / 16+. Note these
     * are POINTS, not sessions - three quick 2-point sessions shade the same
     * as one 6-point one, which is the intended "how much did I actually do"
     * reading.
     */
    private static String heatmapTierClass(double points) {
        if (points <= 0) return null;
        if (points <= 5) return "calendar-day-heat-1";
        if (points <= 15) return "calendar-day-heat-2";
        return "calendar-day-heat-3";
    }

    /** Current + longest streak, straight from SQLite. Uses mockToday rather
     *  than LocalDate.now() so the labels agree with the highlighted day when
     *  "today" has been right-click-mocked. */
    private void refreshStreakLabels() {
        if (currentStreakLabel == null) return; // FXML not wired yet (older layout)
        int[] streaks = db.getStreaks(mockToday);
        currentStreakLabel.setText("Current Streak: " + streaks[0] + (streaks[0] == 1 ? " Day" : " Days"));
        longestStreakLabel.setText("Longest Streak: " + streaks[1] + (streaks[1] == 1 ? " Day" : " Days"));
        currentStreakLabel.pseudoClassStateChanged(STREAK_ALIVE, streaks[0] > 0);
    }

    private StackPane buildDayCell(LocalDate date, List<CalendarNote> notesForDay, double pointsThatDay) {
        StackPane cell = new StackPane();
        cell.getStyleClass().add("calendar-day");
        String heatClass = heatmapTierClass(pointsThatDay);
        if (heatClass != null) {
            cell.getStyleClass().add(heatClass);
            Tooltip.install(cell, new Tooltip(date.format(NOTE_DATE_FMT) + " - "
                    + trimNumber(pointsThatDay) + " points logged"));
        }
        if (date.equals(mockToday)) cell.getStyleClass().add("calendar-day-today");
        if (date.equals(selectedDate)) cell.getStyleClass().add("calendar-day-selected");

        VBox content = new VBox(2);
        content.setAlignment(Pos.TOP_CENTER);
        Label dayLabel = new Label(String.valueOf(date.getDayOfMonth()));
        dayLabel.getStyleClass().add("calendar-day-number");
        content.getChildren().add(dayLabel);

        if (!notesForDay.isEmpty()) {
            Circle dot = new Circle(3.5);
            dot.setFill(Color.web(notesForDay.get(0).getColorHex()));
            content.getChildren().add(dot);
        }

        cell.getChildren().add(content);
        cell.setOnMouseClicked(e -> {
            selectedDate = date;
            // Feature: right-click overrides "today" (mock current date) -
            // left-click only ever changes which date's notes are shown.
            if (e.getButton() == MouseButton.SECONDARY) {
                mockToday = date;
                statusBarLabel.setText("\"Today\" is now mocked to " + date.format(NOTE_DATE_FMT)
                        + " - new sessions and notes use this date until changed again.");
            }
            buildCalendar();
            refreshNotesForSelectedDate();
        });
        return cell;
    }

    @FXML
    private void handlePreviousMonth() {
        currentMonth = currentMonth.minusMonths(1);
        buildCalendar();
    }

    @FXML
    private void handleNextMonth() {
        currentMonth = currentMonth.plusMonths(1);
        buildCalendar();
    }

    /** B.4: undoes a right-click override, resetting the mocked "today"
     *  back to the real system clock. Deliberately leaves selectedDate
     *  alone - jumping the currently-viewed date around as a side effect
     *  of a "reset today" action would be a surprising, unrelated change. */
    @FXML
    private void handleSyncDate() {
        mockToday = LocalDate.now();
        buildCalendar();
        statusBarLabel.setText("\"Today\" synced back to the real system date ("
                + mockToday.format(NOTE_DATE_FMT) + ").");
    }

    // =================================================================
    //  STICKY NOTES  (Rich toolbar, clickable checkboxes, reordering)
    // =================================================================

    private void refreshNotesForSelectedDate() {
        notesContainer.getChildren().clear();
        selectedDateLabel.setText(selectedDate.format(NOTE_DATE_FMT));

        List<CalendarNote> notes = db.getNotesForDate(selectedDate);
        if (notes.isEmpty()) {
            Label empty = new Label("No notes for this date yet.");
            empty.getStyleClass().add("empty-notes-label");
            notesContainer.getChildren().add(empty);
            return;
        }
        for (CalendarNote note : notes) {
            notesContainer.getChildren().add(buildNoteCard(note, notes));
        }
    }

    /** @param notesInOrder the current ordered list for this date - used for
     *                      Up/Down bounds-checking and drag-drop index lookup. */
    private VBox buildNoteCard(CalendarNote note, List<CalendarNote> notesInOrder) {
        VBox card = new VBox(4);
        card.getStyleClass().add("sticky-note");
        card.setStyle("-fx-border-color: " + note.getColorHex() + ";");

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(note.getTitle() == null || note.getTitle().isBlank() ? "(untitled)" : note.getTitle());
        title.getStyleClass().add("sticky-note-title");
        title.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) handleEditNote(note);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // A.3: Up/Down reorder buttons.
        Button upBtn = new Button("\u25B2");
        upBtn.getStyleClass().add("icon-button");
        upBtn.setOnAction(e -> moveNote(note, notesInOrder, -1));
        Button downBtn = new Button("\u25BC");
        downBtn.getStyleClass().add("icon-button");
        downBtn.setOnAction(e -> moveNote(note, notesInOrder, 1));

        Button editBtn = new Button("\u270E");
        editBtn.getStyleClass().add("icon-button");
        editBtn.setOnAction(e -> handleEditNote(note));

        // Pin: promotes the note to the always-visible Universal section.
        Button pinBtn = new Button(note.isPinned() ? "\uD83D\uDCCC" : "\uD83D\uDCCD");
        pinBtn.getStyleClass().add("icon-button");
        if (note.isPinned()) pinBtn.getStyleClass().add("icon-button-active");
        pinBtn.setTooltip(new Tooltip(note.isPinned()
                ? "Unpin - return this note to its own date"
                : "Pin - keep this note visible on every date"));
        pinBtn.setOnAction(e -> togglePinned(note));

        Button deleteBtn = new Button("\u2715");
        deleteBtn.getStyleClass().add("icon-button");
        deleteBtn.setOnAction(e -> {
            db.deleteNote(note.getId());
            refreshPinnedNotes();
            refreshNotesForSelectedDate();
            buildCalendar();
        });
        header.getChildren().addAll(title, spacer, upBtn, downBtn, pinBtn, editBtn, deleteBtn);

        WebView preview = new WebView();
        preview.setPrefHeight(90);
        preview.setStyle("-fx-background-color: transparent;");
        preview.getEngine().loadContent(MarkdownUtil.toStyledDocument(note.getContentMarkdown()));
        setupCheckboxInteraction(preview, note); // A.2

        card.getChildren().addAll(header, preview);
        setupNoteCardDragAndDrop(card, note, notesInOrder); // A.3
        return card;
    }

    /**
     * Item A.2: makes rendered checkboxes genuinely clickable AND persistent.
     * Two things this has to do that aren't obvious:
     *   1. Flexmark's GFM task-list extension renders checkboxes with the
     *      HTML "disabled" attribute by default (confirmed against its
     *      documented output) - disabled inputs don't respond to clicks at
     *      all, so those get stripped via JS right after the page loads.
     *   2. A native click already toggles the checkbox's visual state for
     *      free (standard browser behavior) - this listener's only job is
     *      to relay WHICH checkbox (by document order, matching the
     *      Markdown source's line order) was clicked back to Java so the
     *      change can be saved, not to drive the visual toggle itself.
     * <p>
     * ON THE DEPRECATION WARNING: netscape.javascript.JSObject lives in the
     * jdk.jsobject module, which the OpenJDK team is deprecating for
     * removal FROM THE JDK - but per JDK-8338250, that module will keep
     * being delivered bundled WITH JavaFX itself (JavaFX is its only
     * remaining consumer), so this keeps compiling and running as long as
     * javafx-web stays a dependency here, which it already is. This is a
     * deliberate, informed choice to keep the officially-documented,
     * decade-stable WebEngine<->JS bridge pattern rather than switch to a
     * workaround: a JSObject-free alternative exists (have the click
     * handler set window.location.hash and read it back via
     * WebEngine#locationProperty() instead of exposing a bridge object),
     * but JDK-8157686 documents locationProperty() missing some JS-driven
     * navigation changes in WebView, so it trades a decade-proven mechanism
     * for one with its own known reliability caveat - not a clear win.
     */
    @SuppressWarnings("removal")
    private void setupCheckboxInteraction(WebView webView, CalendarNote note) {
        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("javaCheckboxBridge", new CheckboxBridge(db, note));

                webView.getEngine().executeScript(
                        "(function(){" +
                                "  var boxes = document.querySelectorAll('input[type=checkbox]');" +
                                "  for (var i = 0; i < boxes.length; i++) {" +
                                "    boxes[i].removeAttribute('disabled');" +
                                "    boxes[i].setAttribute('data-idx', i);" +
                                "    boxes[i].addEventListener('click', function() {" +
                                "      var idx = parseInt(this.getAttribute('data-idx'));" +
                                "      javaCheckboxBridge.toggle(idx);" +
                                "    });" +
                                "  }" +
                                "})();"
                );
            }
        });
    }

    /** Exposed to JavaScript via JSObject#setMember - must stay public for
     *  WebEngine's JS-to-Java reflection bridge to see its methods. */
    public static class CheckboxBridge {
        private final DatabaseHelper db;
        private final CalendarNote note;

        public CheckboxBridge(DatabaseHelper db, CalendarNote note) {
            this.db = db;
            this.note = note;
        }

        /** Called from JS with the 0-based index of the checkbox that was
         *  just clicked, in document order (which matches Markdown source
         *  order, since Flexmark renders task items in document order). */
        public void toggle(int index) {
            note.setContentMarkdown(toggleNthCheckbox(note.getContentMarkdown(), index));
            db.updateNote(note);
        }

        private static String toggleNthCheckbox(String markdown, int checkboxIndex) {
            String[] lines = markdown.split("\n", -1);
            int count = 0;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (trimmed.matches("^[-*+]\\s+\\[[ xX]\\].*")) {
                    if (count == checkboxIndex) {
                        lines[i] = trimmed.matches("^[-*+]\\s+\\[ \\].*")
                                ? lines[i].replaceFirst("\\[ \\]", "[x]")
                                : lines[i].replaceFirst("\\[[xX]\\]", "[ ]");
                        break;
                    }
                    count++;
                }
            }
            return String.join("\n", lines);
        }
    }

    /** Item A.3: drag-and-drop reordering directly on the note card, in
     *  addition to the Up/Down buttons. Same Dragboard/TransferMode pattern
     *  as the skill-reorder ListView, adapted for a VBox of cards instead
     *  of ListView cells. */
    private void setupNoteCardDragAndDrop(VBox card, CalendarNote note, List<CalendarNote> notesInOrder) {
        card.setOnDragDetected(event -> {
            Dragboard dragboard = card.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(note.getId()));
            dragboard.setContent(content);
            event.consume();
        });

        card.setOnDragOver(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        card.setOnDragEntered(event -> {
            if (event.getGestureSource() != card && event.getDragboard().hasString()) {
                card.setOpacity(0.5);
            }
        });
        card.setOnDragExited(event -> card.setOpacity(1.0));

        card.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasString()) {
                int draggedNoteId = Integer.parseInt(dragboard.getString());
                reorderNotes(draggedNoteId, note.getId(), notesInOrder);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        card.setOnDragDone(DragEvent::consume);
    }

    private void moveNote(CalendarNote note, List<CalendarNote> notesInOrder, int direction) {
        int index = notesInOrder.indexOf(note);
        int targetIndex = index + direction;
        if (index < 0 || targetIndex < 0 || targetIndex >= notesInOrder.size()) return;

        List<CalendarNote> mutable = new ArrayList<>(notesInOrder);
        CalendarNote temp = mutable.get(index);
        mutable.set(index, mutable.get(targetIndex));
        mutable.set(targetIndex, temp);
        db.updateNoteOrder(mutable);
        refreshNotesForSelectedDate();
    }

    private void reorderNotes(int draggedNoteId, int dropOnNoteId, List<CalendarNote> notesInOrder) {
        List<CalendarNote> mutable = new ArrayList<>(notesInOrder);
        int draggedIdx = -1, dropIdx = -1;
        for (int i = 0; i < mutable.size(); i++) {
            if (mutable.get(i).getId() == draggedNoteId) draggedIdx = i;
            if (mutable.get(i).getId() == dropOnNoteId) dropIdx = i;
        }
        if (draggedIdx < 0 || dropIdx < 0 || draggedIdx == dropIdx) return;

        CalendarNote dragged = mutable.remove(draggedIdx);
        mutable.add(dropIdx, dragged);
        db.updateNoteOrder(mutable);
        refreshNotesForSelectedDate();
    }

    /**
     * Item A.1: the Markdown formatting toolbar shared by Add/Edit Note.
     *
     * DESIGN NOTE - why not HTMLEditor or a custom TextFlow editor:
     *   - HTMLEditor stores/returns HTML, not Markdown - swapping to it
     *     would mean abandoning content_markdown entirely and rebuilding
     *     the whole render pipeline around HTML, plus its toolbar is fixed
     *     (font/size/color/alignment/etc.) and can't be trimmed down to just
     *     the six buttons asked for here.
     *   - A genuine WYSIWYG rich-text editor built on TextFlow (tracking
     *     carets, selections, and per-run styling by hand) is realistically
     *     a small word-processor's worth of work - far more than a PoC
     *     toolbar needs.
     *   - This TextArea-plus-toolbar approach is what most Markdown editors
     *     actually do (GitHub's comment box included): buttons wrap/prefix
     *     the RAW markdown text, and the existing WebView preview (already
     *     built, Flexmark-powered) shows the rendered result. Zero new
     *     dependencies, and Markdown stays the source of truth as required.
     */
    private HBox buildMarkdownToolbar(TextArea bodyArea) {
        HBox toolbar = new HBox(4);
        toolbar.getStyleClass().add("md-toolbar");

        toolbar.getChildren().addAll(
                buildToolbarButton("B", "Bold (Ctrl+B)", () -> wrapSelection(bodyArea, "**", "**")),
                buildToolbarButton("I", "Italic (Ctrl+I)", () -> wrapSelection(bodyArea, "_", "_")),
                buildToolbarButton("H1", "Headline 1", () -> prefixLine(bodyArea, "# ")),
                buildToolbarButton("H2", "Headline 2", () -> prefixLine(bodyArea, "## ")),
                buildToolbarButton("\u2022", "Bullet list", () -> prefixLine(bodyArea, "- ")),
                buildToolbarButton("1.", "Numbered list", () -> prefixLine(bodyArea, "1. ")),
                buildToolbarButton("\u2611", "Checklist item", () -> prefixLine(bodyArea, "- [ ] "))
        );

        // Ctrl+B / Ctrl+I as explicitly requested, local to this TextArea.
        bodyArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.B) {
                wrapSelection(bodyArea, "**", "**");
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.I) {
                wrapSelection(bodyArea, "_", "_");
                event.consume();
            }
        });

        return toolbar;
    }

    private Button buildToolbarButton(String label, String tooltip, Runnable action) {
        Button btn = new Button(label);
        btn.getStyleClass().addAll("icon-button", "md-toolbar-button");
        btn.setTooltip(new Tooltip(tooltip));
        btn.setOnAction(e -> action.run());
        return btn;
    }

    /** Wraps the current selection in Markdown syntax (Bold/Italic); if
     *  nothing is selected, inserts a placeholder already wrapped and
     *  selects it, so typing immediately replaces it. */
    private void wrapSelection(TextArea area, String prefix, String suffix) {
        String selectedText = area.getSelectedText();
        if (selectedText.isEmpty()) {
            String placeholder = "text";
            int insertPos = area.getCaretPosition();
            area.insertText(insertPos, prefix + placeholder + suffix);
            area.selectRange(insertPos + prefix.length(), insertPos + prefix.length() + placeholder.length());
        } else {
            int caretBefore = area.getSelection().getStart();
            area.replaceSelection(prefix + selectedText + suffix);
            area.selectRange(caretBefore + prefix.length(), caretBefore + prefix.length() + selectedText.length());
        }
        area.requestFocus();
    }

    /** Inserts a line-level Markdown prefix (headline/bullet/numbered/checklist)
     *  at the start of the current line. */
    private void prefixLine(TextArea area, String prefix) {
        String text = area.getText();
        int caret = area.getCaretPosition();
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        area.insertText(lineStart, prefix);
        area.requestFocus();
    }

    @FXML
    private void handleAddNote() {
        Dialog<CalendarNote> dialog = new Dialog<>();
        dialog.setTitle("New Sticky Note - " + selectedDate);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        ButtonType addType = new ButtonType("Add Note", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);

        TextField titleField = new TextField();
        titleField.setPromptText("Title");
        TextArea bodyArea = new TextArea();
        bodyArea.setPromptText("Markdown supported - use the toolbar below, or type it directly.");
        bodyArea.setPrefRowCount(6);
        HBox toolbar = buildMarkdownToolbar(bodyArea);
        ComboBox<Skill> linkedSkillBox = new ComboBox<>(skills);
        linkedSkillBox.setPromptText("(optional) link to a skill");

        VBox content = new VBox(6, titleField, bodyArea, toolbar, linkedSkillBox);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt != addType) return null;
            CalendarNote note = new CalendarNote(selectedDate, titleField.getText(), bodyArea.getText());
            Skill linked = linkedSkillBox.getValue();
            if (linked != null) {
                note.setSkillId(linked.getId());
                note.setColorHex(linked.getColorHex());
            }
            return note;
        });

        dialog.showAndWait().ifPresent(note -> {
            db.insertNote(note);
            refreshNotesForSelectedDate();
            buildCalendar();
        });
    }

    private void handleEditNote(CalendarNote note) {
        Dialog<CalendarNote> dialog = new Dialog<>();
        dialog.setTitle("Edit Sticky Note");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        ButtonType saveType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField titleField = new TextField(note.getTitle());
        TextArea bodyArea = new TextArea(note.getContentMarkdown());
        bodyArea.setPrefRowCount(6);
        HBox toolbar = buildMarkdownToolbar(bodyArea);
        ComboBox<Skill> linkedSkillBox = new ComboBox<>(skills);
        linkedSkillBox.setPromptText("(optional) link to a skill");
        if (note.getSkillId() != null) {
            skills.stream().filter(s -> s.getId() == note.getSkillId()).findFirst()
                    .ifPresent(linkedSkillBox::setValue);
        }

        VBox content = new VBox(6, titleField, bodyArea, toolbar, linkedSkillBox);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt != saveType) return null;
            note.setTitle(titleField.getText());
            note.setContentMarkdown(bodyArea.getText());
            Skill linked = linkedSkillBox.getValue();
            note.setSkillId(linked == null ? null : linked.getId());
            if (linked != null) note.setColorHex(linked.getColorHex());
            return note;
        });

        dialog.showAndWait().ifPresent(updated -> {
            db.updateNote(updated);
            refreshNotesForSelectedDate();
            buildCalendar();
        });
    }

    // =================================================================
    //  STATUS COLOR CUSTOMIZATION
    // =================================================================

    private void handleSetStatusColor(String hex) {
        if (selectedSkill == null) return;
        selectedSkill.setColorHex(hex);
        db.updateSkill(selectedSkill);
        statusDot.setFill(Color.web(hex));
        refreshVisualization();
    }

    private void setSkillStatus(String status) {
        if (selectedSkill == null) return;
        selectedSkill.setStatus(status);
        db.updateSkill(selectedSkill);
        refreshVisualization();
    }

    // =================================================================
    //  COLLAPSIBLE, CATEGORY-GROUPED SKILL FILTER
    // =================================================================

    /**
     * A.1 REFACTOR: was grouped by the old flat `category` string; now reads
     * the real hierarchy via db.getSkillTree(). One TitledPane per root
     * Category (same visual shape as before), but the checkboxes inside now
     * cover EVERY descendant at any depth (indented per level), not just
     * that category's direct skills - so individual subskills are
     * filterable too, not just whole categories.
     */
    private void rebuildSkillFilterPanel() {
        filterCategoriesBox.getChildren().clear();

        for (Skill category : db.getSkillTree()) {
            // ITEM 4 REVISI: was a FlowPane with leading spaces for "indent" -
            // spaces barely render as visible indentation, AND a wrapping
            // FlowPane can put a child on a different visual row than its
            // parent, breaking any indentation cue entirely. A VBox (one
            // checkbox per line) is what makes a real left-margin indent
            // actually mean something.
            VBox checkColumn = new VBox(4);
            checkColumn.getStyleClass().add("filter-flow");

            for (Skill node : Skill.flatten(List.of(category))) {
                int depth = node.getDepth(); // 1 = Skill, 2 = Subskill 1, 3 = Subskill 2, ...
                String label = depth <= 1 ? node.getName() : "\u21B3 " + node.getName();

                CheckBox cb = new CheckBox(label);
                cb.getStyleClass().add("filter-checkbox");
                cb.setSelected(filteredSkillIds.contains(node.getId()));
                VBox.setMargin(cb, new Insets(0, 0, 0, depth * 18));
                cb.selectedProperty().addListener((obs, was, isNow) -> {
                    if (isNow) filteredSkillIds.add(node.getId()); else filteredSkillIds.remove(node.getId());
                    refreshVisualization();
                });
                checkColumn.getChildren().add(cb);
            }

            TitledPane pane = new TitledPane(category.getName(), checkColumn);
            pane.getStyleClass().add("filter-category-pane");
            pane.setExpanded(categoryExpandedState.getOrDefault(category.getName(), true));
            pane.expandedProperty().addListener((obs, was, isNow) -> categoryExpandedState.put(category.getName(), isNow));
            filterCategoriesBox.getChildren().add(pane);
        }
    }

    /** Flat, checkbox-filtered list from the flat `skills` list - kept for
     *  any other call site that still wants a simple flat filtered list. */
    private List<Skill> getFilteredSkillList() {
        return skills.stream().filter(s -> filteredSkillIds.contains(s.getId())).toList();
    }

    /**
     * B.3 support: prunes a freshly-fetched (and therefore disposable)
     * db.getSkillTree() forest down to only the nodes that are checked in
     * the filter panel, OR have at least one checked descendant - so a
     * partially-checked branch still shows its connecting path instead of
     * vanishing outright. Mutates the children lists of the tree instance
     * passed in (safe: each call site fetches its own fresh copy).
     */
    private List<Skill> pruneTreeToFiltered(List<Skill> roots) {
        List<Skill> kept = new ArrayList<>();
        for (Skill root : roots) {
            if (pruneNode(root)) kept.add(root);
        }
        return kept;
    }

    private boolean pruneNode(Skill node) {
        List<Skill> keptChildren = new ArrayList<>();
        for (Skill child : node.getChildren()) {
            if (pruneNode(child)) keptChildren.add(child);
        }
        node.getChildren().setAll(keptChildren);
        return !keptChildren.isEmpty() || filteredSkillIds.contains(node.getId());
    }

    // =================================================================
    //  EDITABLE COMB-SHAPED BREADTH LABEL
    // =================================================================

    private void startEditingBreadthLabel() {
        TextField editField = new TextField(breadthCategoryLabel);
        editField.getStyleClass().add("breadth-label-edit");
        editField.setMaxWidth(220);
        StackPane.setAlignment(editField, Pos.TOP_CENTER);
        StackPane.setMargin(editField, new Insets(20, 0, 0, 0));

        Runnable commit = () -> {
            String newText = editField.getText().isBlank() ? breadthCategoryLabel : editField.getText().trim();
            breadthCategoryLabel = newText;
            breadthLabel.setText(newText);
            db.setSetting(SETTING_BREADTH_LABEL, newText);
            visualizationStack.getChildren().remove(editField);
            breadthLabel.setVisible(true);
            breadthLabel.setManaged(true);
            repositionBreadthLabel();
        };

        editField.setOnAction(e -> commit.run());
        editField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) commit.run();
        });

        breadthLabel.setVisible(false);
        breadthLabel.setManaged(false);
        visualizationStack.getChildren().add(editField);
        editField.requestFocus();
        editField.selectAll();
    }

    // =================================================================
    //  ZOOM / SPACING
    // =================================================================

    @FXML
    private void handleZoomIn() {
        zoomLevel = Math.min(ZOOM_MAX, zoomLevel + ZOOM_STEP);
        refreshVisualization();
    }

    @FXML
    private void handleZoomOut() {
        zoomLevel = Math.max(ZOOM_MIN, zoomLevel - ZOOM_STEP);
        refreshVisualization();
    }

    @FXML
    private void handleZoomReset() {
        zoomLevel = 1.0;
        hSpacingSlider.setValue(1.0);
        vSpacingSlider.setValue(1.0);
        viewPinnedByUser = false;
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);
        refreshVisualization();
        centerCanvasScroll();
    }

    // =================================================================
    //  VISUALIZATION TOGGLE
    // =================================================================

    @FXML
    private void handleToggleVisualization(ActionEvent event) {
        refreshVisualization();
    }

    private void refreshVisualization() {
        // Derive base size from the viewport so the canvas fills the available
        // space on 1080p (item 1.4) and reflows when chart-type control bars
        // hide (item 1.5). Falls back to the compile-time constants during
        // the first render pass before the ScrollPane is laid out.
        // The -2 keeps the canvas a hair inside the viewport at 100%/1.0x, so
        // sub-pixel rounding can't summon a scrollbar when nothing overflows.
        double vpW = structuralCanvasScroll.getViewportBounds().getWidth();
        double vpH = structuralCanvasScroll.getViewportBounds().getHeight();
        double baseW = vpW > 0 ? Math.max(BASE_CANVAS_WIDTH, vpW - 2) : BASE_CANVAS_WIDTH;
        double baseH = vpH > 0 ? Math.max(BASE_CANVAS_HEIGHT, vpH - 2) : BASE_CANVAS_HEIGHT;
        structuralCanvas.setWidth(baseW * zoomLevel * hSpacingSlider.getValue());
        structuralCanvas.setHeight(baseH * zoomLevel * vSpacingSlider.getValue());
        zoomLevelLabel.setText(Math.round(zoomLevel * 100) + "%");

        for (Node n : List.of(curveChart, structuralCanvasScroll)) {
            n.setVisible(false);
            n.setManaged(false);
        }
        showBreadthLabel(false);
        showFilter(false);
        showZoomControls(false);

        Toggle active = vizToggleGroup.getSelectedToggle();
        if (active == null) {
            curveToggle.setSelected(true);
            active = curveToggle;
        }
        boolean chartTypeChanged = active != lastActiveToggle;
        lastActiveToggle = active;
        // A different chart is a fresh view: forget any manual pan/zoom anchor
        // so the new one comes up centered.
        if (chartTypeChanged) viewPinnedByUser = false;
        boolean rotate = rotateLabelsCheckBox.isSelected();
        updateDepthLevelComboBox(active, chartTypeChanged);

        GraphicsContext gc = structuralCanvas.getGraphicsContext2D();
        double w = structuralCanvas.getWidth();
        double h = structuralCanvas.getHeight();

        if (active == curveToggle) {
            show(curveChart);
            renderCurveView(getCurveTargetSkill());

        } else if (active == iShapedToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            VisualizationRenderer.renderIShaped(gc, w, h, selectedSkill);
            autoCenterIfUnpinned();

        } else if (active == combShapedToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            showBreadthLabel(true);
            showFilter(true);
            // B.2: breadth bar always shows every node AT the selected depth
            // (same "show everyone" spirit as before); the filter checkboxes
            // now decide who additionally gets a deep bar, same as before.
            List<Skill> nodesAtDepth = Skill.collectAtDepth(db.getSkillTree(), selectedDepthLevel());
            Set<Skill> deepSet = nodesAtDepth.stream()
                    .filter(s -> filteredSkillIds.contains(s.getId()))
                    .collect(Collectors.toSet());
            VisualizationRenderer.renderBreadthAndDepth(gc, w, h, nodesAtDepth, deepSet, rotate);
            autoCenterIfUnpinned();
            repositionBreadthLabel();

        } else if (active == skillTreeToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            showFilter(true);
            // B.3: real parent-child tree, pruned to the filter panel's
            // checked nodes (or ancestors of a checked descendant).
            VisualizationRenderer.renderSkillTree(gc, w, h, pruneTreeToFiltered(db.getSkillTree()), rotate);
            autoCenterIfUnpinned();

        } else if (active == radarToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            showFilter(true);
            // B.2: only nodes at the selected depth AND checked - same
            // "checked-only" spirit as before, depth added as a filter.
            List<Skill> radarSkills = Skill.collectAtDepth(db.getSkillTree(), selectedDepthLevel()).stream()
                    .filter(s -> filteredSkillIds.contains(s.getId()))
                    .toList();
            VisualizationRenderer.renderRadar(gc, w, h, radarSkills);
            autoCenterIfUnpinned();

        } else if (active == velocityToggle) {
            show(curveChart);
            renderVelocityView();

        } else if (active == timePieToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            VisualizationRenderer.renderTimePie(gc, w, h, db.getMinutesPerRootCategory());
            autoCenterIfUnpinned();
        }
    }

    /**
     * Velocity / momentum: total points earned per DAY over the last N days,
     * as opposed to the Curve view's cumulative "session #" progression. This
     * is the one that answers "am I slowing down?".
     *
     * <p>Reuses the existing curveChart node rather than adding a second
     * LineChart to the FXML - the axes are relabelled here. X is plotted as a
     * day OFFSET (-29..0) with a tick formatter turning it back into a date,
     * because the chart is declared with a NumberAxis and swapping to a
     * CategoryAxis would mean rebuilding the node.
     *
     * <p>Days with no sessions are plotted explicitly as 0 rather than skipped,
     * so a gap in the line reads as a gap in the work.
     */
    private void renderVelocityView() {
        String choice = depthLevelComboBox.getValue();
        Range range = velocityRangeFor(choice, mockToday, customVelocityRange);
        if (range == null) {
            // "Custom" selected but no dates chosen yet (the dialog is about to
            // open, or opened and was cancelled). Nothing sensible to plot.
            return;
        }
        LocalDate start = range.start();
        LocalDate end = range.end();
        long windowDays = range.days();
        // Bounded query: only the days actually plotted come back, so a 7-day
        // chart no longer reads every log row in the database.
        Map<LocalDate, Double> perDay = db.getPointsPerDay(start, end);

        curveChart.setTitle("Velocity - points per day (" + velocityRangeLabel(choice, range) + ")");
        NumberAxis xAxis = (NumberAxis) curveChart.getXAxis();
        NumberAxis yAxis = (NumberAxis) curveChart.getYAxis();
        xAxis.setLabel("Date");
        yAxis.setLabel("Points that day");
        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(-(windowDays - 1));
        xAxis.setUpperBound(0);
        xAxis.setTickUnit(Math.max(1, windowDays / 7.0));
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number offset) {
                return end.plusDays(offset.longValue()).format(VELOCITY_TICK_FMT);
            }
            @Override public Number fromString(String s) { return 0; }
        });
        // A 365-day span draws 365 symbol nodes on top of the line, which is
        // both unreadable and the single biggest cost in rendering a long
        // range. Past ~90 points the line alone carries the shape.
        curveChart.setCreateSymbols(windowDays <= 90);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Points / day");
        double peak = 0;
        double total = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            double pts = perDay.getOrDefault(d, 0.0);
            peak = Math.max(peak, pts);
            total += pts;
            series.getData().add(new XYChart.Data<>(ChronoUnit.DAYS.between(end, d), pts));
        }

        curveChart.getData().setAll(series);
        statusBarLabel.setText(String.format("Velocity: %s pts over %d days (avg %.1f/day, peak %s).",
                trimNumber(total), windowDays, total / windowDays, trimNumber(peak)));
    }

    /** Chart-title fragment: presets read as "last 6 months", a custom range
     *  spells out its endpoints since there is no preset name to fall back on. */
    private String velocityRangeLabel(String choice, Range range) {
        if (VELOCITY_CUSTOM.equals(choice)) {
            return range.start().format(VELOCITY_TICK_FMT) + " to " + range.end().format(VELOCITY_TICK_FMT);
        }
        return "last " + (choice == null ? "30 days" : choice);
    }

    /** 7 or 30 days, taken from the depth combo when it's showing the velocity
     *  window options. Defaults to 30 - a 7-day window on a new database is
     *  mostly empty and reads as "no momentum" rather than "no data yet". */
    private int velocityWindowDays() {
        String choice = depthLevelComboBox.getValue();
        return choice != null && choice.startsWith("7") ? 7 : 30;
    }

    // =================================================================
    //  VELOCITY TIME RANGE
    // =================================================================

    /** The velocity combo's options, in display order. "Custom" is a sentinel:
     *  it resolves to no range of its own and opens the date-picker dialog
     *  instead - see {@link #velocityRangeFor}. */
    private static final String VELOCITY_CUSTOM = "Custom";
    private static final List<String> VELOCITY_OPTIONS = List.of(
            "7 days", "30 days", "2 months", "4 months", "6 months",
            "8 months", "10 months", "12 months", VELOCITY_CUSTOM);

    /** An inclusive date range for the velocity chart. */
    record Range(LocalDate start, LocalDate end) {
        long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }
    }

    /** Set only while a "Custom" range is in effect; null for every preset. */
    private Range customVelocityRange;

    /** The combo value to fall back to when the custom dialog is cancelled.
     *  Tracked because reverting has to restore a real previous choice, and by
     *  the time the dialog closes the combo already reads "Custom". */
    private String lastVelocityChoice = "30 days";

    /**
     * Resolves a combo label to the date range the chart should plot.
     *
     * <p>Months use {@link LocalDate#minusMonths} rather than a day count:
     * 12 months back from 31 Aug is 31 Aug, whereas 365 days back is a day or
     * two off depending on leap years and month lengths. Calendar arithmetic is
     * what the user means by "6 months".
     *
     * <p>Returns null for "Custom" when no custom range has been chosen yet -
     * the caller opens the dialog rather than plotting anything.
     *
     * @param label the combo's current value
     * @param today the reference day; the calendar's right-click "mock today"
     *              flows through here, so the chart agrees with the highlight
     * @param customRange the current custom range, or null if none is in effect
     */
    static Range velocityRangeFor(String label, LocalDate today, Range customRange) {
        if (label == null) return new Range(today.minusDays(29), today);
        if (VELOCITY_CUSTOM.equals(label)) return customRange;

        if (label.endsWith("days")) {
            int days = Integer.parseInt(label.substring(0, label.indexOf(' ')));
            // Inclusive of today, so "7 days" plots 7 points, not 8.
            return new Range(today.minusDays(days - 1L), today);
        }
        if (label.endsWith("months")) {
            int months = Integer.parseInt(label.substring(0, label.indexOf(' ')));
            // plusDays(1) so the span stays inclusive on both ends: 1 month back
            // from the 3rd is the 3rd, and plotting both would double-count it.
            return new Range(today.minusMonths(months).plusDays(1), today);
        }
        return new Range(today.minusDays(29), today);
    }

    /**
     * Two DatePickers and an OK/Cancel, for the "Custom" option.
     *
     * <p>Returns empty when the user cancels, closes the window, or picks an
     * unusable pair - the caller reverts the combo in every one of those cases.
     * Validation lives on the OK button's disabled state rather than in an
     * error popup: an un-clickable button with the reason next to it is less
     * annoying than a dialog that lets you commit a mistake and then scolds you.
     */
    private Optional<Range> askForCustomRange(LocalDate today) {
        Range current = customVelocityRange;
        DatePicker startPicker = new DatePicker(current != null ? current.start() : today.minusMonths(1));
        DatePicker endPicker = new DatePicker(current != null ? current.end() : today);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Custom Velocity Range");
        dialog.setHeaderText("Pick the first and last day to plot. Both are included.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Label hint = new Label();
        hint.setWrapText(true);
        hint.getStyleClass().add("metric-label");
        hint.setMaxWidth(320);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Start date"), startPicker);
        form.addRow(1, new Label("End date"), endPicker);
        form.add(hint, 0, 2, 2, 1);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets()
                .add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        Node okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            LocalDate s = startPicker.getValue();
            LocalDate e = endPicker.getValue();
            // A null is reachable: the editor accepts free text, and an
            // unparseable entry leaves the value null rather than throwing.
            if (s == null || e == null) {
                hint.setText("Pick both dates.");
                okButton.setDisable(true);
            } else if (e.isBefore(s)) {
                hint.setText("The end date is before the start date.");
                okButton.setDisable(true);
            } else {
                long days = ChronoUnit.DAYS.between(s, e) + 1;
                hint.setText(days + (days == 1 ? " day" : " days") + " will be plotted.");
                okButton.setDisable(false);
            }
        };
        startPicker.valueProperty().addListener((obs, o, n) -> validate.run());
        endPicker.valueProperty().addListener((obs, o, n) -> validate.run());
        validate.run();

        return dialog.showAndWait()
                .filter(bt -> bt == ButtonType.OK)
                .map(bt -> new Range(startPicker.getValue(), endPicker.getValue()));
    }

    /**
     * Handles a velocity-combo selection. Presets just re-render; "Custom"
     * opens the dialog and reverts the combo if it is dismissed.
     *
     * @return true if the caller should re-render, false if the selection was
     *         reverted (which re-enters this method and renders on its own)
     */
    private boolean onVelocityChoice(String choice) {
        if (!VELOCITY_CUSTOM.equals(choice)) {
            lastVelocityChoice = choice;
            customVelocityRange = null;
            return true;
        }

        Optional<Range> picked = askForCustomRange(mockToday);
        if (picked.isPresent()) {
            customVelocityRange = picked.get();
            lastVelocityChoice = VELOCITY_CUSTOM;
            return true;
        }

        // Cancelled: put the combo back. Guarded so the write does not
        // re-trigger this handler, then rendered explicitly - the reverted
        // value is the one already on screen, so the listener would not fire
        // anyway and the chart would be left showing nothing.
        updatingDepthCombo = true;
        try {
            depthLevelComboBox.setValue(lastVelocityChoice);
        } finally {
            updatingDepthCombo = false;
        }
        return false;
    }

    private static final DateTimeFormatter VELOCITY_TICK_FMT = DateTimeFormatter.ofPattern("d MMM");

    private void show(Node n) {
        n.setVisible(true);
        n.setManaged(true);
    }

    private void showFilter(boolean visible) {
        filterScrollPane.setVisible(visible);
        filterScrollPane.setManaged(visible);
    }

    private void showBreadthLabel(boolean visible) {
        breadthLabel.setVisible(visible);
        breadthLabel.setManaged(visible);
    }

    private void showZoomControls(boolean visible) {
        zoomControlsRow.setVisible(visible);
        zoomControlsRow.setManaged(visible);
    }

    // =================================================================
    //  DEPTH-LEVEL COMBO (B.2): Comb-Shaped/Radar depth picker,
    //  Curve's specific-descendant picker. Guarded against re-entrant
    //  refreshVisualization() calls while populating programmatically.
    // =================================================================

    private boolean updatingDepthCombo = false;

    private void setupDepthLevelComboBox() {
        depthLevelComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingDepthCombo || newVal == null || newVal.equals(oldVal)) return;
            // Custom is a sentinel, not a range: hand the selection to the
            // dialog flow, which returns false when it is cancelled (the
            // revert happened inside) and true when the chart should redraw.
            if (vizToggleGroup.getSelectedToggle() == velocityToggle
                    && !onVelocityChoice(newVal)) {
                return;
            }
            refreshVisualization();
        });
    }

    private void showDepthLevelComboBox(boolean visible) {
        depthLevelComboBox.setVisible(visible);
        depthLevelComboBox.setManaged(visible);
    }

    private void updateDepthLevelComboBox(Toggle active, boolean chartTypeChanged) {
        if (active == combShapedToggle || active == radarToggle) {
            showDepthLevelComboBox(true);
            if (chartTypeChanged) populateDepthComboForLevels();

        } else if (active == curveToggle) {
            showDepthLevelComboBox(true);
            if (chartTypeChanged || selectedSkill != lastCurveComboSkill) {
                populateDepthComboForCurve();
                lastCurveComboSkill = selectedSkill;
            }
        } else if (active == velocityToggle) {
            // Same combo, different meaning: it picks the velocity WINDOW.
            showDepthLevelComboBox(true);
            if (chartTypeChanged) populateDepthComboForVelocity();
        } else {
            showDepthLevelComboBox(false);
        }
    }

    /** Velocity's time-range options. Reuses depthLevelComboBox rather than
     *  adding another control to an already busy toolbar. Restores the previous
     *  choice on re-entry so switching away to another chart and back does not
     *  silently reset a 12-month view to 30 days. */
    private void populateDepthComboForVelocity() {
        updatingDepthCombo = true;
        try {
            depthComboSkillOptions.clear();
            depthLevelComboBox.setItems(FXCollections.observableArrayList(VELOCITY_OPTIONS));
            // Custom survives the round trip only if its dates do; otherwise
            // fall back to the last preset so the combo never shows "Custom"
            // with nothing behind it.
            String restore = VELOCITY_CUSTOM.equals(lastVelocityChoice) && customVelocityRange == null
                    ? "30 days" : lastVelocityChoice;
            depthLevelComboBox.setValue(restore);
        } finally {
            updatingDepthCombo = false;
        }
    }

    /** "Show Categories Only" / "Show Skills Only" / "Show Subskill N Only",
     *  one per depth actually present in the current tree - so this stays
     *  correct as the hierarchy grows deeper, nothing hardcoded. */
    private void populateDepthComboForLevels() {
        updatingDepthCombo = true;
        try {
            depthComboSkillOptions.clear();
            String previous = depthLevelComboBox.getValue();

            int maxDepth = Skill.maxDepth(db.getSkillTree());
            List<String> labels = new ArrayList<>();
            for (int depth = 0; depth <= maxDepth; depth++) {
                labels.add(depthFilterLabel(depth));
            }
            depthLevelComboBox.setItems(FXCollections.observableArrayList(labels));

            if (previous != null && labels.contains(previous)) {
                depthLevelComboBox.setValue(previous);
            } else if (!labels.isEmpty()) {
                depthLevelComboBox.getSelectionModel().selectFirst();
            }
        } finally {
            updatingDepthCombo = false;
        }
    }

    private String depthFilterLabel(int depth) {
        if (depth == 0) return "Show Categories Only";
        if (depth == 1) return "Show Skills Only";
        return "Show Subskill " + (depth - 1) + " Only";
    }

    private int selectedDepthLevel() {
        return Math.max(0, depthLevelComboBox.getSelectionModel().getSelectedIndex());
    }

    /** Curve mode: lists every LEAF descendant of the skill currently
     *  selected in the top skillComboBox (or just itself, if it's already a
     *  leaf) as a breadcrumb path, so picking a Category or a mid-level
     *  Skill lets you drill down to one specific subskill's actual logged
     *  curve - a bare Category/Skill node usually has no logs of its own. */
    private void populateDepthComboForCurve() {
        updatingDepthCombo = true;
        try {
            depthComboSkillOptions.clear();
            if (selectedSkill == null) {
                depthLevelComboBox.setItems(FXCollections.observableArrayList());
                return;
            }

            Skill match = Skill.flatten(db.getSkillTree()).stream()
                    .filter(s -> s.getId() == selectedSkill.getId())
                    .findFirst().orElse(null);
            if (match == null) {
                depthLevelComboBox.setItems(FXCollections.observableArrayList());
                return;
            }

            List<Skill> candidates = match.isLeaf()
                    ? List.of(match)
                    : Skill.flatten(List.of(match)).stream().filter(Skill::isLeaf).toList();

            List<String> labels = new ArrayList<>();
            for (Skill c : candidates) {
                depthComboSkillOptions.add(c);
                labels.add(breadcrumbPath(c));
            }
            depthLevelComboBox.setItems(FXCollections.observableArrayList(labels));
            if (!labels.isEmpty()) depthLevelComboBox.getSelectionModel().selectFirst();
        } finally {
            updatingDepthCombo = false;
        }
    }

    private String breadcrumbPath(Skill node) {
        List<String> parts = new ArrayList<>();
        for (Skill cur = node; cur != null; cur = cur.getParent()) {
            parts.add(0, cur.getName());
        }
        return String.join(" \u203a ", parts);
    }

    private Skill getCurveTargetSkill() {
        int idx = depthLevelComboBox.getSelectionModel().getSelectedIndex();
        if (idx >= 0 && idx < depthComboSkillOptions.size()) return depthComboSkillOptions.get(idx);
        return selectedSkill;
    }

    private void renderCurveView(Skill skill) {
        curveChart.getData().clear();
        // Velocity view repurposes this same LineChart and leaves the X axis
        // fixed-range with a date formatter - undo that here so switching back
        // to Curve doesn't inherit a date scale on a "Session #" axis.
        curveChart.setTitle(null);
        NumberAxis xAxis = (NumberAxis) curveChart.getXAxis();
        xAxis.setTickLabelFormatter(null);
        xAxis.setAutoRanging(true);
        xAxis.setLabel("Session #");
        ((NumberAxis) curveChart.getYAxis()).setLabel("Cumulative Points");
        if (skill == null) return;

        List<ProgressLog> logs = db.getLogsForSkill(skill.getId());
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(skill.getName());

        double cumulative = 0;
        int session = 0;
        for (ProgressLog log : logs) {
            cumulative += log.getPointsEarned();
            session++;
            series.getData().add(new XYChart.Data<>(session, cumulative));
        }
        if (logs.isEmpty()) {
            series.getData().add(new XYChart.Data<>(0, 0));
        }
        curveChart.getData().add(series);
    }

    // =================================================================
    //  DATA MANAGEMENT: Save / .utrack Import-Export / PDF Export
    // =================================================================

    @FXML
    private void handleSaveState() {
        if (selectedSkill != null) db.updateSkill(selectedSkill);
        statusBarLabel.setText("All changes saved (" + LocalDate.now() + ").");
        showAlert(Alert.AlertType.INFORMATION, "Saved", "Your progress is safely stored in the local database.");
    }

    @FXML
    private void handleExportUtrack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Uni Tracker Backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Uni Tracker Backup", "*.utrack"));
        chooser.setInitialFileName("unitracker_backup.utrack");
        File file = chooser.showSaveDialog(getWindow());
        if (file == null) return;

        try {
            List<CalendarNote> allNotes = db.getNotesForMonth(currentMonth);
            UtrackFileUtil.export(file, skills, allNotes, db.getAllProgressLogs());
            statusBarLabel.setText("Exported to " + file.getName());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Export failed", e.getMessage());
        }
    }

    @FXML
    private void handleImportUtrack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Uni Tracker Backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Uni Tracker Backup", "*.utrack"));
        File file = chooser.showOpenDialog(getWindow());
        if (file == null) return;

        try {
            UtrackFileUtil.importInto(file, db);
            loadSkillsFromDatabase();
            buildCalendar();
            refreshNotesForSelectedDate();
            statusBarLabel.setText("Imported " + file.getName());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Import failed", e.getMessage());
        }
    }

    @FXML
    private void handleExportPdf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Progress Report (PDF)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Document", "*.pdf"));
        chooser.setInitialFileName("uni_tracker_report.pdf");
        File file = chooser.showSaveDialog(getWindow());
        if (file == null) return;

        try {
            Node activeView = getCurrentlyVisibleVisualizationNode();
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.web("#0B1A2B"));
            WritableImage fxImage = activeView.snapshot(params, null);
            BufferedImage chartImage = SwingFXUtils.fromFXImage(fxImage, null);
            String chartTitle = getActiveToggleLabel();

            // HIERARCHY REFACTOR NOTE: must be a tree-linked list (parent/child
            // wired up), not the flat `skills` field directly - PdfExportUtil's
            // rootCategoryName() walks getParent(), which a plain
            // db.getAllSkills() row never populates.
            List<Skill> treeLinkedSkills = Skill.flatten(db.getSkillTree());

            Map<Integer, List<ProgressLog>> logsBySkill = new HashMap<>();
            for (Skill s : treeLinkedSkills) {
                logsBySkill.put(s.getId(), db.getLogsForSkill(s.getId()));
            }
            PdfExportUtil.exportProgressReport(file, treeLinkedSkills, logsBySkill, chartImage, chartTitle);
            statusBarLabel.setText("PDF report saved to " + file.getName());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "PDF export failed", e.getMessage());
        }
    }

    private Node getCurrentlyVisibleVisualizationNode() {
        if (curveChart.isVisible()) return curveChart;
        return structuralCanvasScroll;
    }

    private String getActiveToggleLabel() {
        Toggle t = vizToggleGroup.getSelectedToggle();
        return t instanceof ToggleButton tb ? tb.getText() : "Chart";
    }

    // =================================================================
    //  HELPERS
    // =================================================================

    private Window getWindow() {
        return rootPane.getScene().getWindow();
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        alert.getDialogPane().getStyleClass().add("glass-panel");
        alert.showAndWait();
    }
}
