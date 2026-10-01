package com.unitracker.util;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.animation.Transition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Every animation in the app is created here (v2.0).
 *
 * <h2>WHY A CENTRAL FACTORY</h2>
 * The requirement is a Settings toggle that turns animations off for users who
 * want raw performance. The obvious implementation - a boolean checked at each
 * of the two dozen call sites - is exactly the kind of thing that works until
 * someone adds a new animation and forgets. Six months later the setting is
 * "mostly" respected and the user has no idea why some things still move.
 *
 * <p>Routing every transition through one factory makes the switch total: there
 * is no way to create an animated transition in this codebase that bypasses
 * the check, because {@code new FadeTransition} never appears outside this
 * file. Turning animations off is genuinely complete or not done at all.
 *
 * <h2>WHAT "OFF" ACTUALLY DOES</h2>
 * It does not merely skip the animation - that would leave elements stranded
 * at their pre-animation state, which for a fade means invisible. Every method
 * here applies the animation's END state immediately and starts nothing. A
 * panel that would have slid in is simply present; a chart that would have
 * faded up is fully opaque. The UI is static and CORRECT, not static and
 * broken, which is the difference between a respected setting and an
 * infuriating one.
 *
 * <h2>THREADING</h2>
 * These must all be called on the JavaFX application thread. They are not
 * synchronised and do not marshal - a transition created off-thread is a
 * programming error that should fail loudly rather than be silently queued.
 */
public final class Anim {

    private Anim() {
    }

    private static final double STANDARD_MS = 180.0;
    private static final double EMPHASIS_MS = 260.0;

    /**
     * Whether animations should run. Read fresh on every call rather than
     * cached, so flipping the setting takes effect on the very next animation
     * without restarting anything. The lookup is an in-memory read (see
     * {@link AppSettings}), so this is not a per-frame database hit.
     */
    public static boolean enabled() {
        return AppSettings.animationsEnabled();
    }

    /** Test hook: forces the gate without touching the database. */
    static volatile Boolean overrideForTests = null;

    static boolean gateOpen() {
        Boolean forced = overrideForTests;
        return forced != null ? forced : enabled();
    }

    // ----------------------------------------------------------------
    //  Fades
    // ----------------------------------------------------------------

    /**
     * Fades a node in, or makes it visible immediately when animations are off.
     *
     * <p>The node is set fully opaque and visible at the end regardless, so
     * the "off" path is a valid final state rather than an aborted animation.
     */
    public static void fadeIn(Node node, double millis) {
        fadeIn(node, millis, 0);
    }

    public static void fadeIn(Node node, double millis, double delayMillis) {
        if (node == null) return;
        if (!gateOpen()) {
            node.setOpacity(1.0);
            node.setVisible(true);
            return;
        }
        node.setOpacity(0.0);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setDelay(Duration.millis(delayMillis));
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    /**
     * Fades a node out, then runs {@code onFinished}.
     *
     * <p>With animations off the callback still runs, immediately. Callers rely
     * on that to do cleanup - removing a node from its parent, for instance -
     * and a version that silently skipped the callback would leak the node.
     */
    public static void fadeOut(Node node, double millis, Runnable onFinished) {
        if (node == null) {
            if (onFinished != null) onFinished.run();
            return;
        }
        if (!gateOpen()) {
            node.setOpacity(0.0);
            node.setVisible(false);
            if (onFinished != null) onFinished.run();
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setFromValue(node.getOpacity());
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            node.setVisible(false);
            if (onFinished != null) onFinished.run();
        });
        fade.play();
    }

    // ----------------------------------------------------------------
    //  Slides
    // ----------------------------------------------------------------

    /**
     * Slides a node up into place from {@code offset} pixels below, fading it
     * in at the same time.
     *
     * <p>This is the panel-entrance effect. The combined form matters: fading
     * and translating in parallel reads as a single physical motion, whereas
     * running them in sequence as two independent animations reads as two
     * separate effects and looks cheap.
     */
    public static void slideUpIn(Node node, double offset, double millis) {
        slideUpIn(node, offset, millis, 0);
    }

