package com.unitracker.controller;

import com.unitracker.util.Anim;
import com.unitracker.util.FocusTimerState;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.List;

/**
 * Controller for the v2.0 floating timer widget.
 *
 * <h2>THIS CLASS IS A PURE OBSERVER. IT OWNS NO TIME STATE.</h2>
 * There is no field here holding a remaining-time count, and no
 * {@code AnimationTimer} or {@code Timeline} anywhere in this file. The only
 * thing this class stores about the session is the two most recent values it
 * was TOLD, and those exist solely so it can tell whether the display actually
 * changed.
 *
 * <p>That is deliberate, and it is the whole reason the pop-out handoff cannot
 * lose a second. A widget with its own ticker would have two clocks in the same
 * process, and the two would disagree - rounding alone guarantees it, and the
 * disagreement compounds over a 25-minute session. Worse, transferring a
 * counter between the two owners at pop-out and at dock is precisely the moment
 * a value gets read from one place and written to another, which is how seconds
 * go missing.
 *
 * <p>Instead {@link #update} is called by DashboardController's single tick,
 * with a remaining-seconds value that has already been computed. Two views
 * rendering the same tick are therefore showing the same number by
 * construction, not by luck, and the handoff re-parents a Label rather than
 * moving a countdown.
 *
 * @see DashboardController for the owner of the deadline
 */
public class FloatingTimerController {

    // ---- Injected from FloatingTimer.fxml ----
    @FXML private VBox card;
    @FXML private Label skillLabel;
    @FXML private Label clockLabel;
    @FXML private Label stateLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button playPauseButton;
    @FXML private Button resetButton;
    @FXML private Button dockButton;

    // ---- Callbacks into the dashboard. Null until FloatingTimerWindow wires them. ----
    private Runnable onPlayPause;
    private Runnable onReset;
    private Runnable onDock;

/**
     * The last values pushed in, used ONLY to skip redundant UI writes.
     *
     * <p>This is a rendering optimisation, not state: if it were ever used to
     * compute anything it would become a second copy of the timer, which is
     * the exact thing this class is designed to avoid. Every method that needs
     * a value is handed it as a parameter.
     */
    private long lastRenderedSeconds = Long.MIN_VALUE;
    private FocusTimerState.Phase lastPhase = null;
    private String lastStateText = null;

    /**
     * True once the drag handlers are installed. Guards against a double
     * registration, which would make a single drag move the window twice as far
     * as the cursor.
     */
    private boolean dragHandlersInstalled = false;

    /**
     * Where in the window the pointer grabbed it, in SCREEN coordinates.
     *
     * <p>Screen-relative, not scene-relative: the scene moves with the window, so
     * a scene-space anchor drifts every time the window moves, while a
     * screen-space anchor stays fixed to the cursor for the whole gesture.
     * Captured once on press and never recomputed.
     */
    private double dragAnchorX;
    private double dragAnchorY;

    /** True between the press and the release that ends a drag. */
    private boolean dragging;

    /** The stage being dragged. Assigned by FloatingTimerWindow after load(). */
    private Stage stage;

    @FXML
    private void initialize() {
        playPauseButton.setOnAction(e -> fire(onPlayPause));
        resetButton.setOnAction(e -> fire(onReset));
        dockButton.setOnAction(e -> fire(onDock));
        // Deliberately NOT disabling the buttons here.
        //
        // The first version disabled both and relied on the first update() to
        // re-enable them - but update() only re-enabled them inside
        // `if (running != lastRunningState)`. On a widget popped out while the
        // session was paused or idle, running was false and lastRunningState
        // started false, so the condition was false, the body never ran, and
        // the button stayed permanently disabled. Clicking it did nothing at
        // all, with no error, while the sidebar's identical button worked.
        //
        // The widget is not observable in that window anyway: it is constructed,
        // loaded and wired before FloatingTimerWindow returns, and the user
        // cannot click it until it is shown. So there is no race to guard
        // against, and the buttons are enabled in wire() instead - which is the
        // point at which the callbacks they fire actually exist.
        setControlsEnabled(false);
    }

    /** Wires the callbacks and the stage. Called by FloatingTimerWindow. */
    void wire(Stage stage, Runnable onPlayPause, Runnable onReset, Runnable onDock) {
        this.stage = stage;
        this.onPlayPause = onPlayPause;
        this.onReset = onReset;
        this.onDock = onDock;
        // The callbacks exist now, so the buttons can work. Enabling them here
        // rather than in update() means correctness no longer depends on
        // receiving a push in some particular state.
        setControlsEnabled(true);
    }

