package com.unitracker.util;

import com.unitracker.db.DatabaseHelper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The complete, PURE timekeeping model of the Focus Timer (v2.0).
 *
 * <h2>WHY THIS CLASS EXISTS</h2>
 * The v1 timer kept its remaining time in a {@code long} field, decremented by
 * an {@code AnimationTimer}, and on pause it recovered the remaining time by
 * PARSING THE LABEL'S TEXT BACK INTO A NUMBER
 * ({@code remainingPomodoroSeconds()} split the label on ":"). That has two
 * fatal properties for a timer that is supposed to survive the app closing:
 *
 * <ul>
 *   <li>the number only exists in memory, so it is gone when the process is;</li>
 *   <li>the number is derived from a rendered string, so any change to the
 *       display format silently corrupts the resume value.</li>
 * </ul>
 *
 * <p>This class replaces both with a single wall-clock DEADLINE. The one piece
 * of state that matters is {@code endsAt} - an absolute {@link Instant} - and
 * everything else is computed from it against the current clock. That has one
 * property the old design could not have: it is correct whether the app was
 * closed for ten seconds or ten days, because the clock kept running either
 * way and the answer is a subtraction.
 *
 * <h2>WHY IT IS STATIC AND DEPENDENCY-FREE</h2>
 * Every method here is pure arithmetic on an {@link Instant}. There is no
 * JavaFX, no database, no system clock read inside the logic itself (the
 * caller passes "now" in). That is deliberate: it makes the entire
 * "timer must not reset on restart" requirement testable by ordinary
 * assertions with no toolkit, no database and no sleeping - see
 * {@code devcheck/TimerStateCheck}, which simulates an app that was closed
 * for a day by simply passing a different {@code now}.
 *
 * <h2>THE PAUSE SUBTLETY - AND WHY PAUSE STORES A COUNT</h2>
 * A paused timer is not counting down, so it has no meaningful deadline: the
 * wall clock keeps moving but the timer is not supposed to. The obvious fix -
 * store {@code pauseInstant + remaining} as the deadline and treat it like any
 * other - is WRONG, and the assertion harness caught it doing damage. If the
 * app is closed for two hours while paused, that synthetic deadline falls two
 * hours into the past, and on restart the timer reads as zero remaining. The
 * user pauses for lunch, comes back, and their session is gone.
 *
 * <p>So a pause stores a COUNT, not an instant: "1200 seconds were left when I
 * paused". That number has no relationship to the wall clock and therefore
 * cannot go stale. Resuming turns it back into a deadline by adding it to the
 * resume instant. The consequence is that a pause survives any length of
 * closure, which is the behaviour the requirement actually asks for.
 */
public final class FocusTimerState {

    private FocusTimerState() {
    }

    /** How long is left, rounded UP to whole seconds, never negative.
     *
     *  <p>Rounding up rather than down is deliberate: a 0.4-second remainder
     *  should still show "1" and not "0", otherwise the final second of every
     *  session is displayed as 00:00 before completion actually fires, which
     *  looks like the timer stalled.
     *
     *  <p>The three cases are kept strictly separate, because collapsing them
     *  is an easy mistake: exactly zero is zero, a small POSITIVE remainder
     *  rounds up to one, and a negative duration is clamped at zero. Testing
     *  "is it negative" alone conflates the last two with the first and makes
     *  the clock read 00:01 at the exact moment it should read 00:00. */
    public static long remainingSeconds(Instant endsAt, Instant now) {
        if (endsAt == null || now == null) return 0L;
        Duration remaining = Duration.between(now, endsAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return 0L;
        }
        long wholeSeconds = remaining.getSeconds();
        // getNano() is the sub-second remainder of a positive Duration, so any
        // non-zero value here means "part of the next second is still to come".
        if (remaining.getNano() > 0) {
            wholeSeconds += 1L;
        }
        return wholeSeconds;
    }

    /** True once the deadline has passed. This is the single condition the
     *  startup restore uses to decide whether to fire completion.
     *
     *  <p>Only meaningful for a RUNNING timer. A paused one has no deadline to
     *  compare against, which is exactly why {@link #restore} branches on the
     *  paused flag before it ever calls this. */
    public static boolean isExpired(Instant endsAt, Instant now) {
        if (endsAt == null || now == null) return false;
        return !now.isBefore(endsAt);
    }

    /**
     * Freezes the remaining time at the moment of a pause, returning the count
     * to persist.
     *
     * <p>This is deliberately a number and not an {@link Instant}. See the class
     * comment: an instant-based pause deadline silently expires while the app
     * is closed, whereas a count has no relationship to the clock and is
     * therefore correct however long the pause lasts.
     */
    public static long freezeRemaining(Instant endsAt, Instant pausedAt) {
        return Math.max(0L, remainingSeconds(endsAt, pausedAt));
    }

