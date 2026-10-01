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
import com.unitracker.model.ProgressPreset;
import com.unitracker.model.Skill;
import com.unitracker.util.Anim;
import com.unitracker.util.AppSettings;
import com.unitracker.util.FocusTimerState;
import com.unitracker.util.MarkdownUtil;
import com.unitracker.util.PdfExportUtil;
import com.unitracker.util.SoundPlayer;
import com.unitracker.util.UtrackFileUtil;
import com.unitracker.util.UiScale;
import com.unitracker.util.VisualizationRenderer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.layout.ColumnConstraints;
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
import java.time.Instant;
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
     *  selected INDEX maps back into FocusTimerState.AVAILABLE_MINUTES. */
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
    /** v2.0 - the projected completion date for the selected skill. See
     *  {@link #refreshEta()} for why this is the most useful figure the app
     *  can show, and why it reports an honest "no estimate" rather than a
     *  fabricated one when the skill has no recent activity. */
    @FXML private Label etaLabel;
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

    /**
     * Where the user last dragged the chart divider, or -1 for none.
     *
     * <p>Remembered ONLY for the modes that actually show the controls pane, so
     * a drag made there is restored on re-entry - and can never strand
     * Velocity/Time Split/Curve at half height, because those modes take the
     * {@code wanted <= 0} branch and use a ratio of zero regardless.
     */
    private double multiSkillDividerRatio = -1;

    /** True while this class is writing the divider, so the drag listener can
     *  tell its own writes from a user's drag. */
    private boolean writingDivider = false;

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

    /**
     * The durations offered in the Focus Timer dropdown, in minutes.
     *
     * <p>Delegates to {@link FocusTimerState#AVAILABLE_MINUTES} rather than
     * holding its own copy. The Settings dialog builds a reward row per
     * duration from the same list, and when the list lived only here there was
     * no compiler-level connection between the two - so a duration added to
     * the dropdown but not to the settings grid would silently leave the user
     * unable to price a block the timer happily runs.
     */
    private static int[] TIMER_PRESETS() {
        return FocusTimerState.AVAILABLE_MINUTES;
    }

    // ---- v2.0: settings keys now live in AppSettings, so a typo is a compile
    // ---- error rather than a silently-defaulted preference. The local
    // ---- constants that used to duplicate them are gone; see the
    // ---- KEY_SIDEBAR_PREFIX / KEY_MUTED notes where they survive.
    /** Below this content height the notes list collapses to pinned-only (PART 2).
     *  ~2 note cards' worth - under that a scrollable list shows nothing useful. */
    private static final double NOTES_COMPACT_THRESHOLD = 170;
    /** The sidebar column's own clamp, mirrored from Dashboard.fxml. Keep these
 *  in step with the sidebar ScrollPane's minWidth/maxWidth - NOT the inner
 *  column's - or the clock will scale against the wrong range. The ScrollPane is
 *  the node the centre HBox actually lays out, so its width is the one that
 *  decides how much room the clock gets. */
    private static final double COLUMN_MIN_WIDTH = 300;
    private static final double COLUMN_MAX_WIDTH = 420;
    /** Focus-timer clock size at those two widths. The floor is what lets
     *  "01:30:00" render whole in a narrow window instead of ellipsising;
     *  see setupPomodoro for the calibration. */
    private static final double CLOCK_FONT_MIN = 35;
    private static final double CLOCK_FONT_MAX = 50;

    // =================================================================
    //  FOCUS TIMER STATE  (v2.0 - absolute deadline, not a countdown)
    // =================================================================

    /**
     * Ticks the countdown once per second. An AnimationTimer rather than a
     * Timeline or a Thread: it already runs on the JavaFX application thread,
     * so updating the label needs no Platform.runLater, and it stops cleanly
     * with the app without leaving a non-daemon thread behind.
     */
    private AnimationTimer pomodoroTimer;

    /**
     * The id of the {@code timer_sessions} row backing the current session, or
     * -1 when no session is live. Persisted at every state change, which is
     * why there is no ON_CLOSE handler needed for the timer at all - the state
     * is already on disk by the time the window closes. That is a strictly
     * stronger guarantee than saving on close, which loses the session if the
     * process is killed instead.
     */
    private long pomodoroSessionId = -1;

    /**
     * The ABSOLUTE deadline of the running session, or null when paused or idle.
     *
     * <p>This single field replaces v1's {@code pomodoroSecondsLeft}, and it is
     * the whole of the "the timer must not reset when the app is closed"
     * requirement. A decrementing counter only exists in memory, so it dies
     * with the process; a wall-clock deadline is answered by the clock itself
     * and is therefore correct whether the app was closed for a minute or a
     * week. All the arithmetic lives in {@link FocusTimerState}, which is pure
     * and unit-tested.
     */
    private Instant pomodoroEndsAt;

    /**
     * Total length of the live session in seconds, captured when it starts.
     *
     * <p>Held separately from the configured duration so the progress bar and
     * the reward both describe the session that ACTUALLY ran. Reading the
     * current setting instead would mean that changing the duration dropdown
     * mid-session silently retargets the bar and the payout.
     */
    private int pomodoroTotalSeconds = AppSettings.DEFAULT_TIMER_MINUTES * 60;

    /**
     * Frozen remaining time while paused, in seconds.
     *
     * <p>Stored as a COUNT, not as a synthesised future deadline, because a
     * deadline-based pause expires on its own: paused for an hour over lunch
     * and the app comes back to an empty timer. A count has no relationship to
     * the wall clock and survives an arbitrary shutdown.
     */
    private long pomodoroFrozenRemaining = 0L;

    private boolean pomodoroRunning;

    /**
     * The skill this live session should be credited to, or -1 for none.
     *
     * <p>Captured when the session STARTS (or when it is RESTORED), and
     * deliberately not re-read at completion time. Reading it at completion
     * would break the case the v2.0 requirement exists for: a session that
     * expired while the app was shut gets completed on the next launch, by
     * which time the ComboBox holds whatever the user happened to have
     * selected, and the hour of work would be attributed to the wrong skill.
     * The same applies to the DB row - once completeTimerSession has marked it
     * FINISHED it is no longer returned by loadActiveTimerSession, so a lookup
     * at that point finds nothing at all.
     */
    private int pomodoroTargetSkillId = -1;

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
        // Settings must be readable before anything that consults one. The
        // database is already open - MainApp initialises it before loading this
        // FXML - so the cache can be primed here and every later read is
        // in-memory rather than a database round trip.
        AppSettings.load();
        // Resolution-independent type scale. Applied before the first layout
        // pass so the very first frame is already at the right size and the UI
        // does not visibly reflow a moment after appearing.
        //
        // LISTENING FOR THE SCENE, NOT POLLING FOR IT. There is no Scene here.
        // FXMLLoader calls initialize() from inside load(), and MainApp builds
        // the Scene only after load() has returned, so rootPane.getScene() is
        // null at this point. Calling UiScale.apply(rootPane.getScene(), ...)
        // here therefore did nothing at all - it returned early, printed
        // nothing, and the generated stylesheets were never attached to any
        // scene. Nothing failed; the feature was simply dead on arrival.
        //
        // A sceneProperty listener makes the ordering irrelevant instead of
        // depending on it, which is also what lets the FXML harness load a view
        // and build its own Scene without the preferences being skipped.
        rootPane.sceneProperty().addListener((obs, oldScene, newScene) ->
                applyStylePreferences(newScene));
        // The listener above only fires on a CHANGE. If this controller is ever
        // constructed around an already-parented root - a nested view, or a
        // harness that attaches the scene first - the scene is here now and no
        // change will ever come. Cheap to cover, so cover it.
        applyStylePreferences(rootPane.getScene());

        setupVizToggleGroup();
        syncChartResizeHandle();
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
        SoundPlayer.setMuted(AppSettings.isMuted());
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

        // LAST, and deliberately so. Restoring a session that expired while the
        // app was shut has to award points to a real Skill object, and the
        // skills list is only populated by loadSkillsFromDatabase() above.
        // Running this earlier would silently log nothing, which is the exact
        // failure mode the derived-points model was introduced to eliminate.
        restoreTimerOnStartup();

        if (statusBarLabel.getText().startsWith("Connected")) {
            statusBarLabel.setText("Connected to local SQLite database.");
        }
    }

    /**
     * Closes the floating widget when the application exits.
     *
     * <p>Called from {@code MainApp#stop()}. A still-visible
     * {@code StageStyle.TRANSPARENT} always-on-top window outliving its owner
     * is a genuinely nasty failure mode: the JVM waits on non-daemon toolkit
     * threads, the process appears to hang on exit, and the user is left with
     * an orphaned widget on their desktop that they must kill by hand. Closing
     * it explicitly makes shutdown deterministic.
     */
    public void shutdownWindows() {
        if (pomodoroTimer != null) {
            pomodoroTimer.stop();
        }
        if (floatingTimer != null) {
            try {
                floatingTimer.stage().setOnHidden(null);
                floatingTimer.close();
            } catch (RuntimeException e) {
                System.err.println("[DashboardController] Closing the floating timer failed: " + e.getMessage());
            }
        }
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
    //  FOCUS TIMER  (v2.0)
    //
    //  WHAT CHANGED, AND WHY IT WAS NECESSARY RATHER THAN COSMETIC:
    //
    //  v1 kept the remaining time in a long field and, on pause, recovered it
    //  by PARSING THE CLOCK LABEL'S TEXT. Three separate defects fell out of
    //  that design, and all three are now closed:
    //
    //   1. A session was only written to the database if the user pressed OK
    //      on a confirmation dialog. Press Cancel, or close the app with that
    //      dialog open, and the time was simply lost - no log row, no points.
    //      Completion is now COMMITTED IMMEDIATELY and corrected afterwards
    //      through an Undo affordance, which is the only ordering in which
    //      work that has actually happened cannot be lost.
    //
    //   2. That dialog was opened with showAndWait() from inside an
    //      AnimationTimer pulse, which starts a NESTED EVENT LOOP on the
    //      render thread. Everything the dialog's handler then did - the log
    //      insert, the rollup, the calendar rebuild - ran re-entrantly inside
    //      a frozen frame. There is no modal here any more.
    //
    //   3. Nothing about a running session was persisted, so closing the app
    //      reset it to full. The deadline is now stored on every state change
    //      and recovered on startup - including the case where it expired
    //      while the app was shut, which fires completion exactly once.
    // =================================================================

    /**
     * Wires the clock, the duration dropdown and the tick.
     *
     * <p>No session state is created or restored here: this runs before the
     * skill list is loaded, and the startup restore has to be able to award
     * points to a real skill, which means {@code restoreTimerOnStartup()} runs
     * later - see initialize().
     */
    private void setupPomodoro() {
        if (pomodoroTimeLabel == null) return; // layout without the widget

        // RESIZE FIX. The FXML's minWidth="0" guarantees the clock can never
        // force the panel outside the sidebar, but on its own it buys that by
        // ellipsising to "01:30..." - a truncated timer is worse than a small
        // one. The column is clamped 300..420px, leaving roughly 150px for the
        // clock at the floor against about 212px needed at 50px.
        //
        // So the size follows the width, AND the scale tier (v2.0) - otherwise
        // a user on a 4K display who has chosen "Extra large" gets a 4K-sized
        // panel containing a 1K-sized clock.
        if (timerPane != null) {
            timerPane.widthProperty().addListener((obs, old, w) ->
                    pomodoroTimeLabel.setStyle("-fx-font-size: "
                            + Math.round(clockFontFor(w.doubleValue())) + "px;"));
        }

        if (timerDurationCombo != null) {
            List<String> labels = new ArrayList<>();
            for (int m : TIMER_PRESETS()) {
                labels.add(FocusTimerState.formatDuration(m));
            }
            timerDurationCombo.setItems(FXCollections.observableArrayList(labels));
            timerDurationCombo.setValue(FocusTimerState.formatDuration(timerMinutes()));
            // Changing the duration mid-session would make the progress bar lie
            // about how far along the RUNNING session is, so it is refused
            // while live and offered once the session ends. Previously it
            // silently reset the timer, throwing away elapsed focus time.
            timerDurationCombo.valueProperty().addListener((obs, old, chosen) -> {
                if (chosen == null || updatingTimerCombo) return;
                int minutes = FocusTimerState.minutesAt(timerDurationCombo.getSelectionModel().getSelectedIndex());
                if (isTimerSessionLive()) {
                    // Put the selection back and tell the user why, rather than
                    // either corrupting the bar or dropping their session.
                    updatingTimerCombo = true;
                    timerDurationCombo.setValue(FocusTimerState.formatDuration(
                            pomodoroTotalSeconds / 60));
                    updatingTimerCombo = false;
                    statusBarLabel.setText("Reset or finish the current session before changing its length.");
                    return;
                }
                AppSettings.set(AppSettings.KEY_TIMER_MINUTES, minutes);
                reloadTimerDuration();
                statusBarLabel.setText("Focus timer set to " + FocusTimerState.formatDuration(minutes)
                        + " (worth " + trimNumber(pointsForDuration(minutes)) + " pts).");
            });
        }

        pomodoroTimer = new AnimationTimer() {
            private long lastWholeSecond = -1;

            @Override
            public void handle(long now) {
                // The deadline is authoritative, so the tick does not accumulate
                // elapsed time - it asks the clock. That is what makes the
                // display immune to the dropped frames, the throttled window,
                // and the debugger breakpoint that v1's startNanos accumulator
                // would have silently absorbed as "time you did not spend".
                long remaining = FocusTimerState.remainingSeconds(pomodoroEndsAt, Instant.now());
                if (remaining == lastWholeSecond) return; // one UI write per second
                lastWholeSecond = remaining;

                if (remaining <= 0) {
                    stop();
                    completeLiveSession();
                } else {
                    // ONE remaining value, pushed to EVERY view. This single
                    // fan-out is why the sidebar clock and the floating widget
                    // can never disagree: there is only one number, computed
                    // once, and no view derives its own.
                    updatePomodoroDisplay(remaining);
                    pushTickToFloatingTimer(remaining);
                }
            }
        };

        pomodoroTotalSeconds = timerMinutes() * 60;
        reloadTimerDuration();
    }

    /** True while a session is running or paused, i.e. something the user
     *  would lose by being interrupted. */
    private boolean isTimerSessionLive() {
        return pomodoroSessionId > 0 || pomodoroRunning || pomodoroFrozenRemaining > 0;
    }

    /** Resets the display to the configured duration with no live session. */
    private void reloadTimerDuration() {
        pomodoroEndsAt = null;
        pomodoroFrozenRemaining = 0L;
        pomodoroTotalSeconds = timerMinutes() * 60;
        pomodoroRunning = false;
        pomodoroTargetSkillId = -1;
        if (pomodoroStartButton != null) {
            pomodoroStartButton.setText("Start");
        }
        updatePomodoroDisplay(pomodoroTotalSeconds);
        // AND PUSH IT TO THE WIDGET. Omitting this is what made the discard
        // button look broken: it stopped the ticker, cleared the session and
        // reset the sidebar clock, but the widget's clock froze at the abandoned
        // time and its button stayed reading "Pause". Since the ticker was
        // stopped, nothing would ever have corrected it - the display only
        // recovered when the user clicked Pause, because that transition
        // happened to push. Pushed here rather than in the reset handler so
        // that every path reaching this state also refreshes both views.
        pushTickToFloatingTimer(pomodoroTotalSeconds);
        // The dimmed "paused" styling has to come off too, or a discarded
        // session leaves the widget looking paused at a full duration.
        syncFloatingTimerEmphasis();
    }

    // -----------------------------------------------------------------
    //  Start / pause / resume / reset
    // -----------------------------------------------------------------

    @FXML
    private void handlePomodoroToggle() {
        if (pomodoroTimer == null) return;
        SoundPlayer.play(SoundPlayer.Sfx.CLICK);

        if (pomodoroRunning) {
            pauseTimer();
        } else if (pomodoroFrozenRemaining > 0) {
            resumeTimer();
        } else {
            startTimer();
        }
    }

    /**
     * Starts a fresh session against the currently selected skill.
     *
     * <p>THE TARGET IS CAPTURED HERE, NOT AT COMPLETION. The skill id is
     * written into the {@code timer_sessions} row, so a session that is
     * interrupted - app closed, machine rebooted - still knows where its
     * points belong. Reading the selection at completion time would attribute
     * an hour of work to whatever happened to be highlighted hours later.
     */
    private void startTimer() {
        int minutes = timerMinutes();
        Instant now = Instant.now();
        pomodoroTotalSeconds = minutes * 60;
        pomodoroEndsAt = now.plusSeconds(pomodoroTotalSeconds);
        pomodoroFrozenRemaining = 0L;
        pomodoroRunning = true;

        Skill target = selectedSkill;
        int targetId = target == null ? -1 : target.getId();
        pomodoroTargetSkillId = targetId;
        String label = target == null ? null : target.getName();
        pomodoroSessionId = db.startTimerSession(targetId, label, pomodoroTotalSeconds,
                FocusTimerState.toEpochMillis(pomodoroEndsAt));

        if (pomodoroSessionId < 0) {
            // The session could not be recorded, so it would be lost on a
            // crash. Say so rather than pretending the work is being tracked.
            statusBarLabel.setText("Could not start the focus timer - no session was recorded.");
            pomodoroEndsAt = null;
            pomodoroRunning = false;
            pomodoroTargetSkillId = -1;
            return;
        }

        pomodoroStartButton.setText("Pause");
        pomodoroTimer.start();
        updatePomodoroDisplay(pomodoroTotalSeconds);
        // Push now rather than waiting for the tick. The first tick is up to a
        // second away, and if this was triggered from the WIDGET then the widget
        // is the view that has to change - leaving it reading "Start" while the
        // session is already running is the same class of stale label as the
        // one this push exists to prevent.
        pushTickToFloatingTimer(pomodoroTotalSeconds);
        syncFloatingTimerEmphasis();

        if (target == null) {
            statusBarLabel.setText("Focus timer started, but no skill is selected - "
                    + "the session will not be logged. Pick a skill in the dashboard.");
        } else {
            statusBarLabel.setText("Focus timer started on " + target.getName() + " - "
                    + FocusTimerState.formatDuration(minutes) + " worth "
                    + trimNumber(pointsForDuration(minutes)) + " pts.");
        }
    }

    /**
     * Freezes the remaining time as a COUNT and persists it.
     *
     * <p>Persist-then-stop, deliberately: if the app is killed in the
     * microsecond between the two, the row still says RUNNING with a deadline
     * in the past, which the startup restore handles as a completion. The
     * reverse order could lose the pause entirely.
     */
    private void pauseTimer() {
        Instant now = Instant.now();
        long remaining = FocusTimerState.freezeRemaining(pomodoroEndsAt, now);
        if (pomodoroSessionId > 0) {
            db.pauseTimerSession(pomodoroSessionId, remaining);
        }
        pomodoroTimer.stop();
        pomodoroRunning = false;
        pomodoroEndsAt = null; // no longer meaningful while paused
        pomodoroFrozenRemaining = remaining;
        pomodoroStartButton.setText("Resume");
        updatePomodoroDisplay(remaining);
        // Required, not optional: the ticker has just been stopped, so nothing
        // else will ever push. Without this the widget keeps its last tick's
        // clock (up to a second stale) and its button still reads "Pause", so
        // pausing from the sidebar left the widget offering to pause a paused
        // session.
        pushTickToFloatingTimer(remaining);
        syncFloatingTimerEmphasis();
        statusBarLabel.setText("Focus timer paused at "
                + FocusTimerState.formatClock(remaining) + ". It will survive closing the app.");
    }

    /** Turns the frozen count back into a live deadline, re-anchored to now. */
    private void resumeTimer() {
        Instant deadline = FocusTimerState.deadlineForResume(pomodoroFrozenRemaining, Instant.now());
        if (pomodoroSessionId > 0) {
            db.resumeTimerSession(pomodoroSessionId, FocusTimerState.toEpochMillis(deadline));
        }
        pomodoroEndsAt = deadline;
        pomodoroFrozenRemaining = 0L;
        pomodoroRunning = true;
        pomodoroStartButton.setText("Pause");
        pomodoroTimer.start();
        long remaining = FocusTimerState.remainingSeconds(deadline, Instant.now());
        updatePomodoroDisplay(remaining);
        // Same reason as pauseTimer: the widget's button label and clock are
        // both stale after a resume, and this is one of the transitions the user
        // can trigger from the widget itself.
        pushTickToFloatingTimer(remaining);
        syncFloatingTimerEmphasis();
        statusBarLabel.setText("Focus timer resumed.");
    }

    @FXML
    private void handlePomodoroReset() {
        if (pomodoroTimer == null) return;
        pomodoroTimer.stop();
        // The elapsed time is genuinely discarded, so the row is ABANDONED
        // rather than FINISHED - which is what stops the startup restore from
        // later deciding this abandoned session had expired and awarding it.
        if (pomodoroSessionId > 0) {
            db.abandonTimerSession(pomodoroSessionId);
        }
        pomodoroSessionId = -1;
        reloadTimerDuration();
        statusBarLabel.setText("Focus timer reset to "
                + FocusTimerState.formatClock(pomodoroTotalSeconds) + ".");
    }

    // -----------------------------------------------------------------
    //  Completion - commit first, confirm afterwards
    // -----------------------------------------------------------------

    /**
     * A live session reached its deadline. Commits it IMMEDIATELY.
     *
     * <p>ORDER IS THE WHOLE DESIGN. The database is updated first and the user
     * is told afterwards. v1 did the opposite - it asked, then recorded - which
     * meant a session could evaporate on a Cancel press or a closed window. A
     * user who cannot lose recorded work is strictly better served by a
     * correction affordance than by a confirmation gate: nothing is lost, and
     * misattribution is one Undo away.
     *
     * <p>The commit runs inline rather than being deferred with
     * {@code Platform.runLater}, because it is the one step that must happen
     * exactly once. Only the visual refresh and the toast are deferred, so the
     * animation pulse returns immediately instead of doing a calendar rebuild
     * and a chart render inside a frame.
     */
    private void completeLiveSession() {
        long sessionId = pomodoroSessionId;
        int targetId = pomodoroTargetSkillId;
        int minutes = Math.max(1, pomodoroTotalSeconds / 60);
        double points = pointsForDuration(minutes);

        // Clear the live state FIRST. If anything below throws, the timer is
        // left in a clean idle state rather than re-firing completion forever
        // on the next tick.
        pomodoroRunning = false;
        pomodoroEndsAt = null;
        pomodoroFrozenRemaining = 0L;
        pomodoroSessionId = -1;
        pomodoroTargetSkillId = -1;
        if (pomodoroStartButton != null) {
            pomodoroStartButton.setText("Start");
        }

        // Mark the row FINISHED before writing the log. completeTimerSession
        // records the reward, so if the process dies between the two, the next
        // startup sees a FINISHED row and does not award it a second time.
        if (sessionId > 0) {
            db.completeTimerSession(sessionId, points);
        }

        Skill target = findSkillById(targetId);
        pomodoroTotalSeconds = timerMinutes() * 60;
        updatePomodoroDisplay(pomodoroTotalSeconds);
        SoundPlayer.play(SoundPlayer.Sfx.TIMER_FINISH);

        if (target == null) {
            // Either no skill was attached, or it has since been deleted. The
            // session is still closed out so it cannot re-fire, and the user is
            // told plainly that nothing was logged.
            statusBarLabel.setText("Focus session finished, but no skill was attached to it - "
                    + "nothing was logged. Start the next session with a skill selected.");
            showToast("Session finished - not logged (no skill attached)", null);
            return;
        }

        double pointsBefore = target.getCurrentPoints();
        ProgressLog log = new ProgressLog(target.getId(), mockToday, minutes, points,
                ProgressLog.SOURCE_TIMER, (int) sessionId);
        log.setNote("Focus session (" + FocusTimerState.formatDuration(minutes) + ")");
        commandManager.execute(new LogProgressCommand(db, target, skills, log));

        celebrateIfJustCompleted(pointsBefore, target);
        String message = "+" + trimNumber(points) + " pts to " + target.getName();
        statusBarLabel.setText("Focus session logged: " + trimNumber(points)
                + " pts to " + target.getName() + ", rolled up to its parent skill and category.");

        // The refresh and the toast are the only deferred work; the award above
        // is already durable.
        Platform.runLater(() -> {
            afterLogChanged();
            buildCalendar();
            showToast(message, () -> {
                if (commandManager.undo()) {
                    Platform.runLater(() -> {
                        afterLogChanged();
                        buildCalendar();
                        statusBarLabel.setText("Focus session undone.");
                    });
                }
            });
        });
    }

    /** The live Skill object for an id in the controller's flat list, or null
     *  when the id is -1 or the skill has since been deleted. */
    private Skill findSkillById(int skillId) {
        if (skillId < 0) return null;
        for (Skill s : skills) {
            if (s.getId() == skillId) return s;
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Startup restore
    // -----------------------------------------------------------------

    /**
     * Recovers a session that was live when the app last closed.
     *
     * <p>THIS IS THE v2.0 HEADLINE BEHAVIOUR, and it has three outcomes, all
     * decided by {@link FocusTimerState#restore} rather than by any logic here:
     * <ul>
     *   <li>time left - the session resumes exactly where it was;</li>
     *   <li>paused - it resumes paused with its frozen remainder, however long
     *       ago that was;</li>
     *   <li>expired while shut - completion fires once, now, and awards the
     *       points. Coming back after lunch and finding your finished session
     *       silently restarted at 25:00 was the v1 behaviour and is exactly
     *       what the requirement forbids.</li>
     * </ul>
     *
     * <p>Called from initialize() AFTER loadSkillsFromDatabase(), because the
     * expired case has to award points to a real Skill object and the list is
     * empty before then.
     */
    private void restoreTimerOnStartup() {
        if (pomodoroTimer == null) return;
        DatabaseHelper.TimerSessionState stored = db.loadActiveTimerSession();
        if (stored == null) return;

        Instant now = Instant.now();
        FocusTimerState.RestoreAction action = FocusTimerState.restoreFrom(stored, now);

        switch (action.kind()) {
            case COMPLETE_NOW -> {
                // Rebuild the in-memory session description so the shared
                // completion path can commit it identically. The target skill
                // comes from the STORED row, not from the current selection:
                // the work was done hours or days ago against whatever was
                // selected then.
                pomodoroSessionId = stored.id();
                pomodoroTargetSkillId = stored.skillId();
                pomodoroTotalSeconds = Math.max(60, stored.durationSeconds());
                pomodoroEndsAt = null;
                pomodoroFrozenRemaining = 0L;
                pomodoroRunning = false;
                SoundPlayer.play(SoundPlayer.Sfx.TIMER_FINISH);
                completeLiveSession();
                statusBarLabel.setText("Welcome back - a focus session finished while the app was closed "
                        + "and has been logged.");
            }
            case RESUME_RUNNING -> {
                pomodoroSessionId = stored.id();
                pomodoroTargetSkillId = stored.skillId();
                pomodoroTotalSeconds = Math.max(60, stored.durationSeconds());
                pomodoroEndsAt = action.nextDeadline();
                pomodoroFrozenRemaining = 0L;
                pomodoroRunning = true;
                pomodoroStartButton.setText("Pause");
                pomodoroTimer.start();
                updatePomodoroDisplay(action.remainingSeconds());
                statusBarLabel.setText("Focus timer resumed with "
                        + FocusTimerState.formatClock(action.remainingSeconds()) + " left.");
            }
            case RESUME_PAUSED -> {
                pomodoroSessionId = stored.id();
                pomodoroTargetSkillId = stored.skillId();
                pomodoroTotalSeconds = Math.max(60, stored.durationSeconds());
                pomodoroEndsAt = null;
                pomodoroFrozenRemaining = action.remainingSeconds();
                pomodoroRunning = false;
                pomodoroStartButton.setText("Resume");
                updatePomodoroDisplay(action.remainingSeconds());
                statusBarLabel.setText("Focus session restored, still paused at "
                        + FocusTimerState.formatClock(action.remainingSeconds()) + ".");
            }
            case NONE -> {
                // Nothing to do. Left explicit so the enum cannot grow a case
                // without this switch failing to compile.
            }
        }
    }

    // -----------------------------------------------------------------
    //  Non-modal confirmation toast
    // -----------------------------------------------------------------

    /** The transient message strip, created on first use. */
    private VBox toastContainer;
    /** Auto-dismiss handle, so a second toast cancels the first one's timer
     *  rather than the first one hiding the new message early. */
    private PauseTransition toastHideTimer;

    /**
     * Wraps the existing status bar in a VBox and adds a toast strip beneath
     * it.
     *
     * <p>Built in code rather than in Dashboard.fxml so that Phase 3a needs no
     * view change; Phase 4 replaces this with declarative markup. The
     * reparenting is safe because {@code getBottom()} is the status-bar HBox
     * this class never otherwise touches.
     */
    private void ensureToastContainer() {
        if (toastContainer != null) return;
        Node existingBottom = rootPane.getBottom();
        VBox stack = new VBox(toastContainer = new VBox());
        stack.setFillWidth(true);
        if (existingBottom != null) {
            stack.getChildren().add(existingBottom);
        }
        toastContainer.getStyleClass().add("toast-strip");
        toastContainer.setVisible(false);
        toastContainer.setManaged(false);
        stack.getChildren().add(toastContainer);
        rootPane.setBottom(stack);
    }

    /**
     * Shows a transient message, optionally with an Undo action.
     *
     * <p>NON-MODAL BY DESIGN. This replaces a {@code showAndWait()} dialog
     * that was being opened from inside an animation pulse, and it is
     * dismissible by doing nothing - an undo prompt that blocks the interface
     * until it is answered is not a prompt, it is a tax.
     *
     * @param undoAction run when the user presses Undo; null for no button
     */
    private void showToast(String message, Runnable undoAction) {
        if (statusBarLabel == null) return;
        ensureToastContainer();

        if (toastHideTimer != null) {
            toastHideTimer.stop();
        }
        toastContainer.getChildren().clear();

        Label text = new Label(message);
        text.getStyleClass().add("toast-label");
        text.setWrapText(true);

        HBox row = new HBox(12, text);
        row.setAlignment(Pos.CENTER_LEFT);

        if (undoAction != null) {
            Button undo = new Button("Undo");
            undo.getStyleClass().add("toast-undo-button");
            undo.setOnAction(e -> {
                hideToast();
                undoAction.run();
            });
            row.getChildren().add(undo);
        }

        toastContainer.getChildren().add(row);
        toastContainer.setVisible(true);
        toastContainer.setManaged(true);
        Anim.slideUpIn(toastContainer, 10.0, Anim.standardMillis());

        // Auto-dismiss. A little longer than a plain notification, because an
        // Undo affordance the user can barely reach in time is not an Undo
        // affordance.
        toastHideTimer = new PauseTransition(javafx.util.Duration.seconds(7));
        toastHideTimer.setOnFinished(e -> hideToast());
        toastHideTimer.play();
    }

    private void hideToast() {
        if (toastContainer == null || !toastContainer.isVisible()) return;
        if (toastHideTimer != null) {
            toastHideTimer.stop();
        }
        // Anim.fadeOut applies the end state even with animations disabled, so
        // the "off" path still leaves the strip properly hidden rather than
        // stranded at full opacity.
        Anim.fadeOut(toastContainer, Anim.standardMillis(), () -> {
            toastContainer.setVisible(false);
            toastContainer.setManaged(false);
            toastContainer.getChildren().clear();
        });
    }

    // -----------------------------------------------------------------
    //  Floating timer window  (v2.0)
    //
    //  THE HANDOFF INVARIANT: popping out or docking moves a LABEL between
    //  windows. It does not transfer a counter, and it does not restart the
    //  AnimationTimer. The deadline (pomodoroEndsAt) and the single tick are
    //  untouched by either operation, so there is no arithmetic at the moment
    //  of the switch and therefore no second that can be lost. The widget reads
    //  the same value the sidebar does, from the same computation.
    // -----------------------------------------------------------------

    /** Null until the user pops the timer out for the first time; created
     *  lazily so a user who never uses the widget never loads its FXML. */
    private FloatingTimerWindow floatingTimer;

    /** The pop-out / dock toggle, declared in Dashboard.fxml. Retitled
     *  between "Pop out timer" and "Dock timer" by
     *  {@link #refreshTimerWindowButton()}. */
    @FXML private Button timerWindowButton;

    /** The dashboard's stage, cached on first use. Never null after
     *  initialize(), because the scene is attached before initialize() runs. */
    private Stage dashboardStage;

    /**
     * v2.0. Called by MainApp right after {@code primaryStage.show()}.
     *
     * <p>Needed because the controller now has to HIDE the dashboard to reveal
     * the floating widget, which is impossible when the only reference to the
     * stage is the {@code Window} reachable from the scene.
     */
    public void attachPrimaryStage(Stage stage) {
        this.dashboardStage = stage;
    }

    private Stage dashboardStage() {
        if (dashboardStage == null && rootPane.getScene() != null
                && rootPane.getScene().getWindow() instanceof Stage stage) {
            dashboardStage = stage;
        }
        return dashboardStage;
    }

    /**
     * Keeps the toggle's label honest about what it will do next.
     *
     * <p>Null-safe, because the button is declared in FXML and an older layout
     * without it must not throw during initialisation.
     */
    private void refreshTimerWindowButton() {
        if (timerWindowButton == null) {
            return;
        }
        boolean floating = floatingTimer != null && floatingTimer.isShowing();
        timerWindowButton.setText(floating ? "Dock timer" : "Pop out timer");
        timerWindowButton.setTooltip(new Tooltip(floating
                ? "Put the focus timer back in the dashboard"
                : "Run the focus timer in a small always-on-top window"));
    }

    /** Shows the widget and hides the dashboard, or the reverse. Bound from
     *  Dashboard.fxml as {@code onAction="#handleToggleFloatingTimer"}. */
    @FXML
    private void handleToggleFloatingTimer() {
        if (floatingTimer != null && floatingTimer.isShowing()) {
            dockFloatingTimer();
        } else {
            popOutFloatingTimer();
        }
    }

    /**
     * Moves the timer into the floating widget and hides the dashboard.
     *
     * <p>NOTE WHAT DOES NOT HAPPEN HERE. The AnimationTimer is not stopped, the
     * deadline is not rewritten, and no remaining-time value is computed. The
     * dashboard is hidden but its scene stays alive, so the single tick keeps
     * running and keeps updating a sidebar that nobody can see - which costs one
     * Label write a second and guarantees that docking back needs no catch-up.
     */
    private void popOutFloatingTimer() {
        Stage main = dashboardStage();
        if (main == null) {
            statusBarLabel.setText("Could not open the floating timer - the main window is not available.");
            return;
        }
        // Captured BEFORE hiding: a hidden stage's position is not meaningful,
        // and this is what decides which monitor the widget opens on.
        Rectangle2D dashboardBounds = new Rectangle2D(
                main.getX(), main.getY(), main.getWidth(), main.getHeight());

        try {
            if (floatingTimer == null) {
                floatingTimer = FloatingTimerWindow.create(
                        this::handlePomodoroToggle,   // the widget's Pause/Start
                        this::handlePomodoroReset,    // the widget's reset
                        this::dockFloatingTimer);     // the widget's dock button
                // If the user closes the widget with the window chrome rather
                // than the dock button, the dashboard has to come back or the
                // app is left running with nothing visible on screen.
                floatingTimer.stage().setOnHidden(e -> {
                    if (main.isShowing()) {
                        return; // we are docking deliberately; dockFloatingTimer owns it
                    }
                    main.show();
                    refreshTimerWindowButton();
                });
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[DashboardController] Could not build the floating timer");
            e.printStackTrace();
            // describe(), not getMessage(): a LoadException from FXML frequently
            // has a null message and the real reason is on its cause, so the
            // old code produced an alert reading just "null".
            showAlert(Alert.AlertType.ERROR, "Floating timer unavailable",
                    "The floating timer window could not be created: " + describe(e));
            return;
        }

        floatingTimer.show(dashboardBounds);
        main.hide();
        AppSettings.set(AppSettings.KEY_FLOATING_TIMER_OPEN, true);
        refreshTimerWindowButton();

        // SEED THE WIDGET WITH THE CURRENT STATE, before anything else. The
        // widget is created blank, and while the session is paused or idle the
        // ticker is stopped - so there is no tick coming that would ever fill it
        // in. That is precisely the reported symptom: pop out while paused, and
        // the widget shows an empty clock and a Start button that does nothing.
        // When the session IS running the ticker would fix this within a second,
        // which is exactly why it looked like it worked from that state only.
        long remainingNow = pomodoroRunning && pomodoroEndsAt != null
                ? FocusTimerState.remainingSeconds(pomodoroEndsAt, Instant.now())
                : (pomodoroFrozenRemaining > 0 ? pomodoroFrozenRemaining : pomodoroTotalSeconds);
        pushTickToFloatingTimer(remainingNow);
        syncFloatingTimerEmphasis();

        // The session state is unchanged; only the presentation moved.
        statusBarLabel.setText(isTimerSessionLive()
                ? "Focus timer moved to the floating window - it keeps running."
                : "Focus timer is in the floating window. Start it there or here.");
    }

    /**
     * Returns the timer to the dashboard and closes the widget.
     *
     * <p>Order matters: the dashboard is shown BEFORE the widget is hidden, so
     * there is never a moment with no visible window. Hiding first would flash
     * the desktop, which on a multi-monitor setup looks like the app crashed.
     */
    private void dockFloatingTimer() {
        Stage main = dashboardStage();
        if (main != null && !main.isShowing()) {
            main.show();
        }
        if (floatingTimer != null) {
            // Suppress the widget's onHidden handler, which would otherwise
            // re-show a dashboard that is already visible and, worse, would
            // fight this method if the two ever disagreed about the state.
            floatingTimer.stage().setOnHidden(null);
            floatingTimer.hide();
        }
        AppSettings.set(AppSettings.KEY_FLOATING_TIMER_OPEN, false);
        refreshTimerWindowButton();

        // Re-render the sidebar clock immediately from the same deadline. Not
        // strictly necessary - the tick has been updating it all along - but it
        // makes the docked state correct even if the tick was somehow starved
        // while the dashboard was hidden, and it costs one write.
        if (pomodoroEndsAt != null) {
            updatePomodoroDisplay(FocusTimerState.remainingSeconds(pomodoroEndsAt, Instant.now()));
        } else if (pomodoroFrozenRemaining > 0) {
            updatePomodoroDisplay(pomodoroFrozenRemaining);
        }
        statusBarLabel.setText("Focus timer docked.");
    }

    /**
     * Pushes one already-computed tick value to the widget.
     *
     * <p>Takes the value as a parameter rather than recomputing it. That is the
     * single most important line in this integration: if the widget computed its
     * own remaining time from its own {@code Instant.now()}, the two views
     * would differ by the microseconds between the two calls - which, rounded
     * to whole seconds, is a visible one-second disagreement roughly half the
     * time.
     */
    /**
     * The session's phase as a SINGLE value.
     *
     * <p>Both views are rendered from this, which is what makes the widget's
     * Start/Resume/Pause behave identically to the sidebar's. Previously the
     * widget was handed only a {@code running} boolean and had to infer the rest,
     * and inferred it wrong in a way that broke the buttons outright.
     *
     * <p>IDLE is the case that matters: a session is not live only when there is
     * no countdown AND nothing to resume, so the frozen-remaining check has to
     * come first. Getting that order wrong reports a paused session as idle and
     * offers "Start" - which discards the paused work by implication, since the
     * sidebar's toggle treats an idle state as start-from-scratch.
     */
    private FocusTimerState.Phase timerPhase() {
        if (pomodoroRunning) {
            return FocusTimerState.Phase.RUNNING;
        }
        if (pomodoroFrozenRemaining > 0) {
            return FocusTimerState.Phase.PAUSED;
        }
        return FocusTimerState.Phase.IDLE;
    }

    private void pushTickToFloatingTimer(long remainingSeconds) {
        if (floatingTimer == null || !floatingTimer.isShowing()) {
            return;
        }
        floatingTimer.controller().update(
                remainingSeconds,
                pomodoroTotalSeconds,
                timerPhase(),
                floatingTimerSkillName(),
                floatingTimerStateText());
    }

    private String floatingTimerSkillName() {
        Skill target = findSkillById(pomodoroTargetSkillId);
        if (target != null) {
            return target.getName();
        }
        return selectedSkill == null ? "No skill selected" : selectedSkill.getName();
    }

    /** The short status line under the widget's buttons. */
    private String floatingTimerStateText() {
        if (pomodoroRunning) {
            double points = pointsForDuration(Math.max(1, pomodoroTotalSeconds / 60));
            return "Running - worth " + trimNumber(points) + " pts";
        }
        if (pomodoroFrozenRemaining > 0) {
            return "Paused at " + FocusTimerState.formatClock(pomodoroFrozenRemaining);
        }
        return "Ready - " + FocusTimerState.formatClock(pomodoroTotalSeconds);
    }

    /**
     * Called from every transition so the widget's dimmed styling tracks the real
     * state rather than being inferred from the button label.
     *
     * <p>Derived from the phase, so PAUSED and IDLE cannot be confused: both are
     * "not running", and dimming an idle widget would imply a session is waiting
     * to be resumed when there is nothing to resume.
     */
    private void syncFloatingTimerEmphasis() {
        if (floatingTimer != null && floatingTimer.isShowing()) {
            floatingTimer.controller().setPausedLook(timerPhase() == FocusTimerState.Phase.PAUSED);
        }
    }

    // -----------------------------------------------------------------
    //  Settings
    // -----------------------------------------------------------------

    /**
     * Opens the v2.0 Settings dialog.
     *
     * <p>Every setting in it is write-through, so by the time this returns the
     * application is already running with whatever the user changed. Nothing
     * here needs to re-read anything, and there is no Apply button precisely
     * because there is nothing left to apply.
     *
     * <p>The dashboard is REFRESHED on close, not on every keystroke. The
     * settings themselves take effect immediately (Anim and UiScale read them
     * live), but the parts of the dashboard that were computed from a setting -
     * the level badge's "points to next level" figure, the duration dropdown's
     * selection - are cheap to recompute and not worth a full chart re-render
     * per spinner tick.
     */
    @FXML
    private void handleOpenSettings() {
        Scene scene = rootPane.getScene();
        SettingsDialogController.show(scene);

        // Re-sync whatever the settings can influence. Each of these is
        // individually cheap; the chart re-render is the only heavy one, and it
        // is needed because a level-threshold change can move the badge and a
        // duration change can alter the timer's target.
        if (timerDurationCombo != null) {
            int minutes = timerMinutes();
            if (updatingTimerCombo) {
                // Already inside a repopulate; the listener will pick it up.
                updatingTimerCombo = true;
                timerDurationCombo.setValue(FocusTimerState.formatDuration(minutes));
                updatingTimerCombo = false;
            } else {
                timerDurationCombo.setValue(FocusTimerState.formatDuration(minutes));
            }
        }
        // lastKnownLevel = -1 suppresses the level-up fanfare, because changing
        // the points-per-level threshold can legitimately move the level and
        // that is a configuration change, not an achievement.
        lastKnownLevel = -1;
        refreshLevelBadge();
        if (!isTimerSessionLive()) {
            reloadTimerDuration();
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    private void updatePomodoroDisplay(long secondsLeft) {
        if (pomodoroTimeLabel == null) return;
        pomodoroTimeLabel.setText(FocusTimerState.formatClock(secondsLeft));
        // Progress is derived from the session's OWN total, not the currently
        // configured duration - otherwise changing the dropdown mid-session
        // would move the bar's endpoints and misreport how far along it is.
        pomodoroProgressBar.setProgress(
                FocusTimerState.progress(secondsLeft, pomodoroTotalSeconds));
    }

    /** Font size for the clock, now factoring in the UI scale tier as well as
     *  the pane width. Package-private and static purely so the self-check can
     *  call it without standing up a JavaFX scene. */
    static double clockFontFor(double paneWidth) {
        return UiScale.clockFontSize(AppSettings.uiScale(), paneWidth, CLOCK_FONT_MIN, CLOCK_FONT_MAX);
    }

    // =================================================================
    //  TIMER + LEVEL CONFIGURATION  (persisted in app_settings)
    // =================================================================

    /** Currently selected timer length, from the cached settings layer. */
    private int timerMinutes() {
        return AppSettings.timerMinutes();
    }

    /** Points for a completed session of {@code minutes}.
     *  @see AppSettings#pointsForDuration(int) - the arithmetic and its
     *      asserted mirror live there. */
    private double pointsForDuration(int minutes) {
        return AppSettings.pointsForDuration(minutes);
    }

    /** Points per level. @see AppSettings#pointsPerLevel() */
    private double pointsPerLevel() {
        return AppSettings.pointsPerLevel();
    }

    /**
     * Item 2.2's configuration half: the reward for the CURRENT duration, the
     * baseline reward used to scale every other duration, and the points
     * needed per level.
     *
     * <p>No schema change: all three land in the existing generic app_settings
     * table, so the user's historical log data is untouched. Only the selected
     * duration gets an explicit override; the baseline scales the rest
     * proportionally, so the common case - "a 25-minute block is worth 5, make
     * the others make sense" - is one number.
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
                0, 1000, AppSettings.getDouble(AppSettings.KEY_TIMER_DEFAULT_POINTS,
                        AppSettings.DEFAULT_TIMER_POINTS), 0.5));
        baseline.setEditable(true);

        Spinner<Double> perLevel = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1, 100000, pointsPerLevel(), 10));
        perLevel.setEditable(true);

        Label explain = new Label("Durations without their own reward are scaled from the baseline, "
                + "so " + AppSettings.DEFAULT_TIMER_MINUTES + " min = baseline.");
        explain.setWrapText(true);
        explain.getStyleClass().add("metric-label");
        explain.setMaxWidth(320);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Points for " + FocusTimerState.formatDuration(minutes)), thisDuration);
        form.addRow(1, new Label("Baseline (per " + AppSettings.DEFAULT_TIMER_MINUTES + " min)"), baseline);
        form.addRow(2, new Label("Points per level"), perLevel);
        form.add(explain, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStylesheets()
                .add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("glass-panel");

        dialog.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            AppSettings.set(AppSettings.KEY_TIMER_POINTS_PREFIX + minutes, thisDuration.getValue());
            AppSettings.set(AppSettings.KEY_TIMER_DEFAULT_POINTS, baseline.getValue());
            AppSettings.set(AppSettings.KEY_POINTS_PER_LEVEL, perLevel.getValue());
            // The badge reads pointsPerLevel() live, so the level can move
            // here; suppress the fanfare because lowering the threshold is not
            // an achievement.
            lastKnownLevel = -1;
            refreshLevelBadge();
            statusBarLabel.setText(FocusTimerState.formatDuration(minutes) + " is now worth "
                    + trimNumber(thisDuration.getValue()) + " pts, "
                    + trimNumber(perLevel.getValue()) + " pts per level.");
        });
    }


    /** Global sound on/off, persisted so it survives a restart. */
    @FXML
    private void handleToggleMute() {
        boolean muted = muteToggle.isSelected();
        SoundPlayer.setMuted(muted);
        AppSettings.set(AppSettings.KEY_MUTED, muted);
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

        // v2.0 FIX - THE N+1 IN THE STATUS PASS. The status column was the one
        // field on Skill that genuinely had to be persisted (exports and the
        // status dot read it), so it cannot be folded into the derived-points
        // recompute the way current_points was. That made this a loop of one
        // UPDATE per CHANGED skill, each its own implicit commit on the FX
        // thread.
        //
        // The quiet case is fine, but the loud case is the one users hit: log
        // anything after a fortnight away, or import a file, and every skill
        // flips at once - N round trips, all synchronous, before the UI
        // redraws. That is the freeze.
        //
        // So the in-memory model is still updated per skill (it has to be - the
        // statuses drive bound labels), but persistence is BATCHED BY TARGET
        // STATUS. There are only two possible statuses, so a change set that
        // previously cost N commits now costs at most TWO, regardless of how
        // many skills are involved.
        //
        // Grouping by status rather than issuing one statement per skill matters
        // because a single mixed batch cannot be expressed as one statement:
        // different rows need different values. Splitting on the value is what
        // turns N statements into exactly 2.
        List<Integer> becameStalled = new ArrayList<>();
        List<Integer> becameActive = new ArrayList<>();
        for (Skill s : skills) {
            LocalDate last = lastActivity.get(s.getId());
            boolean stale = last != null && last.isBefore(cutoff);
            String next = stale ? Skill.STATUS_STALLED : Skill.STATUS_ACTIVE;
            if (!next.equals(s.getStatus())) {
                s.setStatus(next);
                (stale ? becameStalled : becameActive).add(s.getId());
            }
        }
        int changed = becameStalled.size() + becameActive.size();
        if (!becameStalled.isEmpty()) {
            db.setSkillStatuses(becameStalled, Skill.STATUS_STALLED);
        }
        if (!becameActive.isEmpty()) {
            db.setSkillStatuses(becameActive, Skill.STATUS_ACTIVE);
        }
        if (changed > 0) {
            statusBarLabel.setText(changed + " skill" + (changed == 1 ? "" : "s")
                    + " re-evaluated for inactivity (" + STALLED_AFTER_DAYS + "-day rule).");
        }
    }

    /**
     * Everything that has to be recomputed after points change: decay status,
     * streaks, level, and the charts that read aggregates rather than the
     * selected skill. Called from every log/undo path so none of them can
     * drift out of sync.
     *
     * <p>INCLUDES {@link #refreshVisualization()}, deliberately. Several call
     * sites used to follow this with their own {@code refreshVisualization()},
     * on the reasonable but wrong assumption that the two were separate steps -
     * so a single "Log Session" click rendered the whole canvas twice and did
     * roughly double the database work behind it. The visible result was a
     * hitch on every action. Keeping the render inside this one method means
     * the guarantee is "one render per change" by construction rather than by
     * every call site remembering not to add its own.
     */
    private void afterLogChanged() {
        applyStalledStatuses();
        refreshStreakLabels();
        refreshLevelBadge();
        // The ETA depends on the trailing window, which a log just changed, so
        // it has to be recomputed here or it would show a rate that is one
        // session out of date. Bound rather than re-queried per frame, since
        // afterLogChanged runs on user actions only.
        refreshEta();
        refreshVisualization();
    }

    /**
     * v2.0: refreshes the projected completion date for the selected skill.
     *
     * <p>WHY THIS IS THE HIGHEST-VALUE NUMBER IN THE APP. A percentage is
     * descriptive and a target is aspirational, but neither is actionable.
     * "Finishes around 14 Nov" answers the only question a progress tracker
     * exists to answer: am I on track, and what does stopping cost me?
     *
     * <p>WHY IT REPORTS AN EXPLANATION RATHER THAN A BLANK WHEN IT CANNOT
     * PROJECT. The projection is driven by the skill's own trailing 14-day
     * rate, which is the only honest basis for it. A skill with no recent
     * activity has a rate of zero, and dividing by zero would produce a date
     * days or months in the past - telling the user a stalled skill is nearly
     * finished. So the zero-rate case is reported as the stall it IS. "Stalled
     * - log to get an estimate" is a true, useful statement; "finishes in 3
     * days" for a skill nobody has touched in a month is a lie that happens to
     * look encouraging.
     *
     * <p>Uses the MOCKED today, not the system date, so the calendar's
     * right-click "pretend this is today" feature moves the projection with it.
     * A projection that disagreed with the calendar's highlighted day would
     * look broken.
     */
    private void refreshEta() {
        if (etaLabel == null) return;
        if (selectedSkill == null) {
            etaLabel.setText("No skill selected");
            etaLabel.getStyleClass().remove("eta-reached");
            return;
        }
        Skill skill = selectedSkill;
        if (skill.getTargetPoints() <= 0) {
            etaLabel.setText("No target set");
            etaLabel.getStyleClass().remove("eta-reached");
            return;
        }
        if (skill.getCurrentPoints() >= skill.getTargetPoints()) {
            etaLabel.setText("Reached");
            etaLabel.getStyleClass().add("eta-reached");
            return;
        }
        LocalDate projected =
                db.getProjectedCompletionDate(skill.getId(), mockToday, ETA_WINDOW_DAYS);
        if (projected == null) {
            // The interesting branch: no recent activity, or a projection so far
            // out that the trailing-window assumption no longer describes
            // reality. Both mean the same thing to the user: there is no
            // meaningful rate right now.
            etaLabel.setText("Needs activity");
            etaLabel.getStyleClass().remove("eta-reached");
            etaLabel.setTooltip(new Tooltip(
                    "No sessions in the last " + ETA_WINDOW_DAYS
                    + " days, so there is no rate to extrapolate from. "
                    + "Log a session and an estimate will appear."));
            return;
        }
        etaLabel.getStyleClass().remove("eta-reached");
        // Guard the tooltip: it may still hold the "needs activity" text from a
        // previous selection, which would now be wrong.
        etaLabel.setTooltip(new Tooltip("Projected from the last " + ETA_WINDOW_DAYS
                + " days of activity. Log regularly for a more accurate estimate."));
        long days = ChronoUnit.DAYS.between(mockToday, projected);
        etaLabel.setText(projected.format(ETA_DATE_FMT)
                + "  (" + (days <= 0 ? "today" : days + "d") + ")");
    }

    /** Trailing window the ETA extrapolates from, in days. Matches the window
     *  documented on DatabaseHelper#getProjectedCompletionDate. */
    private static final int ETA_WINDOW_DAYS = 14;
    private static final DateTimeFormatter ETA_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

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

    /**
     * Live search over note titles + markdown bodies. Typing "#java" lists
     * every date that tag appears on; clearing the box restores the normal
     * selected-date view.
     *
     * <p>DEBOUNCED, and that is not a nicety. The v1 version re-queried and
     * RE-RENDERED on every keystroke, and because each rendered note card owns
     * a {@link WebView} - a native WebKit page - typing a six-character query
     * against thirty matching notes created and abandoned a hundred and eighty
     * WebKit pages in under two seconds. The memory spike was visible per
     * character and the UI stuttered on each one.
     */
    private void setupNoteSearch() {
        if (noteSearchField == null) return;
        // Restarts on every keystroke, so only the FINAL query survives. A
        // PauseTransition is the right tool: it needs no thread and is
        // cancelled by simply calling play() again.
        final javafx.animation.PauseTransition debounce =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(220));
        debounce.setOnFinished(e -> {
            String text = noteSearchField.getText();
            if (text == null || text.isBlank()) {
                refreshNotesForSelectedDate();
            } else {
                showSearchResults(text);
            }
        });
        noteSearchField.textProperty().addListener((obs, old, text) -> {
            // A cleared box should feel instant - the user is dismissing the
            // search, not refining it - so it bypasses the debounce.
            if (text == null || text.isBlank()) {
                debounce.stop();
                refreshNotesForSelectedDate();
                return;
            }
            debounce.play();
        });
    }

    private void showSearchResults(String query) {
        // A stale in-flight search must not be able to render over a newer one.
        if (lastSearchQuery != null && lastSearchQuery.equals(query)) {
            return;
        }
        lastSearchQuery = query;
        disposeNoteCards(notesContainer);
        List<CalendarNote> hits = db.searchNotes(query);
        selectedDateLabel.setText("Search: \"" + query.trim() + "\"");

        if (hits.isEmpty()) {
            Label empty = new Label("No notes match \"" + query.trim() + "\".");
            empty.getStyleClass().add("empty-notes-label");
            notesContainer.getChildren().add(empty);
            return;
        }

        Label summary = new Label(hits.size() + " note" + (hits.isEmpty() ? "" : "s") + " found");
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

    /**
     * The query whose results are currently on screen.
     *
     * <p>Exists purely to suppress a redundant rebuild. The debounce already
     * prevents a burst of identical queries, but re-selecting the same
     * ComboBox value, or a paste event that fires the listener twice, can still
     * ask for the same list - and rebuilding it means disposing and recreating
     * a WebView per note for no visible change.
     */
    private String lastSearchQuery;

    /**
     * Releases and clears a container of note cards.
     *
     * <p><b>WHY A BARE {@code getChildren().clear()} IS A LEAK HERE.</b> Every
     * note card owns a {@link WebView}. Dropping the JavaFX Node reference does
     * NOT free it: a WebView is a handle on a native WebKit page, and the page
     * - its DOM, its JS heap, its rendered content - stays alive until the
     * engine is explicitly torn down. The consequence without this method is
     * severe and easy to miss: memory climbs monotonically for the whole
     * session, WebKit helper processes multiply, and clicking through a month
     * of calendar days is enough to trigger it.
     *
     * <p><b>WHAT THIS ACTUALLY DOES, AND THE LIMITATION.</b> JavaFX 25 exposes
     * no public way to dispose a {@code WebEngine} - {@code dispose()} exists
     * but is package-private, so it cannot be called from application code. The
     * available mitigations are therefore applied in order of how much they
     * actually reclaim:
     * <ol>
     *   <li><b>Detach the JS bridge.</b> {@code javaCheckboxBridge} is a member
     *       of the JavaScript {@code window} object, so while it is installed
     *       it pins a live {@code CheckboxBridge} - and through it the
     *       {@code DatabaseHelper} singleton and the note model - to the JS
     *       heap. Setting it to null breaks that reference DETERMINISTICALLY,
     *       which is the part of the leak that would otherwise survive
     *       indefinitely.</li>
     *   <li><b>Load {@code about:blank}.</b> This replaces the loaded document,
     *       releasing the page, its JS heap and its rendered content - which is
     *       by far the largest share of the native memory. It is the practical
     *       equivalent of disposal.</li>
     *   <li><b>Disable JavaScript</b> so the replacement page cannot allocate
     *       anything, and so a stray late callback cannot touch a disposed
     *       engine.</li>
     *   <li><b>Drop the node reference.</b> The now-nearly-empty engine itself
     *       becomes unreachable and is reclaimed by the GC. That step is NOT
     *       deterministic, and this method does not pretend otherwise - it
     *       removes the unbounded, long-lived growth, which is the part that
     *       actually hurt.</li>
     * </ol>
     *
     * <p>Walked recursively rather than assuming a direct-child shape, because
     * cards nest (card -> header row -> buttons) and a WebView could legally
     * sit at any depth. The identity set guards against a cycle a future layout
     * change could introduce.
     */
    private static void disposeNoteCards(javafx.scene.layout.Pane container) {
        if (container == null) return;
        java.util.Set<Node> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        List<WebView> webViews = new ArrayList<>();
        collectWebViews(container, webViews, seen);
        // Clear the list FIRST, so the nodes are detached even if a release
        // below throws - otherwise one bad engine leaves the whole list shown.
        container.getChildren().clear();
        for (WebView webView : webViews) {
            try {
                javafx.scene.web.WebEngine engine = webView.getEngine();
                // 1. Break the JS -> Java reference, which is what pins the
                //    database and the note model.
                engine.setJavaScriptEnabled(false);
                engine.executeScript(
                        "try { window.javaCheckboxBridge = null; } catch (e) { }");
                // 2. Drop the loaded document and its native resources.
                engine.load("about:blank");
            } catch (RuntimeException alreadyGone) {
                // An engine that was never loaded, or has already been torn
                // down, can throw here. It is already in the state we want, so
                // this must not abort the remaining releases.
                System.err.println("[DashboardController] WebEngine release skipped: "
                        + alreadyGone.getMessage());
            }
        }
    }

    private static void collectWebViews(Node node, List<WebView> found, java.util.Set<Node> seen) {
        if (node == null || !seen.add(node)) {
            return;
        }
        if (node instanceof WebView webView) {
            found.add(webView);
            // Do not descend into a WebView: its internals are not scene-graph
            // children we own, and walking them is pointless work.
            return;
        }
        if (node instanceof javafx.scene.layout.Pane pane) {
            for (Node child : pane.getChildren()) {
                collectWebViews(child, found, seen);
            }
        } else if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                collectWebViews(scroll.getContent(), found, seen);
            }
        } else if (node instanceof javafx.scene.control.ListView<?> list) {
            // Virtualised cells are not children, so there is nothing to walk.
            // Listed explicitly so a future reader knows the case was considered.
            return;
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
        // Disposes the WebViews the outgoing cards own. A bare clear() leaks
        // one native WebKit page per pinned note, every time this runs.
        disposeNoteCards(pinnedNotesContainer);
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
    /**
         * Makes the chart divider a comfortable drag target - and only in the
         * modes that have anything to divide.
         *
         * <p><b>THE PROBLEM.</b> The divider between the zoom/spacing toolbar and
         * the canvas was a 1-2px visible line, and that was also the only part of
         * the window where the cursor became {@code v-resize}. Catching it meant
         * putting the pointer on a hairline, which is why resizing the chart in
         * Comb-Shaped, Skill-Tree and Radar felt impossible.
         *
* <p><b>WHERE THE THICKNESS ACTUALLY COMES FROM.</b> JavaFX 25's SplitPane
     * has no divider-thickness property - checked with javap, not assumed - so the
     * only lever is CSS. Which CSS matters is not guessable: SplitPaneSkin lays a
     * vertical divider out with
     * {@code divider.resize(paneWidth, dividers.get(0).prefWidth(-1))}, so the
     * splitter's thickness is read from the divider's prefWIDTH. The HORIZONTAL
     * padding in styles.css therefore sets the grab HEIGHT, while the vertical
     * background insets paint the hairline inside it.
     *
     * <p>An earlier version of that rule used vertical padding, which contributes
     * nothing to prefWidth, so the divider measured 975x0.000 - no hit area at
     * all. {@code -fx-min-height}, {@code -fx-pref-height} and
     * {@code -fx-max-height} were each tried and each still measured 0.000px,
     * because the skin reads only the one property.
     *
     * <p>This method deliberately sets no geometry. The handle is sized entirely by
     * CSS so that it scales with the interface tier; {@code pickOnBounds} is set
     * here because it is a Node property with no CSS equivalent.
         *
         * <p><b>WHY RESOLUTION IS LAZY.</b> The styled divider node is created by
         * the SplitPane's own skin, so it does not exist until the control has been
         * styled. An earlier version looked it up during {@code initialize()} and
         * retried with {@code Platform.runLater} - which is exactly the wrong shape:
         * a harness that pumps the FX thread from inside itself can never run that
         * posted runnable, so the handle would have been silently absent under test
         * while working fine in the app. Resolving on first use instead is
         * deterministic in both.
         *
         * <p><b>SCOPING.</b> The divider only means anything when the top pane has
         * content to divide. In Velocity, Time Split, Curve and I-Shaped there is no
         * filter list, so it is hidden and non-interactive there. Its height is
         * left alone rather than zeroed: the node belongs to the skin, and
         * collapsing a skin-owned layout box is a good way to get a SplitPane that
         * stops laying out correctly.
         */
        private void syncChartResizeHandle() {
            if (mainSplitPane == null) {
                return;
            }
            if (chartResizeDivider == null) {
                javafx.scene.Node found = mainSplitPane.lookup(".split-pane-divider");
                if (found == null) {
                    // Skin not built yet. The next visibility change will try again.
                    return;
                }
                chartResizeDivider = found;
                chartResizeDivider.setPickOnBounds(true);
                if (!chartResizeDivider.getStyleClass().contains("chart-resize-divider")) {
                    chartResizeDivider.getStyleClass().add("chart-resize-divider");
                }
            }

            //
            // Visibility is read from the FILTER's own managed flag rather than from
            // the selected toggle button. That flag is the single source of truth for
            // whether the top pane has content, it is already correct by the time a
            // chart switch finishes, and asking the ToggleGroup instead would be a
            // second copy of the same fact that could drift the first time a mode's
            // controls changed.
            //
            boolean multiSkill = filterScrollPane != null && filterScrollPane.isManaged();

            chartResizeDivider.setVisible(multiSkill);
            // Non-interactive as well as invisible: an invisible-but-pickable
            // divider would keep swallowing clicks aimed at the chart underneath it,
            // which is the failure mode a plain setVisible(false) leaves behind.
            chartResizeDivider.setMouseTransparent(!multiSkill);
            chartResizeDivider.setPickOnBounds(multiSkill);
    }

    /** The skin-owned divider node, resolved on first use by syncChartResizeHandle. */
    private javafx.scene.Node chartResizeDivider = null;

    private void setupResponsiveDivider() {
        if (chartControlsPane == null || mainSplitPane == null) return;

        // prefHeight(-1) = "how tall does this pane's visible content actually
        // want to be", which is 0 when both rows are hidden and grows as they
        // appear. Region.USE_COMPUTED_SIZE would give the same number here.
        Runnable anchor = () -> reanchorDivider(false);

        // THE POST-BACK DEBOUNCE. These listeners used to post a Runnable to the
        // FX queue on EVERY height change, and a window-resize drag changes the
        // height on every single frame. That is two queue posts per frame for
        // the whole duration of the drag: the queue grows faster than it
        // drains, the divider visibly lags the pointer, and the app feels
        // sticky while being resized. Worse, the queued work runs AFTER the
        // current pulse, so it lands on stale geometry.
        //
        // Both listeners now share ONE PauseTransition. Calling play() on an
        // already-running PauseTransition RESTARTS it, so a burst of resize
        // events collapses into a single post once the user stops moving - and
        // the post happens on the next idle, against up-to-date geometry.
        // Coalescing rather than dropping is the right trade: dropping entirely
        // would miss the final resize position, which is the one that matters.
        final javafx.animation.PauseTransition coalesce =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(16));
        coalesce.setOnFinished(e -> anchor.run());
        chartControlsPane.heightProperty().addListener((obs, o, n) -> coalesce.play());
        mainSplitPane.heightProperty().addListener((obs, o, n) -> coalesce.play());

        // Remember where the USER put the divider - but only while the controls
        // it controls are on screen. See reanchorDivider.
        //
        // getDividers() is an ObservableList and only takes an InvalidationListener,
        // which fires for any divider changing; the position itself is what
        // matters, so each Divider's positionProperty is listened to directly.
        if (!mainSplitPane.getDividers().isEmpty()) {
            mainSplitPane.getDividers().get(0).positionProperty().addListener((obs, o, pos) -> {
                if (writingDivider) {
                    return; // our own write; not a user preference
                }
                if (filterScrollPane != null && filterScrollPane.isManaged()) {
                    multiSkillDividerRatio = clamp(pos.doubleValue(), 0.0, 0.5);
                }
            });
        }

        Platform.runLater(anchor);
    }

    /**
     * Puts the divider where the controls' current preferred height says it
     * should be.
     *
     * <p><b>WHY THIS HAS TO BE CALLED EXPLICITLY, NOT ONLY FROM A LISTENER.</b>
     * The reported bug was that returning from Comb-Shaped/Skill-Tree/Radar to
     * Velocity/Time Split/Curve left the chart short with a large empty band
     * above it. The cause is a deadlock in the original design:
     *
     * <ul>
     *   <li>the re-anchor was triggered by {@code chartControlsPane}'s
     *       HEIGHT changing;</li>
     *   <li>but that pane is declared {@code resizableWithParent="false"}, so
     *       SplitPane holds it at its current PIXEL height - and hiding the
     *       filter list does not change a pixel height, only a preferred
     *       one;</li>
     *   <li>so hiding the controls produced no height change, the listener never
     *       fired, the divider stayed where it was, and the now-empty top pane
     *       kept occupying that fraction of the height.</li>
     * </ul>
     *
     * <p>Nothing threw and nothing looked broken; the chart was simply laid out
     * in whatever space was left over. So the visibility toggles now call this
     * directly instead of hoping a listener notices.
     *
     * @param force when true the "did the preferred height change" guard is
     *              bypassed, which is what an explicit call after a visibility
     *              change needs - the content has changed by definition, and the
     *              guard exists only to stop a resize or a drag from fighting us
     */
    private void reanchorDivider(boolean force) {
        if (chartControlsPane == null || mainSplitPane == null) {
            return;
        }
        double total = mainSplitPane.getHeight();
        if (total <= 0) {
            return; // not laid out yet; the listeners will catch up
        }
        double wanted = chartControlsPane.prefHeight(-1);

        // ITEMS 4 + 5. Only re-anchor on a listener when the CONTENT's
        // preferred height actually changed. Without this guard:
        //
        //  (4) dragging the divider changes this pane's actual height, so the
        //      listener fired mid-drag and yanked the divider back;
        //  (5) on window resize the listener re-applied a ratio on top of
        //      SplitPane's own resizableWithParent behaviour - two mechanisms
        //      writing one divider, which is why it held at 1080p and broke at 2K.
        //
        // Epsilon rather than != because prefHeight is a computed double and
        // sub-pixel jitter would defeat an exact comparison.
        if (!force && Math.abs(wanted - lastAnchoredControlsHeight) < 0.5) {
            return;
        }
        lastAnchoredControlsHeight = wanted;

        //
        // Whether the FULL multi-skill controls are on screen. This is what
        // decides whether a remembered divider means anything.
        //
        // The filter list is the distinguishing part. The multi-skill modes
        // show filter + toolbar; Time Split shows the TOOLBAR ONLY. Both are
        // "controls visible", so wanted > 0 for both - but the panes are
        // different sizes, so a ratio remembered in one is wrong in the other.
        // Applying a 0.35 divider dragged in Comb-Shaped to Time Split gave its
        // 31px toolbar a 162px half of the window and squashed the chart to
        // 302px: the same dead band, reached by a different route.
        boolean fullMultiSkillControls = filterScrollPane != null
                && filterScrollPane.isManaged();

        double ratio;
        if (wanted <= 0) {
            // No controls on screen: the chart gets the whole column.
            //
            // Stated explicitly rather than left to the arithmetic below, and it
            // does read as the fix for the dead band - but measured, it is not
            // what fixes it. Mutation of this branch alone changes nothing,
            // because clamp(0 / total) is also 0. The correction that matters
            // is that reanchorDivider is CALLED at all when visibility changes;
            // see reanchorAfterControlsChange.
            //
            // What the branch does guarantee is that a remembered multi-skill
            // divider can never be applied here, whatever the arithmetic below
            // would otherwise pick.
            ratio = 0.0;
        } else if (fullMultiSkillControls && multiSkillDividerRatio >= 0) {
            // Honour a divider the user dragged while the full multi-skill
            // controls were up, and only there.
            ratio = multiSkillDividerRatio;
        } else {
            // Never let the controls take more than half: a long filter list
            // must not squeeze the chart out of existence.
            ratio = clamp(wanted / total, 0.0, 0.5);
        }
        writingDivider = true;
        try {
            mainSplitPane.setDividerPositions(ratio);
        } finally {
            writingDivider = false;
        }
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
            String key = AppSettings.KEY_SIDEBAR_PREFIX + pane.getId();
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
            // THE FEEDBACK LOOP THAT WAS NOT ACTUALLY BROKEN.
            //
            // centerCanvasScroll sets hvalue/vvalue. Setting either can summon
            // or dismiss a scrollbar, which changes the viewport, which
            // re-enters THIS listener, which calls centerCanvasScroll again.
            // The 2px threshold above damps the width/height half of that loop
            // but the recentring half was previously unguarded, so on first
            // load and on some resizes the canvas visibly oscillated for a few
            // frames instead of settling.
            //
            // The fix is re-entrancy suppression, not a bigger epsilon: a
            // recentre that we ourselves initiated must not be able to trigger
            // another one. While the flag is set, hvalue/vvalue changes are
            // recognised as our own and ignored. The flag is cleared on the
            // next pulse, so a genuine user scroll is never suppressed.
            if (!centringCanvas) {
                centreCanvasOnce();
            }
        });
    }

    /** Guards against {@link #centreCanvasOnce} re-triggering itself through
     *  the viewport listener. See the call site for the full explanation. */
    private boolean centringCanvas = false;

    /**
     * Centres the canvas scroll pane exactly once per burst of viewport
     * changes, instead of once per change.
     *
     * <p>IMPORTANT: this only suppresses a RE-ENTRANT recentre. It deliberately
     * does not gate on the pane being visible, because "hidden" is exactly the
     * state in which a viewport change is most likely to be spurious, and the
     * original code's visible-check was incidental damping rather than a real
     * guard. A hidden pane has no work to do and centreCanvasScroll() is a
     * cheap property write, so gating on visibility would only hide the
     * symptom.
     */
    private void centreCanvasOnce() {
        if (viewPinnedByUser || centringCanvas) {
            return;
        }
        centringCanvas = true;
        centerCanvasScroll();
        // Cleared on the next pulse, by which time any scrollbar churn the
        // recentre caused has already been absorbed - and a real user scroll
        // arriving after that is handled normally.
        PauseTransition release = new PauseTransition(javafx.util.Duration.millis(1));
        release.setOnFinished(e -> centringCanvas = false);
        release.play();
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

    // NOTE: the old clockFontFor(double) that used to live here has been
    // replaced by the scale-aware version in the Focus Timer section. It now
    // factors in the active UiScale tier as well as the pane width, so a user
    // who has chosen a larger interface on a 4K display does not end up with a
    // 4K-sized panel containing a 1K-sized clock. SqlLogicCheck mirrors the new
    // formula - keep the two in step.

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
        // v2.0 FIX. This was Math.max(4, barTopYInViewport - 18), and the
        // clamp was the bug rather than the protection. Once the user scrolled
        // down far enough for the bar's top edge to approach the top of the
        // viewport, the computed margin went negative and the clamp pinned it
        // at 4px - after which the label STOPPED TRACKING the bar entirely and
        // parked near the top of the chart area, no matter how much further the
        // user scrolled. The javadoc above promised it tracked the bar's actual
        // on-screen position, and after ~14px of scroll it did not.
        //
        // A negative margin is not an error: it is the correct StackPane offset
        // for "this label belongs above a bar that is currently partly above
        // the viewport", which is precisely what happens while scrolling.
        // StackPane applies the margin and then clips; the label slides out of
        // view along with the bar it is attached to, which is what a label
        // pinned to a bar should do. Clamping it instead decoupled the two.
        double labelMarginTop = barTopYInViewport - 18; // sit ~18px above the bar's top edge
        StackPane.setMargin(breadthLabel, new Insets(labelMarginTop, 0, 0, 0));

        double viewportWidth = structuralCanvasScroll.getViewportBounds().getWidth();
        double canvasWidth = structuralCanvas.getWidth();
        double scrollableWidth = Math.max(0, canvasWidth - viewportWidth);
        double scrollOffsetX = structuralCanvasScroll.getHvalue() * scrollableWidth;

        double barMidXInCanvas = canvasWidth / 2.0; // true midpoint of the breadth bar - see javadoc above
        double barMidXInViewport = barMidXInCanvas - scrollOffsetX;
        double viewportCenterX = viewportWidth / 2.0;
        // TOP_CENTER already puts the label at viewportCenterX; translateX nudges
        // it from there to the bar's true midpoint, whatever that currently is.
        //
        // The translate is now REPLACED rather than accumulated, and the margin
        // above likewise overwrites the FXML's <Insets top="22"/>. Both were
        // already overwrites, so there is no change in kind - but the previous
        // code left the FXML's own value as the thing a reader would find first,
        // which is a small trap for whoever edits the FXML next.
        breadthLabel.setTranslateX(barMidXInViewport - viewportCenterX);
    }

    /**
     * Re-reads the parent_id-derived depth map that the indented ComboBox cells
     * render from.
     *
     * <p>THE STALE-CACHE BUG THIS CLOSES. {@code skillDepths} was populated in
     * exactly one place - {@link #loadSkillsFromDatabase()} - so it went stale
     * on every structural change:
     * <ul>
     *   <li>Create a Subskill under a Category and it appeared in the dropdown
     *       rendered as a bold "◆ Category" at indent 0, because the new id was
     *       absent from the map and {@code getOrDefault} supplied 0;</li>
     *   <li>Re-parent a skill via Edit and its dropdown indent did not change
     *       at all, even though the tree view updated immediately.</li>
     * </ul>
     * Both corrected themselves only after a manual Refresh, which is why the
     * behaviour looked intermittent rather than broken.
     *
     * <p>Centralised so no future caller can forget: the map is now refreshed at
     * every site that adds, edits or deletes a skill.
     */
    private void refreshSkillDepths() {
        skillDepths = db.getSkillDepths();
    }

    /**
     * Reloads the skill list from the database, keeping the current selection.
     *
     * <p><b>THE SELECTION BUG THIS FIXES.</b> The old body ended with
     * {@code selectFirst()}, because on the very first load - when nothing is
     * selected yet - "select the first row" is the right thing to do. The
     * problem is that the same line also ran on every RELOAD, so hitting
     * Refresh while working on the fifth skill silently moved the selection to
     * whichever skill happened to sort first. Everything bound to
     * {@code selectedSkill} followed it: the metrics showed another skill's
     * numbers, the chart drew another skill's curve, and the status toggles
     * showed another skill's status.
     *
     * <p>What made it read as data corruption rather than a selection bug is
     * that nothing appeared to be wrong on the surface - the list refreshed
     * correctly, the counts were right - but the user was suddenly looking at
     * the wrong skill's progress. The values on screen disagreed with the row
     * they had clicked a moment earlier, with no way to get back to it except
     * clicking again. Refresh is supposed to be a no-op from the user's point
     * of view, so "no-op" has to include the selection.
     *
     * <p>The id is captured BEFORE the list is replaced, because the whole
     * point is that {@code setAll} throws away every old {@code Skill} instance
     * and builds new ones. Matching on the id is what survives that; matching
     * on object identity or on {@code equals} would not, and would silently
     * degrade back to select-first.
     *
     * <p>Two cases have to fall back, and both are real rather than
     * theoretical:
     * <ul>
     *   <li>Nothing was selected - the genuine first load, and the only time
     *       select-first is correct.</li>
     *   <li>The previously selected skill no longer exists, which happens if
     *       it was deleted in another window. Selecting a stale instance would
     *       bind the whole dashboard to an object the database no longer knows
     *       about, so falling back to the first row is the safe choice.</li>
     * </ul>
     */
    private void loadSkillsFromDatabase() {
        // BEFORE setAll. See above: after it, selectedSkill is a detached
        // instance and the id is the only thing that still identifies the user.
        // Boxed, because null means "nothing was selected" and has to be
        // distinguishable from a real id - a primitive sentinel would have to
        // be a value the database can never produce.
        Integer previouslySelectedId =
                selectedSkill == null ? null : selectedSkill.getId();

        skills.setAll(db.getAllSkills());
        // getAllSkills() returns a FLAT list with null parents, so Skill#getDepth()
        // is always 0 there. One recursive-CTE round trip gives the real depths,
        // which is what the indented ComboBox cells render from.
        refreshSkillDepths();
        // Prunes ids of skills that were deleted, rather than only ever adding.
        // The set is bounded by the number of skills either way, but leaving
        // dead ids in it means the filter panel's "all selected" state and the
        // visualisation's id lookups disagree with the database.
        filteredSkillIds.clear();
        for (Skill s : skills) filteredSkillIds.add(s.getId());
        pruneCategoryExpansionState();
        rebuildSkillFilterPanel();

        if (skills.isEmpty()) {
            selectSkill(null);
            return;
        }

        Skill restored = null;
        if (previouslySelectedId != null) {
            for (Skill candidate : skills) {
                // getId() returns a primitive int, so this comparison unboxes
                // and is a value comparison. Written as == on two boxed values
                // it would silently be a reference comparison and would fail to
                // match ids outside the Integer cache (-128..127), which is
                // exactly the kind of bug that only reproduces once a user has
                // added enough skills.
                if (candidate.getId() == previouslySelectedId) {
                    restored = candidate;
                    break;
                }
            }
        }
        if (restored != null) {
            // selectSkill() rather than the selection model alone, because
            // every bound metric, the chart and the status toggles are
            // re-bound by this call. Setting only the selection would leave the
            // dashboard displaying the previously selected skill's data with the
            // dropdown arrow on a different one - the same class of bug as
            // above, in a subtler form.
            selectSkill(restored);
            skillComboBox.getSelectionModel().select(restored);
        } else {
            // Genuine first load, or the selected skill is gone.
            skillComboBox.getSelectionModel().selectFirst();
        }
    }

    /**
     * Attaches both style-generating preferences to {@code scene}.
     *
     * <p>Motion first, then the scale tier, so the tier's sheet is appended
     * last. The two set disjoint properties today, but this ordering means a
     * future tier-specific transform loses to the motion override rather than
     * silently defeating it.
     */
    private void applyStylePreferences(Scene scene) {
        if (scene == null) {
            return;
        }
        UiScale.applyMotion(scene, AppSettings.animationsEnabled());
        UiScale.apply(scene, AppSettings.uiScale());
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
        // The ETA is a per-skill figure, not a bound property: it needs a
        // database query rather than a computation from two observables, so it
        // is refreshed on selection rather than bound.
        refreshEta();
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

    /**
     * v2.0 (item 7): the Add Skill dialog now offers a MASTERY CURVE.
     *
     * <p>WHY THIS MATTERS. Before, creating a skill meant inventing a target
     * number from nothing, and the two goals most people actually have - "20
     * hours to basic proficiency" and "the 10,000-hour rule" - are
     * well-known conventions that the user had to translate into their own
     * points scale by hand, for every single skill. Picking a curve does that
     * arithmetic once and also lays down the level ladder, so the skill has
     * named milestones from the moment it exists rather than none at all.
     *
     * <p>The curve's target WRITES INTO the target spinner, which stays
     * visible and editable. That is deliberate: the curve is a starting point,
     * not a constraint, and hiding the number behind a preset would make the
     * user think they could not override it.
     */
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
        // The bounds are held in locals because SpinnerValueFactory, the type
        // Spinner actually exposes, has no getMin()/getMax() - those live on the
        // concrete DoubleSpinnerValueFactory, so reading them back would need a
        // cast. Two named constants beat an unchecked downcast.
        final double targetMin = 10.0;
        final double targetMax = 1_000_000.0;
        Spinner<Double> targetSpinner = new Spinner<>(targetMin, targetMax, 100.0, 10.0);
        targetSpinner.setEditable(true);
        ComboBox<String> structureBox = new ComboBox<>(FXCollections.observableArrayList(
                Skill.STRUCTURE_I, Skill.STRUCTURE_COMB));
        structureBox.getSelectionModel().selectFirst();

        // ---- The curve picker ------------------------------------------------
        List<ProgressPreset> curves = new ArrayList<>(
                db.getPresetsOfKind(ProgressPreset.KIND_CURVE));
        ProgressPreset noCurve = new ProgressPreset(ProgressPreset.KIND_CURVE,
                "No preset (set a target by hand)", "");
        List<ProgressPreset> curveOptions = new ArrayList<>();
        curveOptions.add(noCurve);
        curveOptions.addAll(curves);

        ComboBox<ProgressPreset> curveBox = new ComboBox<>(FXCollections.observableArrayList(curveOptions));
        curveBox.setMaxWidth(Double.MAX_VALUE);
        curveBox.getStyleClass().add("depth-level-combo");

        // Pre-select the user's default from Settings, if they set one.
        long defaultCurveId = AppSettings.defaultCurveId();
        if (defaultCurveId > 0) {
            for (ProgressPreset p : curveOptions) {
                if (p.getId() == defaultCurveId) {
                    curveBox.setValue(p);
                    break;
                }
            }
        }
        if (curveBox.getValue() == null) {
            curveBox.setValue(noCurve);
        }

        Label curveHint = new Label();
        curveHint.setWrapText(true);
        curveHint.getStyleClass().add("metric-label");
        curveHint.setMaxWidth(360);

        Runnable describeCurve = () -> {
            ProgressPreset chosen = curveBox.getValue();
            if (chosen == null || chosen.getId() <= 0) {
                curveHint.setText("No preset selected. The target above is yours to choose.");
                return;
            }
            // Only offer the target when it is actually inside the spinner's
            // range - writing 20000 into a spinner capped at 100 would silently
            // clamp to 100 and leave the user with a target nobody asked for.
            double target = chosen.getTargetPoints() == null ? 0 : chosen.getTargetPoints();
            if (target > 0) {
                targetSpinner.getValueFactory().setValue(
                        Math.max(targetMin, Math.min(targetMax, target)));
            }
            List<ProgressPreset.Milestone> milestones = chosen.getMilestones();
            StringBuilder sb = new StringBuilder();
            sb.append(milestones.size()).append(" milestone")
              .append(milestones.size() == 1 ? "" : "s").append(": ");
            for (int i = 0; i < milestones.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(milestones.get(i).label()).append(" @ ")
                  .append(ProgressPreset.trimPoints(milestones.get(i).threshold()));
            }
            curveHint.setText(sb.toString());
        };
        curveBox.valueProperty().addListener((obs, old, chosen) -> describeCurve.run());
        describeCurve.run();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Name"), nameField);
        grid.addRow(1, new Label("Parent"), parentBox);
        grid.addRow(2, new Label("Mastery curve"), curveBox);
        grid.add(curveHint, 1, 3);
        grid.addRow(4, new Label("Target Points"), targetSpinner);
        grid.addRow(5, new Label("Structure"), structureBox);
        dialog.getDialogPane().setContent(grid);

        Node addButton = dialog.getDialogPane().lookupButton(addButtonType);
        addButton.disableProperty().bind(nameField.textProperty().isEmpty());

        // The chosen curve, captured at OK-time rather than read from the combo
        // afterwards - the dialog is gone by then.
        final ProgressPreset[] chosenCurve = {null};
        dialog.setResultConverter(bt -> {
            if (bt != addButtonType) return null;
            chosenCurve[0] = curveBox.getValue();
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
            // A skill created on a curve that has already been passed (e.g. a
            // 400-point curve for someone with 500 existing points) shows its
            // milestones immediately rather than waiting for future logs.
            // recordMilestonesCrossed is idempotent, so this is safe even when
            // nothing has been crossed yet.
            if (chosenCurve[0] != null && chosenCurve[0].getId() > 0) {
                int crossed = db.recordMilestonesCrossed(skill.getId(), LocalDate.now()).size();
                if (crossed > 0) {
                    statusBarLabel.setText("Created \"" + skill.getName() + "\" on the \""
                            + chosenCurve[0].getName() + "\" curve - " + crossed
                            + " milestone(s) already reached.");
                }
            }
            // A NEW skill's id is not in skillDepths, so without this its
            // ComboBox cell would render at indent 0 as a bold Category
            // until the user pressed Refresh.
            refreshSkillDepths();
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
            EditSkillCommand edit = new EditSkillCommand(db, selectedSkill, before, after);
            commandManager.execute(edit);
            // A re-parent changes EVERY depth beneath the moved node, not just
            // the moved one, so the whole map has to be re-read. Without this
            // the dropdown indentation silently kept describing the old tree
            // while the tree view showed the new one.
            if (edit.didChangeParent()) {
                refreshSkillDepths();
            }
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
            DeleteSkillCommand delete = new DeleteSkillCommand(db, skills, selectedSkill);
            commandManager.execute(delete);

            if (!delete.didDelete()) {
                // v2.0: DeleteSkillCommand can legitimately refuse - the
                // database rejects a delete it cannot complete. Saying
                // "Deleted" then would be a lie, and the user would press
                // Ctrl+Z expecting to undo something that never happened.
                statusBarLabel.setText("\"" + deletedName + "\" could not be deleted - "
                        + "the database refused. Nothing was changed.");
                return;
            }
            // A delete removes a whole subtree, so every depth below the
            // removed node is gone from the map as well.
            refreshSkillDepths();
            pruneCategoryExpansionState();

            if (!skills.isEmpty()) {
                skillComboBox.getSelectionModel().selectFirst();
            } else {
                selectSkill(null);
            }
            refreshAfterHistoryChange();
            statusBarLabel.setText("Deleted " + deletedName
                    + (delete.subtreeSize() > 1
                       ? " and " + (delete.subtreeSize() - 1) + " item(s) beneath it. "
                       : ". ")
                    + "Press Ctrl+Z to undo.");
        });
    }

    /**
     * Drops expansion-state entries for categories that no longer exist.
     *
     * <p>{@code categoryExpandedState} is keyed by CATEGORY NAME, so it
     * accumulated two kinds of dead entry: names of deleted categories, and
     * the old name of every category that was ever renamed. Harmless in
     * isolation, but it is a map that only ever grows for the life of the
     * session, and a stale name can only ever be read back by accident.
     */
    private void pruneCategoryExpansionState() {
        Set<String> liveNames = new HashSet<>();
        for (Skill s : skills) {
            if (s.getParentId() == Skill.NO_PARENT) {
                liveNames.add(s.getName());
            }
        }
        categoryExpandedState.keySet().removeIf(name -> !liveNames.contains(name));
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

        Dialog<List<ProgressLog>> dialog = buildAdvancedLogDialog();
        dialog.showAndWait().ifPresent(logs -> {
            double pointsBefore = selectedSkill.getCurrentPoints();
            commandManager.execute(new BatchLogProgressCommand(db, selectedSkill, skills, logs));
            celebrateIfJustCompleted(pointsBefore, selectedSkill);
            afterLogChanged();
            // No refreshVisualization() here: afterLogChanged already rendered.
            // The old trailing call made every batch insert draw the canvas
            // twice, for no visible difference.
            buildCalendar();
            statusBarLabel.setText("Logged " + logs.size() + " session" + (logs.size() == 1 ? "" : "s")
                    + " to " + selectedSkill.getName() + ".");
        });
    }

    /**
     * Builds the Advanced Log dialog WITHOUT showing it.
     *
     * <p>Split out from {@link #openAdvancedLogDialog()} for one concrete reason:
     * the dialog is modal, so a harness cannot open it to check it, and this
     * dialog had accumulated three separate regressions that all look correct
     * in code review - truncated labels, an unstyled control, dead space at the
     * bottom. {@code FxmlCheck} now lays this out for real and asserts on the
     * result, which is only possible because the construction is separable from
     * the showing.
     *
     * <p>The layout, and the two bugs behind it:
     *
     * <p><b>THE TRUNCATED LABELS.</b> The batch mode used to be a single HBox of
     * {@code [From:][picker][To:][picker]}. Two DatePickers want roughly 240px
     * each, the labels need about 40px each, and the dialog is 440px wide - so
     * the labels were the only thing that could give, and a {@code Label} with
     * no room renders as an ellipsis. The result read as "... 25/09/2026 [x]
     * ... 01/10/2026 [x]", which is worse than either label: it is not merely
     * truncated, it has lost the one word that says which end of the range a
     * date is. They are now stacked in a two-row GridPane, so each label has the
     * full width to itself and the pickers share the remaining column.
     *
     * <p><b>THE DEAD SPACE.</b> The pane was hard-coded to
     * {@code prefHeight(400)}, chosen so the taller batch mode would fit without
     * clipping. That left roughly 150px of empty panel between the content and
     * the buttons in the shorter single-date mode. Sizing to one mode's
     * requirements cannot fit both, so the window is now resized to whatever the
     * content actually needs whenever the mode changes - see
     * {@link #applyAdvancedLogWindowHeight}.
     */
    Dialog<List<ProgressLog>> buildAdvancedLogDialog() {
        Dialog<List<ProgressLog>> dialog = new Dialog<>();
        dialog.setTitle("Advanced Log - " + selectedSkill.getName());
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(
                getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        pane.getStyleClass().addAll("glass-panel", "advanced-log-pane");
        pane.setId("advancedLogPane");
        // Width is fixed because it has to accommodate the stacked range grid;
        // height is NOT set, because that is exactly what produced the dead
        // space. applyAdvancedLogWindowHeight owns the height.
        pane.setPrefWidth(460);
        pane.setMinWidth(Region.USE_PREF_SIZE);
        pane.setMinHeight(Region.USE_PREF_SIZE);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        // ---- Action buttons: styled, not Modena defaults -------------------
        //
        // A Dialog's buttons are created by the DialogPane, so they arrive with
        // the platform skin and no way to style them from FXML. Adding our own
        // classes to the lookup result is the supported way in, and without it
        // Save and Cancel render as light grey rectangles in a dark dialog.
        Button saveButton = (Button) pane.lookupButton(saveType);
        saveButton.getStyleClass().addAll("accent-button", "dialog-action-button");
        Button cancelButton = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancelButton.getStyleClass().addAll("secondary-button", "dialog-action-button");
        saveButton.setId("advancedLogSave");
        cancelButton.setId("advancedLogCancel");

        // ---- a) Minutes / Points -------------------------------------------
        Spinner<Integer> minutesField = new Spinner<>(0, 600, minutesSpinner.getValue(), 5);
        minutesField.setEditable(true);
        Spinner<Double> pointsField = new Spinner<>(0.0, 100.0, pointsSpinner.getValue(), 0.5);
        pointsField.setEditable(true);
        minutesField.setMaxWidth(Double.MAX_VALUE);
        pointsField.setMaxWidth(Double.MAX_VALUE);
        minutesField.setId("advancedLogMinutes");
        pointsField.setId("advancedLogPoints");

        Label minutesLabel = dialogFieldLabel("Minutes");
        Label pointsLabel = dialogFieldLabel("Points");
        HBox inputRow = new HBox(20,
                new VBox(5, minutesLabel, minutesField),
                new VBox(5, pointsLabel, pointsField));
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.setId("advancedLogInputRow");

        // A caption, because "Minutes / Points" and the date choice are two
        // different questions and the dialog previously ran them together with
        // only a separator to say so.
        Label whenCaption = new Label("When should these be logged?");
        whenCaption.getStyleClass().add("dialog-section-caption");

        // ---- b) Specific Date vs Batch (Date Range) mode switch -------------
        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton singleModeBtn = new RadioButton("Specific Date");
        singleModeBtn.setToggleGroup(modeGroup);
        singleModeBtn.setSelected(true);
        singleModeBtn.setId("advancedLogSingleMode");
        RadioButton rangeModeBtn = new RadioButton("Batch (Date Range)");
        rangeModeBtn.setToggleGroup(modeGroup);
        rangeModeBtn.setId("advancedLogRangeMode");
        // Same truncation guard as the labels below: a RadioButton is a Labeled,
        // so an under-width row ellipsises "Batch (Date Range)" the same way.
        singleModeBtn.setMinWidth(Region.USE_PREF_SIZE);
        rangeModeBtn.setMinWidth(Region.USE_PREF_SIZE);
        HBox modeRow = new HBox(18, singleModeBtn, rangeModeBtn);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        modeRow.setId("advancedLogModeRow");

        // ---- c) Specific Date: one row ------------------------------------
        DatePicker singleDatePicker = new DatePicker(mockToday);
        singleDatePicker.setConverter(unambiguousDateConverter());
        singleDatePicker.setId("advancedLogSingleDate");
        applyDarkDatePicker(singleDatePicker);
        Label dateLabel = dialogFieldLabel("Date:");
        dateLabel.setId("advancedLogDateLabel");
        HBox singleDateRow = new HBox(10, dateLabel, singleDatePicker);
        singleDateRow.setAlignment(Pos.CENTER_LEFT);
        singleDateRow.setId("advancedLogSingleDateRow");
        singleDatePicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(singleDatePicker, Priority.ALWAYS);

        // ---- d) Batch: STACKED, one picker per row ------------------------
        DatePicker rangeStartPicker = new DatePicker(mockToday.minusDays(6));
        rangeStartPicker.setConverter(unambiguousDateConverter());
        rangeStartPicker.setId("advancedLogStartDate");
        applyDarkDatePicker(rangeStartPicker);
        DatePicker rangeEndPicker = new DatePicker(mockToday);
        rangeEndPicker.setConverter(unambiguousDateConverter());
        rangeEndPicker.setId("advancedLogEndDate");
        applyDarkDatePicker(rangeEndPicker);

        Label fromLabel = dialogFieldLabel("From:");
        fromLabel.setId("advancedLogFromLabel");
        Label toLabel = dialogFieldLabel("To:");
        toLabel.setId("advancedLogToLabel");

        // TWO ROWS, not one row of four. Column 0 holds the labels, which are
        // pinned to their preferred width and therefore cannot be squeezed into
        // an ellipsis; column 1 holds the pickers, which expand into whatever is
        // left. hgap/vgap rather than nested HBoxes so the two columns stay
        // aligned down the whole block.
        GridPane rangeGrid = new GridPane();
        rangeGrid.setHgap(12);
        rangeGrid.setVgap(8);
        rangeGrid.setId("advancedLogRangeGrid");
        rangeGrid.add(fromLabel, 0, 0);
        rangeGrid.add(rangeStartPicker, 1, 0);
        rangeGrid.add(toLabel, 0, 1);
        rangeGrid.add(rangeEndPicker, 1, 1);
        GridPane.setHalignment(fromLabel, javafx.geometry.HPos.RIGHT);
        GridPane.setHalignment(toLabel, javafx.geometry.HPos.RIGHT);
        GridPane.setHgrow(rangeStartPicker, Priority.ALWAYS);
        GridPane.setHgrow(rangeEndPicker, Priority.ALWAYS);
        rangeGrid.getColumnConstraints().addAll(
                new ColumnConstraints(),
                new ColumnConstraints());
        rangeGrid.getColumnConstraints().get(1).setHgrow(Priority.ALWAYS);
        rangeStartPicker.setMaxWidth(Double.MAX_VALUE);
        rangeEndPicker.setMaxWidth(Double.MAX_VALUE);
        rangeGrid.setVisible(false);
        rangeGrid.setManaged(false);

        // ---- e) The summary, as an info card rather than loose italic text --
        Label rangeCountLabel = new Label();
        // -text-secondary, not the .empty-notes-label class (-text-muted) -
        // that class is meant for subtle "nothing here yet" placeholders,
        // too low-contrast for this dialog's active info text. wrapText +
        // an explicit max width fix the other half of the same visual bug:
        // this was also getting cut off ("same Minut...") rather than
        // wrapping, since a Label has no wrap behavior by default.
        rangeCountLabel.setStyle("-fx-text-fill: -text-secondary; -fx-font-family: 'Poppins'; -fx-font-style: italic;");
        rangeCountLabel.setWrapText(true);
        rangeCountLabel.setMaxWidth(Double.MAX_VALUE);
        rangeCountLabel.setId("advancedLogSummary");
        VBox summaryCard = new VBox(rangeCountLabel);
        summaryCard.getStyleClass().add("info-banner");
        summaryCard.setId("advancedLogSummaryCard");
        summaryCard.setVisible(false);
        summaryCard.setManaged(false);

        Node saveButtonNode = saveButton;

        Runnable revalidate = () -> {
            boolean isRange = modeGroup.getSelectedToggle() == rangeModeBtn;
            boolean dateValid;
            if (isRange) {
                LocalDate start = rangeStartPicker.getValue();
                LocalDate end = rangeEndPicker.getValue();
                dateValid = start != null && end != null && !end.isBefore(start);
                rangeCountLabel.setText(dateValid
                        ? "Will insert " + (ChronoUnit.DAYS.between(start, end) + 1)
                                + " session" + (ChronoUnit.DAYS.between(start, end) == 0 ? "" : "s")
                                + " - one per day, same Minutes/Points each."
                        : "Pick a valid range (end date on or after start date).");
            } else {
                dateValid = singleDatePicker.getValue() != null;
            }
            boolean nothingToLog = minutesField.getValue() <= 0 && pointsField.getValue() <= 0;
            saveButtonNode.setDisable(!dateValid || nothingToLog);
        };

        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            boolean isRange = newT == rangeModeBtn;
            singleDateRow.setVisible(!isRange);
            singleDateRow.setManaged(!isRange);
            rangeGrid.setVisible(isRange);
            rangeGrid.setManaged(isRange);
            summaryCard.setVisible(isRange);
            summaryCard.setManaged(isRange);
            revalidate.run();
            // The window was sized when it opened, against the mode that was
            // showing then. Batch adds a row and the summary card, so without
            // this the dialog would grow its content underneath a fixed window
            // and push the buttons off the bottom - which is what the old
            // hard-coded prefHeight was working around.
            resizeAdvancedLogWindow(dialog);
        });
        rangeStartPicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        rangeEndPicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        singleDatePicker.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        minutesField.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        pointsField.valueProperty().addListener((o, ov, nv) -> revalidate.run());
        revalidate.run();

        // Two things that need a real window, so neither can happen while the
        // dialog is being constructed.
        dialog.setOnShowing(e -> {
            installCalendarAffordances(dialog);
            // Capture the opening height as the anchor for every later resize.
            // Done here rather than lazily on the first toggle so that the very
            // first toggle already has a baseline to be a delta against.
            Platform.runLater(() -> applyAdvancedLogWindowHeight(
                    pane, pane.getScene() == null ? null : pane.getScene().getWindow()));
        });

        VBox content = new VBox(14,
                inputRow,
                new Separator(),
                whenCaption,
                modeRow,
                singleDateRow,
                rangeGrid,
                summaryCard);
        content.setPadding(new Insets(18, 20, 18, 20));
        content.setId("advancedLogRoot");
        pane.setContent(content);

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
        return dialog;
    }

    /**
     * A dialog field caption that cannot be truncated.
     *
     * <p>{@code minWidth(USE_PREF_SIZE)} is the whole point: without it a
     * {@code Label} has a minimum width of zero and an ellipsises the moment its
     * parent is under pressure. With it, the layout gives way instead.
     */
    private static Label dialogFieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dialog-field-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * Applies the dark-theme styling hook to a DatePicker.
     *
     * <p>The class is added here rather than in CSS alone because a DatePicker
     * created in code gets no FXML to carry a styleClass. Its popup is a
     * separate window, so the calendar styling in styles.css has to be written
     * against the popup's own selectors.
     */
    private static void applyDarkDatePicker(DatePicker picker) {
        // A DISTINCT marker class, not "date-picker".
        //
        // DatePicker.DEFAULT_STYLE_CLASS is literally "date-picker", so every
        // DatePicker already answers true to contains("date-picker") whether or
        // not anything was done to it. Asserting on that class proves nothing -
        // a harness check for it passes on completely unstyled controls, which
        // is exactly what it did until it was mutation-tested.
        //
        // ".date-picker" rules in styles.css still style every DatePicker in the
        // app, including the one on the custom-range dialog, which is wanted.
        // This class is for the dialog-specific treatment and, more usefully, as
        // something a test can assert is genuinely present.
        picker.getStyleClass().add("advlog-date-picker");

        // NOT editable.
        //
        // DatePicker is editable by default, which invites the user to type a
        // date - and typing is precisely what goes wrong here, because a typed
        // date has to match the converter's format and the user has no way to
        // see what that is. The only supported interaction is picking from the
        // calendar.
        picker.setEditable(false);

        // Clicking anywhere on the control opens the calendar, not just the
        // arrow. The arrow is small, and after styling it is the one part of
        // the control a user may not recognise as the button.
        picker.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                picker.show();
            }
        });
    }

    /**
     * The client-area height the dialog needs to show all of its content.
/**
     * The decorated height the Advanced Log window had when it opened.
     *
     * <p>Captured ONCE, and never re-read. That is the whole point: the naive
     * approach - measure the content, tell the window how tall to be, then
     * measure again - is a feedback loop, because sizing the pane to the window
     * makes the pane's preferred height follow the window's height. The second
     * measurement therefore reads a larger number than the first, and each pass
     * ratchets the window taller. That is where the enormous empty gap between
     * the summary banner and the button bar came from: not padding, not a
     * vgrow, but the window inflating itself and the content staying put.
     */
    private double advLogBaseStageHeight = -1;

    /**
     * The content height corresponding to {@link #advLogBaseStageHeight}.
     *
     * <p>The pair is what makes the resize a DELTA. Any change to the content's
     * requirements is expressed relative to the state the dialog opened in, so
     * the answer never depends on how tall the window currently is - which is
     * precisely the dependence that made the loop above possible.
     */
    private double advLogBaseContentHeight = -1;

    /**
     * Sizes the Advanced Log window for its current content.
     *
     * <p>Synchronous and package-visible on purpose. The mode toggle calls it
     * from a {@code Platform.runLater}, but {@code FxmlCheck} calls it directly,
     * which is the only way a headless harness can exercise the real arithmetic
     * instead of a reconstruction of it.
     *
     * @return the window height that was applied, or -1 if there was nothing
     *         to do (no window yet, or the content reported no height)
     */
    double applyAdvancedLogWindowHeight(DialogPane pane, javafx.stage.Window window) {
        if (window == null) {
            return -1;
        }
        // Clear the pane's height constraints before measuring. A pane that
        // has already been sized to a window will otherwise report that size
        // back as its own preference, which is the feedback loop in a different
        // disguise.
        pane.setMinHeight(Region.USE_PREF_SIZE);
        pane.setPrefHeight(Region.USE_COMPUTED_SIZE);
        double content = pane.prefHeight(-1);
        if (!(content > 0)) {
            return -1;
        }

        if (advLogBaseStageHeight <= 0) {
            // First real measurement: this is the state the user opened the
            // dialog in, so it becomes the anchor for every later delta.
            advLogBaseStageHeight = window.getHeight();
            advLogBaseContentHeight = content;
            return advLogBaseStageHeight;
        }

        // A DELTA from the opening state, applied to the OPENING window height.
        //
        // Both halves matter. Using the current window height as the base would
        // re-introduce the loop; using the content height as the new window
        // height would drop the title bar and borders, since those are part of
        // the decorated height and not of the pane's preference - which is what
        // sliced the bottom off the button bar when toggling back to the
        // shorter mode.
        double target = advLogBaseStageHeight + (content - advLogBaseContentHeight);
        if (Math.abs(target - window.getHeight()) > 0.5) {
            window.setHeight(target);
        }
        return target;
    }

    /**
     * Queues a resize for after the current event.
     *
     * <p>The mode toggle changes which rows are managed, and a measure taken
     * before that has propagated would still describe the previous mode.
     */
    private void resizeAdvancedLogWindow(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        Platform.runLater(() -> applyAdvancedLogWindowHeight(
                pane, pane.getScene() == null ? null : pane.getScene().getWindow()));
    }

    /**
     * Installs the calendar affordances that only exist once the control is
     * actually styled.
     *
     * <p>Two separate problems, and only the second is obvious from a
     * screenshot.
     *
     * <p><b>THE ARROW GLYPH IS INVISIBLE ON A DARK PANEL.</b> The arrow button
     * is there and is 29px wide, but the glyph inside it is a plain
     * {@code Region} - not a {@code Path} - whose colour comes from the platform
     * skin and resolves to a dark grey. Dark grey on this panel is invisible,
     * which is why the control reads as a plain text field with no button. CSS
     * alone cannot fix it, because {@code -fx-fill} is a Shape property and this
     * is not a Shape; the node's colour is not a looked-up CSS property at all.
     *
     * <p>So the glyph is replaced with an SVG drawn by JavaFX itself. It is
     * ADDED to the arrow button rather than swapped in for the skin's node:
     * {@code ComboBoxBaseSkin} holds a reference to its own arrow and re-reads
     * it, and removing that node from the graph risks a null dereference deep
     * inside the skin. An extra child is inert as far as the skin is concerned.
     *
     * <p><b>THE FIELD WAS EDITABLE.</b> A DatePicker is editable by default,
     * which invites typing a date - and typing is exactly what goes wrong when
     * the converter's format is anything other than what the user guesses. Made
     * non-editable, with a click anywhere on the control opening the calendar.
     */
    private static void installCalendarAffordances(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        Scene scene = pane.getScene();
        if (scene == null) {
            return;
        }
        for (String id : new String[]{"advancedLogSingleDate", "advancedLogStartDate",
                "advancedLogEndDate"}) {
            Node node = pane.lookup("#" + id);
            if (!(node instanceof DatePicker picker)) {
                continue;
            }
            Node arrowButton = picker.lookup(".arrow-button");
            if (arrowButton instanceof javafx.scene.layout.Pane buttonPane
                    && !hasCalendarGlyph(buttonPane)) {
                buttonPane.getChildren().add(calendarGlyph());
            }
        }
    }

    private static boolean hasCalendarGlyph(javafx.scene.layout.Pane buttonPane) {
        return buttonPane.getChildren().stream()
                .anyMatch(c -> "advlog-calendar-glyph".equals(c.getId()));
    }

    /** A small calendar outline, drawn by JavaFX rather than by a font. */
    private static Node calendarGlyph() {
        // SVGPath, not Path: Path has no String constructor, and the SVG path
        // data is what makes this readable and font-free. Four small paths in a
        // 14x14 box: the frame, the header rule, and the two binding rings.
        javafx.scene.shape.SVGPath frame = glyphPath(
                "M 2.5 3.5 h 9 a 1 1 0 0 1 1 1 v 7 a 1 1 0 0 1 -1 1 h -9 a 1 1 0 0 1 -1 -1 v -7 "
                        + "a 1 1 0 0 1 1 -1 z",
                "#E7ECF3", "transparent");
        javafx.scene.shape.SVGPath header = glyphPath("M 2.5 6 h 9", "#A8EB12", "transparent");
        javafx.scene.shape.SVGPath ringLeft = glyphPath("M 5 1.5 v 2.5", "#E7ECF3", "transparent");
        javafx.scene.shape.SVGPath ringRight = glyphPath("M 9 1.5 v 2.5", "#E7ECF3", "transparent");

        javafx.scene.layout.StackPane glyph = new javafx.scene.layout.StackPane(
                frame, header, ringLeft, ringRight);
        // StackPane takes only children, so the size is set afterwards. 14x14
        // centres inside the 29px arrow button.
        glyph.setPrefSize(14, 14);
        glyph.setMinSize(javafx.scene.layout.Region.USE_PREF_SIZE,
                javafx.scene.layout.Region.USE_PREF_SIZE);
        glyph.setId("advlog-calendar-glyph");
        glyph.setPickOnBounds(false);
        glyph.setMouseTransparent(true);
        return glyph;
    }

    private static javafx.scene.shape.SVGPath glyphPath(String content, String stroke,
                                                        String fill) {
        javafx.scene.shape.SVGPath path = new javafx.scene.shape.SVGPath();
        path.setContent(content);
        path.setStroke(javafx.scene.paint.Color.web(stroke));
        path.setStrokeWidth(1.2);
        path.setFill(fill.equals("transparent")
                ? javafx.scene.paint.Color.TRANSPARENT
                : javafx.scene.paint.Color.web(fill));
        path.setId("advlog-calendar-glyph-part");
        return path;
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
        // afterLogChanged renders the visualization; the old trailing
        // refreshVisualization() drew the whole canvas a second time on every
        // undo, redo, edit and delete.
        afterLogChanged();
        buildCalendar();
        refreshNotesForSelectedDate();
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
        // v2.0 FIX. This was the UNBOUNDED getPointsPerDay(), a GROUP BY over
        // every row in progress_logs with no WHERE clause - while the calendar
        // can only ever display ONE MONTH of it. Clicking a calendar day called
        // buildCalendar() and therefore re-scanned the user's entire log
        // history, and the calendar repaints on every day click.
        //
        // The bounded overload exists precisely for this, and it hits
        // idx_progress_logs_log_date. Querying the month's own range is both
        // correct and dramatically cheaper as the history grows.
        Map<LocalDate, Double> pointsByDate = db.getPointsPerDay(
                currentMonth.atDay(1), currentMonth.atEndOfMonth());

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
        // Disposes the WebViews the outgoing cards own - see disposeNoteCards.
        // Skipping this is the single biggest memory leak in the application.
        disposeNoteCards(notesContainer);
        // A fresh date view supersedes any search results, so the same query is
        // allowed to re-render next time it is typed.
        lastSearchQuery = null;
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
                // The bridge is given a refresh callback so a toggle is
                // reflected everywhere, not just in the WebView that
                // originated it. Without it the calendar's note marker, the
                // pinned section, and every other copy of the note kept
                // showing the pre-toggle state until the user navigated away
                // and back - which reads as "the checkbox didn't save".
                CheckboxBridge bridge = new CheckboxBridge(db, note, () -> {
                    // Re-render the day list, but NOT by rebuilding the card
                    // that just issued the toggle: replacing the WebView under
                    // the user's cursor while they are clicking checkboxes
                    // would fight them and would also dispose the very engine
                    // that raised the event. So the calendar is refreshed
                    // (cheap, and shows the completion marker) and the card
                    // itself is left alone - its native checkbox already
                    // shows the new state, and the model it renders from has
                    // been updated in place.
                    buildCalendar();
                    refreshPinnedNotes();
                });
                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("javaCheckboxBridge", bridge);

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
     *  WebEngine's JS-to-Java reflection bridge to see its methods.
     *
     *  <h3>THE THREADING RULE THIS CLASS EXISTS TO RESPECT</h3>
     *  Java invoked from JavaScript runs on a <strong>WebKit thread</strong>,
     *  NOT the JavaFX application thread. Oracle's own JavaFX tutorial is
     *  explicit about this, and v1 violated it: {@link #toggle} mutated the
     *  shared {@link CalendarNote} and issued a {@code db.updateNote} straight
     *  from that thread.
     *
     *  <p>Two distinct hazards followed:
     *  <ol>
     *      <li>a genuine DATA RACE on the single non-thread-safe JDBC
     *          {@code Connection} shared with the FX thread, which surfaces
     *          intermittently as "database is locked", a lost toggle, or a torn
     *          read of the markdown body - timing-dependent and therefore
     *          miserable to reproduce;</li>
     *      <li>a read of a JavaFX-bound {@code StringProperty} from a
     *          non-FX thread, which violates JavaFX's single-thread rule
     *          outright.</li>
     *  </ol>
     *
     *  <p>So the whole toggle is marshalled onto the FX thread with
     *  {@code Platform.runLater}, and the refresh happens there too. The user
     *  sees the same result - the native checkbox already toggles itself in
     *  WebKit - but every read and write is now correctly ordered.
     */
    public static class CheckboxBridge {
        private final DatabaseHelper db;
        private final CalendarNote note;
        private final Runnable onChanged;

        public CheckboxBridge(DatabaseHelper db, CalendarNote note) {
            this(db, note, null);
        }

        /**
         * @param onChanged run on the FX thread after a successful write, so the
         *                  calendar and the pinned section can be brought back
         *                  in line. Without it a tick in a note updated the
         *                  database but left every OTHER rendering of that note
         *                  showing the pre-toggle state until the user
         *                  navigated away and back.
         */
        public CheckboxBridge(DatabaseHelper db, CalendarNote note, Runnable onChanged) {
            this.db = db;
            this.note = note;
            this.onChanged = onChanged;
        }

        /** Called from JS with the 0-based index of the checkbox that was
         *  just clicked, in document order (which matches Markdown source
         *  order, since Flexmark renders task items in document order). */
        public void toggle(int index) {
            // Everything below runs on the FX thread. Deliberately NOT holding
            // a lock: a lock would make the two threads take turns, whereas
            // runLater puts the work in the single queue that every other
            // UI-driven database access also uses, so ordering is consistent
            // with the rest of the application.
            Platform.runLater(() -> {
                try {
                    String updated = toggleNthCheckbox(note.getContentMarkdown(), index);
                    note.setContentMarkdown(updated);
                    if (db.updateNote(note) && onChanged != null) {
                        onChanged.run();
                    }
                } catch (RuntimeException failure) {
                    // Never let an exception escape back through the JS bridge.
                    // An uncaught throw here surfaces as an opaque WebKit
                    // console error and leaves the note's rendered checkbox
                    // disagreeing with what is stored.
                    System.err.println("[CheckboxBridge] Toggling checkbox " + index
                            + " on note " + note.getId() + " failed: " + failure);
                }
            });
        }

        /**
         * Precompiled, because {@code String.matches} recompiles the Pattern
         * on every call - and this runs over every line of the note body, up to
         * three times per line. It is also the one piece of logic that had to
         * be correct, since a mistyped pattern means a checkbox that silently
         * toggles the wrong line.
         */
        private static final java.util.regex.Pattern TASK_ITEM =
                java.util.regex.Pattern.compile("^[-*+]\\s+\\[[ xX]\\].*");
        private static final java.util.regex.Pattern TASK_UNCHECKED =
                java.util.regex.Pattern.compile("^[-*+]\\s+\\[ \\].*");
        private static final java.util.regex.Pattern FILL_UNCHECKED =
                java.util.regex.Pattern.compile("\\[ \\]");
        private static final java.util.regex.Pattern FILL_CHECKED =
                java.util.regex.Pattern.compile("\\[[xX]\\]");

        private static String toggleNthCheckbox(String markdown, int checkboxIndex) {
            if (markdown == null) return "";
            String[] lines = markdown.split("\n", -1);
            int count = 0;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (!TASK_ITEM.matcher(trimmed).matches()) {
                    continue;
                }
                if (count == checkboxIndex) {
                    // Matcher.replaceFirst on the ORIGINAL line, not the
                    // trimmed one - trimming would silently strip the
                    // indentation of a nested task item.
                    if (TASK_UNCHECKED.matcher(trimmed).matches()) {
                        lines[i] = FILL_UNCHECKED.matcher(lines[i]).replaceFirst("[x]");
                    } else {
                        lines[i] = FILL_CHECKED.matcher(lines[i]).replaceFirst("[ ]");
                    }
                    break;
                }
                count++;
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
        //
        // The -2 keeps the canvas a hair inside the viewport at 100%/1.0x, so
        // sub-pixel rounding can't summon a scrollbar when nothing overflows.
        double vpW = structuralCanvasScroll.getViewportBounds().getWidth();
        double vpH = structuralCanvasScroll.getViewportBounds().getHeight();
        // v2.0 FIX. This was Math.max(BASE_CANVAS_WIDTH, vpW - 2), which
        // INVERTED the stated intent three lines above. Math.max forces the
        // canvas to at least 600x380, so on any viewport narrower than 602x382
        // the canvas was guaranteed to overflow and a scrollbar appeared -
        // regardless of zoom level, spacing, or how little content there was.
        // The user saw a permanent horizontal scrollbar on a chart that would
        // have fitted perfectly.
        //
        // Math.min is the correct operator: "as big as the viewport, but never
        // beyond it at 100%". The constants remain as a FALLBACK for the
        // pre-layout pass, where the viewport is still 0 and there is nothing
        // sensible to fit to.
        double baseW = vpW > 0 ? Math.min(BASE_CANVAS_WIDTH, vpW - 2) : BASE_CANVAS_WIDTH;
        double baseH = vpH > 0 ? Math.min(BASE_CANVAS_HEIGHT, vpH - 2) : BASE_CANVAS_HEIGHT;
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
        reanchorAfterControlsChange();
    }

    private void showBreadthLabel(boolean visible) {
        breadthLabel.setVisible(visible);
        breadthLabel.setManaged(visible);
    }

    private void showZoomControls(boolean visible) {
        zoomControlsRow.setVisible(visible);
        zoomControlsRow.setManaged(visible);
        reanchorAfterControlsChange();
    }

    /**
     * Re-anchors the divider after a controls row is shown or hidden.
     *
     * <p>Synchronous, which is both simpler and more correct than posting it.
     * A chart-type switch calls {@code showZoomControls} and {@code showFilter}
     * in sequence, so this runs twice; the second call recomputes from the
     * settled visibility state and wins, because {@code prefHeight(-1)} reads
     * the managed flags as they are rather than from a cached layout. Posting
     * would have made the correction land a frame late for no benefit.
     *
     * <p>Needed at all because the pane's pixel height does not change when its
     * content is hidden - see {@link #reanchorDivider}.
     */
    private void reanchorAfterControlsChange() {
        // Keep the resize handle's visibility in step with the controls it
        // divides. Called from both toggles, so whichever one changed last
        // decides - and it is the filter's own managed flag that the handler
        // reads, so this cannot disagree with the layout.
        syncChartResizeHandle();
        reanchorDivider(true);
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
            showFailure("Export failed", e);
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
            showFailure("Import failed", e);
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

            // v2.0 FIX - THE N+1 THAT FROZE THE WINDOW. This was a loop
            // calling db.getLogsForSkill() once per skill, i.e. one indexed
            // SELECT per node of the tree, all on the FX thread, immediately
            // before a full render and a PDF write. On a realistic 60-skill
            // database that is 60 round trips plus a scene snapshot plus file
            // I/O with no repaint, no progress indicator and no way to cancel -
            // which is why "Export PDF" read as a hung application rather than
            // a slow one.
            //
            // One scan of the log table, grouped in memory, is the same data for
            // a fraction of the cost. Note that a skill with no logs is simply
            // ABSENT from the map rather than mapped to an empty list, which is
            // what PdfExportUtil already handles.
            Map<Integer, List<ProgressLog>> logsBySkill = db.getLogsGroupedBySkill();
            PdfExportUtil.exportProgressReport(file, treeLinkedSkills, logsBySkill, chartImage, chartTitle);
            statusBarLabel.setText("PDF report saved to " + file.getName());
        } catch (Exception e) {
            showFailure("PDF export failed", e);
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

    /**
     * Reports a failed operation to the user, with a message they can act on.
     *
     * <p>WHY THIS EXISTS. Every failure site used to call
     * {@code showAlert(type, title, e.getMessage())}, and
     * {@code Throwable.getMessage()} returns <strong>null</strong> for a
     * NullPointerException, for many SQLException instances, and for any
     * exception constructed without a message. The user therefore got a dialog
     * whose body read literally {@code null}, with nothing in the log and
     * nothing to report it with - strictly worse than no error dialog at all,
     * because it looks like the app is dismissing them.
     *
     * <p>This also keeps the diagnostics somewhere the developer can reach.
     * The full stack trace goes to stderr, which is where launch4j captures it
     * for a packaged build, so a user can be asked to send it.
     */
    private void showFailure(String title, Throwable error) {
        // Always log first: if the alert itself fails to display, the trace is
        // still captured.
        System.err.println("[DashboardController] " + title);
        error.printStackTrace();

        String message = describe(error);
        showAlert(Alert.AlertType.ERROR, title, message);
    }

    /**
     * A human-readable explanation that is never blank and never "null".
     *
     * <p>The root cause is preferred over the wrapper, because
     * {@code InvocationTargetException} and {@code ExecutionException} carry
     * the useful message on their cause while reporting their own as null.
     */
    private static String describe(Throwable error) {
        Throwable root = error;
        // Walk to the deepest cause, bounded so a cyclic cause chain (which
        // Throwable.initCause permits) cannot spin forever.
        for (int depth = 0; depth < 10; depth++) {
            Throwable cause = root.getCause();
            if (cause == null || cause == root) {
                break;
            }
            root = cause;
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        if (error == root) {
            return message;
        }
        return message + " (while " + error.getClass().getSimpleName() + ")";
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
