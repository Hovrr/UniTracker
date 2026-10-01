package com.unitracker.util;

import javafx.collections.ObservableList;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Screen;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolution-independent UI scaling (v2.0).
 *
 * <h2>THE CONSTRAINT THAT SHAPES EVERYTHING HERE</h2>
 * JavaFX CSS is based on CSS 2.1. It has <strong>no {@code var()}</strong>,
 * <strong>no {@code calc()}</strong>, and no unit arithmetic on {@code -fx-font-size}
 * - which is exactly the set of features a "one variable, rescale everything"
 * approach depends on. The v1 stylesheet therefore hard-coded 38 distinct
 * {@code -fx-font-size: Npx} values, and there was no way to change them all
 * at runtime.
 *
 * <h2>THE TECHNIQUE THIS USES</h2>
 * The working pattern is documented in JDK-8205473 and is the only reliable
 * native mechanism. JavaFX <em>does</em> support {@code em} units for
 * {@code -fx-font-size}, and resolves them against the <strong>inherited</strong>
 * font size - so if every rule states its size in {@code em} and only
 * {@code .root} states an absolute size, changing {@code .root} rescales the
 * entire text hierarchy proportionally.
 *
 * <pre>
 *   styles.css   .root      { -fx-font-size: 13px; }   &lt;-- the ONLY absolute size
 *                .label     { -fx-font-size: 1.0em; }
 *                .metric-value { -fx-font-size: 2.0em; }
 *
 *   scale.css    .root      { -fx-font-size: 16.5px; }  &lt;-- 1.27x, text only
 * </pre>
 *
 * <h2>THE TRAP, AND WHY THIS CLASS HANDLES IT</h2>
 * JDK-8205473 also documents a sharp edge: {@code em} on a <em>non-font</em>
 * property - {@code -fx-padding}, {@code -fx-min-width}, {@code -fx-spacing} -
 * resolves against {@code Font.getDefault()} (a constant 12-13px), NOT against
 * the root font. So sizing padding in {@code em} gives text that scales while
 * panels stay the same size, which is precisely the broken-looking result
 * {@code scale.css} is designed to avoid.
 *
 * <p>This class therefore scales two things, in two different ways:
 * <ol>
 *   <li><b>Text</b> via {@code em} in the base stylesheet - free, exact, and
 *       automatic. Nothing in Java needs to walk the scene graph.</li>
 *   <li><b>Structural padding and corner radii</b> via generated absolute
 *       rules in a per-tier stylesheet that this class writes at runtime. There
 *       are only a handful of these, so generating them is cheap and exact.</li>
 * </ol>
 *
 * <h2>WHY GENERATE THE STYLESHEET RATHER THAN SHIP FOUR STATIC ONES</h2>
 * A static file per tier would drift the moment someone adds a panel padding,
 * because there is nothing to make them remember to update all four. Writing
 * it from one place means the spacing scale is defined once, as arithmetic on
 * a base value, and every tier is derived from it.
 *
 * <h2>WHY THE TIERS ARE INCLUSIVE OF A "COMPACT" TIER</h2>
 * Resolution scaling is not the only reason to shrink text. A 1080p screen at
 * 125% Windows scaling has fewer effective pixels than a 1440p screen at
 * 100%, and the same is true of a small laptop panel. The {@link Scale} enum
 * includes an explicit COMPACT tier so a user can force a smaller UI
 * regardless of what the automatic detection decides.
 */
public final class UiScale {

    private UiScale() {
    }

    /** The scale tiers a user can choose from. */
    public enum Scale {
        /** Chosen from the screen's logical size. */
        AUTO,
        /** For small panels and heavily OS-scaled 1080p displays. */
        COMPACT,
        /** The baseline: designed against 1920x1080, and right for 1440p too. */
        DEFAULT,
        /** Genuinely large canvases: 3440x1440 ultrawide, 3200x1800, and up. */
        LARGE,
        /** 4K and above, where the default text is genuinely small. */
        EXTRA_LARGE;

        /** The base font size in px for a non-AUTO tier. */
        public double baseFontSize() {
            return switch (this) {
                case COMPACT -> 11.5;
                case DEFAULT -> 13.0;
                case LARGE -> 15.5;
                case EXTRA_LARGE -> 18.0;
                case AUTO -> 13.0; // never used; AUTO resolves first
            };
        }

        /** Multiplier applied to the structural spacing scale. */
        public double spacingFactor() {
            return switch (this) {
                case COMPACT -> 0.88;
                case DEFAULT -> 1.0;
                case LARGE -> 1.14;
                case EXTRA_LARGE -> 1.28;
                case AUTO -> 1.0;
            };
        }