    /**
     * Turns a frozen remainder back into a deadline at the moment of resume.
     *
     * <p>Because the input is a count, the time the user spent paused is
     * simply never subtracted - which is the whole point, and the property a
     * naive "keep the original deadline" implementation gets wrong.
     */
    public static Instant deadlineForResume(long remainingSeconds, Instant resumedAt) {
        return resumedAt.plusSeconds(Math.max(0L, remainingSeconds));
    }

    /**
     * Recovers a stored session on startup and decides what to do with it.
     *
     * @param storedEndsAt  the persisted deadline; only consulted for a RUNNING
     *                      timer, because a paused one is restored from its
     *                      frozen count instead
     * @param pausedRemainingSeconds the frozen remainder from a pause, or 0
     * @param now           the current instant
     * @param wasPaused     whether the session was paused when the app closed
     * @return what the UI should do
     */
    public static RestoreAction restore(Instant storedEndsAt, long pausedRemainingSeconds,
                                        Instant now, boolean wasPaused) {
        if (wasPaused) {
            // A paused timer is never "expired" - it is not counting down, so
            // there is no deadline to have passed. Resume with the frozen
            // remainder, re-anchored to now. This is correct whether the app
            // was shut for a minute or a fortnight.
            long remaining = Math.max(0L, pausedRemainingSeconds);
            return new RestoreAction(RestoreAction.Kind.RESUME_PAUSED,
                    deadlineForResume(remaining, now), remaining);
        }
        if (storedEndsAt == null) {
            return new RestoreAction(RestoreAction.Kind.NONE, null, 0L);
        }
        if (isExpired(storedEndsAt, now)) {
            // The requirement this class exists for: the app was closed across
            // the deadline, so the completion logic must fire on startup rather
            // than the timer silently restarting.
            return new RestoreAction(RestoreAction.Kind.COMPLETE_NOW, null, 0L);
        }
        return new RestoreAction(RestoreAction.Kind.RESUME_RUNNING, storedEndsAt,
                remainingSeconds(storedEndsAt, now));
    }

    /** The decision {@link #restore} made, and the deadline to go with it. */
    public record RestoreAction(Kind kind, Instant nextDeadline, long remainingSeconds) {

        public enum Kind {
            /** Nothing to restore. */
            NONE,
            /** Was counting down, still has time - carry on from the stored deadline. */
            RESUME_RUNNING,
            /** Was paused - resume with the remainder, referenced from now. */
            RESUME_PAUSED,
            /** Expired while the app was closed - run the completion logic once. */
            COMPLETE_NOW
        }

        public boolean hasSomethingToRestore() {
            return kind != Kind.NONE;
        }
    }

    /**
     * The three states a focus session can be in, as ONE value.
     *
     * <p><b>WHY THIS EXISTS RATHER THAN A {@code boolean running} FLAG.</b>
     * Three states cannot be encoded in one boolean, and the two-view bug this
     * replaces was caused by exactly that. The floating widget used to enable
     * its own action button from a DELTA:
     *
     * <pre>
     *   if (running != lastRunningState) { ...enable, relabel... }
     * </pre>
     *
     * <p>A delta cannot express "there are three buttons' worth of state here",
     * and it cannot fire at all on the first push when the session is not
     * running - {@code running == false} matches the initial
     * {@code lastRunningState == false}, so the body never executed and the
     * button stayed at the disabled it was given by {@code initialize()}. The
     * result was that popping the widget out while paused or idle produced a
     * widget whose Start/Resume button silently did nothing, while the
     * identical sidebar button worked, because the sidebar sets its own label
     * directly at each transition instead of inferring it.
     *
     * <p>Pushing the phase as a VALUE removes the whole class of problem: the
     * widget renders the phase it is told, so the first push is as correct as
     * the thousandth, and no amount of state churn can leave a label behind.
     */
    public enum Phase {
        /** No session. The button offers to start one. */
        IDLE,
        /** A session exists with time on the clock, not counting. */
        PAUSED,
        /** A session exists and is counting down. */
        RUNNING;

        /**
         * What the primary action button should SAY.
         *
         * <p>"Start" and "Resume" are deliberately distinct words: the only
         * difference between IDLE and PAUSED is whether work would be lost, and
         * telling the user which of those they are in is the entire purpose of
         * the label. One generic "Start" for both would hide that.
         */
        public String actionLabel() {
            return switch (this) {
                case IDLE -> "Start";
                case PAUSED -> "Resume";
                case RUNNING -> "Pause";
            };
        }

        /** The tooltip for the primary action button, mirroring the label. */
        public String actionTooltip() {
            return switch (this) {
                case IDLE -> "Start a focus session";
                case PAUSED -> "Resume this focus session";
                case RUNNING -> "Pause this focus session";
            };
        }

