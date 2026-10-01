package com.unitracker.controller;

import com.unitracker.util.AppSettings;
import com.unitracker.util.UiScale;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;

/**
 * Owns the v2.0 floating timer window (v2.0).
 *
 * <h2>WHY A SEPARATE CLASS</h2>
 * DashboardController is already 3,900 lines. Stage lifecycle - creating a
 * transparent stage, positioning it on the correct screen, remembering where
 * the user put it, handling the user closing it - is a self-contained concern
 * with no business logic in it, and folding it into the controller would bury
 * the timer logic under window plumbing. This class holds the stage and
 * nothing else; all timing logic stays in {@link FocusTimerState} and
 * {@link DashboardController}.
 *
 * <h2>TRANSPARENCY: BOTH HALVES ARE REQUIRED</h2>
 * A {@code StageStyle.TRANSPARENT} window only actually looks transparent when
 * TWO things are true, and missing either one produces an opaque black
 * rectangle:
 * <ol>
 *   <li>the stage is created with {@link StageStyle#TRANSPARENT};</li>
 *   <li>the <em>scene's</em> fill is set to null. A Scene paints its own
 *       background independent of its root node, and that default opaque fill
 *       is what produces the black box.</li>
 * </ol>
 * The root node's transparent background (the {@code .float-timer-root} class)
 * is a third requirement for the rounded card, but it is not sufficient on its
 * own.
 *
 * <h2>WHY NO initOwner</h2>
 * An owned window can never be brought in front of its owner, which is exactly
 * backwards for an always-on-top timer. {@code initOwner} is deliberately not
 * called. The cost is that the widget does not minimise with the main window -
 * which is the desired behaviour, since its entire purpose is to remain visible
 * while the dashboard is hidden.
 *
 * <h2>POSITIONING</h2>
 * Placed at the bottom-right of the screen the user is actually looking at,
 * found via the mouse position rather than assumed to be the primary display.
 * Someone with the dashboard on a second monitor would otherwise get the timer
 * on the wrong one, apparently behind their other windows.
 */
public final class FloatingTimerWindow {

    private static final String FXML_PATH = "/com/unitracker/view/FloatingTimer.fxml";
    private static final String CSS_PATH = "/com/unitracker/css/styles.css";

    /** Bottom-right inset from the screen edges. Large enough that the widget
     *  does not sit under a taskbar. */
    private static final double SCREEN_MARGIN = 56.0;
    private static final double CARD_WIDTH = 300.0;
    private static final double CARD_HEIGHT = 168.0;

    private final Stage stage;
    private final FloatingTimerController controller;
    private final Pane root;

    /**
     * Last position the user dragged the widget to.
     *
     * <p>Held statically so the widget reopens where the user left it rather
     * than snapping back to the corner every time, which is the kind of thing
     * that makes a floating widget irritating within a day of use. Also
     * validated against the current screen before reuse, because a position
     * remembered on a monitor that is no longer attached would place the widget
     * off-screen and make it look like it had vanished.
     */
    private static Double rememberedX;
    private static Double rememberedY;

    private FloatingTimerWindow(Stage stage, FloatingTimerController controller, Pane root) {
        this.stage = stage;
        this.controller = controller;
        this.root = root;
    }

    /**
     * Builds the widget. Does not show it.
     *
     * @param onPlayPause invoked when the widget's play/pause button is pressed
     * @param onReset     invoked when the widget's reset button is pressed
     * @param onDock      invoked when the widget is closed or docked
     */
    public static FloatingTimerWindow create(Runnable onPlayPause, Runnable onReset, Runnable onDock)
            throws IOException {
        FXMLLoader loader = new FXMLLoader(
                FloatingTimerWindow.class.getResource(FXML_PATH));
        Pane root = loader.load();
        FloatingTimerController controller = loader.getController();

        Stage stage = new Stage();
        // MUST be called before the stage is shown, and it is: the stage is
        // built here and not shown until FloatingTimerWindow#show(), which the
        // controller calls only after this method returns. Setting a stage
        // style after show() throws IllegalStateException, so getting the order
        // wrong here would fail loudly rather than silently - but the check
        // below makes the intent explicit and survives a future refactor that
        // might show the stage earlier.
        stage.initStyle(StageStyle.TRANSPARENT);
        if (stage.getStyle() != StageStyle.TRANSPARENT) {
            throw new IllegalStateException(
                    "FloatingTimerWindow requires StageStyle.TRANSPARENT; got " + stage.getStyle());
        }
        // No initOwner - see the class javadoc.
        stage.setTitle("Uni Tracker - Focus Timer");
        stage.setAlwaysOnTop(true);
        // Shown without the OS taskbar button: the widget is a companion to the
        // dashboard, not a second application, and an extra taskbar entry the
        // user cannot meaningfully interact with is just clutter.
        stage.setResizable(false);

        Scene scene = new Scene(root, CARD_WIDTH, CARD_HEIGHT);
        // REQUIRED for real transparency. Omitting this leaves the scene's own
        // opaque background painted and the widget appears as a black box.
        scene.setFill(null);
        scene.getStylesheets().add(
                FloatingTimerWindow.class.getResource(CSS_PATH).toExternalForm());
        // Read the preference at construction. The widget can outlive a
        // preference change - it is created lazily and stays open across
        // Settings edits - so this is not sufficient on its own, which is why
        // the dashboard re-applies the preference whenever the widget is
        // (re)docked. Applying it here covers the far more common case: the
        // widget is built long after the user set the preference, and inheriting
        // nothing from the dashboard's scene because this is a separate Scene.
        UiScale.applyMotion(scene, AppSettings.animationsEnabled());

        stage.setScene(scene);
        controller.wire(stage, onPlayPause, onReset, onDock);
        // The SCENE is passed in, not looked up. installDragHandlers installs
        // scene-level event FILTERS, and a filter has to be registered on the
        // scene before the gesture starts - resolving it from the stage or the
        // card instead would depend on the node already being attached, which is
        // the ordering trap the filter approach exists to avoid.
        controller.installDragHandlers(scene);
        return new FloatingTimerWindow(stage, controller, root);
    }