        /**
         * Boundaries, in display DIAGONAL pixels, for {@link #forDiagonal}.
         *
         * <p>Diagonal rather than width or pixel count, because a diagonal is
         * the only single number that does not need a separate rule for every
         * aspect ratio: 2560x1080 ultrawide and 1920x1200 have similar areas
         * and very different shapes, and a width-based rule would classify them
         * apart for no reason a user would recognise.
         *
         * <p><b>THE NUMBERS ARE CHOSEN AGAINST REAL DIAGONALS, and that is the
         * whole point - the previous boundaries were not.</b> The exact
         * diagonals involved, to two decimals:
         *
         * <pre>
         *   1366x768    1536.37   COMPACT
         *   1600x900    1802.77   COMPACT
         *   1920x1080   2202.91   DEFAULT   &lt;-- was LARGE
         *   2560x1080   2780.20   DEFAULT
         *   2560x1440   2928.39   DEFAULT   &lt;-- was LARGE
         *   2560x1600   3018.82   DEFAULT
         *   3440x1440   3729.35   LARGE
         *   3200x1800   3674.23   LARGE
         *   3840x2160   4405.80   EXTRA_LARGE
         *   5120x2880   5848.05   EXTRA_LARGE
         * </pre>
         *
         * <p><b>THE 1920x1080 CASE IS A REAL BUG THIS FIXES.</b> The old rule
         * read {@code diagonal < 2200 ? DEFAULT : LARGE}, and 1920x1080 has a
         * diagonal of 2202.91 - just <em>over</em> the boundary. So the one
         * resolution the entire 1K layout was designed and calibrated against
         * was classified as a large display and got 15.5px text. It survived
         * because the boundaries were never checked against a real diagonal,
         * only against the assumption that 1920x1080 "is" 1920x1080. The comment
         * in {@link #resolve} already said 1080p should get the baseline, and
         * the code did not do it.
         *
         * <p>2560x1440 is DEFAULT by the same reasoning: it is a 1440p-class
         * panel, and 13px on 2560x1440 is not small. Only a 4K diagonal, where
         * 13px genuinely is small, earns the larger tiers.
         */
        private static final double COMPACT_BELOW = 1900;
        private static final double DEFAULT_BELOW = 3400;
        private static final double LARGE_BELOW = 4300;

        /** Resolves AUTO against the primary screen.
         *
         *  <p>Measures LOGICAL pixels, not physical ones: {@code getWidth()}
         *  on a Bounds already has the OS scale factor divided out, so this
         *  correctly identifies a 1080p panel at 150% scaling as small rather
         *  than as a large logical canvas. */
        public Scale resolve() {
            if (this != AUTO) {
                return this;
            }
            Rectangle2D bounds = primaryScreenBounds();
            double width = bounds == null ? 1920 : bounds.getWidth();
            double height = bounds == null ? 1080 : bounds.getHeight();
            return forDiagonal(Math.hypot(width, height));
        }

        /**
         * The tier for a display diagonal in pixels.
         *
         * <p>Split out from {@link #resolve()} so the boundaries can be tested
         * without a display. {@code resolve()} needs {@code Screen}, which needs
         * a running toolkit, which is not available in a headless harness - so
         * as a single method these thresholds were untestable, and untestable
         * thresholds are exactly how 1920x1080 ended up on the wrong tier.
         *
         * @param diagonal the display diagonal in logical pixels
         */
        public static Scale forDiagonal(double diagonal) {
            if (diagonal < COMPACT_BELOW) {
                return COMPACT;
            }
            if (diagonal < DEFAULT_BELOW) {
                return DEFAULT;
            }
            if (diagonal < LARGE_BELOW) {
                return LARGE;
            }
            return EXTRA_LARGE;
        }

        private static Rectangle2D primaryScreenBounds() {
            try {
                return Screen.getPrimary().getBounds();
            } catch (RuntimeException noToolkitYet) {
                // Called before the JavaFX toolkit is up (e.g. from a devcheck
                // harness). Fall back rather than failing.
                return null;
            }
        }

        public String displayName() {
            return switch (this) {
                case AUTO -> "Automatic (match screen)";
                case COMPACT -> "Compact";
                case DEFAULT -> "Default (1080p)";
                case LARGE -> "Large (1440p)";
                case EXTRA_LARGE -> "Extra large (4K)";
            };
        }

        @Override
        public String toString() {
            return displayName();
        }
    }

    /** Where the generated stylesheet is cached, so it is written once per
     *  tier per run rather than on every resize. */
    private static String appliedTier = null;