    /**
     * Enables or disables the two session controls together.
     *
     * <p>The dock button is never touched: docking is always available, even
     * with no session, since it is the only way to get the widget back.
     */
    private void setControlsEnabled(boolean enabled) {
        playPauseButton.setDisable(!enabled);
        resetButton.setDisable(!enabled);
    }

    /** Guards every callback so a null never throws inside a JavaFX handler. */
    private static void fire(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    // -----------------------------------------------------------------
    //  The single write path
    // -----------------------------------------------------------------

    /**
     * Renders one tick.
     *
     * <p>Called from DashboardController's AnimationTimer on the FX thread, so
     * no {@code Platform.runLater} is needed here. Guarded anyway: the method
     * is public, and a future caller reaching it from a background thread would
     * otherwise produce the intermittent, unreproducible layout corruption that
     * JavaFX's single-thread rule is famous for. Marshalling is the safe
     * default.
     *
     * @param remainingSeconds already computed by FocusTimerState - NOT derived
     *                         here, so every observer shows an identical value
     * @param totalSeconds     the session's own total, for the progress bar
     * @param phase            IDLE, PAUSED or RUNNING. A VALUE, not a delta: the
     *                         button's label and enabled state are derived from
     *                         it directly, so the first push is as correct as
     *                         any later one and a stale label is not possible
     * @param skillName        the target skill, or null when unattributed
     * @param stateText        a short status line ("Paused", "Finished", ...)
     */
    public void update(long remainingSeconds, int totalSeconds, FocusTimerState.Phase phase,
                       String skillName, String stateText) {
        if (!Platform.isFxApplicationThread()) {
            // Copy the arguments into final locals for the lambda; the values
            // are primitives and effectively-final references, so this is safe
            // and allocation-light at one call per second.
            final long r = remainingSeconds;
            final int t = totalSeconds;
            final FocusTimerState.Phase p = phase;
            final String skill = skillName;
            final String state = stateText;
            Platform.runLater(() -> update(r, t, p, skill, state));
            return;
        }
        if (card == null) {
            return; // FXML not loaded yet, or already torn down
        }
        if (phase == null) {
            return; // never render from a null phase; it would NPE the switch
        }

        // Only write what actually changed. A Label.setText on the FX thread
        // dirties the layout, and this widget redraws sixty times a second
        // underneath, so writing an identical string sixty times a second is
        // pure waste.
        if (remainingSeconds != lastRenderedSeconds) {
            lastRenderedSeconds = remainingSeconds;
            clockLabel.setText(FocusTimerState.formatClock(remainingSeconds));
            progressBar.setProgress(FocusTimerState.progress(remainingSeconds, totalSeconds));
        }

        // The button is rendered FROM the phase, and compared against the last
        // phase rather than the last `running` flag. Comparing phases is what
        // makes PAUSED and IDLE distinguishable: both are "not running", so a
        // boolean comparison cannot tell "Resume" from "Start", and cannot tell
        // a reset button apart from a resume button.
        if (phase != lastPhase) {
            lastPhase = phase;
            playPauseButton.setText(phase.actionLabel());
            setToolTip(playPauseButton, phase.actionTooltip());
        }

        if (skillName != null && !skillName.equals(skillLabel.getText())) {
            skillLabel.setText(skillName);
            // The label is capped at 210px and CLIPS, because JavaFX 25's
            // Labeled offers no ellipsis or truncation property at all. A
            // tooltip is the only way to surface a long skill name in full.
            // Skipped for short names, so the common case carries no tooltip
            // skin and the widget stays light.
            if (skillName.length() > 22) {
                setToolTip(skillLabel, skillName);
            } else {
                skillLabel.setTooltip(null);
            }
        }

        if (stateText == null ? lastStateText != null : !stateText.equals(lastStateText)) {
            lastStateText = stateText;
            stateLabel.setText(stateText == null ? "" : stateText);
        }
    }

    private static void setToolTip(javafx.scene.control.Control control, String text) {
        // Reusing one Tooltip instance avoids leaking a new one per state
        // change; a fresh Tooltip per call is a small but real leak over a
        // long session, since JavaFX keeps its skin alive while installed.
        Tooltip existing = control.getTooltip();
        if (existing == null) {
            control.setTooltip(new Tooltip(text));
        } else {
            existing.setText(text);
        }
    }

    /**
     * Dims the card when the session is paused.
     *
     * <p>Separate from {@link #update} because it is a visual emphasis change
     * rather than a value. Uses explicit add/remove rather than a
     * {@code toggleAll} helper, because the style class list is an
     * {@code ObservableList<String>} and there is no conditional-toggle method
     * on it - the alternative is remove-then-add, which visibly restyles the
     * node in two passes.
     */
    public void setPausedLook(boolean paused) {
        if (card == null) return;
        if (paused) {
            if (!card.getStyleClass().contains("float-timer-paused")) {
                card.getStyleClass().add("float-timer-paused");
            }
        } else {
            card.getStyleClass().remove("float-timer-paused");
        }
    }

// -----------------------------------------------------------------
    //  Dragging
    // -----------------------------------------------------------------

    /**
     * Makes the widget draggable, using SCENE-level event filters.
     *
     * <p>A {@code StageStyle.TRANSPARENT} stage has no title bar, so without
     * this the widget could not be moved at all.
     *
     * <h2>WHY FILTERS ON THE SCENE, RATHER THAN HANDLERS ON THE CARD</h2>
     *
     * <p>This is the fix for a drag that felt resistant, trailed the cursor and
     * stopped dead partway through. Node handlers run in the CAPTURING-then-
     * BUBBLING phase, and by the time a handler on the card sees an event the
     * node underneath the cursor has already had its chance:
     *
     * <ul>
     *   <li>{@code Button} installs a {@code PressDrags} gesture recogniser.
     *       A press on a button begins a gesture the button owns, and its own
     *       handling of the ensuing drag can consume the event. The card's
     *       bubbling handler then never runs, so the window stops moving the
     *       instant the cursor crosses a button - which, on a widget whose card
     *       is largely covered by controls, is most of the drag.</li>
     *   <li>Anything that swallows the {@code MOUSE_PRESSED} ends the sequence
     *       entirely, because the drag only continues for the press target.</li>
     * </ul>
     *
     * <p>A <b>filter</b> on the {@link Scene} runs in the CAPTURING phase,
     * before any node has seen the event, so no child can intercept or
     * interrupt the sequence no matter which control the cursor is over.
     *
     * <h2>WHY THE ANCHOR IS IN SCREEN COORDINATES</h2>
     *
     * <p>{@link MouseEvent#getSceneX()} is relative to the SCENE, which moves
     * when the window moves. Deriving the offset from scene coordinates while
     * simultaneously moving the window therefore feeds the window's own motion
     * back into the next event's arithmetic - each event partially cancels the
     * last, so the window accelerates away from the cursor and then stalls.
     * {@link MouseEvent#getScreenX()} is relative to the virtual desktop and is
     * unaffected by the window's position, so the anchor stays valid for the
     * whole gesture. Captured ONCE, on press, and never recomputed.
     *
     * <h2>WHY THE MOVE IS NOT DEFERRED TO A FRAME PUMP</h2>
     *
     * <p>An earlier version recorded the target position and applied it from an
     * {@code AnimationTimer}, to avoid several native moves per frame. In
     * practice that introduced up to a full frame of latency, which reads to the
     * user as the widget <em>trailing</em> the cursor, and a stutter in the
     * pulse reads as the drag having stalled. With the expensive work removed
     * (the drop shadow is switched off for the duration, below) a direct
     * {@code setX/setY} per drag event is both simpler and visibly tighter.
     *
     * @param scene the widget's scene, which must already own the node
     */
    void installDragHandlers(Scene scene) {
        if (dragHandlersInstalled || scene == null || card == null) {
            return;
        }
        dragHandlersInstalled = true;

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (stage == null || e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            // Let the buttons be buttons. A press that lands on a control is
            // that control's business; swallowing it here would make the widget
            // undraggable by its own buttons and would break Pause and Reset.
            if (isInsideButton(e.getTarget())) {
                return;
            }
            // Screen-space anchor, taken once. See the class note on why scene
            // coordinates are not usable for this.
            dragAnchorX = e.getScreenX() - stage.getX();
            dragAnchorY = e.getScreenY() - stage.getY();
            dragging = true;
            // Drops the live blur for the duration of the drag. A shadow
            // recomputed at every new window position is the single biggest
            // cost in moving a translucent window, and it is invisible on a
            // window that is moving anyway. Restored on release.
            card.getStyleClass().add("float-timer-dragging");
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (stage == null || !dragging) {
                return;
            }
            // Deliberately nothing else here: no Screen lookup, no CSS change, no
            // clamping, no layout read. Every one of those costs far more than
            // the move itself, and at native mouse-report rates they are what
            // makes a drag feel like it is fighting something.
            stage.setX(e.getScreenX() - dragAnchorX);
            stage.setY(e.getScreenY() - dragAnchorY);
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (!dragging) {
                return;
            }
            endDrag();
            e.consume();
        });

        // The pointer leaving the window mid-drag ends the drag. A release
        // outside the window never reaches these filters, so without this the
        // widget would keep its dragging styling and would resume moving on the
        // next press-and-drag without re-anchoring to the press it actually
        // received.
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (dragging) {
                // Not consumed: the pointer really has left, and the exit is
                // not ours to hide from anything else.
                endDrag();
            }
        });
    }

    /**
     * Whether {@code node} is a button, or is contained by one.
     *
     * <p>Walks the ancestor chain rather than testing {@code instanceof} on the
     * target alone, because the target of a press on a button is very often one
     * of its CHILDREN - the graphic, the label, or an internal region - and
     * those are plain {@code Node}s that are not themselves buttons.
     */
    private static boolean isInsideButton(Object node) {
        javafx.scene.Node current = (node instanceof javafx.scene.Node n) ? n : null;
        while (current != null) {
            if (current instanceof javafx.scene.control.ButtonBase) {
                return true;
            }
            // Stop at the card: everything above it is the widget chrome, and a
            // press cannot be both on a button and on the card.
            if (current instanceof VBox) {
                return false;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * Finalises a drag and clamps the window back onto a screen.
     *
     * <p>The clamp runs HERE and nowhere else, which is the point. Keeping a
     * widget fully reachable is worth asking the window manager where the
     * screens are once per gesture; doing it on every drag event would query the
     * display topology hundreds of times a second.
     */
    private void endDrag() {
        dragging = false;
        if (card != null) {
            card.getStyleClass().remove("float-timer-dragging");
        }
        clampToScreen();
    }

    /**
     * Nudges the window back inside a screen if it was dragged out of one.
     *
     * <p>Only ever called on release. Deliberately a no-op when the window is
     * merely PARTLY off-screen: the user is allowed to park it against an edge,
     * and only a window that has lost its title bar from every monitor is
     * actually lost.
     */
    private void clampToScreen() {
        if (stage == null) {
            return;
        }
        try {
            // getScreensForArea does not exist; the rectangle overload is the
            // same query with the arguments assembled here.
            List<Screen> screens = Screen.getScreensForRectangle(
                    stage.getX(), stage.getY(),
                    Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
            if (screens.isEmpty()) {
                return;
            }
            Rectangle2D s = screens.get(0).getBounds();
            double x = stage.getX();
            double y = stage.getY();
            double maxX = s.getMaxX() - 40;
            double maxY = s.getMaxY() - 40;
            double clampedX = Math.max(s.getMinX(), Math.min(x, maxX));
            double clampedY = Math.max(s.getMinY(), Math.min(y, maxY));
            if (clampedX != x || clampedY != y) {
                stage.setX(clampedX);
                stage.setY(clampedY);
            }
        } catch (RuntimeException noDisplay) {
            // Headless or a transient display error: leaving the window where
            // the user put it is strictly better than refusing to.
            System.err.println("[FloatingTimer] Could not clamp the window to a screen: "
                    + noDisplay.getMessage());
        }
    }

    /** Exposed for the entrance animation, which needs the root not the card. */
    VBox getCard() {
        return card;
    }

    /**
     * The entrance animation.
     *
     * <p>Handled here rather than by the caller because this class knows the
     * widget's own shape, and the animation is a property of the widget. With
     * animations disabled {@link Anim} applies the end state immediately, so
     * the widget still appears fully opaque rather than stuck at zero.
     */
    void playEntrance() {
        if (card == null) {
            return;
        }
        Anim.slideUpIn(card, 14.0, Anim.emphasisMillis());
    }

    /**
     * Doubles as the widget's tear-down hook.
     *
     * <p>Called by FloatingTimerWindow when the stage is closed. It cancels the
     * entrance animation's pending state by forcing the end values, which
     * matters when the user docks the widget within the first 260ms: without
     * this the card would be left at partial opacity and partial offset when
     * the stage came back.
     */
    void prepareForReuse() {
        if (card == null) {
            return;
        }
        card.setOpacity(1.0);
        card.setTranslateY(0);
        lastRenderedSeconds = Long.MIN_VALUE;
        // Null, not a phase. The widget can be re-used across many sessions, and
        // a cached phase would let a reused widget skip rendering a state that
        // happens to match the one it last showed - while its clock, skill name
        // and status text are all about to change. Nulling it guarantees the
        // next update() renders its button unconditionally.
        lastPhase = null;
        lastStateText = null;
        // Also drops the paused styling, for the same reason: a widget closed
        // while paused must not come back dimmed if it is next shown idle.
        card.getStyleClass().remove("float-timer-paused");
    }
}