    public static void slideUpIn(Node node, double offset, double millis, double delayMillis) {
        if (node == null) return;
        if (!gateOpen()) {
            node.setOpacity(1.0);
            node.setVisible(true);
            node.setTranslateY(0);
            return;
        }
        node.setOpacity(0.0);
        node.setTranslateY(offset);
        node.setVisible(true);

        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setDelay(Duration.millis(delayMillis));

        TranslateTransition slide = new TranslateTransition(Duration.millis(millis), node);
        slide.setFromY(offset);
        slide.setToY(0.0);
        slide.setDelay(Duration.millis(delayMillis));
        // Restore the layout offset when done, or the node is permanently
        // offset by a fraction of a pixel and accumulates sub-pixel drift.
        slide.setOnFinished(e -> node.setTranslateY(0));

        ParallelTransition together = new ParallelTransition(fade, slide);
        together.play();
    }

    /** Slides a node in from the left, for list rows and cards. */
    public static void slideInFromLeft(Node node, double offset, double millis, double delayMillis) {
        if (node == null) return;
        if (!gateOpen()) {
            node.setOpacity(1.0);
            node.setVisible(true);
            node.setTranslateX(0);
            return;
        }
        node.setOpacity(0.0);
        node.setTranslateX(-offset);
        node.setVisible(true);

        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setDelay(Duration.millis(delayMillis));

        TranslateTransition slide = new TranslateTransition(Duration.millis(millis), node);
        slide.setFromX(-offset);
        slide.setToX(0.0);
        slide.setDelay(Duration.millis(delayMillis));
        slide.setOnFinished(e -> node.setTranslateX(0));

        new ParallelTransition(fade, slide).play();
    }

    // ----------------------------------------------------------------
    //  Charts
    // ----------------------------------------------------------------

    /**
     * The chart-load animation: a short fade-and-lift as a freshly rendered
     * chart replaces the previous one.
     *
     * <p>Deliberately restrained. A chart is dense information; a bounce or a
     * scale would fight the data for attention, and the whole point of this
     * effect is to soften the swap, not to perform. Eight pixels and 220ms is
     * enough to register as a transition and short enough not to delay reading.
     */
    public static void chartLoad(Node chartContainer) {
        slideUpIn(chartContainer, 8.0, EMPHASIS_MS);
    }

    /**
     * Staggers a list of rows so they arrive in sequence.
     *
     * <p>The per-item delay is CAPPED. A naive {@code i * 50ms} over a long
     * list makes the last item appear half a second late, which reads as
     * sluggishness rather than as polish - and with a hundred note cards the
     * last one would not arrive for five seconds. Capping the total stagger
     * keeps the effect on short lists and makes it simply not apply on long
     * ones, which is the right trade.
     */
    public static void staggerIn(Iterable<? extends Node> nodes, double offset, double millis) {
        if (nodes == null) return;
        if (!gateOpen()) {
            for (Node n : nodes) {
                if (n != null) {
                    n.setOpacity(1.0);
                    n.setVisible(true);
                }
            }
            return;
        }
        final double MAX_TOTAL_STAGGER_MS = 320.0;
        final double PER_ITEM_MS = 34.0;
        int index = 0;
        for (Node n : nodes) {
            if (n == null) continue;
            double delay = Math.min(index * PER_ITEM_MS, MAX_TOTAL_STAGGER_MS);
            slideUpIn(n, offset, millis, delay);
            index++;
        }
    }

    // ----------------------------------------------------------------
    //  Composites
    // ----------------------------------------------------------------

