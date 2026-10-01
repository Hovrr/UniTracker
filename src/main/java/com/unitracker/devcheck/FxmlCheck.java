package com.unitracker.devcheck;

import com.unitracker.util.AppSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventTarget;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Loads every FXML in the project for real and reports whether they wire up.
 *
 * <p>WHY THIS EXISTS: FXML is resolved by reflection at load time, so a typo in
 * an {@code onAction="#handler"}, an {@code fx:id} whose field was never
 * declared, or a missing {@code <?import?>} all compile perfectly and then throw
 * the instant the app starts. javac cannot see any of it. This is the cheapest
 * thing that turns those into a build-time failure.
 *
 * <p>v2.0 also loads {@code FloatingTimer.fxml} and verifies the three
 * properties that make a {@code StageStyle.TRANSPARENT} window actually look
 * transparent. Those are runtime-only failures - the FXML loads perfectly and
 * the widget appears as a black rectangle - so asserting them here is the only
 * way to catch them without a human looking at a screen.
 *
 * <p>RUN IT AGAINST A THROWAWAY DATABASE. {@code DashboardController#initialize}
 * reads and can write skills (applyStalledStatuses), and DatabaseHelper derives
 * its path from {@code user.home}. Always launch with
 * {@code -Duser.home=<some temp dir>} so a check run can never touch the real
 * ~/.unitracker/unitracker.db. See devcheck-fxml.py, which does exactly that.
 */
public class FxmlCheck extends Application {

    private static boolean dashboardOk = false;
    private static boolean floatingOk = false;
    private static boolean settingsOk = false;

    /** The no-motion override still mirrors every scaled CSS selector. */
    private static boolean motionOk = false;

    /** The override actually beats styles.css in the cascade, not just in text. */
    private static boolean motionEffectOk = false;

    /** Each scale tier lands on the resolutions it is meant for. */
    private static boolean tiersOk = false;

    /** The widget's icon buttons are vectors, not font-dependent glyphs. */
    private static boolean iconsOk = false;

    /** The widget's primary button works from every session phase. */
    private static boolean phaseUiOk = false;

    /** A discard resets the clock and the button immediately, in the widget. */
    private static boolean discardUiOk = false;

    /** The scene-level drag filters move the window and spare the buttons. */
    private static boolean dragFilterOk = false;

    /** The dashboard derives the same phase the widget is rendered from. */
    private static boolean dashboardPhaseOk = false;

    /** The progress bar survives every sidebar state at a 1080p viewport. */
    private static boolean layoutOk = false;

    /** The Advanced Log modal's layout and theming. */
    private static boolean advancedLogOk = false;

    /** The DatePicker popup is dark with legible text. */
    private static boolean advPopupOk = false;

    /** The real views actually render bigger at bigger tiers. */
    private static boolean scaleOk = false;

    /** The Settings preview button is compact and does not move on hover. */
    private static boolean previewOk = false;

    /** The chart gets its height back after a multi-skill mode. */
    private static boolean chartOk = false;

    /** No action-row control renders as an ellipsis. */
    private static boolean ellipsisOk = false;

    private static boolean resizeHitBoxOk = false;

    /** A themed region must be this dark at its darkest pixel. */
    private static final double DARK_BACKGROUND_MAX_LUMINANCE = 0.25;

    /** ...and must contain pixels at least this light, i.e. readable text. */
    private static final double LIGHT_TEXT_MIN_LUMINANCE = 0.70;

    /** The modal still fits after being toggled to the taller mode. */
    private static boolean advlogToggleOk = false;

    /** The loaded dashboard's scene, reused by the layout check. */
    private static Scene dashboardScene = null;

    @Override
    public void start(Stage stage) {
        try {
            // The controller expects the schema to exist; the app normally does
            // this in MainApp before loading any FXML.
            com.unitracker.db.DatabaseHelper.getInstance().initializeDatabase();

            // ---- Dashboard -------------------------------------------------
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/unitracker/view/Dashboard.fxml"));
            Parent root = loader.load();

            // Applying the stylesheet catches a malformed CSS file too, which is
            // otherwise only ever visible as an ugly warning in a running app.
            Scene scene = new Scene(root, 1440, 900);
            scene.getStylesheets().add(
                    getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());
            dashboardOk = true;

            // ---- Floating timer widget ------------------------------------
            floatingOk = checkFloatingTimer();

            // ---- Settings dialog -----------------------------------------
            settingsOk = checkSettingsDialog();

            // ---- Regression guards for the two UI bugs this fixed --------
            //
            // Both are checked here because neither is reachable by a compiler
            // and neither throws at runtime: a control that animates when it
            // should not, and a button that renders a missing-glyph box, are
            // both perfectly valid programs.
            motionOk = checkMotionOverrideMirrorsStylesheet();
            motionEffectOk = checkMotionOverrideBeatsBaseStylesheet();
            tiersOk = checkAutoScaleTiers();
            iconsOk = checkWidgetIconsAreVector();
            phaseUiOk = checkWidgetButtonWorksFromEveryPhase();
            discardUiOk = checkWidgetDiscardResetsEverythingImmediately();
            dragFilterOk = checkSceneLevelDragFilter();
            dashboardPhaseOk = checkDashboardPhaseDerivation(loader.getController());
            layoutOk = checkProgressBarSurvives1080p(scene, root);
            advancedLogOk = checkAdvancedLogDialog(loader.getController());
            advlogToggleOk = checkAdvancedLogFitsAfterToggle(loader.getController());
            advPopupOk = checkDatePickerPopupContrast(loader.getController());
            scaleOk = checkRealViewsScaleAcrossTiers();
            previewOk = checkPreviewButtonIsCompact();
            chartOk = checkChartRegainsHeightAfterMultiSkillModes(
                    loader.getController(), root, scene);
            ellipsisOk = checkActionRowHasNoEllipsis(root);
            resizeHitBoxOk = checkChartResizeHitBox(loader.getController(), root, scene);

            boolean allOk = dashboardOk && floatingOk && settingsOk
                    && motionOk && motionEffectOk && tiersOk && iconsOk
                    && phaseUiOk && discardUiOk && dragFilterOk && dashboardPhaseOk
                    && layoutOk && advancedLogOk && advlogToggleOk && advPopupOk && scaleOk && previewOk && chartOk && ellipsisOk && resizeHitBoxOk;
            System.out.println(allOk ? "FXML_OK" : "FXML_FAIL");
            Platform.exit();
            if (!allOk) {
                // Non-zero so the calling script fails loudly rather than
                // scrolling past a FAIL in the middle of the output.
                Runtime.getRuntime().halt(1);
            }
        } catch (Throwable t) {
            System.out.println("FXML_FAIL");
            t.printStackTrace();
            Platform.exit();
            // Non-zero so the calling script fails loudly rather than scrolling past.
            Runtime.getRuntime().halt(1);
        }
    }

    /**
     * Loads the widget and asserts the properties that only fail at runtime.
     *
     * <p>Each assertion here corresponds to a specific "the widget is broken and
     * I cannot tell you why" symptom:
     * <ul>
     *   <li>an opaque scene fill renders a black rectangle around the card;</li>
     *   <li>a non-transparent root paints over the transparent scene, producing
     *       the same black box by a different route;</li>
     *   <li>a missing stylesheet leaves the card unstyled, which still looks
     *       like a working window - just a hideous one;</li>
     *   <li>a missing controller means the widget cannot be driven at all.</li>
     * </ul>
     */
    private static boolean checkFloatingTimer() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/FloatingTimer.fxml"));
            javafx.scene.layout.Pane widgetRoot = loader.load();

            if (loader.getController() == null) {
                System.out.println("FLOATING_FAIL: FXML declared no controller");
                return false;
            }

            Stage widgetStage = new Stage(javafx.stage.StageStyle.TRANSPARENT);
            Scene widgetScene = new Scene(widgetRoot, 300, 168);
            widgetScene.setFill(null);
            widgetScene.getStylesheets().add(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
            widgetStage.setScene(widgetScene);

            if (widgetScene.getFill() != null) {
                System.out.println("FLOATING_FAIL: the scene fill must be null for transparency");
                return false;
            }
            if (!widgetScene.getStylesheets().contains(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm())) {
                System.out.println("FLOATING_FAIL: the stylesheet was not applied to the widget scene");
                return false;
            }

            // Force a CSS pass. Style-class selectors in lookup()/lookupAll()
            // only match once the node has been styled, and nothing has been
            // shown here, so without this the card would appear not to exist.
            widgetRoot.applyCss();
            widgetRoot.layout();

            // THE REAL CHECK: that the FXML and the stylesheet AGREE.
            //
            // Asserting via a CSS class selector in lookup() is unreliable
            // headless, because it depends on when JavaFX decides to resolve
            // styles. So this verifies the two halves separately and more
            // directly: the card node must actually CARRY the class (that is
            // the FXML's claim) and the stylesheet must actually DEFINE it
            // (that is the CSS's claim). A typo in either file fails here, and
            // so does the far more common mistake of renaming one and not the
            // other - which is what silently produces an unstyled widget that
            // still loads and still runs.
            javafx.scene.layout.VBox cardNode = (javafx.scene.layout.VBox) widgetRoot.lookup("#card");
            if (cardNode == null) {
                System.out.println("FLOATING_FAIL: fx:id 'card' did not resolve to a node");
                return false;
            }
            if (!cardNode.getStyleClass().contains("float-timer-card")) {
                // The single most common FXML mistake this harness exists to
                // catch: styleClass is COMMA-separated, and a space-separated
                // pair loads without error as one class literally named
                // "glass-panel float-timer-card", matching no CSS rule. The
                // widget then renders unstyled and still works, so nothing
                // else would ever notice.
                System.out.println("FLOATING_FAIL: the card does not carry the .float-timer-card "
                        + "class; actual classes = " + cardNode.getStyleClass()
                        + "  (FXML styleClass must be COMMA-separated)");
                return false;
            }
            String css = readStylesheet();
            for (String required : new String[]{".float-timer-card", ".float-timer-clock",
                    ".float-timer-root", ".float-timer-skill", ".float-timer-state"}) {
                if (!css.contains(required + " {")) {
                    System.out.println("FLOATING_FAIL: the stylesheet does not define " + required);
                    return false;
                }
            }
            // The widget's own root class MUST be transparent in CSS. A
            // non-transparent root paints over the nulled scene fill and
            // produces the black rectangle that is the classic symptom of
            // getting StageStyle.TRANSPARENT half-right.
            if (!css.contains(".float-timer-root") || !css.contains("background-color: transparent")) {
                System.out.println("FLOATING_FAIL: the widget root is not forced transparent in CSS");
                return false;
            }

            // Every fx:id in the widget must have been injected, or a button
            // silently does nothing at runtime.
            for (String id : new String[]{"card", "clockLabel", "skillLabel", "stateLabel",
                    "progressBar", "playPauseButton", "resetButton", "dockButton"}) {
                if (widgetRoot.lookup("#" + id) == null) {
                    System.out.println("FLOATING_FAIL: fx:id '" + id + "' did not resolve to a node");
                    return false;
                }
            }
            // The clock must fit the card. A widget whose clock fell back to a
            // font that is too wide would overflow the fixed 300px stage and
            // clip the buttons - so assert the resolved size is inside it.
            javafx.scene.control.Label clock = (javafx.scene.control.Label) widgetRoot.lookup("#clockLabel");
            if (clock == null) {
                System.out.println("FLOATING_FAIL: the clock label is missing");
                return false;
            }
            double clockWidth = clock.prefWidth(-1);
            if (clockWidth > 268.0) {
                System.out.println("FLOATING_FAIL: the clock would overflow the widget, needs "
                        + clockWidth + "px of 268px available");
                return false;
            }

            // Never shown: the harness is headless-ish and a visible
            // always-on-top window would block the run.
            widgetStage.hide();
            return true;
        } catch (Throwable t) {
            System.out.println("FLOATING_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Asserts that the no-motion override still mirrors {@code styles.css}.
     *
     * <p><b>WHAT REGRESSION THIS CATCHES.</b> The "Enable animations" setting
     * used to be a no-op for button motion, because every visible movement in
     * the application was a CSS {@code -fx-scale} on {@code :hover} and
     * {@code :pressed} - resolved by the styling engine, which never consults
     * the transition gate. The fix was an override sheet appended at runtime
     * (see {@code UiScale#NO_MOTION_CSS}), because JavaFX has no way to
     * conditionally include a rule.
     *
     * <p>That design buys correctness at the cost of a HARD COUPLING: the
     * override has to name every selector {@code styles.css} scales. Add a new
     * hover scale to {@code styles.css} and forget the override, and the
     * setting stops working for that control - with no error anywhere, and no
     * visible sign that anything regressed until a user reports the toggle
     * again. Both directions of the mistake are invisible; this is the check
     * that makes one of them loud.
     *
     * <p>Only the styles.css-to-override direction is asserted. The reverse is
     * deliberately not: an override entry for a selector that no longer scales
     * is dead CSS, harmless, and failing the build over it would train
     * developers to stop reading the failure.
     */
    private static boolean checkMotionOverrideMirrorsStylesheet() {
        final String css;
        try {
            css = readStylesheet();
        } catch (java.io.IOException e) {
            // Failing to READ styles.css is itself the bug this check is about,
            // and it must not be swallowed: it would otherwise report every
            // selector as unmirrored, or - worse - be caught by a caller that
            // treats an exception as a pass.
            System.out.println("MOTION_FAIL: could not read styles.css: " + e);
            return false;
        }
        String override = com.unitracker.util.UiScale.noMotionStylesheet();

        // Comments are stripped BEFORE the scan. The stylesheet is heavily
        // commented, and a comment that mentions a scale in prose would
        // otherwise be parsed as a rule - producing a selector that looks real,
        // fails the mirror check, and sends the reader hunting for a rule that
        // does not exist.
        StringBuilder live = new StringBuilder();
        int i = 0;
        while (i < css.length()) {
            if (css.startsWith("/*", i)) {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? css.length() : end + 2;
            } else {
                live.append(css.charAt(i));
                i++;
            }
        }
        String cleaned = live.toString();

        java.util.LinkedHashSet<String> scaled = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher rules =
                java.util.regex.Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(cleaned);
        while (rules.find()) {
            String body = rules.group(2);
            if (!body.contains("-fx-scale-x") && !body.contains("-fx-scale-y")) {
                continue;
            }
            for (String selector : rules.group(1).split(",")) {
                String trimmed = selector.trim();
                // A rule can be preceded on the same line by a previous rule's
                // closing brace only if the file is malformed, but a leading
                // newline or stray space is normal.
                if (!trimmed.isEmpty()) {
                    scaled.add(trimmed);
                }
            }
        }

        // Guards against the check passing VACUOUSLY. If the regex ever stops
        // matching - a stylesheet restructure, a nested rule - then "every
        // scaled selector is mirrored" would be true of nothing at all, and
        // this would report success while testing nothing. A count floor is
        // what turns that silent failure into a loud one.
        if (scaled.size() < 6) {
            System.out.println("MOTION_FAIL: expected at least 6 scaled selectors in styles.css, "
                    + "found " + scaled.size() + " - the scan itself is broken, "
                    + "so this check is currently proving nothing. Found: " + scaled);
            return false;
        }

        for (String selector : scaled) {
            if (!override.contains(selector)) {
                System.out.println("MOTION_FAIL: styles.css scales '" + selector
                        + "' but the no-motion override does not neutralise it, so that "
                        + "control will keep animating with animations disabled. Add '"
                        + selector + "' to UiScale.NO_MOTION_CSS.");
                return false;
            }
        }

        // The override must actually neutralise rather than merely mention: a
        // selector listed with the wrong value would satisfy the contains() above
        // and still animate.
        if (!override.contains("-fx-scale-x: 1") || !override.contains("-fx-scale-y: 1")) {
            System.out.println("MOTION_FAIL: the no-motion override does not pin the scale to 1");
            return false;
        }

        System.out.println("MOTION_OK: " + scaled.size() + " scaled selector(s) mirrored.");
        return true;
    }

    /**
     * Asserts the floating widget's icon buttons are NOT text glyphs.
     *
     * <p>Both buttons used to carry a symbol-plane codepoint in {@code text} -
     * U+23CB for dock, U+21BB for reset. Those live in obscure symbol fonts
     * that many Windows installations do not ship, and a missing glyph renders
     * as a filled box, so the button read as a solid white rectangle. Nothing
     * failed: the codepoint is valid, the button worked, and the FXML loaded.
     *
     * <p>Both are now {@code SVGPath} graphics, which JavaFX draws itself and
     * which therefore cannot depend on an installed font. This asserts the
     * shape of the fix rather than its appearance, because appearance needs a
     * screen: the button must have a graphic, and it must NOT still be carrying
     * a glyph in {@code text}.
     */
    private static boolean checkWidgetIconsAreVector() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/FloatingTimer.fxml"));
            javafx.scene.layout.Pane widgetRoot = loader.load();
            for (String id : new String[]{"dockButton", "resetButton"}) {
                javafx.scene.control.Button button =
                        (javafx.scene.control.Button) widgetRoot.lookup("#" + id);
                if (button == null) {
                    System.out.println("ICONS_FAIL: " + id + " did not resolve");
                    return false;
                }
                if (button.getGraphic() == null) {
                    System.out.println("ICONS_FAIL: " + id + " has no graphic; it is a text button, "
                            + "which is the missing-glyph-box bug. Use an SVGPath in <graphic>.");
                    return false;
                }
                // A glyph left behind in text would be drawn UNDER the graphic,
                // so the box would still appear.
                if (button.getText() != null && !button.getText().isEmpty()) {
                    System.out.println("ICONS_FAIL: " + id + " still carries text=\""
                            + button.getText() + "\" alongside its graphic; both would render.");
                    return false;
                }
                // The graphic must actually contain a vector. A Label or Image
                // would reintroduce a font or asset dependency respectively.
                //
                // Checked by TRAVERSAL rather than by lookup(".shape"): SVGPath
                // is a Shape subclass but it does not carry the "shape" style
                // class, and the graphic is a StackPane wrapper anyway, so a
                // selector-based check silently reports "no SVGPath" on a
                // perfectly correct button.
                if (!containsSvgPath(button.getGraphic())) {
                    System.out.println("ICONS_FAIL: " + id + "'s graphic contains no SVGPath, "
                            + "it holds a " + button.getGraphic().getClass().getSimpleName());
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            System.out.println("ICONS_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** Recursively tests a subtree for an {@code SVGPath}. */
    private static boolean containsSvgPath(javafx.scene.Node node) {
        if (node == null) {
            return false;
        }
        if (node instanceof javafx.scene.shape.SVGPath) {
            return true;
        }
        // Only a Parent has children, and getChildrenUnmodifiable lives there -
        // asking a plain Node for it is a compile error, not an empty list.
        if (!(node instanceof javafx.scene.Parent)) {
            return false;
        }
        // getChildrenUnmodifiable rather than getChildren: it does not build a
        // writable list as a side effect of asking a question.
        for (javafx.scene.Node child : ((javafx.scene.Parent) node).getChildrenUnmodifiable()) {
            if (containsSvgPath(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Proves the no-motion override actually WINS the cascade, by measuring it.
     *
     * <p>{@link #checkMotionOverrideMirrorsStylesheet()} only proves the two
     * files agree on selector names. That is necessary and not sufficient: the
     * entire mechanism rests on an unstated assumption about cascade order -
     * that a stylesheet appended to {@code Scene#getStylesheets()} later than
     * {@code styles.css} takes precedence over it.
     *
     * <p>That assumption is worth testing, because the failure mode is
     * invisible. JavaFX resolves equally-specific selectors in stylesheet
     * order, so this works today; but if a future change moved the base
     * stylesheet to be appended last, or gave a base rule higher specificity,
     * the override would still contain every correct selector, the mirror check
     * would still pass, and the toggle would still do nothing. Every check
     * would be green and the bug would be back.
     *
     * <p>So: hover a real button and read back the resolved scale, with the
     * override absent and present. Both halves are asserted, because "scale is
     * 1.0" alone would also be satisfied by a stylesheet that failed to apply
     * at all - which would turn this into a check that passes when everything
     * is broken.
     */
    private static boolean checkMotionOverrideBeatsBaseStylesheet() {
        try {
            double scaledWithBase = resolvedHoverScale(false);
            double scaledWithOverride = resolvedHoverScale(true);

            // Without the override the button MUST move, or there is no motion
            // to suppress and this check is testing nothing.
            if (Math.abs(scaledWithBase - 1.0) < 0.001) {
                System.out.println("MOTION_EFFECT_FAIL: an .icon-button at rest on hover resolved to scale "
                        + scaledWithBase + ", expected 1.06 from styles.css. The base stylesheet "
                        + "is not being applied, so this check cannot detect anything.");
                return false;
            }
            // With the override it MUST NOT move.
            if (Math.abs(scaledWithOverride - 1.0) > 0.001) {
                System.out.println("MOTION_EFFECT_FAIL: with the override applied the button still "
                        + "resolves to scale " + scaledWithOverride + ". The override did not win "
                        + "the cascade - most likely a stylesheet ordering or specificity change.");
                return false;
            }
            System.out.println("MOTION_EFFECT_OK: hover scale " + scaledWithBase
                    + " with motion, 1.0 without.");
            return true;
        } catch (Throwable t) {
            System.out.println("MOTION_EFFECT_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * The scale a hovered {@code .icon-button} actually resolves to.
     *
     * @param withOverride whether to append the generated no-motion sheet
     */
    private static double resolvedHoverScale(boolean withOverride) {
        javafx.scene.control.Button button = new javafx.scene.control.Button();
        // getStyleClass().add, NOT setStyleClass: setStyleClass is an FXMLLoader
        // convention with no counterpart method on the node. The returned list
        // is the live one, so adding to it is what actually styles the button.
        button.getStyleClass().add("icon-button");
        javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(button);
        Scene scene = new Scene(root, 100, 100);
        scene.getStylesheets().add(
                FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
        if (withOverride) {
            // Exactly what UiScale.applyMotion does, via its public entry point,
            // so this exercises the real code path rather than a reconstruction
            // of it that could drift.
            com.unitracker.util.UiScale.applyMotion(scene, false);
        }
        // Drive the pseudo-class directly. A headless harness never receives a
        // real hover, and this is what the :hover selectors actually match on.
        button.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("hover"), true);
        // A fresh pulse would also do, but the explicit pass makes the check
        // independent of when the harness happens to run relative to a frame.
        root.applyCss();
        root.layout();
        return button.getScaleX();
    }

    /**
     * Asserts each scale tier lands on the resolutions it is meant for.
     *
     * <p>This exists because the boundaries were once wrong in a way nobody
     * could see. {@code 1920x1080} has a diagonal of 2202.91, and the old rule
     * read {@code diagonal < 2200 ? DEFAULT : LARGE} - so the single resolution
     * the 1K layout was designed and calibrated against was handed the 15.5px
     * "large" tier. The rule's own comment claimed 1080p got the baseline; the
     * code did not do that, and no check contradicted the comment.
     *
     * <p>Written against the exact diagonals rather than as a fuzz range,
     * because the failure was a 3-pixel overshoot: any threshold test loose
     * enough to miss that would also miss every threshold error worth catching.
     */
    private static boolean checkAutoScaleTiers() {
        //  display,        diagonal,  expected
        Object[][] cases = {
                {"1366x768", 1366.0, 768.0, com.unitracker.util.UiScale.Scale.COMPACT},
                {"1600x900", 1600.0, 900.0, com.unitracker.util.UiScale.Scale.COMPACT},
                {"1920x1080", 1920.0, 1080.0, com.unitracker.util.UiScale.Scale.DEFAULT},
                {"2560x1080", 2560.0, 1080.0, com.unitracker.util.UiScale.Scale.DEFAULT},
                {"2560x1440", 2560.0, 1440.0, com.unitracker.util.UiScale.Scale.DEFAULT},
                {"3440x1440", 3440.0, 1440.0, com.unitracker.util.UiScale.Scale.LARGE},
                {"3840x2160", 3840.0, 2160.0, com.unitracker.util.UiScale.Scale.EXTRA_LARGE},
                {"5120x2880", 5120.0, 2880.0, com.unitracker.util.UiScale.Scale.EXTRA_LARGE},
        };
        for (Object[] c : cases) {
            String label = (String) c[0];
            double diagonal = Math.hypot((Double) c[1], (Double) c[2]);
            com.unitracker.util.UiScale.Scale actual =
                    com.unitracker.util.UiScale.Scale.forDiagonal(diagonal);
            if (actual != c[3]) {
                System.out.println("TIERS_FAIL: " + label + " (diagonal " + diagonal
                        + ") resolves to " + actual + ", expected " + c[3]);
                return false;
            }
        }

        // THE INVARIANT THIS WHOLE FEATURE RESTS ON.
        //
        // The generated tier sheet is APPENDED AFTER styles.css, so it wins the
        // cascade. That makes the DEFAULT tier the only thing standing between
        // "I picked the default size" and "the interface changed anyway": if the
        // generated values drift from the hand-written ones, choosing the
        // baseline visibly changes the app.
        //
        // So rather than assert the generated sheet is non-empty - which is
        // what a smoke test would do, and which passed while I was checking the
        // wrong stylesheet entirely - this compares the generated DEFAULT values
        // against the values styles.css actually ships.
        String generated = com.unitracker.util.UiScale.buildScaleStylesheet(
                com.unitracker.util.UiScale.Scale.DEFAULT);
        String base = readBaseStylesheetUnchecked();
        if (base == null) {
            System.out.println("TIERS_FAIL: could not read styles.css");
            return false;
        }
        // selector -> the property whose absolute value must agree.
        String[][] agree = {
                {".root", "-fx-font-size: 13px"},
                {".glass-panel", "-fx-padding: 16px"},
                {".glass-panel", "-fx-background-radius: 18px"},
                {".accent-button", "-fx-padding: 8px 18px"},
                {".secondary-button", "-fx-padding: 8px 16px"},
                {".icon-button", "-fx-padding: 2px 8px"},
                {".search-field", "-fx-padding: 7px 12px"},
                // The chart resize handle. Listed here for the same reason as the
                // rest: the value in styles.css and the DEFAULT tier's generated
                // value must be textually identical, so picking the default size
                // cannot change the grab height.
                //
                // Written as four numbers rather than two because UiScale emits
                // left and right separately - and it has to, since each is
                // 7.5 * factor. Collapsed to "0 7.5px" the two files would
                // disagree on text while meaning the same thing, which this
                // comparison would report as a mismatch.
                {".main-split-pane .split-pane-divider", "-fx-padding: 0px 7.5px 7.5px"},
        };
        // styles.css uses JavaFX's unitless shorthand ("8 18") in places, which
        // means the same thing as "8px 18px" but is not string-equal. Comparing
        // the raw text would have reported a false mismatch and, worse, invited
        // someone to "fix" one side to match the other.
        String normalisedBase = normalisePadding(base);
        // Both sides go through the same normalisation, otherwise a value that
        // is written as a shorthand on one side and in full on the other reads as
        // a disagreement when it is not one.
        String normalisedGenerated = normalisePadding(generated);
        for (String[] pair : agree) {
            String selector = pair[0];
            String declaration = pair[1];
            if (!normalisedBase.contains(declaration)) {
                System.out.println("TIERS_FAIL: styles.css does not declare \"" + declaration
                        + "\" for " + selector + ", so the DEFAULT tier can no longer be "
                        + "proven to match it. Either the baseline spacing changed or this "
                        + "list needs updating.");
                return false;
            }
            if (!normalisedGenerated.contains(declaration)) {
                System.out.println("TIERS_FAIL: the DEFAULT tier generates \"" + declaration
                        + "\" for " + selector + " differently from styles.css. The generated "
                        + "sheet is appended last and wins the cascade, so choosing the "
                        + "default size would change the interface.");
                return false;
            }
        }

        // And the inverse: the generated sheet must not introduce spacing for a
        // selector the stylesheet leaves alone. A first version of this method
        // did exactly that for three selectors, inventing padding that never
        // existed.
        java.util.regex.Matcher gen = java.util.regex.Pattern
                .compile("([^{}]+)\\{([^{}]*)\\}").matcher(stripComments(generated));
        while (gen.find()) {
            if (!gen.group(2).contains("-fx-padding")
                    && !gen.group(2).contains("-fx-background-radius")) {
                continue;
            }
            // group(1) carries the whitespace between the previous rule's "}"
            // and this selector, so it must be trimmed and collapsed before it
            // can be compared to anything. The first version of this line
            // produced " ..glass-panel" and then failed to recognise a selector
            // that was in the known list all along.
            String selector = gen.group(1).trim().replaceAll("\\s+", " ");
            boolean known = false;
            for (String[] pair : agree) {
                if (pair[0].equals(selector)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                System.out.println("TIERS_FAIL: the generated sheet adds spacing for "
                        + selector + ", which styles.css does not declare. That invents "
                        + "styling rather than scaling it.");
                return false;
            }
        }

        System.out.println("TIERS_OK: " + cases.length + " resolution(s) on the intended tier; "
                + "DEFAULT provably matches the hand-written spacing.");
        return true;
    }

    /**
     * Rewrites {@code -fx-padding: 8 18} as {@code -fx-padding: 8px 18px}.
     *
     * <p>Extended beyond its original form because a two-integer regex silently
     * corrupted fractional values: {@code -fx-padding: 0 7.5px 7.5px} was
     * matched as "0 7" and rewritten to {@code 0px 7px.5px 7.5px}, which then
     * failed to match itself. Anything with a decimal point or a value count
     * other than two was mangled rather than reported. Values that already carry
     * a unit are left alone, so this stays a no-op for the common case.
     */
    private static String normalisePadding(String css) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-fx-padding:\\s*([^;\\n}]+)").matcher(css);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(css, last, m.start());
            StringBuilder value = new StringBuilder();
            for (String part : m.group(1).trim().split("\\s+")) {
                if (value.length() > 0) {
                    value.append(' ');
                }
                value.append(part.matches("\\d+(\\.\\d+)?") ? part + "px" : part);
            }
            out.append("-fx-padding: ").append(value);
            last = m.end();
        }
        out.append(css.substring(last));
        return out.toString();
    }

    /**
     * Loads the widget wired to real callbacks, with its scene already styled.
     *
     * <p>Shared by the three behavioural checks below so that none of them has
     * to hand-roll a partially-configured widget - a check that forgets to call
     * {@code wire} would see disabled buttons and report a failure that has
     * nothing to do with the bug under test.
     *
     * @param harness the live widget, or null to build one with no-op callbacks
     */
    private static FloatingHarness newWidget(String label) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/FloatingTimer.fxml"));
            javafx.scene.layout.Pane root = loader.load();
            com.unitracker.controller.FloatingTimerController controller =
                    loader.getController();
            Stage stage = new Stage(javafx.stage.StageStyle.TRANSPARENT);
            Scene scene = new Scene(root, 300, 168);
            scene.setFill(null);
            scene.getStylesheets().add(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
            stage.setScene(scene);

            FloatingHarness h = new FloatingHarness(controller, root, stage, scene);
            // wire() with no-op callbacks: these checks are about the widget's
            // own rendering and hit-testing, not about the dashboard behind it.
            // Reached reflectively because it is package-private by design -
            // only FloatingTimerWindow should be wiring the widget's callbacks.
            try {
                java.lang.reflect.Method m = controller.getClass()
                        .getDeclaredMethod("wire", Stage.class, Runnable.class,
                                Runnable.class, Runnable.class);
                m.setAccessible(true);
                m.invoke(controller, stage, (Runnable) h::countPlayPause,
                        (Runnable) h::countReset, (Runnable) () -> { });
            } catch (ReflectiveOperationException e) {
                System.out.println(label + "_FAIL: could not wire the widget: " + e);
                return null;
            }
            return h;
        } catch (Throwable t) {
            System.out.println(label + "_FAIL: could not build the widget: " + t);
            t.printStackTrace();
            return null;
        }
    }

    /** A loaded, wired widget plus counters for the callbacks it fires. */
    private static final class FloatingHarness {
        final com.unitracker.controller.FloatingTimerController controller;
        final javafx.scene.layout.Pane root;
        final Stage stage;
        final Scene scene;
        int playPauseClicks;
        int resetClicks;

        FloatingHarness(com.unitracker.controller.FloatingTimerController controller,
                        javafx.scene.layout.Pane root, Stage stage, Scene scene) {
            this.controller = controller;
            this.root = root;
            this.stage = stage;
            this.scene = scene;
        }

        void countPlayPause() {
            playPauseClicks++;
        }

        void countReset() {
            resetClicks++;
        }

        javafx.scene.control.Button playPause() {
            return (javafx.scene.control.Button) root.lookup("#playPauseButton");
        }

        javafx.scene.control.Label clock() {
            return (javafx.scene.control.Label) root.lookup("#clockLabel");
        }

        javafx.scene.control.Label state() {
            return (javafx.scene.control.Label) root.lookup("#stateLabel");
        }

        void dispose() {
            stage.hide();
        }
    }

    /**
     * The widget's primary button must work from EVERY phase.
     *
     * <p>This is the reported bug, verbatim: pop the widget out while the
     * session is paused or idle, and the Start/Resume button did nothing. The
     * cause was that {@code initialize()} disabled both buttons and only
     * {@code update()} re-enabled them, inside {@code if (running !=
     * lastRunningState)} - which is false on the very first push whenever the
     * session was not running. So the button stayed permanently disabled, with
     * no error and no visual difference from "working".
     *
     * <p>Asserted per phase, and the enabled state is checked SEPARATELY from
     * the firing: a button that is disabled reports nothing when clicked, so
     * "the click did nothing" and "the click was never delivered" look identical
     * from the outside. Checking the text alone would also pass on a widget
     * that labelled itself correctly and still could not be pressed.
     */
    private static boolean checkWidgetButtonWorksFromEveryPhase() {
        FloatingHarness h = newWidget("PHASE_UI");
        if (h == null) {
            return false;
        }
        try {
            for (com.unitracker.util.FocusTimerState.Phase phase
                    : com.unitracker.util.FocusTimerState.Phase.values()) {

                // Fresh controller state per phase, because a reused widget
                // caches the last rendered phase and would legitimately skip a
                // re-render - which is the bug in reverse.
                FloatingHarness fresh = newWidget("PHASE_UI");
                if (fresh == null) {
                    return false;
                }
                fresh.controller.update(60 * 60, 60 * 60, phase, "Testing", phase.name());

                javafx.scene.control.Button button = fresh.playPause();
                if (button == null) {
                    System.out.println("PHASE_UI_FAIL: playPauseButton did not resolve");
                    return false;
                }
                if (!button.getText().equals(phase.actionLabel())) {
                    System.out.println("PHASE_UI_FAIL: in " + phase + " the button reads \""
                            + button.getText() + "\", expected \"" + phase.actionLabel() + "\"");
                    return false;
                }
                if (button.isDisabled()) {
                    System.out.println("PHASE_UI_FAIL: in " + phase
                            + " the button is DISABLED. This is the reported bug: it was "
                            + "disabled in initialize() and only re-enabled from a delta on the "
                            + "running flag, which cannot fire on the first push when the "
                            + "session was not running.");
                    return false;
                }
                // And it must actually dispatch.
                button.fire();
                if (fresh.playPauseClicks != 1) {
                    System.out.println("PHASE_UI_FAIL: clicking the " + phase
                            + " button dispatched " + fresh.playPauseClicks
                            + " callbacks, expected 1");
                    return false;
                }
                // The reset button must be live in every phase too: it is the
                // other half of "the widget's controls are usable".
                javafx.scene.control.Button reset =
                        (javafx.scene.control.Button) fresh.root.lookup("#resetButton");
                if (reset == null || reset.isDisabled()) {
                    System.out.println("PHASE_UI_FAIL: reset is unavailable in " + phase);
                    return false;
                }
                reset.fire();
                if (fresh.resetClicks != 1) {
                    System.out.println("PHASE_UI_FAIL: reset dispatched " + fresh.resetClicks
                            + " callbacks in " + phase + ", expected 1");
                    return false;
                }
                fresh.dispose();
            }
            System.out.println("PHASE_UI_OK: the widget's button works from IDLE, PAUSED and RUNNING.");
            return true;
        } finally {
            h.dispose();
        }
    }

    /**
     * A discard must reset the clock and the button IMMEDIATELY.
     *
     * <p>Reported symptom: the countdown stopped but the clock froze at the
     * abandoned time and the button kept reading "Pause", and only recovered
     * after the user pressed Pause. That is exactly what happens when the reset
     * path refreshes one view and not the other, and nothing self-heals because
     * the ticker has been stopped - so the frozen display persists until some
     * unrelated event happens to push.
     *
     * <p>The widget half is asserted here by driving it through the exact
     * sequence the dashboard performs. The dashboard half is covered by
     * {@link #checkDashboardPhaseDerivation}.
     */
    private static boolean checkWidgetDiscardResetsEverythingImmediately() {
        FloatingHarness h = newWidget("DISCARD_UI");
        if (h == null) {
            return false;
        }
        try {
            // A running session at 42:17, then discarded.
            h.controller.update(42 * 60 + 17, 60 * 60,
                    com.unitracker.util.FocusTimerState.Phase.RUNNING,
                    "Deep Work", "Running - worth 12 pts");
            if (!h.playPause().getText().equals("Pause")) {
                System.out.println("DISCARD_UI_FAIL: setup failed, expected Pause, got \""
                        + h.playPause().getText() + "\"");
                return false;
            }
            if (!h.clock().getText().equals("42:17")) {
                System.out.println("DISCARD_UI_FAIL: setup failed, expected 42:17, got \""
                        + h.clock().getText() + "\"");
                return false;
            }

            // Now the discard push: the full duration, and IDLE. This is what
            // reloadTimerDuration() sends after clearing the session.
            h.controller.update(60 * 60, 60 * 60,
                    com.unitracker.util.FocusTimerState.Phase.IDLE,
                    "Deep Work", "Ready - 01:00:00");

            if (!h.clock().getText().equals("01:00:00")) {
                System.out.println("DISCARD_UI_FAIL: after a discard the clock reads \""
                        + h.clock().getText() + "\", expected the full 01:00:00. "
                        + "A frozen clock is the reported symptom.");
                return false;
            }
            if (!h.playPause().getText().equals("Start")) {
                System.out.println("DISCARD_UI_FAIL: after a discard the button reads \""
                        + h.playPause().getText() + "\", expected Start. The button stayed "
                        + "on Pause, so pressing it would have paused a discarded session.");
                return false;
            }
            if (h.state().getText() == null || !h.state().getText().startsWith("Ready")) {
                System.out.println("DISCARD_UI_FAIL: after a discard the subtitle reads \""
                        + h.state().getText() + "\", expected it to start with Ready.");
                return false;
            }
            // The progress bar must go back to empty, not stay where it was.
            javafx.scene.control.ProgressBar bar =
                    (javafx.scene.control.ProgressBar) h.root.lookup("#progressBar");
            if (bar == null || bar.getProgress() > 0.001) {
                System.out.println("DISCARD_UI_FAIL: after a discard the progress bar reads "
                        + (bar == null ? "absent" : bar.getProgress())
                        + ", expected 0.");
                return false;
            }

            // And the button must be immediately usable, not merely relabelled.
            h.playPause().fire();
            if (h.playPauseClicks != 1) {
                System.out.println("DISCARD_UI_FAIL: the button is labelled Start after a "
                        + "discard but does not dispatch.");
                return false;
            }
            System.out.println("DISCARD_UI_OK: clock, button, subtitle and progress all reset.");
            return true;
        } finally {
            h.dispose();
        }
    }

    /**
     * The drag must be driven by SCENE filters, and must spare the buttons.
     *
     * <p>Driven with synthetic events rather than inspected: the bug was a drag
     * that stopped when the cursor crossed a control, and the only way to catch
     * that is to fire a drag at a point over a control and check the window
     * actually moved. A reflection check for "was a filter registered" would pass
     * on the old broken code just as happily.
     *
     * <p>Synthetic coordinates are supplied in SCREEN space and the stage is
     * parked at a known position, so the expected result is arithmetic rather
     * than "moved somehow".
     */
    private static boolean checkSceneLevelDragFilter() {
        FloatingHarness h = newWidget("DRAG");
        if (h == null) {
            return false;
        }
        try {
            com.unitracker.controller.FloatingTimerController controller = h.controller;
            // installDragHandlers is package-private, as it is in production; the
            // harness is in a different package, so it goes through the same
            // public entry point the window uses.
            installDragHandlersReflectively(controller, h.scene);

            h.stage.setX(400);
            h.stage.setY(300);

            // Fired AT THE CARD, not at the scene. The filter inspects
            // event.getTarget(), and an event fired at the Scene has the Scene as
            // its target - so firing there would make every press look like a
            // press on empty space, and the button-exclusion path below would
            // never be exercised at all.
            javafx.scene.layout.VBox card = (javafx.scene.layout.VBox) h.root.lookup("#card");
            if (card == null) {
                System.out.println("DRAG_FAIL: fx:id 'card' did not resolve");
                return false;
            }
            // Press on the card's background, well away from any control, then
            // drag down and right by a known amount.
            fireMouse(card, MouseEvent.MOUSE_PRESSED, 60, 20, 460, 320);
            fireMouse(card, MouseEvent.MOUSE_DRAGGED, 160, 120, 560, 420);
            fireMouse(card, MouseEvent.MOUSE_RELEASED, 160, 120, 560, 420);

            if (Math.abs(h.stage.getX() - 500) > 1 || Math.abs(h.stage.getY() - 400) > 1) {
                System.out.println("DRAG_FAIL: a drag of +100/+100 from a press at screen "
                        + "(460,320) left the window at (" + h.stage.getX() + "," + h.stage.getY()
                        + "), expected (500,400). The scene-level filter did not move it.");
                return false;
            }

            // A press ON A BUTTON must not start a drag, or Pause and Reset would
            // be unusable. The reset button is at the card's bottom right; the
            // harness looks it up and presses its centre in scene coordinates.
            javafx.scene.control.Button reset =
                    (javafx.scene.control.Button) h.root.lookup("#resetButton");
            if (reset == null) {
                System.out.println("DRAG_FAIL: resetButton did not resolve");
                return false;
            }
            h.root.applyCss();
            h.root.layout();
            javafx.geometry.Bounds bounds = reset.localToScene(reset.getBoundsInLocal());
            double sx = h.stage.getX() + bounds.getMinX() + bounds.getWidth() / 2;
            double sy = h.stage.getY() + bounds.getMinY() + bounds.getHeight() / 2;
            double before = h.stage.getX();
            // At the BUTTON, so its target really is the control.
            fireMouse(reset, MouseEvent.MOUSE_PRESSED,
                    bounds.getMinX() + bounds.getWidth() / 2,
                    bounds.getMinY() + bounds.getHeight() / 2, sx, sy);
            fireMouse(reset, MouseEvent.MOUSE_DRAGGED,
                    bounds.getMinX() + bounds.getWidth() / 2 + 80,
                    bounds.getMinY() + bounds.getHeight() / 2 + 80,
                    sx + 80, sy + 80);
            fireMouse(reset, MouseEvent.MOUSE_RELEASED,
                    bounds.getMinX() + bounds.getWidth() / 2 + 80,
                    bounds.getMinY() + bounds.getHeight() / 2 + 80,
                    sx + 80, sy + 80);
            if (Math.abs(h.stage.getX() - before) > 1) {
                System.out.println("DRAG_FAIL: a drag that started on the reset button moved the "
                        + "window to " + h.stage.getX() + ". Presses on a control must be left "
                        + "to that control.");
                return false;
            }

            // The filter must also leave the press UNCONSUMED, so the control
            // still receives it. That is the property that keeps Pause and Reset
            // usable, and it is a different property from "the window did not
            // move" - a filter that consumed every press would pass the check
            // above while making the widget's buttons dead, which is the bug
            // being fixed. Verified by observing the dispatch itself.
            if (h.resetClicks != 1) {
                System.out.println("DRAG_FAIL: a press+release on the reset button dispatched "
                        + h.resetClicks + " callbacks, expected 1. The drag filter must consume "
                        + "the press ONLY when it is actually taking the drag.");
                return false;
            }

            System.out.println("DRAG_OK: the scene filter moves the window and leaves buttons "
                    + "clickable.");
            return true;
        } finally {
            h.dispose();
        }
    }

    private static void installDragHandlersReflectively(
            com.unitracker.controller.FloatingTimerController controller, Scene scene) {
        try {
            java.lang.reflect.Method m =
                    com.unitracker.controller.FloatingTimerController.class
                            .getDeclaredMethod("installDragHandlers", Scene.class);
            m.setAccessible(true);
            m.invoke(controller, scene);
        } catch (ReflectiveOperationException e) {
            System.out.println("DRAG_FAIL: installDragHandlers(Scene) is missing or "
                    + "inaccessible: " + e);
        }
    }

    /**
     * Fires a mouse event into the scene, with both scene- and screen-space
     * coordinates set consistently.
     *
     * <p>Both are needed: the drag arithmetic reads {@code getScreenX()}, and
     * {@link MouseEvent}'s constructor takes scene coordinates, so a synthetic
     * event with a mismatched pair would move the window by the wrong amount and
     * make the assertion fail for a reason that has nothing to do with the code.
     */
    private static void fireMouse(EventTarget target, javafx.event.EventType<MouseEvent> type,
                                  double sceneX, double sceneY, double screenX, double screenY) {
        MouseEvent e = new MouseEvent(type, sceneX, sceneY, screenX, screenY,
                MouseButton.PRIMARY, 1,
                false, false, false, false,
                false, false, false, false, false, false,
                null);
        javafx.event.Event.fireEvent(target, e);
    }

    /**
     * The dashboard must derive IDLE and PAUSED distinctly, because both are
     * "not running".
     *
     * <p>This is the logic the widget is rendered from. If it reported PAUSED as
     * IDLE, the widget would offer "Start" for a paused session - and Start is
     * wired to the same handler that begins a fresh session, so one click would
     * discard the paused work. Asserted through the real controller by setting
     * the three fields the phase is derived from.
     */
    private static boolean checkDashboardPhaseDerivation(Object controller) {
        if (controller == null) {
            System.out.println("DASH_PHASE_FAIL: Dashboard.fxml declared no controller");
            return false;
        }
        try {
            Class<?> c = controller.getClass();
            java.lang.reflect.Field running = c.getDeclaredField("pomodoroRunning");
            java.lang.reflect.Field frozen = c.getDeclaredField("pomodoroFrozenRemaining");
            java.lang.reflect.Field phase = c.getDeclaredField("pomodoroFrozenRemaining");
            running.setAccessible(true);
            frozen.setAccessible(true);
            phase.setAccessible(true);
            java.lang.reflect.Method timerPhase =
                    c.getDeclaredMethod("timerPhase");
            timerPhase.setAccessible(true);

            // Idle: nothing running, nothing to resume.
            running.setBoolean(controller, false);
            frozen.setLong(controller, 0L);
            expectPhase(timerPhase, controller,
                    com.unitracker.util.FocusTimerState.Phase.IDLE, "idle");

            // Paused: not running, but there IS time on the clock to resume.
            // This is the case that matters - getting the order wrong here is
            // what would make a paused session look idle.
            running.setBoolean(controller, false);
            frozen.setLong(controller, 900L);
            expectPhase(timerPhase, controller,
                    com.unitracker.util.FocusTimerState.Phase.PAUSED, "paused");

            // Running wins over a stale frozen value, which is what a resume
            // that has not yet cleared it would look like.
            running.setBoolean(controller, true);
            frozen.setLong(controller, 900L);
            expectPhase(timerPhase, controller,
                    com.unitracker.util.FocusTimerState.Phase.RUNNING, "running");

            // Leave the controller as the rest of the harness expects it.
            running.setBoolean(controller, false);
            frozen.setLong(controller, 0L);

            System.out.println("DASH_PHASE_OK: idle, paused and running are distinguished.");
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("DASH_PHASE_FAIL: could not drive the dashboard's phase: " + e);
            return false;
        }
    }

    private static void expectPhase(java.lang.reflect.Method timerPhase, Object controller,
                                    com.unitracker.util.FocusTimerState.Phase expected,
                                    String what) throws ReflectiveOperationException {
        Object actual = timerPhase.invoke(controller);
        if (actual != expected) {
            throw new IllegalStateException(
                    "the dashboard reports " + what + " as " + actual + ", expected " + expected);
        }
    }

    /** The minimum rendered height the skill progress bar must never fall below. */
    private static final double PROGRESS_BAR_MIN_HEIGHT = 8.0;

/** The Advanced Log dialog pane width, mirrored from its prefWidth in
 *  DashboardController. */
    private static final double ADVLOG_WIDTH = 460.0;

    /** Widths the dialog's labels are measured at. See the check for why more
     *  than one: a truncation assertion at a single width can pass whether or
     *  not the labels are actually pinned. */
    private static final double[] ADVLOG_WIDTHS = {460.0, 380.0};

    /** The 1080p viewport this layout has to survive: a maximised 1920x1080
     *  window's usable height is around this once the title bar is gone. */
    private static final double VIEWPORT_1080P_HEIGHT = 900.0;

    /**
     * Viewport heights the layout has to survive, shortest first.
     *
     * <p><b>WHY 900px ALONE IS NOT ENOUGH, AND WHY THIS LOOKS LIKE OVERKILL.</b>
     * The first version of this check ran only at 900px, and it passed even with
     * the progress bar's height pin deleted - which made it worthless. The reason
     * is that fixing the SIDEBAR removed the height demand altogether, so at
     * 900px the right panel has slack and nothing is ever compressed. A check
     * that can only fail in a state the fix has made unreachable proves nothing
     * about the pin.
     *
     * <p>So the viewport is also squeezed below the point where the right panel
     * is comfortable. The user's requirement is that the bar must NEVER shrink,
     * "under any window size", and a window height of 480 is a real thing - a
     * half-screen window on a large display, or a maximised window on a laptop
     * with a short viewport. These heights are the ones that actually generate
     * compression, which is what makes the assertion mean something.
     */
    private static final double[] VIEWPORT_HEIGHTS = {900.0, 780.0, 660.0, 560.0, 480.0};

    /**
     * The skill progress bar must never be crushed, in ANY sidebar state.
     *
     * <p><b>THE REGRESSION THIS CATCHES.</b> On a 1080p display, expanding
     * Sticky Notes made the left column's preferred height exceed the window's.
     * Because the centre row is an HBox, the whole row then grew to its tallest
     * column; the right panel was taller than the window, the chart got no room,
     * and the right VBox under vertical compression shrank whatever was cheapest
     * to shrink - which was the progress bar, down to almost nothing. It
     * disappeared entirely, with no error and no clipping message: it had simply
     * been laid out at two pixels tall.
     *
     * <p>All eight combinations of the three sidebar sections are exercised, not
     * just the reported one. "All expanded" is the case that was broken, but a
     * fix that only survives that case would still be fragile, and the states
     * interact: collapsing one section gives the others room, and which section
     * absorbs it is not obvious from reading the FXML.
     *
     * <p>Asserted twice per state. The rendered height must clear
     * {@link #PROGRESS_BAR_MIN_HEIGHT}, which is the reported symptom. And it
     * must equal the bar's own preferred height, which is the mechanism -
     * {@code minHeight="-Infinity"} means "never smaller than I want to be", so
     * anything else means the pin is not actually in force and the assertion is
     * passing for the wrong reason.
     */
    private static boolean checkProgressBarSurvives1080p(Scene scene, Parent root) {
        try {
            // BEFORE any lookup. lookup("#id") is resolved by the CSS engine, so
            // on a root that has never been styled it returns null for every id -
            // which looks exactly like "the fx:id is missing from the FXML" and
            // sends you looking in the wrong file.
            root.applyCss();

            javafx.scene.control.ProgressBar bar = (javafx.scene.control.ProgressBar)
                    root.lookup("#mainProgressBar");
            if (bar == null) {
                System.out.println("LAYOUT_FAIL: fx:id 'mainProgressBar' did not resolve");
                return false;
            }
            javafx.scene.control.TitledPane calendar = (javafx.scene.control.TitledPane)
                    root.lookup("#calendarPane");
            javafx.scene.control.TitledPane timer = (javafx.scene.control.TitledPane)
                    root.lookup("#timerPane");
            javafx.scene.control.TitledPane notes = (javafx.scene.control.TitledPane)
                    root.lookup("#notesPane");
            if (calendar == null || timer == null || notes == null) {
                System.out.println("LAYOUT_FAIL: a sidebar TitledPane did not resolve "
                        + "(calendar=" + (calendar != null) + ", timer=" + (timer != null)
                        + ", notes=" + (notes != null) + ")");
                return false;
            }

            String[] names = {"calendar", "timer", "notes"};
            javafx.scene.control.TitledPane[] panes = {calendar, timer, notes};

            double worst = Double.MAX_VALUE;
            double pref = bar.prefHeight(-1);
            String worstState = "";

            for (double viewport : VIEWPORT_HEIGHTS) {
                root.resize(1440, viewport);
                for (int mask = 0; mask < 8; mask++) {
                    StringBuilder state = new StringBuilder();
                    for (int i = 0; i < panes.length; i++) {
                        boolean expanded = (mask & (1 << i)) != 0;
                        // Only assign when different: setExpanded fires the pane
                        // listener, which persists the state to the database,
                        // and churning that writes needless settings rows.
                        if (panes[i].isExpanded() != expanded) {
                            panes[i].setExpanded(expanded);
                        }
                        state.append(expanded ? names[i] : "-").append(' ');
                    }

                    // A TitledPane expanded state changes its CONTENT managed
                    // flag, so the change only shows up after a fresh CSS pass
                    // and layout. Skipping either measures the previous state.
                    root.applyCss();
                    root.layout();

                    double height = bar.getHeight();
                    String where = "at " + (int) viewport + "px tall with ["
                            + state.toString().trim() + "] expanded";
                    if (height < worst) {
                        worst = height;
                        worstState = where;
                    }
                    if (height < PROGRESS_BAR_MIN_HEIGHT) {
                        System.out.println("LAYOUT_FAIL: " + where + " the skill progress "
                                + "bar laid out at " + height + "px, below the "
                                + PROGRESS_BAR_MIN_HEIGHT + "px floor. It has been crushed "
                                + "out of existence - this is the height regression.");
                        return false;
                    }
                    if (Math.abs(height - pref) > 1.0) {
                        System.out.println("LAYOUT_FAIL: " + where + " the progress bar is "
                                + height + "px but wants " + pref + "px. It cleared the "
                                + "floor by being squeezed rather than by being pinned, so "
                                + "the minHeight pin is not in force.");
                        return false;
                    }
                }
            }

            // The sidebar must also be BOUNDED BY THE WINDOW, which is the other half
            // of the fix: if it could still demand height from its contents, the
            // progress bar would survive only because the right panel happened
            // to win the negotiation.
            javafx.scene.control.ScrollPane sidebar =
                    (javafx.scene.control.ScrollPane) root.lookup("#sidebarScroll");
            if (sidebar == null) {
                System.out.println("LAYOUT_FAIL: fx:id 'sidebarScroll' did not resolve. "
                        + "The left column must be inside a scrolling viewport or its "
                        + "height is unbounded.");
                return false;
            }

            // Checked at the 1080p height specifically: the claim is that the
            // sidebar is bounded by the WINDOW, and a taller window legitimately
            // gives it more room.
            root.resize(1440, VIEWPORT_1080P_HEIGHT);
            root.applyCss();
            root.layout();
            if (sidebar.getHeight() > VIEWPORT_1080P_HEIGHT + 1) {
                System.out.println("LAYOUT_FAIL: the sidebar viewport is " + sidebar.getHeight()
                        + "px tall in a " + VIEWPORT_1080P_HEIGHT
                        + "px scene. It is not being bounded by the window.");
                return false;
            }

            System.out.println("LAYOUT_OK: the progress bar held " + pref + "px across "
                    + (VIEWPORT_HEIGHTS.length * 8) + " layout states (worst: " + worstState
                    + " at " + worst + "px); the sidebar is bounded by the window at "
                    + sidebar.getHeight() + "px.");
            return true;
        } catch (Throwable t) {
            System.out.println("LAYOUT_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * The Advanced Log modal: no truncated labels, stacked range pickers, dark
     * theme.
 *
 * <p>Three separate user-visible defects, all asserted here because all three
 * are invisible to the compiler and all three look fine in code review.
 *
 * <p>1. <b>Truncated labels.</b> The batch mode was one HBox of
 * {@code [From:][picker][To:][picker]}. Two DatePickers need ~240px each in a
 * 440px dialog, so the labels were the only thing that could give, and a Label
 * with no room renders as an ellipsis - so the user saw "... 25/09/2026 [x]
 * ... 01/10/2026 [x]" and lost the words that said which end of the range each
 * date was.
 *
 * <p>2. <b>Unstyled controls.</b> The DatePicker, and the dialog's Save and
 * Cancel buttons, rendered with Modena's light skin in a dark dialog. A Dialog's
 * buttons are created by the DialogPane rather than declared anywhere, so they
 * cannot be styled from FXML at all - the classes have to be added in Java.
 *
 * <p>3. <b>Dead space.</b> The pane carried a hard-coded {@code prefHeight(400)}
 * so the taller batch mode would fit, leaving ~150px of empty panel above the
 * buttons in the shorter mode.
 *
 * <p>The dialog is built through {@code buildAdvancedLogDialog()}, which does not
 * show it - a modal dialog cannot be opened by a headless harness, so
 * separating construction from showing is what makes any of this checkable.
 */
private static boolean checkAdvancedLogDialog(Object controller) {
        if (controller == null) {
            System.out.println("ADVLOG_FAIL: Dashboard.fxml declared no controller");
            return false;
        }
        javafx.scene.control.Dialog<?> dialog = null;
        try {
            java.lang.reflect.Method build = controller.getClass()
                    .getDeclaredMethod("buildAdvancedLogDialog");
            build.setAccessible(true);
            dialog = (javafx.scene.control.Dialog<?>) build.invoke(controller);
            if (dialog == null) {
                System.out.println("ADVLOG_FAIL: buildAdvancedLogDialog() returned null");
                return false;
            }
            javafx.scene.control.DialogPane pane = dialog.getDialogPane();

            // The pane needs a CSS pass before any lookup will resolve anything,
            // and doing that before the first lookup is the natural order to
            // write and the useless one: it reports every id as missing, which
            // reads exactly like a missing fx:id in the builder.
            //
            // NOT wrapped in a new Scene. Dialog puts its DialogPane in a Scene
            // of its own when it is constructed, and constructing a second one
            // throws "is already set as root of another scene". Reusing the
            // existing one and resizing it is both correct and the only option.
            //
            // The explicit size matters: an unshown dialog's pane has no size of
            // its own, and laying it out at zero width would make every label
            // measure as truncated - the check would then fail for a reason that
            // has nothing to do with the code under test.
            if (pane.getScene() == null) {
                new Scene(pane, ADVLOG_WIDTH, 700);
            }
            pane.resize(ADVLOG_WIDTH, 700);
            pane.applyCss();
            pane.layout();

            // ---- theming -------------------------------------------------
            if (!hasClass(pane, "advanced-log-pane") || !hasClass(pane, "glass-panel")) {
                System.out.println("ADVLOG_FAIL: the dialog pane does not carry the dark-theme "
                        + "classes. Actual: " + pane.getStyleClass());
                return false;
            }
            for (String id : new String[]{"advancedLogSave", "advancedLogCancel",
                    "advancedLogSingleDate", "advancedLogStartDate", "advancedLogEndDate"}) {
                javafx.scene.Node node = pane.lookup("#" + id);
                if (node == null) {
                    System.out.println("ADVLOG_FAIL: " + id + " did not resolve");
                    return false;
                }
                // Asserted on advlog-date-picker, NOT on "date-picker": the
                // latter is DatePicker.DEFAULT_STYLE_CLASS and is therefore
                // present on every DatePicker whether or not it was styled, so
                // checking it passes on completely unstyled controls.
                boolean isButton = id.endsWith("Save") || id.endsWith("Cancel");
                if (!isButton && !hasClass(node, "advlog-date-picker")) {
                    System.out.println("ADVLOG_FAIL: " + id
                            + " is missing the advlog-date-picker marker class, so it was never "
                            + "prepared for dark styling. Actual: " + node.getStyleClass());
                    return false;
                }
            }
            // Save must be the accent action and Cancel the secondary one, not
            // the other way round and not both Modena.
            if (!hasClass(pane.lookup("#advancedLogSave"), "accent-button")) {
                System.out.println("ADVLOG_FAIL: Save is not styled as the primary action. "
                        + "Actual: " + pane.lookup("#advancedLogSave").getStyleClass());
                return false;
            }
            if (!hasClass(pane.lookup("#advancedLogCancel"), "secondary-button")) {
                System.out.println("ADVLOG_FAIL: Cancel is not styled as a secondary action. "
                        + "Actual: " + pane.lookup("#advancedLogCancel").getStyleClass());
                return false;
            }
            if (!hasClass(pane.lookup("#advancedLogSummaryCard"), "info-banner")) {
                System.out.println("ADVLOG_FAIL: the summary line is not in an .info-banner card");
                return false;
            }

            // ---- lay it out -----------------------------------------------

            java.util.Set<javafx.scene.control.ToggleButton> modes = new java.util.LinkedHashSet<>();
            for (String id : new String[]{"advancedLogSingleMode", "advancedLogRangeMode"}) {
                modes.add((javafx.scene.control.ToggleButton) pane.lookup("#" + id));
            }
            if (modes.size() != 2) {
                System.out.println("ADVLOG_FAIL: could not resolve both mode toggles");
                return false;
            }

            for (javafx.scene.control.ToggleButton mode : modes) {
                if (!mode.isSelected()) {
                    mode.setSelected(true);
                }

                // TWO WIDTHS, because a truncation check at a single width can
                // pass for the wrong reason. At 460px the stacked grid has room
                // and nothing is crushed even without the labels pinned - so a
                // check there does not distinguish the fix from its absence. 380px
                // is where a one-row layout genuinely cannot fit two pickers and
                // their labels, which is the reported bug, so that is the width
                // at which the assertion has to hold.
                for (double width : ADVLOG_WIDTHS) {
                pane.resize(width, 700);
                pane.applyCss();
                pane.layout();

                String where = (mode.getId().contains("Range") ? "Batch (Date Range)" : "Specific Date")
                        + " at " + (int) width + "px";

                // ---- 1. no truncated labels ------------------------------
                //
                // Two different failures look alike in a screenshot, and they
                // need different assertions:
                //
                //   - a FIXED-WIDTH label narrower than its preferred width
                //     ellipsises. That is the reported bug: the labels read
                //     "..." and lost the word identifying which date was which.
                //
                //   - a WRAPPING label narrower than its preferred width is
                //     correct, because it wraps instead. Its preferred width is
                //     the unwrapped width, so comparing it against the actual
                //     width would report a truncation that is not happening -
                //     which is exactly what the first version of this check did,
                //     failing on the summary line that wraps perfectly well.
                //     For those the assertion is on HEIGHT: the label must be
                //     tall enough for the lines it is actually wrapping to.
                for (javafx.scene.Node node : pane.lookupAll(".label")) {
                    if (!isInActiveBranch(node, pane)) {
                        continue; // belongs to the other mode
                    }
                    javafx.scene.control.Label label = (javafx.scene.control.Label) node;
                    if (label.getText() == null || label.getText().isEmpty()) {
                        continue;
                    }
                    if (label.isWrapText()) {
                        double needed = label.prefHeight(label.getWidth());
                        if (label.getHeight() < needed - 1.0) {
                            System.out.println("ADVLOG_FAIL: in " + where + " mode the wrapping "
                                    + "label \"" + label.getText() + "\" is only "
                                    + label.getHeight() + "px tall but needs " + needed
                                    + "px for the text at this width, so the last line is "
                                    + "clipped.");
                            return false;
                        }
                        continue;
                    }
                    double pref = label.prefWidth(-1);
                    if (pref <= 0) {
                        continue; // empty text
                    }
                    if (label.getWidth() < pref - 1.0) {
                        System.out.println("ADVLOG_FAIL: in " + where + " mode the label \""
                                + label.getText() + "\" is " + label.getWidth()
                                + "px wide but needs " + pref + "px, so it is truncated to an "
                                + "ellipsis. Labels must be pinned with minWidth(USE_PREF_SIZE).");
                        return false;
                    }

                    // THE PIN ITSELF, asserted as a property.
                    //
                    // Not redundant with the width check above, and the reason is
                    // worth recording because it was measured rather than
                    // assumed: removing the pin does NOT truncate these labels at
                    // 460, 380, 340 or even 300px. JavaFX's Label already reports
                    // a computed minimum width equal to its text width, and the
                    // GridPane overflows rather than squeezing. A behavioural
                    // check therefore cannot tell a pinned label from an unpinned
                    // one here - the width assertion above passed with the pin
                    // deleted.
                    //
                    // The pin still earns its place: it is what makes the
                    // guarantee independent of the parent layout, of a future
                    // change to a container that does squeeze, and of the larger
                    // scale tiers where the text is wider. So the lock is verified
                    // directly, because that is the only way to verify it at all.
                    if (hasClass(label, "dialog-field-label")
                            && label.getMinWidth() != javafx.scene.layout.Region.USE_PREF_SIZE) {
                        System.out.println("ADVLOG_FAIL: the field label \""
                                + label.getText() + "\" has minWidth " + label.getMinWidth()
                                + " instead of Region.USE_PREF_SIZE, so it is no longer "
                                + "guaranteed to resist truncation.");
                        return false;
                    }
                }

                // ---- 2. the range pickers are stacked, not side by side ----
                // Tested against the MODE, not against the human-readable
                // description: that string now carries the width for the
                // failure message, so comparing against it silently stopped
                // this check from ever running.
                if (mode.getId().contains("Range")) {
                    javafx.scene.Node startNode = pane.lookup("#advancedLogStartDate");
                    javafx.scene.Node endNode = pane.lookup("#advancedLogEndDate");
                    if (!(startNode instanceof javafx.scene.control.DatePicker)
                            || !(endNode instanceof javafx.scene.control.DatePicker)) {
                        System.out.println("ADVLOG_FAIL: the range controls are not DatePickers");
                        return false;
                    }
                    Bounds startBounds = ((javafx.scene.control.DatePicker) startNode)
                            .localToScene(((javafx.scene.control.DatePicker) startNode)
                                    .getBoundsInLocal());
                    Bounds endBounds = ((javafx.scene.control.DatePicker) endNode)
                            .localToScene(((javafx.scene.control.DatePicker) endNode)
                                    .getBoundsInLocal());
                    if (endBounds.getMinY() <= startBounds.getMinY()) {
                        System.out.println("ADVLOG_FAIL: the To picker is not below the From "
                                + "picker (start minY=" + startBounds.getMinY()
                                + ", end minY=" + endBounds.getMinY() + "). They are still "
                                + "side by side, which is what crushed the labels.");
                        return false;
                    }
                    // Strictly stacked, not merely lower: a tiny vertical overlap
                    // would still overlap the labels and still truncate them.
                    if (endBounds.getMinY() < startBounds.getMaxY() - 1.0) {
                        System.out.println("ADVLOG_FAIL: the range pickers overlap vertically "
                                + "(start maxY=" + startBounds.getMaxY()
                                + ", end minY=" + endBounds.getMinY() + ")");
                        return false;
                    }
                }
                }
            }

            System.out.println("ADVLOG_OK: labels untruncated in both modes, range pickers "
                    + "stacked, dark theme applied.");
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("ADVLOG_FAIL: could not build the dialog: " + e);
            return false;
        } catch (Throwable t) {
            System.out.println("ADVLOG_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
 * Whether {@code node} is in the mode currently being shown.
 *
 * * <p>Walks the ancestor chain rather than asking the node directly, and the
 * distinction matters: {@code isManaged()} and {@code isVisible()} report the
 * node's OWN flag only. The inheritance from an unmanaged or hidden parent is
 * something the LAYOUT applies, not something the property reflects. So a Label
 * inside the collapsed range grid still answers {@code true} to both, and a
 * check that trusted them would measure labels belonging to the other mode.
 * That produced a false failure on the summary line while the dialog was
 * actually correct.
 *
 * <p>Stopping at {@code root} so the pane's own visibility is not re-tested.
 */
    /** The bottom edge of the last row of content must sit this far inside the
 *  window. A small margin rather than zero, because a control flush against the
 *  edge reads as clipped even when it technically is not. */
    private static final double BOTTOM_COMFORT_MARGIN = 8.0;

private static boolean checkDatePickerPopupContrast(Object controller) {
        if (controller == null) {
            System.out.println("ADVPOPUP_FAIL: no controller");
            return false;
        }
        try {
            java.lang.reflect.Method build = controller.getClass()
                    .getDeclaredMethod("buildAdvancedLogDialog");
            build.setAccessible(true);
            @SuppressWarnings("unchecked")
            javafx.scene.control.Dialog<?> dialog =
                    (javafx.scene.control.Dialog<?>) build.invoke(controller);
            javafx.scene.control.DialogPane pane = dialog.getDialogPane();
            if (pane.getScene() == null) {
                new Scene(pane, ADVLOG_WIDTH, 400);
            }
            pane.applyCss();
            pane.layout();

            javafx.scene.control.DatePicker picker =
                    (javafx.scene.control.DatePicker) pane.lookup("#advancedLogSingleDate");
            javafx.scene.control.skin.DatePickerSkin skin =
                    (javafx.scene.control.skin.DatePickerSkin) picker.getSkin();
            javafx.scene.Parent pop = (javafx.scene.Parent) skin.getPopupContent();
            if (pop.getScene() == null) {
                new Scene(pop, 340, 270);
            }
            // The popup is its OWN scene and inherits nothing from the dialog.
            // Forgetting that was the whole of the white-on-white bug: the
            // dialog was correctly themed and the popup was stock Modena.
            pop.getScene().getStylesheets().add(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css")
                            .toExternalForm());
            pop.applyCss();
            pop.layout();

            if (!checkPopupSelectorsMatchTheRealSkin(pop)) {
                return false;
            }

            // The real class names, established by inspecting the live skin.
            // The previous stylesheet targeted .calendar, .month-cell and
            // .year-cell - none of which exist here - so every one of those
            // rules matched nothing and the grid kept Modena's light
            // background while the text rules made its text light.
            for (String required : new String[]{".month-year-pane", ".calendar-grid",
                    ".day-cell", ".day-name-cell", ".spinner-label"}) {
                if (pop.lookup(required) == null) {
                    System.out.println("ADVPOPUP_FAIL: the popup has no " + required
                            + ". The stylesheet must target the class names the skin actually "
                            + "uses; verify them against the live tree before styling.");
                    return false;
                }
            }

            // Luminance, measured off a real snapshot of the rendered popup.
            //
            // For each region: the DARKEST pixel must be dark (the background is
            // themed) and the BRIGHTEST must be light (the text on it is
            // legible). Both halves are needed - a dark region with no bright
            // pixels means unreadable text, and a region that is bright
            // throughout is the white-on-white regression.
            // A NORMAL day cell, chosen deliberately.
            //
            // lookup(".day-cell") returns the first match, which in a month shown
            // at its start is a previous-month cell - and those are styled by a
            // different, deliberately dimmer rule. Asserting contrast on one of
            // those would pass while every visible day in the month was
            // unreadable, which is exactly what happened when the text colour
            // was regressed.
            javafx.scene.Node normalDay = null;
            for (javafx.scene.Node c : pop.lookupAll(".day-cell")) {
                if (!c.getStyleClass().contains("previous-month")
                        && !c.getStyleClass().contains("next-month")) {
                    normalDay = c;
                    break;
                }
            }
            if (normalDay == null) {
                System.out.println("ADVPOPUP_FAIL: the popup has no in-month day cell to test");
                return false;
            }

            String[][] regions = {
                    {".day-name-cell", "a day-name cell"},
                    {".month-year-pane", "the month/year header"},
                    {".calendar-grid", "the calendar grid"},
            };
            for (String[] r : regions) {
                javafx.scene.Node n = pop.lookup(r[0]);
                if (n == null) {
                    System.out.println("ADVPOPUP_FAIL: " + r[0] + " did not resolve");
                    return false;
                }
                double darkest = darkestLuminance(pop, n);
                double brightest = brightestLuminance(pop, n);
                if (darkest >= DARK_BACKGROUND_MAX_LUMINANCE) {
                    System.out.println("ADVPOPUP_FAIL: " + r[1] + " has a background luminance of "
                            + fmt(darkest) + " (limit " + DARK_BACKGROUND_MAX_LUMINANCE
                            + "). It is rendering light, so its light text is unreadable.");
                    return false;
                }
                if (brightest <= LIGHT_TEXT_MIN_LUMINANCE) {
                    System.out.println("ADVPOPUP_FAIL: " + r[1] + " has no light pixels: its "
                            + "brightest luminance is " + fmt(brightest) + " (need "
                            + LIGHT_TEXT_MIN_LUMINANCE + "+). Either the text is as dark as the "
                            + "background, or it is not being drawn at all.");
                    return false;
                }
            }
            // And the in-month day cell, which is the one the user reads.
            double dayDark = darkestLuminance(pop, normalDay);
            double dayBright = brightestLuminance(pop, normalDay);
            if (dayDark >= DARK_BACKGROUND_MAX_LUMINANCE
                    || dayBright <= LIGHT_TEXT_MIN_LUMINANCE) {
                System.out.println("ADVPOPUP_FAIL: an in-month day cell has background luminance "
                        + fmt(dayDark) + " and brightest pixel " + fmt(dayBright)
                        + "; it needs a dark background (< " + DARK_BACKGROUND_MAX_LUMINANCE
                        + ") with light text (> " + LIGHT_TEXT_MIN_LUMINANCE + ").");
                return false;
            }

            System.out.println("ADVPOPUP_OK: popup backgrounds dark (< "
                    + DARK_BACKGROUND_MAX_LUMINANCE + ") with light text (> "
                    + LIGHT_TEXT_MIN_LUMINANCE + ") in every region - "
                    + regions.length + " measured off a rendered snapshot.");
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("ADVPOPUP_FAIL: could not build the dialog: " + e);
            return false;
        } catch (Throwable t) {
            System.out.println("ADVPOPUP_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }

    private static double luminance(javafx.scene.paint.Color c) {
        return 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
    }

    private static double darkestLuminance(javafx.scene.Node snapshotRoot, javafx.scene.Node region) {
        javafx.scene.image.WritableImage img = snapshot(snapshotRoot);
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        int[] box = box(snapshotRoot, region);
        double worst = 2;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                worst = Math.min(worst, luminance(pr.getColor(x, y)));
            }
        }
        return worst;
    }

    private static double brightestLuminance(javafx.scene.Node snapshotRoot, javafx.scene.Node region) {
        javafx.scene.image.WritableImage img = snapshot(snapshotRoot);
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        int[] box = box(snapshotRoot, region);
        double best = -1;
        for (int y = box[1]; y <= box[3]; y++) {
            for (int x = box[0]; x <= box[2]; x++) {
                best = Math.max(best, luminance(pr.getColor(x, y)));
            }
        }
        return best;
    }

    private static javafx.scene.image.WritableImage snapshot(javafx.scene.Node root) {
        javafx.scene.image.WritableImage img = new javafx.scene.image.WritableImage(
                (int) root.getLayoutBounds().getWidth(),
                (int) root.getLayoutBounds().getHeight());
        root.snapshot(null, img);
        return img;
    }

    /** Clamped x0,y0,x1,y1 of a node within its snapshot root. */
    private static int[] box(javafx.scene.Node snapshotRoot, javafx.scene.Node region) {
        javafx.geometry.Bounds b = region.localToScene(region.getBoundsInLocal());
        int w = (int) snapshotRoot.getLayoutBounds().getWidth();
        int h = (int) snapshotRoot.getLayoutBounds().getHeight();
        return new int[]{
                Math.max(0, (int) b.getMinX()),
                Math.max(0, (int) b.getMinY()),
                Math.min(w - 1, (int) b.getMaxX()),
                Math.min(h - 1, (int) b.getMaxY())};
    }
/**
     * Every {@code .date-picker-popup} selector in our stylesheet must name a
     * style class the live popup actually has.
     *
     * <p>This exists because of a bug that no amount of luminance checking would
     * have caught on its own. The previous stylesheet styled
     * {@code .date-picker-popup .calendar}, {@code .month-cell} and
     * {@code .year-cell}. Inspecting the real JavaFX 25 skin shows the popup is
     * built from {@code .month-year-pane}, {@code .spinner} and
     * {@code .calendar-grid}, with day cells classed {@code .day-cell} and
     * {@code .day-name-cell}.
     *
     * <p>None of those three selectors matched anything. They parsed fine, they
     * applied nothing, and the popup kept Modena's light background while the
     * text rules made its text light - white on white. A rule for a class that
     * does not exist is not an error in JavaFX CSS; it is silence.
     *
     * <p>So the stylesheet's popup selectors are checked against the live tree.
     * Only the popup direction is asserted: a selector naming a class the popup
     * does not have is dead CSS that looks intentional, whereas the reverse -
     * a popup class with no rule - is merely unstyled.
     */
    private static boolean checkPopupSelectorsMatchTheRealSkin(
            javafx.scene.Parent pop) {
        java.util.Set<String> live = new java.util.LinkedHashSet<>();
        collectStyleClasses(pop, live);
        String css;
        try {
            css = readStylesheet();
        } catch (java.io.IOException e) {
            System.out.println("ADVPOPUP_FAIL: could not read styles.css: " + e);
            return false;
        }
        java.util.regex.Matcher rules = java.util.regex.Pattern
                .compile("\\.date-picker-popup[^{]*\\{").matcher(stripComments(css));
        int checked = 0;
        while (rules.find()) {
            String selector = rules.group();
            java.util.regex.Matcher tokens =
                    java.util.regex.Pattern.compile("\\.([a-zA-Z0-9_-]+)").matcher(selector);
            while (tokens.find()) {
                String className = tokens.group(1);
                // Skip the selector's own leading component and pseudo-classes.
                if (className.equals("date-picker-popup")) {
                    continue;
                }
                if (!live.contains(className)) {
                    System.out.println("ADVPOPUP_FAIL: styles.css targets .date-picker-popup "
                            + className + ", but the live popup has no such class. It is dead "
                            + "CSS. Popup classes actually present: " + live);
                    return false;
                }
                checked++;
            }
        }
        if (checked < 5) {
            System.out.println("ADVPOPUP_FAIL: only " + checked + " popup selector token(s) were "
                    + "checked, which is too few to be meaningful - the scan is probably broken. "
                    + "Popup classes present: " + live);
            return false;
        }
        System.out.println("ADVPOPUP_SEL_OK: " + checked + " popup selector token(s) all match "
                + "classes the live skin actually has.");
        return true;
    }

    private static void collectStyleClasses(javafx.scene.Node n, java.util.Set<String> into) {
        into.addAll(n.getStyleClass());
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                collectStyleClasses(c, into);
            }
        }
    }

    private static String readStylesheet() throws java.io.IOException {
        try (java.io.InputStream is = FxmlCheck.class
                .getResourceAsStream("/com/unitracker/css/styles.css")) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String stripComments(String css) {
        StringBuilder live = new StringBuilder();
        int i = 0;
        while (i < css.length()) {
            if (css.startsWith("/*", i)) {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? css.length() : end + 2;
            } else {
                live.append(css.charAt(i));
                i++;
            }
        }
        return live.toString();
    }

    /**
     * END TO END: the real views must actually render bigger at bigger tiers.
     *
     * <p>This is the check the feature never had. Every previous tier check
     * asserted on the GENERATED CSS TEXT or on a synthetic button in a bare
     * Scene, and the feature was completely broken anyway - so text-based
     * checks stayed green while the dropdown did nothing.
     *
     * <p><b>THE ACTUAL DEFECT.</b> styles.css is attached to the ROOT NODE -
     * {@code stylesheets="@../css/styles.css"} on the Dashboard's BorderPane,
     * and {@code pane.getStylesheets()} for the dialogs. The generated tier
     * sheet was injected into {@code scene.getStylesheets()}. JavaFX resolves
     * node stylesheets AFTER scene stylesheets, so for two rules of equal
     * specificity ({@code .root}) the node-level styles.css always won:
     *
     * <pre>
     *   user agent  <  scene.getStylesheets()  <  node.getStylesheets()  <  inline
     * </pre>
     *
     * <p>So the root font size stayed at 13px at every tier, and because every
     * text rule in styles.css is expressed in {@code em}, the entire type
     * hierarchy was frozen too. Nothing threw; the sheet was attached; it just
     * never won.
     *
     * <p>What this asserts, per tier, on nodes that come from the real FXML:
     * {@code getFont().getSize()} increases monotonically across
     * COMPACT -> DEFAULT -> LARGE -> EXTRA_LARGE, and so does
     * {@code getLayoutBounds().getHeight()} for a Label. Font size is the
     * primary signal because it is what {@code em} resolves against; height is
     * included so a change that moves text without resizing the box is also
     * visible.
     */
    private static boolean checkRealViewsScaleAcrossTiers() {
        double[] expectedBase = {11.5, 13.0, 15.5, 18.0};
        com.unitracker.util.UiScale.Scale[] tiers = {
                com.unitracker.util.UiScale.Scale.COMPACT,
                com.unitracker.util.UiScale.Scale.DEFAULT,
                com.unitracker.util.UiScale.Scale.LARGE,
                com.unitracker.util.UiScale.Scale.EXTRA_LARGE};
        try {
            // ---- the two real views -------------------------------------
            FXMLLoader dashboardLoader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/Dashboard.fxml"));
            Parent dashboard = dashboardLoader.load();
            Scene dashScene = new Scene(dashboard, 1440, 900);
            // styles.css comes from the FXML's own stylesheets attribute, exactly
            // as in production. Adding it again here would mask the very cascade
            // bug this check exists to catch.

            FXMLLoader settingsLoader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/SettingsDialog.fxml"));
            javafx.scene.layout.Pane settings = settingsLoader.load();
            settingsLoader.getController();
            javafx.scene.control.DialogPane settingsPane = new javafx.scene.control.DialogPane();
            settingsPane.getStylesheets().add(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
            settingsPane.setContent(settings);
            Scene settingsScene = new Scene(settingsPane, 620, 520);

            // CSS must be resolved before lookup: lookupAll() is answered by the
            // CSS engine, so on an unstyled root it returns nothing at all - which
            // looks exactly like a view with no labels on it.
            dashScene.getRoot().applyCss();
            dashScene.getRoot().layout();
            settingsScene.getRoot().applyCss();
            settingsScene.getRoot().layout();

            // A real Label and a real Button from each view.
            javafx.scene.control.Label dashLabel = pickLabel(dashboard);
            javafx.scene.control.Button dashButton = pickButton(dashboard);
            javafx.scene.control.Label setLabel = pickLabel(settings);
            if (dashLabel == null || dashButton == null || setLabel == null) {
                System.out.println("SCALE_FAIL: could not find a Label/Button in the real views");
                return false;
            }

            // A .glass-panel node, because its PADDING comes only from the
            // generated tier sheet.
            //
            // Font size alone does not prove the sheet won the cascade: the root
            // font size is additionally forced through an inline style, which
            // outranks every stylesheet, so the text scales even if the tier
            // sheet is attached at the wrong level and is losing. Padding has no
            // such shortcut, and it moves with the tier's spacing factor, so it
            // is the assertion that actually tests WHERE the sheet is attached.
            javafx.scene.layout.Region panel = null;
            for (javafx.scene.Node n : dashboard.lookupAll(".glass-panel")) {
                if (n instanceof javafx.scene.layout.Region r) {
                    panel = r;
                    break;
                }
            }
            if (panel == null) {
                System.out.println("SCALE_FAIL: no .glass-panel in the Dashboard to measure");
                return false;
            }

            String[] what = {"Dashboard label", "Dashboard button", "Settings label"};
            javafx.scene.control.Label[] labels = {dashLabel, setLabel};
            javafx.scene.control.Button[] buttons = {dashButton};

            double[] panelPadding = new double[tiers.length];
            double[] prevFont = new double[labels.length + buttons.length];
            double[] prevHeight = new double[labels.length + buttons.length];
            StringBuilder detail = new StringBuilder();

            for (int tier = 0; tier < tiers.length; tier++) {
                com.unitracker.util.UiScale.Scale chosen = tiers[tier];

                // The real pipeline: attach to the scene, which attaches to the
                // root at the correct cascade level.
                com.unitracker.util.UiScale.clear(dashScene);
                com.unitracker.util.UiScale.clear(settingsScene);
                com.unitracker.util.UiScale.apply(dashScene, chosen);
                com.unitracker.util.UiScale.apply(settingsScene, chosen);
                com.unitracker.util.UiScale.forceRestyle(dashScene);
                com.unitracker.util.UiScale.forceRestyle(settingsScene);
                dashScene.getRoot().layout();
                settingsScene.getRoot().layout();

                panelPadding[tier] = panel.getPadding().getTop();

                int slot = 0;
                for (javafx.scene.control.Label l : labels) {
                    double size = l.getFont().getSize();
                    double height = l.getLayoutBounds().getHeight();
                    if (tier > 0) {
                        if (size <= prevFont[slot]) {
                            System.out.println("SCALE_FAIL: " + what[slot] + " font did not grow "
                                    + "from " + tiers[tier - 1].displayName() + " ("
                                    + fmt(prevFont[slot]) + "px) to " + chosen.displayName()
                                    + " (" + fmt(size) + "px). The tier stylesheet is attached but "
                                    + "is losing the cascade to styles.css.");
                            return false;
                        }
                        if (height <= prevHeight[slot]) {
                            System.out.println("SCALE_FAIL: " + what[slot] + " height did not grow "
                                    + "from " + tiers[tier - 1].displayName() + " ("
                                    + fmt(prevHeight[slot]) + "px) to " + chosen.displayName()
                                    + " (" + fmt(height) + "px)");
                            return false;
                        }
                    }
                    prevFont[slot] = size;
                    prevHeight[slot] = height;
                    detail.append(String.format(java.util.Locale.ROOT, " %s=%.1fpx", what[slot], size));
                    slot++;
                }
                for (javafx.scene.control.Button b : buttons) {
                    double size = b.getFont().getSize();
                    double height = b.getLayoutBounds().getHeight();
                    if (tier > 0) {
                        if (size <= prevFont[slot]) {
                            System.out.println("SCALE_FAIL: " + what[slot] + " font did not grow "
                                    + "from " + tiers[tier - 1].displayName() + " to "
                                    + chosen.displayName() + " (" + fmt(size) + "px)");
                            return false;
                        }
                        if (height <= prevHeight[slot]) {
                            System.out.println("SCALE_FAIL: " + what[slot] + " height did not grow "
                                    + "from " + tiers[tier - 1].displayName() + " to "
                                    + chosen.displayName() + " (" + fmt(height) + "px)");
                            return false;
                        }
                    }
                    prevFont[slot] = size;
                    prevHeight[slot] = height;
                    detail.append(String.format(java.util.Locale.ROOT, " %s=%.1fpx", what[slot], size));
                    slot++;
                }
            }

            if (panelPadding[panelPadding.length - 1] <= panelPadding[0]) {
                System.out.println("SCALE_FAIL: a .glass-panel's padding did not grow across "
                        + "tiers (" + fmt(panelPadding[0]) + "px -> "
                        + fmt(panelPadding[panelPadding.length - 1])
                        + "px). The generated sheet is attached but losing the cascade to "
                        + "styles.css - text scales only because the root font size is forced "
                        + "inline, which hides this.");
                return false;
            }

            // The motion override, on a REAL button in the REAL view.
            //
            // The existing MOTION_EFFECT check hovers a synthetic button in a
            // bare Scene, where a scene-level override wins trivially. That is
            // exactly the arrangement in which the dashboard does NOT respond,
            // because styles.css is on its root. So the assertion has to happen
            // on the real view, or it proves nothing about the dashboard.
            javafx.scene.control.Button realButton = null;
            for (javafx.scene.Node n : dashboard.lookupAll(".accent-button")) {
                if (n instanceof javafx.scene.control.Button b) {
                    realButton = b;
                    break;
                }
            }
            if (realButton == null) {
                System.out.println("SCALE_FAIL: no .accent-button in the Dashboard to hover");
                return false;
            }
            com.unitracker.util.UiScale.applyMotion(dashScene, false);
            hover(realButton);
            dashScene.getRoot().applyCss();
            dashScene.getRoot().layout();
            double stillScale = realButton.getScaleX();
            com.unitracker.util.UiScale.applyMotion(dashScene, true);
            hover(realButton);
            dashScene.getRoot().applyCss();
            dashScene.getRoot().layout();
            double movingScale = realButton.getScaleX();
            if (Math.abs(stillScale - 1.0) > 0.001) {
                System.out.println("SCALE_FAIL: a REAL .accent-button still scales to "
                        + fmt(movingScale) + " on hover with animations disabled. The motion "
                        + "override is not winning the cascade against styles.css.");
                return false;
            }
            if (Math.abs(movingScale - 1.0) < 0.001) {
                System.out.println("SCALE_FAIL: a REAL .accent-button never scales on hover "
                        + "(" + fmt(movingScale) + "), so the disabled-case assertion above "
                        + "would pass for the wrong reason.");
                return false;
            }
            com.unitracker.util.UiScale.applyMotion(dashScene,
                    AppSettings.animationsEnabled());

            System.out.println("SCALE_OK: real Dashboard and Settings views grow across "
                    + tiers.length + " tiers -" + detail);
            return true;
        } catch (Throwable t) {
            System.out.println("SCALE_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** Puts a control into its :hover state without a pointer. */
    private static void hover(javafx.scene.control.Control c) {
        c.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("hover"), true);
    }

    /** The first Label with real text, so the font size is meaningful. */
    private static javafx.scene.control.Label pickLabel(Parent root) {
        for (javafx.scene.Node n : root.lookupAll(".label")) {
            if (n instanceof javafx.scene.control.Label l
                    && l.getText() != null && !l.getText().isEmpty()) {
                return l;
            }
        }
        return null;
    }

    /** The first Button, which the real views all have. */
    private static javafx.scene.control.Button pickButton(Parent root) {
        return (javafx.scene.control.Button) root.lookup(".button");
    }
/**
 * The "Preview the change" button must be compact, and must not move on hover.
 *
 * <p>Two defects, and the second only exists because of the first.
 *
 * <p><b>FULL-WIDTH.</b> The button declared {@code maxWidth="Infinity"} and so
 * stretched across the whole dialog. Removing that attribute does not fix it: a
 * Button's default maxWidth is MAX_VALUE and a VBox stretches a resizable
 * child across its cross axis, so the fix has to be an explicit
 * {@code maxWidth="-Infinity"} - Region.USE_PREF_SIZE - not an omission.
 *
 * <p><b>HOVER BALLOON.</b> At 600px wide, the inherited 2% hover scale reads as
 * the whole control growing. Two things are asserted rather than one:
 *
 * <ul>
 *   <li>the laid-out width is no more than its own preferred width, and is a
 *       small fraction of the dialog - i.e. it is genuinely compact;</li>
 *   <li>hovering changes NEITHER the padding NOR the layout height. The scale is
 *       a transform and never affects layout, so height and padding staying
 *       identical is the actual no-layout-jump property. The scale is also
 *       bounded, so "subtle" is checked rather than assumed - a future
 *       {@code 1.15} would still pass a no-jump test.</li>
 * </ul>
 */
    private static boolean checkPreviewButtonIsCompact() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/SettingsDialog.fxml"));
            javafx.scene.layout.Pane content = loader.load();
            javafx.scene.control.DialogPane pane = new javafx.scene.control.DialogPane();
            pane.getStylesheets().add(
                    FxmlCheck.class.getResource("/com/unitracker/css/styles.css").toExternalForm());
            pane.setContent(content);
            Scene scene = new Scene(pane, 620, 520);
            pane.applyCss();
            pane.layout();

            javafx.scene.control.Button b = (javafx.scene.control.Button)
                    content.lookup("#previewAnimationsButton");
            if (b == null) {
                System.out.println("PREVIEW_FAIL: fx:id 'previewAnimationsButton' did not resolve");
                return false;
            }

            // Compact: never wider than its text, and a small fraction of the
            // dialog. The fraction guards against a regression to "nearly full
            // width" that still happened to satisfy the first test.
            double prefWidth = b.prefWidth(-1);
            double laidOut = b.getWidth();
            if (laidOut > prefWidth + 1.0) {
                System.out.println("PREVIEW_FAIL: the preview button is " + fmt(laidOut)
                        + "px wide but only needs " + fmt(prefWidth)
                        + "px. It is stretching; maxWidth should be "
                        + "Region.USE_PREF_SIZE (-Infinity).");
                return false;
            }
            if (laidOut > content.getWidth() * 0.6) {
                System.out.println("PREVIEW_FAIL: the preview button is " + fmt(laidOut)
                        + "px across a " + fmt(content.getWidth())
                        + "px dialog, which is a bar rather than a button.");
                return false;
            }

            // ---- the row it now shares with the checkbox ------------------
            //
            // The preview button used to sit at the BOTTOM of the tab, a long
            // way from the checkbox it previews. It now shares that checkbox's
            // row, pushed right by an expanding spacer. Both halves matter and
            // fail independently: same row is not the same as right-aligned, and
            // a button that merely happens to be lower would satisfy neither.
            javafx.scene.control.CheckBox checkbox = (javafx.scene.control.CheckBox)
                    content.lookup("#animationsCheckBox");
            if (checkbox == null) {
                System.out.println("PREVIEW_FAIL: fx:id 'animationsCheckBox' did not resolve");
                return false;
            }
            javafx.geometry.Bounds cb = checkbox.localToScene(checkbox.getBoundsInLocal());
            javafx.geometry.Bounds pb = b.localToScene(b.getBoundsInLocal());
            double checkboxCentreY = cb.getMinY() + cb.getHeight() / 2;
            double buttonCentreY = pb.getMinY() + pb.getHeight() / 2;
            if (Math.abs(checkboxCentreY - buttonCentreY) > 8.0) {
                System.out.println("PREVIEW_FAIL: the preview button is not on the checkbox's "
                        + "row - checkbox centre y=" + fmt(checkboxCentreY) + " button centre y="
                        + fmt(buttonCentreY) + ", difference "
                        + fmt(Math.abs(checkboxCentreY - buttonCentreY)) + "px (limit 8).");
                return false;
            }
            // The checkbox must not absorb the slack itself.
            //
            // Visually it looks identical either way - a CheckBox draws its label
            // at the left of whatever box it is given - so the two cases are
            // indistinguishable on screen. They are not the same to a user: a
            // stretched checkbox makes the whole strip between the label and the
            // button a click target that toggles animations, which is a large
            // invisible hit area next to an unrelated button.
            //
            // NOTE: this currently passes whether or not HBox.hgrow=NEVER is
            // present on the checkbox - measured, the layout does not stretch it
            // either way. The assertion is kept because the geometry is the
            // property that matters and it is cheap to verify, not because it
            // currently distinguishes anything.
            double checkboxPref = checkbox.prefWidth(-1);
            if (cb.getWidth() > checkboxPref + 1.0) {
                System.out.println("PREVIEW_FAIL: the checkbox is " + fmt(cb.getWidth())
                        + "px wide but only needs " + fmt(checkboxPref)
                        + "px. It is absorbing the spacer's slack, so the empty space to its "
                        + "right would silently toggle animations when clicked.");
                return false;
            }
            if (pb.getMinX() <= cb.getMaxX() + 40) {
                System.out.println("PREVIEW_FAIL: the preview button is not clear of the "
                        + "checkbox - checkbox ends at x=" + fmt(cb.getMaxX())
                        + " and the button starts at x=" + fmt(pb.getMinX())
                        + ". They must be separated by more than 40px so the button reads as "
                        + "right-aligned rather than as part of the checkbox.");
                return false;
            }

            // Hover: no metric may change, and the scale must stay subtle.
            double restHeight = b.getLayoutBounds().getHeight();
            javafx.geometry.Insets restPadding = b.getPadding();
            b.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("hover"), true);
            pane.applyCss();
            pane.layout();
            double hoverHeight = b.getLayoutBounds().getHeight();
            javafx.geometry.Insets hoverPadding = b.getPadding();
            double hoverScale = b.getScaleX();

            if (Math.abs(hoverHeight - restHeight) > 0.01) {
                System.out.println("PREVIEW_FAIL: hovering changed the button's height from "
                        + fmt(restHeight) + "px to " + fmt(hoverHeight)
                        + "px, so its neighbours will shift. A hover state must not change "
                        + "-fx-padding or -fx-border-width.");
                return false;
            }
            if (!restPadding.equals(hoverPadding)) {
                System.out.println("PREVIEW_FAIL: hovering changed the button's padding from "
                        + restPadding + " to " + hoverPadding);
                return false;
            }
            if (hoverScale > 1.05 || hoverScale < 1.0) {
                System.out.println("PREVIEW_FAIL: the hover scale is " + fmt(hoverScale)
                        + "; it must stay between 1.00 and 1.05 to read as a highlight rather "
                        + "than a balloon.");
                return false;
            }

            System.out.println("PREVIEW_OK: " + fmt(laidOut) + "px wide (pref "
                    + fmt(prefWidth) + "px) in a " + fmt(content.getWidth())
                    + "px dialog; on the checkbox row (dy " + fmt(Math.abs(checkboxCentreY - buttonCentreY))
                    + "px) and right of it (gap " + fmt(pb.getMinX() - cb.getMaxX())
                    + "px); hover leaves height at " + fmt(hoverHeight)
                    + "px and padding at " + hoverPadding + ", scale " + fmt(hoverScale) + ".");
            return true;
        } catch (Throwable t) {
            System.out.println("PREVIEW_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
         * RESIZE_HITBOX_OK - the chart divider must be catchable.
         *
         * <p>This exists because of a long-standing usability defect that no other
         * check could see: the divider was a 1-2px hairline, and that hairline was
         * also the entire area where the cursor turned into {@code v-resize}. Every
         * other harness assertion in this file passed while the control was, in
         * practice, impossible to grab.
         *
         * <p>Four things are asserted, in this order because each is cheaper to fail
         * than the next: the handle is reachable and interactive in the three
         * multi-skill modes, at every UI tier; it does not overlap the toolbar
         * controls above it; dragging it actually reallocates height; and it is
         * completely inert in the single-skill modes.
         *
         * <p>The height threshold is asserted against {@code getLayoutBounds()}, not
         * {@code getBoundsInLocal()}, and the distinction is the whole point of the
         * change: {@code getBoundsInLocal()} includes CSS padding, so a divider that
         * is 15px of padding around a 1px line would sail past a boundsInLocal test
         * while being no easier to catch than before. {@code getLayoutBounds()}
         * excludes padding, so it measures the part a user's pointer can genuinely
         * land on.
         */
        private static boolean checkChartResizeHitBox(Object controller, Parent root, Scene scene) {
            try {
                com.unitracker.util.UiScale.Scale[] tiers = {
                        com.unitracker.util.UiScale.Scale.COMPACT,
                        com.unitracker.util.UiScale.Scale.DEFAULT,
                        com.unitracker.util.UiScale.Scale.LARGE,
                        com.unitracker.util.UiScale.Scale.EXTRA_LARGE};

                javafx.scene.control.SplitPane split =
                        (javafx.scene.control.SplitPane) root.lookup("#mainSplitPane");
                if (split == null) {
                    System.out.println("RESIZE_HITBOX_FAIL: no #mainSplitPane");
                    return false;
                }
                javafx.scene.layout.Region chart =
                        (javafx.scene.layout.Region) root.lookup("#visualizationStack");
                javafx.scene.Node divider = split.lookup(".split-pane-divider");
                if (divider == null) {
                    System.out.println("RESIZE_HITBOX_FAIL: the SplitPane skin has no "
                            + ".split-pane-divider node, so there is no handle to size.");
                    return false;
                }

                java.lang.reflect.Method switchTo = controller.getClass()
                        .getDeclaredMethod("handleToggleVisualization", javafx.event.ActionEvent.class);
                switchTo.setAccessible(true);

                // The controls the enlarged zone must NOT reach, discovered from the
                // toolbar row itself rather than from a hand-kept id list, so a
                // control added to that row is covered without touching this check.
                javafx.scene.layout.Region zoomRow =
                        (javafx.scene.layout.Region) root.lookup("#zoomControlsRow");

                StringBuilder detail = new StringBuilder();
                double dragDelta = -1;
                double draggedChartHeight = -1;

                for (int tier = 0; tier < tiers.length; tier++) {
                    com.unitracker.util.UiScale.Scale chosen = tiers[tier];
                    com.unitracker.util.UiScale.clear(scene);
                    com.unitracker.util.UiScale.apply(scene, chosen);
                    com.unitracker.util.UiScale.forceRestyle(scene);
                    root.layout();

                    // Every multi-skill mode, not just one: the handle is toggled by
                    // the filter's visibility, so a mode that forgot to raise it
                    // would otherwise pass.
                    for (String mode : new String[]{"combShapedToggle", "skillTreeToggle", "radarToggle"}) {
                        javafx.scene.control.ToggleButton tb =
                                (javafx.scene.control.ToggleButton) root.lookup("#" + mode);
                        tb.setSelected(true);
                        switchTo.invoke(controller, new javafx.event.ActionEvent());
                        root.applyCss();
                        root.layout();

                        if (!divider.isVisible()) {
                            System.out.println("RESIZE_HITBOX_FAIL: the resize handle is hidden in "
                                    + tb.getText() + " (" + chosen.displayName()
                                    + "). It must be live in all three multi-skill modes.");
                            return false;
                        }
                        if (divider.isMouseTransparent()) {
                            System.out.println("RESIZE_HITBOX_FAIL: the resize handle is "
                                    + "mouse-transparent in " + tb.getText() + " ("
                                    + chosen.displayName() + "), so it cannot be grabbed at all.");
                            return false;
                        }
                        if (!divider.isPickOnBounds()) {
                            System.out.println("RESIZE_HITBOX_FAIL: pickOnBounds is false in "
                                    + tb.getText() + " (" + chosen.displayName()
                                    + "). The hit area is padding, which is empty space, and empty "
                                    + "space is unpickable without this.");
                            return false;
                        }
                        if (divider.getCursor() != javafx.scene.Cursor.V_RESIZE) {
                            System.out.println("RESIZE_HITBOX_FAIL: the cursor over the handle in "
                                    + tb.getText() + " (" + chosen.displayName() + ") is "
                                    + divider.getCursor() + ", not V_RESIZE.");
                            return false;
                        }

                        double hit = divider.getLayoutBounds().getHeight();
                        if (hit < 12.0) {
                            dumpDividers(split, 0);
System.out.println("RESIZE_HITBOX_FAIL: the interactive hit height in "
                                    + tb.getText() + " (" + chosen.displayName() + ") is "
                                    + fmt(hit) + "px (node " + fmt(divider.getBoundsInLocal().getHeight())
                                    + "px, padding " + fmt(((javafx.scene.layout.Region) divider)
                                            .getPadding().getTop()) + "/" + fmt(((javafx.scene.layout.Region) divider)
                                            .getPadding().getBottom())
                                    + "). Required >= 12.0px.");
                            return false;
                        }

                        // The enlarged zone must not reach up into the toolbar. An
                        // overlap here would be a silent regression: the zoom buttons
                        // would start missing clicks, and nothing else here would
                        // notice.
                        javafx.geometry.Bounds dz = divider.localToScene(divider.getBoundsInLocal());
                        if (zoomRow != null && zoomRow.isManaged()) {
                            for (javafx.scene.Node c : allControlsIn(zoomRow)) {
                                javafx.geometry.Bounds cb = c.localToScene(c.getBoundsInLocal());
                                if (cb.intersects(dz)) {
                                    System.out.println("RESIZE_HITBOX_FAIL: the " + fmt(dz.getHeight())
                                            + "px resize handle overlaps the toolbar control \""
                                            + describe(c) + "\" in " + tb.getText() + " ("
                                            + chosen.displayName() + "). It would steal that control's "
                                            + "clicks.");
                                    return false;
                                }
                            }
                        }

                        detail.append(String.format(java.util.Locale.ROOT, " %s/%.1fpx",
                                chosen.displayName(), hit));
                    }

                    // ---- dragging actually reallocates -------------------------
                    //
                    // Proved by the chart's height changing, not by the divider's
                    // position property changing: a position that moves while the
                    // panes do not is exactly the "drag appears to work but nothing
                    // happens" failure.
                    if (tier == 1) {
                        double before = chart.getHeight();
                        split.getDividers().get(0).setPosition(0.62);
                        root.applyCss();
                        root.layout();
                        draggedChartHeight = chart.getHeight();
                        dragDelta = draggedChartHeight - before;
                        if (Math.abs(dragDelta) < 20.0) {
                            System.out.println("RESIZE_HITBOX_FAIL: dragging the handle changed the "
                                    + "chart height by only " + fmt(dragDelta)
                                    + "px (before " + fmt(before) + "px, after "
                                    + fmt(draggedChartHeight)
                                    + "px). A handle that does not reallocate space is not a "
                                    + "resize handle.");
                            return false;
                        }
                    }
                }

                // ---- inert in the single-skill modes -------------------------
                //
                // Not "invisible" alone: an invisible divider that is still pickable
                // would keep eating clicks aimed at the chart it no longer divides.
                for (String mode : new String[]{"velocityToggle", "timePieToggle", "curveToggle"}) {
                    javafx.scene.control.ToggleButton tb =
                            (javafx.scene.control.ToggleButton) root.lookup("#" + mode);
                    tb.setSelected(true);
                    switchTo.invoke(controller, new javafx.event.ActionEvent());
                    root.applyCss();
                    root.layout();
                    if (divider.isVisible() || !divider.isMouseTransparent()) {
                        System.out.println("RESIZE_HITBOX_FAIL: the resize handle is still live in "
                                + tb.getText() + " (visible=" + divider.isVisible() + ", "
                                + "mouseTransparent=" + divider.isMouseTransparent()
                                + "). Single-skill charts have no controls to divide, so it must be "
                                + "completely inert there.");
                        return false;
                    }
                }

                com.unitracker.util.UiScale.clear(scene);
                com.unitracker.util.UiScale.forceRestyle(scene);
                root.layout();

                System.out.println("RESIZE_HITBOX_OK: catchable in Comb-Shaped/Skill-Tree/Radar"
                        + " at every tier (" + detail.toString().trim()
                        + "); clear of all " + (zoomRow == null ? 0 : countControlsIn(zoomRow))
                        + " toolbar controls; dragging reallocated "
                        + fmt(dragDelta) + "px; inert in the single-skill modes.");
                return true;
            } catch (ReflectiveOperationException e) {
                System.out.println("RESIZE_HITBOX_FAIL: could not drive the chart modes: " + e);
                return false;
            } catch (Throwable t) {
                System.out.println("RESIZE_HITBOX_FAIL: " + t);
                t.printStackTrace();
                return false;
            }
        }

        private static void dumpDividers(javafx.scene.Node node, int depth) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            pad.append("  ");
        }
        if (node.getStyleClass().contains("split-pane-divider")) {
            System.out.println("DIVDUMP " + pad + node.getClass().getSimpleName()
                    + " id=" + node.getId() + " styleClass=" + node.getStyleClass()
                    + " size=" + fmt(node.getBoundsInLocal().getWidth()) + "x"
                    + fmt(node.getBoundsInLocal().getHeight())
                    + " layoutBounds=" + fmt(node.getLayoutBounds().getWidth()) + "x"
                    + fmt(node.getLayoutBounds().getHeight())
                    + " visible=" + node.isVisible() + " parent="
                    + (node.getParent() == null ? "null"
                       : node.getParent().getClass().getSimpleName()
                         + "[" + fmt(node.getParent().getBoundsInLocal().getWidth()) + "x"
                         + fmt(node.getParent().getBoundsInLocal().getHeight()) + "]")
                    + " cursor=" + node.getCursor());
        }
        if (node instanceof javafx.scene.Parent) {
            for (javafx.scene.Node c : ((javafx.scene.Parent) node).getChildrenUnmodifiable()) {
                dumpDividers(c, depth + 1);
            }
        }
    }

    /** Every pickable control inside a container, for overlap checking. */
        private static java.util.List<javafx.scene.Node> allControlsIn(javafx.scene.Node node) {
            java.util.List<javafx.scene.Node> out = new java.util.ArrayList<>();
            if (node instanceof javafx.scene.control.ButtonBase
                    || node instanceof javafx.scene.control.Slider
                    || node instanceof javafx.scene.control.CheckBox
                    || node instanceof javafx.scene.control.Label
                    || node instanceof javafx.scene.control.TextInputControl) {
                out.add(node);
            }
            if (node instanceof javafx.scene.Parent) {
                for (javafx.scene.Node c : ((javafx.scene.Parent) node).getChildrenUnmodifiable()) {
                    out.addAll(allControlsIn(c));
                }
            }
            return out;
        }

        private static int countControlsIn(javafx.scene.Node node) {
            return allControlsIn(node).size();
        }

        private static String describe(javafx.scene.Node n) {
            if (n instanceof javafx.scene.control.Labeled) {
                String t = ((javafx.scene.control.Labeled) n).getText();
                if (t != null && !t.isEmpty()) {
                    return t;
                }
            }
            return n.getClass().getSimpleName() + "#" + n.getId();
        }

private static boolean checkChartRegainsHeightAfterMultiSkillModes(
            Object controller, Parent root, Scene scene) {
        try {
            root.applyCss();
            root.layout();
            javafx.scene.layout.Region chart = (javafx.scene.layout.Region)
                    root.lookup("#visualizationStack");
            if (chart == null) {
                System.out.println("CHART_FAIL: fx:id 'visualizationStack' did not resolve");
                return false;
            }
            // NOT reanchorDivider() itself: the correction has to come from the
            // controller's own showFilter/showZoomControls wiring, or this test
            // verifies the arithmetic while proving nothing about the trigger -
            // which is where the reported bug actually lived.
            //
            // The controller's own handler is invoked, not just the selection.
            // The chart-type buttons are wired with onAction="#handleToggleVisualization"
            // in the FXML, and the ToggleGroup listener does nothing but refuse a
            // null selection - so setting a toggle alone changes nothing at all,
            // and a test that stopped there would measure a chart that never moved
            // for the most boring reason imaginable.
            java.lang.reflect.Method switchTo = controller.getClass()
                    .getDeclaredMethod("handleToggleVisualization",
                            javafx.event.ActionEvent.class);
            switchTo.setAccessible(true);

            java.util.Map<String, javafx.scene.control.ToggleButton> toggles =
                    new java.util.LinkedHashMap<>();
            for (String id : new String[]{"velocityToggle", "timePieToggle", "curveToggle",
                    "combShapedToggle", "skillTreeToggle", "radarToggle"}) {
                javafx.scene.control.ToggleButton tb =
                        (javafx.scene.control.ToggleButton) root.lookup("#" + id);
                if (tb == null) {
                    System.out.println("CHART_FAIL: could not resolve " + id);
                    return false;
                }
                toggles.put(id, tb);
            }

            // Settled through the production method rather than by pumping the
            // FX event queue: the visibility toggles post their re-anchor with
            // Platform.runLater, and a harness already running on the FX thread
            // cannot wait for a posted runnable to execute.

            // ---- a baseline PER single-skill mode, not one shared -----------
            //
            // An earlier version of this check measured Velocity once and then
            // demanded that Time Split and Curve matched it. That is wrong, and
            // not abstractly: Time Split legitimately shows the zoom toolbar
            // (DashboardController calls showZoomControls(true) for it), so its
            // chart is genuinely ~30px shorter than Velocity's. Comparing across
            // modes would have been asserting a bug as a requirement.
            //
            // What the reported bug actually breaks is per-mode STATE: a mode
            // must come back to the height it had before the round trip. So each
            // mode is measured going in and required to match going out.
            java.util.Map<String, double[]> baseline = new java.util.LinkedHashMap<>();
            for (String id : new String[]{"velocityToggle", "timePieToggle", "curveToggle"}) {
                javafx.scene.control.ToggleButton v = toggles.get(id);
                v.setSelected(true);
                switchTo.invoke(controller, new javafx.event.ActionEvent());
                root.applyCss();
                root.layout();
                baseline.put(id, new double[]{
                        chart.getHeight(),
                        chart.localToScene(chart.getBoundsInLocal()).getMinY()});
            }
            double baseHeight = baseline.get("velocityToggle")[0];

            // ---- the round trip --------------------------------------------
            String[] trip = {"combShapedToggle", "skillTreeToggle", "radarToggle"};
            double multiSkillHeight = -1;
            for (String id : trip) {
                javafx.scene.control.ToggleButton t = toggles.get(id);
                t.setSelected(true);
                switchTo.invoke(controller, new javafx.event.ActionEvent());
                root.applyCss();
                root.layout();
                if (multiSkillHeight < 0) {
                    multiSkillHeight = chart.getHeight();
                }
            }

            // Not vacuous: the chart must genuinely have given space up. Without
            // this, "the chart never moved at all" would satisfy every assertion
            // below and the check would report confidence it has not earned.
            if (multiSkillHeight >= baseHeight - 2.0) {
                System.out.println("CHART_FAIL: the chart did not shrink in the multi-skill "
                        + "modes (" + fmt(multiSkillHeight) + "px vs " + fmt(baseHeight)
                        + "px baseline), so this test cannot detect the regression it exists "
                        + "for.");
                return false;
            }

            // Simulate the user dragging the divider while the controls are up.
            //
            // This is the second half of the reported bug: a manual divider
            // position must be remembered for the multi-skill modes and RESTORED
            // on re-entry, without ever being applied where there are no controls
            // to divide - otherwise a drag in Comb-Shaped strands Velocity at half
            // height, which is the same dead band by another route.
            javafx.scene.control.SplitPane split =
                    (javafx.scene.control.SplitPane) root.lookup("#mainSplitPane");
            if (split == null || split.getDividers().isEmpty()) {
                System.out.println("CHART_FAIL: no divider on the main SplitPane");
                return false;
            }
            split.getDividers().get(0).setPosition(0.35);
            root.applyCss();
            root.layout();

            for (String id : new String[]{"velocityToggle", "timePieToggle", "curveToggle"}) {
                javafx.scene.control.ToggleButton b = toggles.get(id);
                b.setSelected(true);
                switchTo.invoke(controller, new javafx.event.ActionEvent());
                root.applyCss();
                root.layout();
                String where = b.getText();
                double height = chart.getHeight();
                double top = chart.localToScene(chart.getBoundsInLocal()).getMinY();
                double[] was = baseline.get(id);
                if (Math.abs(height - was[0]) > 2.0) {
                    System.out.println("CHART_FAIL: returning to " + where + " left the chart at "
                            + fmt(height) + "px, but it was " + fmt(was[0])
                            + "px before the multi-skill modes. It did not get its space back.");
                    return false;
                }
                if (Math.abs(top - was[1]) > 2.0) {
                    System.out.println("CHART_FAIL: returning to " + where + " left the chart top at "
                            + fmt(top) + "px, but it was " + fmt(was[1])
                            + "px - a " + fmt(top - was[1]) + "px dead band above it.");
                    return false;
                }
            }

            System.out.println("CHART_OK: each single-skill mode returns to its own height and top "
                    + "after Comb-Shaped/Skill-Tree/Radar (Velocity " + fmt(baseHeight)
                    + "px, Time Split " + fmt(baseline.get("timePieToggle")[0]) + "px, Curve "
                    + fmt(baseline.get("curveToggle")[0]) + "px; multi-skill gave up "
                    + fmt(multiSkillHeight) + "px) - no dead band.");
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("CHART_FAIL: could not drive the chart modes: " + e);
            return false;
        } catch (Throwable t) {
            System.out.println("CHART_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }
    /**
     * No control in the skill action row may render as an ellipsis.
     *
     * <p>The row was crowded and every label collapsed - "+ N...", "E...",
     * "Del...", "Reor...", "Acti...", "Stall..." - which is worse than no row
     * at all, because six controls become unidentifiable.
     *
     * <p>Asserted against preferred width rather than "is wide enough": a button
     * can be comfortably wide and still be narrower than the text it holds, and
     * the ellipsis is drawn from exactly that comparison.
     */
    private static boolean checkActionRowHasNoEllipsis(Parent root) {
        try {
            javafx.scene.control.ComboBox<?> combo =
                    (javafx.scene.control.ComboBox<?>) root.lookup("#skillComboBox");
            if (combo == null || !(combo.getParent() instanceof Parent row)) {
                System.out.println("ELLIPSIS_FAIL: could not resolve the skill action row");
                return false;
            }
            int checked = 0;
            for (javafx.scene.Node n : row.getChildrenUnmodifiable()) {
                if (!(n instanceof javafx.scene.control.ButtonBase b)) {
                    continue;
                }
                if (b.getText() == null || b.getText().isEmpty()) {
                    continue;
                }
                checked++;
                double pref = b.prefWidth(-1);
                if (b.getWidth() < pref - 1.0) {
                    System.out.println("ELLIPSIS_FAIL: the \"" + b.getText()
                            + "\" button is " + fmt(b.getWidth()) + "px wide but needs "
                            + fmt(pref) + "px, so its label is truncated to an ellipsis.");
                    return false;
                }
                // The mechanism, asserted directly: the pin is what stops the
                // squeeze, and a button without it would rely on the row being
                // wide enough, which is exactly what was not true.
                if (b.getMinWidth() != javafx.scene.layout.Region.USE_PREF_SIZE) {
                    System.out.println("ELLIPSIS_FAIL: the \"" + b.getText()
                            + "\" button has minWidth " + b.getMinWidth()
                            + " instead of Region.USE_PREF_SIZE, so it can still be squeezed.");
                    return false;
                }
            }
            if (checked < 6) {
                System.out.println("ELLIPSIS_FAIL: only " + checked
                        + " labelled buttons found in the action row, expected at least 6 "
                        + "(+ New, Edit, Delete, Reorder, Active, Stalled)");
                return false;
            }
            System.out.println("ELLIPSIS_OK: " + checked
                    + " labelled action-row controls all render their full labels.");
            return true;
        } catch (Throwable t) {
            System.out.println("ELLIPSIS_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static boolean checkAdvancedLogFitsAfterToggle(Object controller) {
        if (controller == null) {
            System.out.println("ADVTOGGLE_FAIL: Dashboard.fxml declared no controller");
            return false;
        }
        try {
            java.lang.reflect.Method build = controller.getClass()
                    .getDeclaredMethod("buildAdvancedLogDialog");
            build.setAccessible(true);
            @SuppressWarnings("unchecked")
            javafx.scene.control.Dialog<?> dialog =
                    (javafx.scene.control.Dialog<?>) build.invoke(controller);
            javafx.scene.control.DialogPane pane = dialog.getDialogPane();
            if (pane.getScene() == null) {
                new Scene(pane, ADVLOG_WIDTH, 400);
            }
            // A layout pass before any lookup: DialogPane creates its button bar
            // during layout, and its style class is "button-bar" - NOT
            // "dialog-button-bar", which is what a reasonable guess suggests and
            // which matches nothing.
            pane.applyCss();
            pane.layout();
            javafx.scene.Node buttonBar = pane.lookup(".button-bar");
            if (buttonBar == null) {
                System.out.println("ADVTOGGLE_FAIL: the dialog pane has no button bar");
                return false;
            }
            buttonBar.setVisible(true);
            buttonBar.setManaged(true);

            // A REAL STAGE.
            //
            // The Dialog owns a Scene for its pane but has no Window, and every
            // question here is about a window: heights, decorations, the button
            // bar's position inside the frame. Handing the pane's own Scene to a
            // Stage gives the harness one, which is what makes the production
            // resize callable at all.
            Stage host = new Stage();
            host.setScene(pane.getScene());
            host.setResizable(true);

            // The production sizing entry point, called directly and
            // synchronously rather than through its Platform.runLater wrapper -
            // the harness cannot pump the FX event loop.
            java.lang.reflect.Method sizeTo = controller.getClass()
                    .getDeclaredMethod("applyAdvancedLogWindowHeight",
                            javafx.scene.control.DialogPane.class, javafx.stage.Window.class);
            sizeTo.setAccessible(true);

            // Fire the REAL onShowing handler rather than calling the helper
            // directly: calling the helper tested the method but not the wiring,
            // so deleting the call from onShowing still passed.
            if (dialog.getOnShowing() == null) {
                System.out.println("ADVTOGGLE_FAIL: the dialog has no onShowing handler, so "
                        + "nothing installs the calendar glyphs or captures the baseline height.");
                return false;
            }
            // null event: Dialog.getOnShowing() is an EventHandler<DialogEvent>
            // and DialogEvent has no public constructor. The handler ignores its
            // argument.
            dialog.getOnShowing().handle(null);
            pane.applyCss();
            pane.layout();

            if (!checkCalendarButtons(pane)) {
                return false;
            }

            // ---- open in Specific Date, sized to it ----------------------
            javafx.scene.control.ToggleButton single =
                    (javafx.scene.control.ToggleButton) pane.lookup("#advancedLogSingleMode");
            javafx.scene.control.ToggleButton batch =
                    (javafx.scene.control.ToggleButton) pane.lookup("#advancedLogRangeMode");
            if (single == null || batch == null) {
                System.out.println("ADVTOGGLE_FAIL: could not resolve the mode toggles");
                return false;
            }
            if (!single.isSelected()) {
                single.setSelected(true);
            }
            pane.applyCss();
            pane.layout();
            // An unshown Stage reports a height of -1 until one is set, which
            // would be captured as the baseline and make every later delta
            // wrong. SIMULATED_DECORATION stands in for the title bar.
            host.setHeight(pane.prefHeight(-1) + SIMULATED_DECORATION);
            double openedAt = ((Number) sizeTo.invoke(controller, pane, host)).doubleValue();
            double contentAtOpen = pane.prefHeight(-1);
            if (!(openedAt > 0)) {
                System.out.println("ADVTOGGLE_FAIL: the opening height could not be measured");
                return false;
            }
            if (!measure(pane, host, openedAt, "Specific Date (opened)")) {
                return false;
            }

            // The reported sequence: Specific -> Batch -> Specific -> Batch.
            String[] sequence = {"Batch (Date Range)", "Specific Date", "Batch (Date Range)"};
            javafx.scene.control.ToggleButton[] targets = {batch, single, batch};
            for (int i = 0; i < targets.length; i++) {
                if (!targets[i].isSelected()) {
                    targets[i].setSelected(true);
                }
                pane.applyCss();
                pane.layout();
                double applied = ((Number) sizeTo.invoke(controller, pane, host)).doubleValue();
                if (!(applied > 0)) {
                    System.out.println("ADVTOGGLE_FAIL: no height computed for " + sequence[i]);
                    return false;
                }
                pane.applyCss();
                pane.layout();

                // THE FORMULA ITSELF, asserted against a window whose height is
                // deliberately NOT its content height. Only in that situation do
                // the candidate answers differ:
                //   opening-anchored delta = openedAt + delta  (correct)
                //   the content height      = content            (loses the title bar)
                //   current-frame anchored  = ratchets every pass
                double expected = openedAt + (pane.prefHeight(-1) - contentAtOpen);
                if (Math.abs(applied - expected) > 1.0) {
                    System.out.println("ADVTOGGLE_FAIL: in " + sequence[i] + " the window was given "
                            + applied + "px but the opening-anchored delta gives " + expected
                            + "px (opened " + openedAt + "px, content delta "
                            + (pane.prefHeight(-1) - contentAtOpen) + "px).");
                    return false;
                }
                if (!measure(pane, host, applied, sequence[i])) {
                    return false;
                }
                if (sequence[i].equals("Specific Date")
                        && Math.abs(applied - openedAt) > 1.0) {
                    System.out.println("ADVTOGGLE_FAIL: returning to Specific Date gave "
                            + applied + "px but the dialog opened at " + openedAt
                            + "px. The window is ratcheting instead of returning.");
                    return false;
                }
            }

            System.out.println("ADVTOGGLE_OK: calendar button present and non-editable; "
                    + "Specific -> Batch -> Specific -> Batch keeps a compact gap and full "
                    + "bottom clearance, window returns to " + (int) openedAt + "px each time.");
            host.hide();
            return true;
        } catch (ReflectiveOperationException e) {
            System.out.println("ADVTOGGLE_FAIL: could not drive the dialog: " + e);
            return false;
        } catch (Throwable t) {
            System.out.println("ADVTOGGLE_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /**
     * Every DatePicker must offer the calendar, not a text field.
     *
     * <p><b>WHAT IS AND IS NOT CHECKED HERE, AND WHY.</b> The arrow button's
     * rendered appearance is NOT asserted, because it cannot be: a JavaFX
     * {@code snapshot()} does not paint the contents of a control's skin, so a
     * pixel measurement of the DatePicker's arrow reports the surrounding
     * button's background whatever the arrow contains. That was measured, not
     * assumed - a 36x30 region containing a laid-out, visible 14x14 SVG glyph
     * with a light stroke still reported the button's luminance.
     *
     * <p>What IS asserted is everything that is verifiable without painting: the
     * button exists, is visible and wide enough to click; the picker is not
     * editable; the whole control opens the calendar on click; and the glyph we
     * install is present, sized, visible, and carries a light-coloured stroke.
     * Whether that reads well against a real desktop still needs a human eye.
     */
    private static boolean checkCalendarButtons(javafx.scene.control.DialogPane pane) {
        for (String id : new String[]{"advancedLogSingleDate", "advancedLogStartDate",
                "advancedLogEndDate"}) {
            javafx.scene.Node node = pane.lookup("#" + id);
            if (!(node instanceof javafx.scene.control.DatePicker picker)) {
                System.out.println("ADVTOGGLE_FAIL: " + id + " did not resolve to a DatePicker");
                return false;
            }
            if (picker.isEditable()) {
                System.out.println("ADVTOGGLE_FAIL: " + id + " is editable, so the user is "
                        + "invited to type a date in a format they cannot see.");
                return false;
            }
            if (picker.getOnMouseClicked() == null) {
                System.out.println("ADVTOGGLE_FAIL: " + id + " has no click handler, so "
                        + "clicking the date field does nothing.");
                return false;
            }
            javafx.scene.Node arrowButton = picker.lookup(".arrow-button");
            if (arrowButton == null) {
                System.out.println("ADVTOGGLE_FAIL: " + id + " has no .arrow-button.");
                return false;
            }
            double width = arrowButton instanceof javafx.scene.layout.Region r ? r.getWidth() : -1;
            if (!arrowButton.isVisible() || width < 20) {
                System.out.println("ADVTOGGLE_FAIL: " + id + "'s arrow button is visible="
                        + arrowButton.isVisible() + " width=" + width
                        + "; it must be at least 20px wide and visible.");
                return false;
            }
            // The glyph: present, sized, visible, and light.
            javafx.scene.Node glyph = arrowButton.lookup("#advlog-calendar-glyph");
            if (glyph == null) {
                System.out.println("ADVTOGGLE_FAIL: " + id + "'s arrow button has no calendar "
                        + "glyph. Modena's own arrow is a Region painted from -fx-shape and its "
                        + "colour is not ours to control reliably, so the SVG overlay is the "
                        + "glyph of record.");
                return false;
            }
            if (!glyph.isVisible() || glyph.getBoundsInLocal().getWidth() < 10) {
                System.out.println("ADVTOGGLE_FAIL: " + id + "'s calendar glyph is "
                        + glyph.getBoundsInLocal() + " visible=" + glyph.isVisible()
                        + "; it must be at least 10px and visible.");
                return false;
            }
            double brightestStroke = -1;
            for (javafx.scene.Node part : ((javafx.scene.Parent) glyph).getChildrenUnmodifiable()) {
                if (part instanceof javafx.scene.shape.Shape shape
                        && shape.getStroke() != null) {
                    javafx.scene.paint.Color s = (javafx.scene.paint.Color) shape.getStroke();
                    brightestStroke = Math.max(brightestStroke,
                            0.2126 * s.getRed() + 0.7152 * s.getGreen() + 0.0722 * s.getBlue());
                }
            }
            if (brightestStroke < 0.70) {
                System.out.println("ADVTOGGLE_FAIL: " + id + "'s calendar glyph strokes are dark "
                        + "(luminance " + String.format(java.util.Locale.ROOT, "%.2f",
                                brightestStroke) + ", need 0.70+). A dark glyph on a dark button is "
                        + "the blank-icon symptom.");
                return false;
            }
        }
        return true;
    }

    /** The largest allowed vertical gap between the summary banner and the
     *  Save button. The dialog's own padding accounts for roughly 16px; anything
     *  past 36px is empty panel, not spacing. */
    private static final double MAX_BANNER_TO_BUTTON_GAP = 36.0;

    /** How much clear space the bottom row must have inside the window. */
    private static final double MIN_BOTTOM_CLEARANCE = 12.0;

    /**
     * The window frame height the harness pretends exists above the client area.
     *
     * <p>Without it the window's height and its content's height are the SAME
     * NUMBER, which collapses every candidate resize formula into one and makes
     * the arithmetic untestable.
     */
    private static final double SIMULATED_DECORATION = 30.0;

    /**
     * Measures one state of the dialog against a real window.
     *
     * <p>Two numbers, because they fail independently: the gap between the
     * summary banner's bottom and the Save button's top (the dead-space
     * complaint), and the buttons' bottom clearance (the clipping complaint).
     */
    private static boolean measure(javafx.scene.control.DialogPane pane, Stage host,
                                   double windowHeight, String where) {
        if (Math.abs(host.getHeight() - windowHeight) > 1.0) {
            System.out.println("ADVTOGGLE_FAIL: in " + where + " the window is " + host.getHeight()
                    + "px but " + windowHeight + "px was computed, so the resize did not reach it.");
            return false;
        }
        // An unshown Stage reports a scene height of 0 and a harness cannot show
        // a window without putting one on screen, so the applied window height is
        // mirrored onto the pane - which is what the real window does to its
        // client area.
        pane.resize(ADVLOG_WIDTH, windowHeight - SIMULATED_DECORATION);
        pane.applyCss();
        pane.layout();
        double viewport = pane.getHeight();

        javafx.scene.Node banner = pane.lookup("#advancedLogSummaryCard");
        javafx.scene.Node save = pane.lookup("#advancedLogSave");
        javafx.scene.Node cancel = pane.lookup("#advancedLogCancel");
        if (banner == null || save == null || cancel == null) {
            System.out.println("ADVTOGGLE_FAIL: " + where
                    + ": the banner or a button did not resolve");
            return false;
        }
        for (javafx.scene.Node button : new javafx.scene.Node[]{save, cancel}) {
            double maxY = button.localToScene(button.getBoundsInLocal()).getMaxY();
            double clearance = viewport - maxY;
            if (clearance < MIN_BOTTOM_CLEARANCE) {
                System.out.println("ADVTOGGLE_FAIL: in " + where + " the " + button.getId()
                        + " ends at y=" + maxY + " in a " + viewport + "px viewport - only "
                        + clearance + "px of clearance. It is clipped at the bottom edge.");
                return false;
            }
        }
        if (banner.isManaged() && banner.isVisible()) {
            double bannerMaxY = banner.localToScene(banner.getBoundsInLocal()).getMaxY();
            double saveMinY = save.localToScene(save.getBoundsInLocal()).getMinY();
            double gap = saveMinY - bannerMaxY;
            if (gap > MAX_BANNER_TO_BUTTON_GAP) {
                System.out.println("ADVTOGGLE_FAIL: in " + where + " there is a " + gap
                        + "px gap between the summary banner and the Save button (limit "
                        + MAX_BANNER_TO_BUTTON_GAP + "px). That is empty panel, not spacing.");
                return false;
            }
        }
        return true;
    }
    private static Class<?> DashboardPhaseTarget(Object controller) {
        return com.unitracker.controller.DashboardController.class;
    }

    private static boolean isInActiveBranch(javafx.scene.Node node, javafx.scene.Node root) {
        for (javafx.scene.Node n = node; n != null && n != root; n = n.getParent()) {
            if (!n.isManaged() || !n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasClass(javafx.scene.Node node, String styleClass) {
        return node != null && node.getStyleClass().contains(styleClass);
    }

    /** Removes CSS comments so a comment mentioning a property is not parsed. */
    private static String readBaseStylesheetUnchecked() {
        try {
            return readStylesheet();
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Loads the settings dialog and asserts it is structurally sound.
     *
     * <p>This one earns its place more than usual, because the dialog is
     * WRITE-THROUGH: every control persists the moment it is touched. A control
     * that is wired to the wrong field does not merely look wrong - it writes
     * the wrong setting, and the user gets a preferences screen that silently
     * corrupts its own state. So this asserts the FXML IDs resolve, the
     * controller's handlers are all present (an {@code onAction} naming a
     * missing method throws at load, but a HANDLER THAT IS NEVER WIRED fails
     * silently), and the stylesheet defines the classes the FXML declares.
     */
    private static boolean checkSettingsDialog() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    FxmlCheck.class.getResource("/com/unitracker/view/SettingsDialog.fxml"));
            // The root is a plain container, NOT a DialogPane: FXMLLoader
            // rejects a DialogPane root with "not a valid type", so the
            // controller installs this into a real Dialog in code.
            javafx.scene.layout.Pane root = loader.load();
            if (loader.getController() == null) {
                System.out.println("SETTINGS_FAIL: FXML declared no controller");
                return false;
            }

            for (String id : new String[]{"animationsCheckBox", "scaleCombo", "scaleHint",
                    "animationsHint", "previewAnimationsButton", "pointsPerLevelSpinner",
                    "baselinePointsSpinner", "rewardGrid", "clearRewardsButton",
                    "defaultCurveCombo", "curveHint", "presetList", "presetDetail",
                    "addCurveButton", "editCurveButton", "deleteCurveButton",
                    "resetPresetsButton", "tabPane"}) {
                if (root.lookup("#" + id) == null) {
                    System.out.println("SETTINGS_FAIL: fx:id '" + id + "' did not resolve to a node");
                    return false;
                }
            }

            // The TabPane must hold a tab per section, and every tab must have
            // at least one child - an empty tab loads fine and renders as a
            // blank panel, which is how a malformed settings screen ships.
            javafx.scene.control.TabPane tabs =
                    (javafx.scene.control.TabPane) root.lookup("#tabPane");
            if (tabs.getTabs().size() != 3) {
                System.out.println("SETTINGS_FAIL: expected 3 tabs, found " + tabs.getTabs().size());
                return false;
            }
            for (javafx.scene.control.Tab tab : tabs.getTabs()) {
                if (tab.getContent() == null) {
                    System.out.println("SETTINGS_FAIL: tab \"" + tab.getText() + "\" has no content");
                    return false;
                }
            }

            // The stylesheet must define every class the dialog's FXML names.
            // A typo here yields an unstyled settings screen that still works,
            // which is precisely the kind of defect nothing else reports.
            String css = readStylesheet();
            for (String cls : new String[]{".settings-pane", ".settings-tabs", ".settings-tab",
                    ".settings-label", ".settings-hint", ".settings-toggle", ".settings-combo",
                    ".settings-spinner", ".settings-rewards", ".settings-list"}) {
                if (!css.contains(cls + " {")) {
                    System.out.println("SETTINGS_FAIL: the stylesheet does not define " + cls);
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            System.out.println("SETTINGS_FAIL: " + t);
            t.printStackTrace();
            return false;
        }
    }

    /** Reads styles.css as text, for the FXML/CSS agreement assertions. */
}