        /**
         * The phase reached by clicking the primary action button.
         *
         * <p>ONE definition of the transition, shared by the sidebar and the
         * widget, which is what makes "the widget does the same thing the
         * sidebar does" a property of the code rather than a claim about two
         * hand-maintained copies of it.
         */
        public Phase toggled() {
            return switch (this) {
                case IDLE -> RUNNING;
                case PAUSED -> RUNNING;
                case RUNNING -> PAUSED;
            };
        }

        public boolean isRunning() {
            return this == RUNNING;
        }
    }

    // ----------------------------------------------------------------
    //  Display formatting
    // ----------------------------------------------------------------

    /**
     * "25:00", or "01:30:00" once there is an hour.
     *
     * <p>The hour is zero-padded to two digits on purpose. The label is
     * left-aligned, so a variable-width hour would shift every digit to its
     * right by roughly a character width the moment the clock crossed from
     * 1:xx to 0:xx, which reads as the whole timer jumping sideways. A
     * leading zero keeps the glyphs anchored.
     */
    public static String formatClock(long totalSeconds) {
        long safe = Math.max(0L, totalSeconds);
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long seconds = safe % 60L;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    /**
     * The durations offered in the Focus Timer dropdown, in minutes.
     *
     * <p>Lives here rather than in DashboardController because TWO independent
     * consumers need it: the sidebar dropdown, and the Settings dialog's
     * per-duration reward grid. When it lived in the controller, adding a
     * duration meant editing a second place that no compiler connected to the
     * first - and the symptom of forgetting is silent and expensive: the user
     * prices a 90-minute block, the settings UI never offers it, and the timer
     * then awards something different from what they set.
     */
    public static final int[] AVAILABLE_MINUTES = {15, 25, 30, 40, 45, 50, 60, 90, 120};

    /** The duration used when nothing is stored, and the fallback for an
     *  out-of-range index. */
    public static final int DEFAULT_MINUTES = 25;

    /**
     * Index-safe lookup, for mapping a ComboBox selection index to a duration.
     *
     * <p>Guarded deliberately: an out-of-range index from a listener would
     * throw ArrayIndexOutOfBoundsException on the FX thread, where it is
     * swallowed and the change is silently dropped rather than reported.
     */
    public static int minutesAt(int index) {
        if (index < 0 || index >= AVAILABLE_MINUTES.length) {
            return DEFAULT_MINUTES;
        }
        return AVAILABLE_MINUTES[index];
    }

    /** "45 min", "1 hr", "1 hr 30 min" - for dropdown labels and status text. */
    public static String formatDuration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int hours = minutes / 60;
        int rest = minutes % 60;
        String h = hours + (hours == 1 ? " hr" : " hrs");
        return rest == 0 ? h : h + " " + rest + " min";
    }

    /** Progress through the session, 0.0 to 1.0, for the progress bar.
     *
     *  <p>Computed from the total duration rather than from "how long has it
     *  been running", so a resumed session shows a correctly advanced bar
     *  immediately rather than starting over. */
    public static double progress(long remainingSeconds, int totalSeconds) {
        if (totalSeconds <= 0) return 0.0;
        double remaining = Math.max(0L, remainingSeconds);
        double done = totalSeconds - remaining;
        double fraction = done / totalSeconds;
        if (fraction < 0.0) return 0.0;
        if (fraction > 1.0) return 1.0;
        return fraction;
    }

    /** Millis-since-epoch to Instant, for reading the stored deadline. */
    public static Instant toInstant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /** Instant to millis-since-epoch, for storing the deadline. */
    public static long toEpochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    /** Whole seconds in a duration, for storing {@code duration_secs}. */
    public static long toSeconds(Duration duration) {
        return duration == null ? 0L : duration.getSeconds();
    }

    /**
     * Rounds a duration to the nearest whole minute, at least one.
     *
     * <p>Used when a user types a custom session length: a 0-minute timer would
     * complete instantly on start, and a fractional one would round to 0 on
     * the way into the database.
     */
    public static int toWholeMinutes(Duration duration) {
        if (duration == null) return 1;
        long minutes = ChronoUnit.MINUTES.between(Instant.EPOCH, Instant.EPOCH.plus(duration));
        return (int) Math.max(1L, minutes);
    }

    /**
     * Rebuilds a live timer from a persisted session, for the controller's
     * startup path. Kept here rather than in the controller so the same
     * conversion is used by the main window and the floating widget.
     */
    public static RestoreAction restoreFrom(
            DatabaseHelper.TimerSessionState stored, Instant now) {
        if (stored == null) {
            return new RestoreAction(RestoreAction.Kind.NONE, null, 0L);
        }
        return restore(toInstant(stored.endsAtEpochMillis()),
                stored.pausedRemainingSeconds(), now, stored.paused());
    }
}