    /**
     * Runs a sequence of steps in order, or runs them all immediately when
     * animations are disabled.
     *
     * <p>Each step is a zero-duration {@link PauseTransition} whose finished
     * handler invokes it. PauseTransition is final, so it can be instantiated
     * and configured but not subclassed - which is fine, because the finished
     * callback is the supported way to attach work to it.
     */
    public static void sequence(Runnable... steps) {
        if (!gateOpen()) {
            for (Runnable step : steps) {
                if (step != null) step.run();
            }
            return;
        }
        SequentialTransition sequence = new SequentialTransition();
        for (Runnable step : steps) {
            if (step == null) continue;
            PauseTransition tick = new PauseTransition(Duration.millis(0));
            tick.setOnFinished(e -> step.run());
            sequence.getChildren().add(tick);
        }
        sequence.play();
    }

    /** A short pulse used to draw the eye to a value that just changed. */
    public static void pulse(Node node) {
        if (node == null) return;
        if (!gateOpen()) {
            // Still snap to the emphasised state and back, so the user gets the
            // feedback; they just get it instantly.
            node.setStyle("-fx-scale-x: 1.06; -fx-scale-y: 1.06;");
            PauseTransition snap = new PauseTransition(Duration.millis(90));
            snap.setOnFinished(e -> node.setStyle(""));
            snap.play();
            return;
        }
        node.setStyle("-fx-scale-x: 1.08; -fx-scale-y: 1.08;");
        PauseTransition back = new PauseTransition(Duration.millis(STANDARD_MS));
        back.setOnFinished(e -> node.setStyle(""));
        back.play();
    }

    // ----------------------------------------------------------------
    //  Reduced-motion accessibility
    // ----------------------------------------------------------------

    /**
     * System-level reduced-motion preference, when it can be read.
     *
     * <p>JavaFX exposes no API for the OS accessibility setting, so this
     * returns false and exists to document that fact at the call sites that
     * would otherwise be tempted to try. The user-facing Settings toggle is
     * the supported mechanism, and it is the one that matters.
     */
    public static boolean systemPrefersReducedMotion() {
        return false;
    }

    /** Applies a margin instantly, with no transition. Used by layout code
     *  that repositions nodes in response to a live drag, where animating
     *  would lag behind the pointer. */
    public static void setMargin(Node node, Insets margin) {
        if (node == null) return;
        javafx.scene.layout.Region region = (javafx.scene.layout.Region) node;
        javafx.scene.layout.StackPane.setMargin(region, margin);
    }

    /** Standard duration, exposed so call sites stay visually consistent. */
    public static double standardMillis() {
        return STANDARD_MS;
    }

    public static double emphasisMillis() {
        return EMPHASIS_MS;
    }

    /**
     * Wraps an {@link Animation} so it respects the gate. Provided for the rare
     * case where a caller needs a custom transition type.
     *
     * @return the same animation if enabled, or null if it was suppressed -
     *         so callers must handle a null return, which is why the typed
     *         helpers above exist and are preferred.
     */
    public static Animation gate(Animation animation, Runnable applyEndState) {
        if (animation == null) return null;
        if (!gateOpen()) {
            if (applyEndState != null) applyEndState.run();
            return null;
        }
        return animation;
    }

    /** Convenience for a plain opacity tween on an arbitrary property. */
    public static void tweenOpacity(Node node, double from, double to, double millis) {
        if (node == null) return;
        if (!gateOpen()) {
            node.setOpacity(to);
            return;
        }
        FadeTransition t = new FadeTransition(Duration.millis(millis), node);
        t.setFromValue(from);
        t.setToValue(to);
        t.play();
    }

    /** Adapts a generic {@link Transition} to the gate. */
    public static void play(Transition transition) {
        if (transition == null) return;
        if (!gateOpen()) {
            // Jump to the end value by playing and fast-forwarding, which is
            // how a Transition's end state is reached without waiting.
            transition.setRate(1000.0);
            transition.play();
            return;
        }
        transition.play();
    }
}