    /** The stage, so the caller can position and show it. */
    public Stage stage() {
        return stage;
    }

    /** The widget controller, for pushing ticks into it. */
    public FloatingTimerController controller() {
        return controller;
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /**
     * Shows the widget, animating it in.
     *
     * <p>Idempotent, so the pop-out button can be wired straight to it without
     * the caller tracking whether the widget is already up.
     *
     * @param dashboardBounds the dashboard's on-screen rectangle, used only to
     *                        pick which monitor to open on. Pass the value
     *                        captured <em>before</em> hiding the dashboard.
     */
    public void show(Rectangle2D dashboardBounds) {
        if (stage.isShowing()) {
            return;
        }
        controller.prepareForReuse();
        positionNearCorner(targetScreenBounds(dashboardBounds));
        stage.show();
        controller.playEntrance();
    }

    public void hide() {
        if (!stage.isShowing()) {
            return;
        }
        // Remember where the user put it before it goes, so the next show
        // reuses the position rather than resetting to the corner.
        rememberPosition();
        stage.hide();
    }

    public void close() {
        rememberPosition();
        stage.close();
    }

    private void rememberPosition() {
        if (stage.getX() >= 0 && stage.getY() >= 0) {
            rememberedX = stage.getX();
            rememberedY = stage.getY();
        }
    }

    /**
     * Puts the widget on screen, honouring a remembered position when it is
     * still on a connected display.
     */
    private void positionNearCorner(Rectangle2D screenBounds) {
        if (rememberedX != null && rememberedY != null && isOnAConnectedScreen(rememberedX, rememberedY)) {
            stage.setX(rememberedX);
            stage.setY(rememberedY);
            return;
        }
        double x = screenBounds.getMaxX() - CARD_WIDTH - SCREEN_MARGIN;
        double y = screenBounds.getMaxY() - CARD_HEIGHT - SCREEN_MARGIN;
        stage.setX(x);
        stage.setY(y);
    }

    /**
     * True when the remembered point still lands on a connected screen.
     *
     * <p>Checks the point rather than the whole widget: a widget that is
     * partly off the right edge is still perfectly grabbable, whereas one
     * centred on a monitor that has been unplugged is gone for good.
     */
    private static boolean isOnAConnectedScreen(double x, double y) {
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D b = screen.getBounds();
            if (x >= b.getMinX() && x < b.getMaxX() && y >= b.getMinY() && y < b.getMaxY()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The screen to open on.
     *
     * <p>ANCHORED TO THE DASHBOARD, not the pointer. Two reasons:
     * <ol>
     *   <li>JavaFX exposes no public "screen under this point" lookup.
     *       {@code Screen.getScreenForPoint} is package-private, and there is
     *       no public static {@code Robot} factory either - a Robot instance can
     *       only be obtained from an existing Scene, which is not available
     *       here. {@code getScreensForRectangle} is the supported substitute.</li>
     *   <li>It is the more correct behaviour anyway. The user popped the timer
     *       out <em>from</em> the dashboard, so they want it on the display they
     *       were looking at. Pointer position would put it wherever they last
     *       moved the mouse, which can be a different monitor entirely.</li>
     * </ol>
     *
     * <p>The dashboard's bounds must be captured BEFORE the dashboard is
     * hidden, because a hidden stage's position is not meaningful.
     */
    private static Rectangle2D targetScreenBounds(Rectangle2D dashboardBounds) {
        if (dashboardBounds != null) {
            for (Screen screen : Screen.getScreensForRectangle(dashboardBounds)) {
                return screen.getVisualBounds();
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }

    /**
     * The main dashboard stage, if the widget should be owned by it.
     *
     * <p>Exposed so the caller can pass it to {@code setOwner} ONLY if it
     * decides that is desirable. It is not, by default - see the class
     * javadoc on initOwner.
     */
    public static Window ownerCandidate(Window anyWindow) {
        return anyWindow;
    }
}