    /**
     * The data URL of the stylesheet currently in force.
     *
     * <p>Kept so it can be REMOVED before a new one is added. Without this the
     * stylesheet list grows by one entry every time the user changes scale in
     * the Settings dialog: each new data URL is appended and wins on the
     * properties it sets, so the UI still looks right, while the list quietly
     * accumulates - and a user who toggles the scale a few dozen times ends up
     * reparsing dozens of full stylesheets on every subsequent scene build.
     * It is also simply wrong to leave a superseded tier's rules active at all.
     */
    private static String appliedUrl = null;

    /**
     * Applies the scale stylesheet for {@code scale} to {@code scene}.
     *
     * <p>Idempotent for a repeated call at the same tier, and correctly
     * SWAPPING for a changed one. Safe to wire straight to a resize or
     * scene-change listener without guarding the call site.
     *
     * <p>Swapping rather than accumulating matters for correctness as well as
     * tidiness: the tiers are not strictly ordered in every property, so a
     * stale earlier rule can win over a later one on any property it happens
     * to share.
     */
    /**
     * Where a generated stylesheet must be attached to win.
     *
     * <p><b>THE CASCADE RULE THAT MADE THE WHOLE FEATURE INVISIBLE.</b>
     * JavaFX resolves stylesheets in this order of PRECEDENCE, lowest first:
     *
     * <pre>
     *   user-agent sheet  &lt;  scene.getStylesheets()  &lt;  node.getStylesheets()  &lt;  inline setStyle()
     * </pre>
     *
     * <p>Within one list, a later entry beats an earlier one on equal
     * specificity. So a stylesheet attached to a NODE outranks anything on the
     * SCENE, regardless of order.
     *
     * <p>Dashboard.fxml attaches styles.css to its root BorderPane
     * ({@code stylesheets="@../css/styles.css"}), and the Settings dialog
     * attaches it to its DialogPane. Both are node-level. Meanwhile this class
     * used to inject the generated tier sheet into
     * {@code scene.getStylesheets()} - a lower level.
     *
     * <p>The result: {@code .root { -fx-font-size: 13px }} from styles.css and
     * {@code .root { -fx-font-size: 15.5px }} from the tier sheet have identical
     * specificity, styles.css sat at the HIGHER level, and so it won. Every tier
     * produced exactly the same UI and the Interface size dropdown looked
     * broken. The same mistake silently disabled the motion override on the
     * dashboard - which is why a button-hover check that passed against a
     * synthetic button in a bare Scene did not mean the real dashboard
     * responded to the setting.
     *
     * <p>So both generated sheets go on the ROOT NODE: the highest level that
     * all the base sheets share, and later than styles.css within that list, so
     * the tier wins on a tie.
     */
    private static ObservableList<String> sheetHost(Scene scene) {
        Parent root = scene == null ? null : scene.getRoot();
        return root == null ? null : root.getStylesheets();
    }

    /**
     * Applies the scale stylesheet for {@code scale} to {@code scene}.
     *
     * <p>Idempotent for a repeated call at the same tier, and correctly
     * SWAPPING for a changed one. Safe to wire straight to a resize or
     * scene-change listener without guarding the call site.
     *
     * <p>Swapping rather than accumulating matters for correctness as well as
     * tidiness: the tiers are not strictly ordered in every property, so a
     * stale earlier rule can win over a later one on any property it happens
     * to share.
     */
    public static void apply(Scene scene, Scale scale) {
        ObservableList<String> host = sheetHost(scene);
        if (host == null) {
            return;
        }
        Scale resolved = scale == null ? Scale.DEFAULT : scale.resolve();
        String marker = resolved.name();
        if (marker.equals(appliedTier) && host.contains(appliedUrl)) {
            return;
        }

        String external = toDataUrl(buildScaleStylesheet(resolved));
        if (external == null) {
            System.err.println("[UiScale] Could not build a stylesheet for tier " + marker
                    + " - falling back to the base stylesheet only.");
            return;
        }
        // Drop the superseded tier first. See appliedUrl's javadoc.
        if (appliedUrl != null) {
            host.remove(appliedUrl);
        }
        if (!host.contains(external)) {
            host.add(external);
        }
        appliedTier = marker;
        appliedUrl = external;
        // The headline change, additionally forced through the INLINE style.
        //
        // Belt and braces for the one property that matters most, and it is not
        // redundant: `em` units resolve against the inherited font size, so if
        // the root size fails to move then every em-based rule in styles.css
        // silently stays at its 1K value and the whole hierarchy looks frozen
        // even though some padding rules may have changed. Inline style
        // outranks every stylesheet at every level, so this cannot lose a tie.
        //
        // Only font-size is set inline; padding and radii are left to the
        // generated sheet, which now wins on its own merits.
        scene.getRoot().setStyle("-fx-font-size: " + trim(resolved.baseFontSize()) + "px;");
        forceRestyle(scene);
        System.out.println("[UiScale] Applied " + marker + " tier (base font "
                + resolved.baseFontSize() + "px).");
    }

