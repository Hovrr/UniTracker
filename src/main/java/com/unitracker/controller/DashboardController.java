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
import com.unitracker.util.UtrackFileUtil;
import com.unitracker.util.VisualizationRenderer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // ----- Calendar -----
    @FXML private Label monthYearLabel;
    @FXML private GridPane calendarGrid;

    // ----- Sticky notes -----
    @FXML private VBox notesContainer;
    @FXML private Label selectedDateLabel;

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

        loadSkillsFromDatabase();
        buildCalendar();
        refreshNotesForSelectedDate();
        refreshVisualization();

        statusBarLabel.setText("Connected to local SQLite database.");
    }

    private void setupVizToggleGroup() {
        vizToggleGroup = new ToggleGroup();
        for (ToggleButton tb : List.of(curveToggle, iShapedToggle,
                combShapedToggle, skillTreeToggle, radarToggle)) {
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
        skillComboBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldSkill, newSkill) -> selectSkill(newSkill));
    }

    private void setupSpinners() {
        minutesSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 600, 25, 5));
        pointsSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 100, 5, 0.5));
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
            input.setContentText(String.format("Enter %s (%.1f\u2013%.1f):", label, slider.getMin(), slider.getMax()));
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
            }
            if (scrollableH > 0) {
                double newV = panStartVValue - deltaY / scrollableH;
                structuralCanvasScroll.setVvalue(clamp(newV, 0, 1));
            }
        });

        structuralCanvas.setOnMouseReleased(event -> structuralCanvas.setCursor(Cursor.OPEN_HAND));
    }

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
        noParent.setName("\u2014 No parent (new Category) \u2014");

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
        dialog.setTitle("Edit Skill \u2014 " + selectedSkill.getName());
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
        commandManager.execute(new LogProgressCommand(db, selectedSkill, log));
        celebrateIfJustCompleted(pointsBefore, selectedSkill);

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
        dialog.setTitle("Advanced Log \u2014 " + selectedSkill.getName());
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
                                + " session(s) \u2014 one per day, same Minutes/Points each."
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
            commandManager.execute(new BatchLogProgressCommand(db, selectedSkill, logs));
            celebrateIfJustCompleted(pointsBefore, selectedSkill);
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

    @FXML
    private void handleRefreshAndClearCache() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Refresh & Clear Cache");
        confirm.setHeaderText(null);
        confirm.setContentText("This clears the Undo/Redo history (cannot be undone afterward) "
                + "and reloads all data fresh from the database. Continue?");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
        confirm.getDialogPane().getStyleClass().add("glass-panel");

        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            commandManager.clearHistory();
            loadSkillsFromDatabase();
            buildCalendar();
            refreshNotesForSelectedDate();
            refreshVisualization();
            statusBarLabel.setText("Cache cleared, data reloaded from database.");
        });
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
        Set<LocalDate> loggedDates = db.getDatesWithLogs();

        int row = 1;
        int col = firstDayCol;
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = currentMonth.atDay(day);
            boolean hasLog = loggedDates.contains(date);
            calendarGrid.add(buildDayCell(date, notesByDate.getOrDefault(date, List.of()), hasLog), col, row);
            col++;
            if (col > 6) {
                col = 0;
                row++;
            }
        }
    }

    private StackPane buildDayCell(LocalDate date, List<CalendarNote> notesForDay, boolean hasLoggedSession) {
        StackPane cell = new StackPane();
        cell.getStyleClass().add("calendar-day");
        if (hasLoggedSession) cell.getStyleClass().add("calendar-day-logged");
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
                        + " \u2014 new sessions and notes use this date until changed again.");
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

        Button deleteBtn = new Button("\u2715");
        deleteBtn.getStyleClass().add("icon-button");
        deleteBtn.setOnAction(e -> {
            db.deleteNote(note.getId());
            refreshNotesForSelectedDate();
            buildCalendar();
        });
        header.getChildren().addAll(title, spacer, upBtn, downBtn, editBtn, deleteBtn);

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
        dialog.setTitle("New Sticky Note \u2014 " + selectedDate);
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
        structuralCanvas.setWidth(BASE_CANVAS_WIDTH * zoomLevel * hSpacingSlider.getValue());
        structuralCanvas.setHeight(BASE_CANVAS_HEIGHT * zoomLevel * vSpacingSlider.getValue());
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
            if (chartTypeChanged) centerCanvasScroll();

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
            if (chartTypeChanged) centerCanvasScroll();
            repositionBreadthLabel();

        } else if (active == skillTreeToggle) {
            show(structuralCanvasScroll);
            showZoomControls(true);
            showFilter(true);
            // B.3: real parent-child tree, pruned to the filter panel's
            // checked nodes (or ancestors of a checked descendant).
            VisualizationRenderer.renderSkillTree(gc, w, h, pruneTreeToFiltered(db.getSkillTree()), rotate);
            if (chartTypeChanged) centerCanvasScroll();

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
            if (chartTypeChanged) centerCanvasScroll();
        }
    }

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
            if (!updatingDepthCombo && newVal != null && !newVal.equals(oldVal)) {
                refreshVisualization();
            }
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
        } else {
            showDepthLevelComboBox(false);
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