    /**
     * Re-resolves CSS and re-lays-out a whole subtree, immediately.
     *
     * <p>{@code applyCss()} on a node only processes that node; font sizes
     * resolved in {@code em} cascade down, so a single root-level pass is not
     * enough for the change to be visible before the next frame - and the
     * Settings dialog's own {@code layout()} would otherwise still be working
     * from the previous tier's geometry, which is how a dialog ends up clipping
     * its own contents at a larger tier.
     *
     * <p>Recursive, depth first, because a child laid out against a stale parent
     * height produces stale bounds even if the child's own CSS is current.
     */
    public static void forceRestyle(Scene scene) {
        Parent root = scene == null ? null : scene.getRoot();
        if (root == null) {
            return;
        }
        restyleRecursively(root);
    }

    private static void restyleRecursively(Parent node) {
        for (javafx.scene.Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent p) {
                restyleRecursively(p);
            }
        }
        if (node instanceof javafx.scene.control.Control control) {
            control.applyCss();
            control.layout();
        } else {
            node.applyCss();
            node.layout();
        }
    }

    /**
     * Forgets the applied tier AND detaches the generated stylesheet.
     *
     * <p>Needed by the Settings dialog, which changes scale at runtime: the
     * {@code appliedTier} guard would otherwise treat a re-apply as a no-op and
     * the change would appear to do nothing.
     *
     * @param scene the scene to detach from, or null to only clear the guard
     * @return true if a stylesheet was actually removed
     */
    public static boolean clear(Scene scene) {
        boolean removed = false;
        if (scene != null && appliedUrl != null) {
            removed = scene.getStylesheets().remove(appliedUrl);
        }
        appliedTier = null;
        appliedUrl = null;
        return removed;
    }

    // =================================================================
    //  MOTION PREFERENCE  (v2.0 - the fix for a no-op animation toggle)
    // =================================================================

    /**
     * The generated "no motion" stylesheet's data URL, computed once and cached.
     *
     * <p>Deliberately NOT tracking WHICH scene it is attached to. The CSS is a
     * constant, so {@link #toDataUrl} returns the same string every time, and
     * that makes "is it already applied?" a question each scene can answer for
     * itself via {@code getStylesheets().contains(...)}.
     *
     * <p>An earlier draft cached a single "currently applied" scene instead, in
     * the style of {@link #appliedUrl}. That is wrong here: this application
     * has THREE scenes alive at once - the dashboard, the floating timer
     * widget, and any open dialog - and a global "already applied" flag would
     * let the first scene's success convince the second that it had nothing to
     * do. The floating widget would keep animating while the dashboard behind
     * it had gone static, which is the same bug in a harder-to-notice form.
     * Per-scene membership is the only correct test, and it needs no tracking
     * at all.
     */
    private static String motionUrl = null;

    /**
     * THE REASON THE ANIMATION TOGGLE PREVIOUSLY DID NOTHING.
     *
     * <p>{@link Anim} gates every <em>programmatic</em> transition, and it does
     * that correctly. But almost all the visible motion in this application is
     * not programmatic at all: it is CSS {@code :hover} and {@code :pressed}
     * scale transforms on the buttons. Those live in the stylesheet, are
     * resolved by the JavaFX CSS engine, and are completely invisible to
     * {@code Anim}. Turning animations off therefore suppressed nothing the
     * user could actually see, because everything they could see was CSS -
     * and the button that does not appear to work is a user-facing failure, not
     * a cosmetic nit.
     *
     * <p>JavaFX has no {@code @keyframes} and no way to conditionally include a
     * rule, so the fix cannot be "don't apply the transform". It has to be an
     * override that WINS the cascade. Appending this after styles.css does
     * exactly that: every animated selector is re-declared with its transform
     * neutralised.
     */
    private static final String NO_MOTION_CSS = """
            /* GENERATED by UiScale - applied only when animations are disabled.
               Every selector here MUST mirror a scaled selector in styles.css.
               That stylesheet cannot be reloaded on preference change without
               losing the class it is currently displaying, so this is the only
               way to neutralise a transform after the fact - which makes the
               mirroring a hard coupling. A harness asserts the two stay in
               step; adding a scale rule to styles.css without adding its
               selector here is the bug this arrangement invites. */

            /* Buttons. The scale is neutralised but the colour change is left
               alone: hover feedback is usability, motion is not. */
            .color-swatch:hover,
            .toast-undo-button:hover,
            .toast-undo-button:pressed,
            .accent-button:hover,
            .primary-button:hover,
            .accent-button:pressed,
            .primary-button:pressed,
            .secondary-button:hover,
            .secondary-button:pressed,
            .icon-button:hover,
            .icon-button:pressed,
            .compact-button:hover,
            .preview-button:hover,
            .preview-button:pressed {
                -fx-scale-x: 1;
                -fx-scale-y: 1;
            }

            /* The effects paired with the hover scales. A live dropshadow blur
               is recomputed on every state change and every frame the node
               moves, so leaving it in place while the scale is pinned to 1
               would keep paying for motion the user can no longer see. */
            .accent-button:hover,
            .primary-button:hover,
            .toast-undo-button:hover {
                -fx-effect: none;
            }
            """;

    /**
     * Applies or removes the motion override for {@code scene}.
     *
     * <p>Safe to call repeatedly and in either direction - the toggle calls it
     * every time the user changes the setting, and it also runs at startup for
     * each scene this application owns.
     *
     * @param animationsEnabled the user's current preference
     */
    public static void applyMotion(Scene scene, boolean animationsEnabled) {
        // Same host as the tier sheet, and for the same reason - see sheetHost.
        // This one had the identical cascade bug: styles.css sits on the root,
        // the override was going onto the scene, so the dashboard's button
        // hover scales were never actually overridden - and a check that passed
        // against a synthetic button in a bare Scene could not tell.
        ObservableList<String> host = sheetHost(scene);
        if (host == null) {
            return;
        }
        if (animationsEnabled) {
            if (motionUrl != null) {
                host.remove(motionUrl);
            }
            return;
        }
        if (motionUrl == null) {
            motionUrl = toDataUrl(NO_MOTION_CSS);
            if (motionUrl == null) {
                System.err.println("[UiScale] Could not build the no-motion stylesheet; "
                        + "CSS hover motion will still play.");
                return;
            }
        }
        if (!host.contains(motionUrl)) {
            // Appended LAST within the root's list, so it beats styles.css on
            // every selector it overrides.
            host.add(motionUrl);
        }
        forceRestyle(scene);
    }

    /**
     * The same override, for a {@code DialogPane}'s own stylesheet list.
     *
     * <p>Needed because a Dialog is a SEPARATE Scene with its OWN stylesheets:
     * it does not inherit the main window's, and the base stylesheet has to be
     * re-added to it explicitly at every call site. Without this, the Settings
     * dialog itself would keep its hover motion while everything behind it had
     * gone static - which reads as the toggle half-working rather than not
     * working at all, and is a much harder bug to diagnose than a toggle that
     * simply did nothing.
     *
     * @return the URL that was added, or null if nothing was needed
     */
    public static String applyMotionTo(DialogPane pane, boolean animationsEnabled) {
        if (pane == null || animationsEnabled) {
            return null;
        }
        if (motionUrl == null) {
            motionUrl = toDataUrl(NO_MOTION_CSS);
            if (motionUrl == null) {
                return null;
            }
        }
        if (!pane.getStylesheets().contains(motionUrl)) {
            pane.getStylesheets().add(motionUrl);
        }
        return motionUrl;
    }

    /**
 * Applies a tier to every window this application currently owns.
 *
 * <p>The Settings dialog is opened FROM the dashboard, so changing the tier
 * there has to reach three independent windows: the dashboard behind it, the
 * dialog itself, and the floating timer widget if it happens to be popped out.
 * Updating only the scene the dialog was handed leaves the other two at the old
 * size - and since the dialog is on top and the widget is always-on-top, those
 * are precisely the two the user is looking at when they decide whether the
 * setting worked.
 *
 * <p>{@link javafx.stage.Window#getWindows()} is used rather than a registry
 * this class maintains, because the windows are owned by JavaFX: a dialog can
 * be shown and closed without anything in this class being told, and a stale
 * registry would both miss a new window and try to style a dead one.
 *
 * <p>Screens with no scene - a showing window whose scene is not yet assigned, or
 * one that is being torn down - are skipped rather than treated as an error.
 *
 * @return the number of windows actually restyled
 */
public static int applyToAllOpenWindows(Scale scale) {
        int touched = 0;
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            Scene scene = window.getScene();
            if (scene == null || scene.getRoot() == null) {
                continue;
            }
            try {
                apply(scene, scale);
                applyMotion(scene, AppSettings.animationsEnabled());
                touched++;
                // A dialog sized for the previous tier's content clips at a larger
                // one. Resizing to its content afterwards is what keeps the
                // Settings window itself from cutting off its own buttons.
                reshow(window);
            } catch (RuntimeException e) {
                System.err.println("[UiScale] Could not rescale a window: "
                        + e.getMessage());
            }
        }
        return touched;
    }

    /**
     * Re-sizes a window to its content, when it can be done safely.
     *
     * <p>Only for showing, resizable windows: {@code sizeToScene()} throws on a
     * fixed-size window and is meaningless on one that is not on screen yet.
     */
    private static void reshow(javafx.stage.Window window) {
        // isResizable lives on Stage; a Window that is not a Stage cannot be
        // sized to its content anyway.
        boolean resizable = !(window instanceof javafx.stage.Stage stage)
                || stage.isResizable();
        if (!window.isShowing() || !resizable) {
            return;
        }
        try {
            window.sizeToScene();
        } catch (RuntimeException notReadyYet) {
            // A window mid-transition throws here and sizes itself on the next
            // pulse anyway; there is nothing to repair.
        }
    }

    /**
     * Re-applies BOTH preferences to a scene, reading them from settings.
     *
     * <p>This is what the Settings dialog's animation checkbox calls. Routing
     * the re-apply through one method rather than duplicating the logic at each
     * call site is what stops the two preferences from drifting apart - and
     * keeping it in UiScale rather than in Anim is deliberate, because this is
     * a STYLESHEET concern, not a transition concern.
     *
     * <p>Order matters: the motion sheet is applied first so that
     * {@link #apply} then appends the scale sheet after it. The scale sheet sets
     * no transforms, so the two do not actually conflict, but a caller that
     * later adds a tier-specific transform will get the base one winning, which
     * is the safer default.
     */
    public static void refresh(Scene scene) {
        if (scene == null) {
            return;
        }
        applyMotion(scene, AppSettings.animationsEnabled());
        apply(scene, AppSettings.uiScale());
        // Force a re-resolve now rather than on the next pulse, so the change is
        // visible the instant the dialog closes instead of one frame later.
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    /**
     * The no-motion stylesheet text.
     *
     * <p>Public rather than package-visible because the guard against the
     * mirror drifting is in another package - see {@code FxmlCheck}, which
     * asserts that every scaled selector in {@code styles.css} also appears
     * here. That assertion is the only thing standing between this class and a
     * silent regression of the exact bug this sheet exists to fix.
     */
    public static String noMotionStylesheet() {
        return NO_MOTION_CSS;
    }

    /**
     * The generated CSS for a tier.
     *
     * <p>Only the STRUCTURAL values appear here - the ones that {@code em}
     * cannot scale. Text sizes are handled entirely by {@code em} in
     * styles.css and are deliberately absent, because a second definition of
     * the same property would simply fight the first.
     *
     * <p><b>EVERY VALUE IS ANCHORED ON WHAT styles.css ALREADY DECLARES.</b>
     * They are not independently chosen numbers, and the difference is not
     * cosmetic. The first version of this method invented its own spacing
     * ({@code 9px 16px} for accent buttons where the stylesheet said
     * {@code 8 18}, {@code 6px 9px} where it said {@code 2 8}) and restated
     * padding for {@code .top-bar}, {@code .status-bar} and
     * {@code .sidebar-pane > .title-content}, which the stylesheet does not
     * declare at all. None of that was visible - the generated sheet was never
     * successfully attached - and all of it would have landed the moment that
     * broke, changing button padding on every screen while the user had chosen
     * the setting labelled "Default (1080p)".
     *
     * <p>So the rule is: restate a property only if the stylesheet already
     * states it, and use the stylesheet's own value as the factor-1.0 anchor.
     * The three selectors that were dropped declared nothing, so generating
     * spacing for them would have invented styling rather than scaled it.
     *
     * <p>Public rather than package-visible because the guard that the DEFAULT
     * tier reproduces the hand-written stylesheet lives in another package -
     * see {@code FxmlCheck}. That guard is what makes "Interface size: Default"
     * a real way to keep the current appearance, which is the property the
     * tier boundaries were chosen to preserve.
     */
    public static String buildScaleStylesheet(Scale scale) {
        double f = scale.spacingFactor();
        StringBuilder css = new StringBuilder();
        css.append("/* GENERATED by UiScale - do not edit; regenerate instead. */\n");
        css.append("/* tier=").append(scale.name())
                .append(" spacingFactor=").append(String.format(Locale.ROOT, "%.2f", f)).append(" */\n\n");
        css.append("/* The single base font size. Every text rule in styles.css is\n");
        css.append("   expressed in em, so this one line rescales the whole type hierarchy. */\n");
        css.append(".root { -fx-font-size: ")
                .append(trim(scale.baseFontSize())).append("px; }\n\n");

        css.append("/* Structural spacing, which em cannot express (see JDK-8205473).\n");
        css.append("   Every value below is the value styles.css ALREADY ships, at\n");
        css.append("   factor 1.0 - not an independent guess. That is what makes the\n");
        css.append("   DEFAULT tier a genuine no-op, so a user who picks \"Default\" to\n");
        css.append("   opt out of scaling gets the interface they already had. */\n");
        css.append(".glass-panel { -fx-padding: ").append(trim(16 * f)).append("px; }\n");
        css.append(".glass-panel { -fx-background-radius: ").append(trim(18 * f)).append("px;\n");
        css.append("                -fx-border-radius: ").append(trim(18 * f)).append("px; }\n");
        css.append(".accent-button { -fx-padding: ").append(trim(8 * f)).append("px ")
                .append(trim(18 * f)).append("px; }\n");
        css.append(".secondary-button { -fx-padding: ").append(trim(8 * f)).append("px ")
                .append(trim(16 * f)).append("px; }\n");
        css.append(".icon-button { -fx-padding: ").append(trim(2 * f)).append("px ")
                .append(trim(8 * f)).append("px; }\n");
        css.append(".search-field { -fx-padding: ").append(trim(7 * f)).append("px ")
                .append(trim(12 * f)).append("px; }\n");
        //
        // The chart resize handle scales WITH the tier, and this is the one
        // generated rule that is load-bearing for usability rather than looks.
        //
        // The divider's laid-out thickness comes from its prefWIDTH, so the
        // HORIZONTAL padding below is what sets the grab height - the vertical
        // numbers only paint the line inside it. At the base factor this is a
        // ~15px target with a ~1px line; scaled down to Compact it is still
        // ~13px, which is the difference between a control you can catch and
        // the sliver it replaced. Regenerating without this rule would silently
        // undo the fix on every tier below Default, which is exactly where a
        // user on a small laptop needs the larger target most.
        //
        // Insets are scaled alongside padding so the visible line stays ~1px at
        // every tier instead of thickening on Large screens.
        double handle = 7.5 * f;
        css.append("\n/* Chart resize handle: horizontal padding is the hit area, "
                + "vertical insets are the line. */\n");
        css.append(".main-split-pane .split-pane-divider {\n");
        css.append("    -fx-padding: 0 ").append(trim(handle)).append("px ")
                .append(trim(handle)).append("px;\n");
        css.append("    -fx-background-insets: ").append(trim(handle)).append("px 0; }\n");
        return css.toString();
    }

    /** Trims a double to something readable, dropping a trailing ".0". */
    private static String trim(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.endsWith(".00")) {
            return s.substring(0, s.length() - 3);
        }
        if (s.endsWith("0")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Wraps CSS text in a {@code data:} URI, returned as a String.
     *
     * <p>A data URI rather than a temp file: there is no file to clean up, no
     * path to escape on Windows, and no risk of a stale file being served on the
     * next run with different content.
     *
     * <p><b>WHY A STRING AND NOT A {@code java.net.URL}.</b> Two independent
     * reasons, and the first one is a bug this method used to have.
     *
     * <p>1. {@code Scene#getStylesheets()} is a list of Strings, not URLs, so
     *    the value is only ever converted back with {@code toExternalForm()}.
     *    Going via {@link URL} loses information and adds a failure mode for
     *    nothing - and in fact cost this method its entire function. The old
     *    code built {@code URI_BASE.resolve(encoded).toURL()}, reasoning that
     *    building the URI by hand would dodge a URLStreamHandler lookup. But a
     *    {@code data:} URI is OPAQUE, and {@link URI#resolve} against an opaque
     *    base returns the child unchanged. The child is a relative URI, so
     *    {@code toURL()} threw "URI is not absolute" every single time and the
     *    method returned null unconditionally. Every caller silently fell back to
     *    the base stylesheet - the generated sheets were never applied at all,
     *    for the scale tiers or for the motion override.
     * <p>2. There is no {@code data:} URLStreamHandler on the JDK, so
     *    {@code URI#toURL()} throws "unknown protocol: data" regardless. A URL
     *    could not be produced here even with correct URI construction.
     *
     * <p>{@code Scene#getStylesheets} hands the string to JavaFX's own loader,
     *    which resolves the {@code data:} form itself, so the String is not
     *    merely a workaround - it is the form the API actually wants.
     *
     * <p>Built by CONCATENATION, not by {@link URI#URI(String, String, String,
     * String)}. That constructor quotes its scheme-specific-part argument as
     * though it were a path, so passing {@code "text/css;charset=utf-8"}
     * produces {@code data://text/css;charset=utf-8/...} - a hierarchical URI
     * with a bogus authority, which JavaFX then rejects with "Invalid URI".
     *
     * <p>The result is re-parsed with {@link URI#create} before being returned.
     * That is not ceremony: it is the only thing standing between a future
     * encoder change and a stylesheet that JavaFX silently refuses to load,
     * which is precisely the failure mode this method had before.
     */
    private static String toDataUrl(String css) {
        // URLEncoder targets form encoding, which turns a space into '+'. Legal
        // in a URI but not the conventional encoding, so normalise it.
        String encoded = java.net.URLEncoder.encode(css, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String candidate = "data:text/css;charset=utf-8," + encoded;
        try {
            java.net.URI.create(candidate);
            return candidate;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The Focus Timer sidebar column's own width clamp, mirrored from
     *  Dashboard.fxml. The clock is calibrated against these two numbers, so
     *  they must stay in step with the sidebar's minWidth/maxWidth or the
     *  clock will size against the wrong range.
     *
     *  <p>They live on the ScrollPane that wraps the column, not on the column's
     *  own VBox: the ScrollPane is the node that participates in the centre
     *  HBox's layout, so it is the one whose width actually decides how much
     *  horizontal room the clock gets. */
    public static final double SIDEBAR_MIN_WIDTH = 300;
    public static final double SIDEBAR_MAX_WIDTH = 420;

    /** The clock font band. The floor is what lets "01:30:00" render whole in
     *  a narrow sidebar instead of ellipsising. */
    public static final double CLOCK_FONT_MIN = 35;
    public static final double CLOCK_FONT_MAX = 50;

    /** The base font size the v1 clock calibration was measured against. */
    private static final double CALIBRATION_BASE_FONT = 13.0;

    /**
     * The focus-timer clock size for a pane width at a given scale tier.
     *
     * <p>The curve is the v1 one, unchanged: interpolate linearly between the
     * sidebar column's own 300..420px clamp, which puts 35px at the floor and
     * 50px at the ceiling. That shape is then OFFSET by the scale tier and
     * re-clamped.
     *
     * <p>Applying the tier as an offset after the interpolation - rather than
     * folding it into the interpolation - is what keeps two properties
     * separately true: at the calibration tier the result is EXACTLY the old
     * value (so the 1K layout is bit-for-bit unchanged), and at any other tier
     * it stays inside the band. Folding the base into the interpolation looks
     * tidier and quietly moves the 1K clock, which is a regression nobody
     * would notice until someone compared screenshots.
     *
     * <p>THE CLAMP BITES AT THE TOP TIER, AND THAT IS CORRECT. The band is not
     * arbitrary - it is what physically fits between the badge column and the
     * panel padding in a 300px-wide sidebar. A user on a 4K display who
     * chooses "Extra large" therefore gets a bigger clock only while there is
     * room for one; at the column ceiling the size is pinned at 50px whatever
     * they choose. Growing it further would push the clock out of the panel
     * and reintroduce the exact truncation bug the band exists to prevent. The
     * multiplier is set so the tier range (11.5 to 18.0) produces a visible
     * difference across most of the band while still clamping at the extreme.
     */
    public static double clockFontSize(Scale scale, double paneWidth,
                                       double minPx, double maxPx) {
        double base = (scale == null ? Scale.DEFAULT : scale.resolve()).baseFontSize();
        double span = SIDEBAR_MAX_WIDTH - SIDEBAR_MIN_WIDTH;
        double t = (paneWidth - SIDEBAR_MIN_WIDTH) / span;
        double interpolated = CLOCK_FONT_MIN + t * (CLOCK_FONT_MAX - CLOCK_FONT_MIN);
        double scaled = interpolated + (base - CALIBRATION_BASE_FONT) * TIER_CLOCK_STEP;
        return Math.max(minPx, Math.min(maxPx, scaled));
    }

    /** Pixels of clock growth per 1px of base font size. Small enough that the
     *  tier range stays inside the physical band rather than saturating it. */
    private static final double TIER_CLOCK_STEP = 1.6;

    /** Loads the base stylesheet's bytes, for the harness that checks the
     *  em-conversion actually happened. */
    static String readBaseStylesheet() throws IOException {
        try (InputStream is = UiScale.class
                .getResourceAsStream("/com/unitracker/css/styles.css")) {
            Objects.requireNonNull(is, "styles.css is missing from the classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
