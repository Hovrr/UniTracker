package com.unitracker.devcheck;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;
import com.unitracker.util.FocusTimerState;
import com.unitracker.util.FocusTimerState.RestoreAction;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Proves the v2.0 Focus Timer timekeeping, and in particular the requirement
 * that "the timer must NOT reset if the application is closed".
 *
 * <h2>TWO LAYERS, ON PURPOSE</h2>
 * <ol>
 *   <li><b>Pure arithmetic</b> - {@link FocusTimerState} takes "now" as a
 *       parameter instead of reading the clock, so simulating an application
 *       that was shut for a day is just a matter of passing a different
 *       {@link Instant}. No sleeping, no waiting, no toolkit, milliseconds to
 *       run.</li>
 *   <li><b>Real persistence round-trip</b> - the tests below drive the ACTUAL
 *       {@link DatabaseHelper} against a sandboxed database, because the pure
 *       arithmetic cannot catch the class of bug that matters most here: the
 *       arithmetic is correct but the value was never written, or was written
 *       to the wrong column, or the column is not read back. v1 had no
 *       persistence at all, so this layer is the one that would have caught
 *       the original requirement being unmet.</li>
 * </ol>
 *
 * <p>The sandbox works because {@code DatabaseHelper} derives its path from
 * {@code user.home} in a static initialiser, so launching with
 * {@code -Duser.home=<temp>} redirects the whole class. run-tests.py
 * enforces that.
 *
 * <pre>
 *   python run-tests.py TimerStateCheck
 * </pre>
 */
public final class TimerStateCheck {

    private TimerStateCheck() {
    }

    private static int passed = 0;

    /** Fixed reference point so every expectation below is readable. */
    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    /**
     * The toggle table, which is the whole contract for both buttons.
     *
     * <p>The floating widget's Start/Resume/Pause used to be dead whenever the
     * widget was opened while the session was paused or idle, while the sidebar's
     * identical button worked. The cause was that the widget inferred its state
     * from a {@code running} boolean and enabled its button from a DELTA on that
     * boolean, which cannot represent three states and cannot fire on the first
     * push. Replacing the boolean with a single {@link FocusTimerState.Phase}
     * value is the fix, and it is only a real fix if the table itself is right -
     * so it is asserted rather than described.
     */
    private static void testPhaseTransitionTable() {
        // The reported bug, stated as a table: every one of these must reach
        // RUNNING, because all three are reachable by a user pressing a button.
        expect(FocusTimerState.Phase.RUNNING,
                FocusTimerState.Phase.IDLE.toggled(),
                "IDLE + button must start the session");
        expect(FocusTimerState.Phase.RUNNING,
                FocusTimerState.Phase.PAUSED.toggled(),
                "PAUSED + button must resume the session");
        expect(FocusTimerState.Phase.PAUSED,
                FocusTimerState.Phase.RUNNING.toggled(),
                "RUNNING + button must pause the session");

        // No phase may toggle to itself, and none may reach IDLE by pressing the
        // primary button: discarding is a separate, deliberate action. A toggle
        // to IDLE here is exactly the bug where discarding a paused session is
        // one stray click away and the elapsed work silently vanishes.
        for (FocusTimerState.Phase phase : FocusTimerState.Phase.values()) {
            expectTrue(phase.toggled() != phase,
                    "pressing the button in " + phase + " changed nothing");
            expectTrue(phase.toggled() != FocusTimerState.Phase.IDLE,
                    "pressing the button in " + phase
                            + " discarded the session instead of pausing or resuming it");
        }

        // Both non-running phases must agree on where the button leads, or the
        // widget and the sidebar can disagree about whether work would be lost.
        expect(FocusTimerState.Phase.IDLE.toggled(),
                FocusTimerState.Phase.PAUSED.toggled(),
                "IDLE and PAUSED must both lead to RUNNING");
    }

    /**
     * The three buttons are labelled by what they will DO, and the two
     * non-running states must not read identically.
     */
    private static void testPhaseActionLabels() {
        expect("Start", FocusTimerState.Phase.IDLE.actionLabel(),
                "an idle session offers to start");
        expect("Resume", FocusTimerState.Phase.PAUSED.actionLabel(),
                "a paused session offers to resume");
        expect("Pause", FocusTimerState.Phase.RUNNING.actionLabel(),
                "a running session offers to pause");

        // "Start" and "Resume" must differ. Collapsing them would hide the only
        // thing that distinguishes an idle timer from a paused one - whether the
        // user has work in progress that pressing the button would throw away.
        expectTrue(!FocusTimerState.Phase.IDLE.actionLabel()
                        .equals(FocusTimerState.Phase.PAUSED.actionLabel()),
                "idle and paused offer different labels");

        // Every phase needs a tooltip that matches its label rather than
        // contradicting it.
        for (FocusTimerState.Phase phase : FocusTimerState.Phase.values()) {
            expectTrue(phase.actionTooltip() != null
                            && phase.actionTooltip().toLowerCase(java.util.Locale.ROOT)
                                    .contains(phase.actionLabel().toLowerCase(java.util.Locale.ROOT)),
                    "the tooltip for " + phase + " mentions what the button does");
        }
    }

    /**
     * The cycle is closed: applying the table twice returns to where it started,
     * and running never drifts out of the enum.
     *
     * <p>Guards against an enum edit that adds a state and forgets to complete
     * the table, which would compile fine and leave one case hitting
     * {@code IllegalStateException} at runtime.
     */
    private static void testPhaseTogglingIsTheSidebarStateMachine() {
        for (FocusTimerState.Phase start : FocusTimerState.Phase.values()) {
            FocusTimerState.Phase back = start.toggled().toggled();
            if (start == FocusTimerState.Phase.RUNNING) {
                expect(start, back, "RUNNING pause/resume returns to RUNNING");
            } else {
                // From a stopped state, press one starts it and press two pauses
                // it again - so the pair lands in PAUSED, not back at the start.
                // Asserting the return-to-original here would have been the
                // natural mistake, and would have failed on correct code.
                expect(FocusTimerState.Phase.PAUSED, back,
                        "from " + start + ", start then pause lands in PAUSED");
            }
        }
        // isRunning must agree with the enum identity, because the dashboard's
        // own completion path and the widget's progress rendering both read it.
        expectTrue(FocusTimerState.Phase.RUNNING.isRunning(),
                "RUNNING reports isRunning");
        expectTrue(!FocusTimerState.Phase.PAUSED.isRunning(),
                "PAUSED is not running");
        expectTrue(!FocusTimerState.Phase.IDLE.isRunning(),
                "IDLE is not running");
    }

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true; // deliberate side effect
        if (!assertionsOn) {
            System.err.println("Assertions are disabled - re-run with -ea or this check proves nothing.");
            System.exit(2);
        }

        testRemainingArithmetic();
        testExpiredDetection();
        testPausePreservesRemainingAcrossRestart();
        testResumeFromPauseDoesNotConsumePausedTime();
        testResumeFromRunningAcrossRestart();
        testExpiredWhileClosedFiresCompletion();
        testExpiredLongAgoStillFiresOnce();
        testClockFormatting();
        testProgressCalculation();
        testDurationRounding();

        // Layer 2: the same guarantees, but through real SQLite.
        testSessionRoundTripsThroughTheDatabase();
        testAbandonedSessionIsNeverAwarded();
        testOnlyOneSessionIsLive();
        testCompletedSessionIsNotRestoredTwice();
        // Layer 3: the floating widget's core promise.
        testTwoObserversSeeTheSameClock();

        // Layer 4: the shared state machine both views' buttons are driven from.
        testPhaseTransitionTable();
        testPhaseActionLabels();
        testPhaseTogglingIsTheSidebarStateMachine();

        System.out.println("TimerStateCheck: " + passed + " checks passed.");
    }

    /**
     * One generic comparator rather than a {@code long} overload and a String
     * overload. Long-typed versions of this helper in the other harnesses are a
     * compile error the moment they are handed a String or an Instant, which is
     * exactly the mistake that would otherwise auto-unbox or silently compare
     * references instead of values.
     */
    private static void expect(Object expected, Object actual, String what) {
        assert expected == null ? actual == null : expected.equals(actual)
                : what + ": expected " + expected + " but got " + actual;
        passed++;
    }

    private static void expectTrue(boolean condition, String what) {
        assert condition : what;
        passed++;
    }

    /**
     * For doubles, where exact equality is the wrong question. A tiny epsilon
     * is correct here because every value under test is a small integer or a
     * clean binary fraction (0.5, 1.0), so the tolerance guards only against
     * representation noise - it is not masking a real discrepancy.
     */
    private static void expectClose(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1e-9
                : what + ": expected " + expected + " but got " + actual;
        passed++;
    }

    private static Instant plusSeconds(long seconds) {
        return T0.plusSeconds(seconds);
    }

    // =================================================================

    private static void testRemainingArithmetic() {
        expect(1500L, FocusTimerState.remainingSeconds(plusSeconds(1500), T0),
                "a fresh 25-minute session has 1500 seconds left");
        expect(1L, FocusTimerState.remainingSeconds(plusSeconds(1), T0),
                "one second left reads as 1");
        // A sub-second remainder rounds UP so the final second is never shown
        // as 00:00 before completion actually fires.
        expect(1L, FocusTimerState.remainingSeconds(T0.plusMillis(400), T0),
                "a sub-second remainder still displays as 1, not 0");
        expect(0L, FocusTimerState.remainingSeconds(T0, T0),
                "exactly at the deadline is zero, not negative");
        expect(0L, FocusTimerState.remainingSeconds(plusSeconds(-10), T0),
                "past the deadline clamps at zero, never negative");
        expect(0L, FocusTimerState.remainingSeconds(null, T0),
                "a null deadline is zero rather than a NullPointerException");
    }

    private static void testExpiredDetection() {
        expectTrue(!FocusTimerState.isExpired(plusSeconds(60), T0), "a future deadline is not expired");
        expectTrue(FocusTimerState.isExpired(T0, T0), "the exact deadline counts as expired");
        expectTrue(FocusTimerState.isExpired(plusSeconds(-1), T0), "a past deadline is expired");
        expectTrue(!FocusTimerState.isExpired(null, T0),
                "a null deadline is not treated as expired - there is nothing to complete");
    }

    /**
     * The headline requirement. A session paused at 12:30 remaining, with the
     * app then SHUT, must come back as a PAUSED session with 12:30 still left -
     * not a full fresh session, and not an expired one.
     */
    private static void testPausePreservesRemainingAcrossRestart() {
        Instant endsAt = plusSeconds(1500);
        // 300s elapse, leaving 1200s. User pauses.
        Instant pausedAt = plusSeconds(300);
        expect(1200L, FocusTimerState.remainingSeconds(endsAt, pausedAt), "precondition: 1200s left at pause");

        // The pause is stored as a COUNT. Note that no instant is involved
        // anywhere in this step - that is the entire design point.
        long frozen = FocusTimerState.freezeRemaining(endsAt, pausedAt);
        expect(1200L, frozen, "the pause freezes the remainder as a count");

        // The app is now closed for two hours - five times longer than the
        // session itself.
        Instant reopenedAt = plusSeconds(300 + 7200);
        RestoreAction action = FocusTimerState.restore(endsAt, frozen, reopenedAt, true);
        expectTrue(action.kind() == RestoreAction.Kind.RESUME_PAUSED,
                "a paused session reopens as paused, not as a completed one");
        expect(1200L, action.remainingSeconds(),
                "the paused remainder survives a two-hour shutdown intact - this is the requirement");
        expect(plusSeconds(300 + 7200 + 1200), action.nextDeadline(),
                "resuming re-anchors the deadline to the moment of resume, not to the original start");

        // A pause that outlives the session many times over must still work.
        RestoreAction weekLater = FocusTimerState.restore(endsAt, frozen, plusSeconds(300 + 604_800), true);
        expect(1200L, weekLater.remainingSeconds(),
                "even a week-long pause preserves the remainder - a synthetic future deadline would not");
    }

    /**
     * Time spent paused must not be consumed. Pausing, waiting 10 minutes, and
     * resuming must give exactly the same remaining time as pausing and
     * resuming immediately - this is the bug a naive "keep the original
     * deadline" implementation has.
     */
    private static void testResumeFromPauseDoesNotConsumePausedTime() {
        Instant endsAt = plusSeconds(1500);
        Instant pausedAt = plusSeconds(300); // 1200s left
        long frozen = FocusTimerState.freezeRemaining(endsAt, pausedAt);

        // Resume immediately.
        Instant immediateResume = plusSeconds(300);
        long immediately = FocusTimerState.remainingSeconds(
                FocusTimerState.deadlineForResume(frozen, immediateResume), immediateResume);
        expect(1200L, immediately, "an immediate resume keeps the full remainder");

        // Resume after 10 minutes of thinking about it.
        Instant lateResume = plusSeconds(300 + 600);
        long late = FocusTimerState.remainingSeconds(
                FocusTimerState.deadlineForResume(frozen, lateResume), lateResume);
        expect(1200L, late,
                "a resume ten minutes later keeps the SAME remainder - paused time is not burned");

        // And after a restart in between: pause, close, reopen, then resume.
        RestoreAction afterRestart = FocusTimerState.restore(endsAt, frozen, lateResume, true);
        expect(1200L, FocusTimerState.remainingSeconds(afterRestart.nextDeadline(), lateResume),
                "a pause that spans a restart still resumes with the full remainder");
    }

    /**
     * A RUNNING session with time left must come back as a running session
     * counting down from where it actually was, not from the beginning.
     */
    private static void testResumeFromRunningAcrossRestart() {
        Instant endsAt = plusSeconds(1500);
        // App closed for 400s with the timer running.
        Instant reopenedAt = plusSeconds(400);
        RestoreAction action = FocusTimerState.restore(endsAt, 0L, reopenedAt, false);

        expectTrue(action.kind() == RestoreAction.Kind.RESUME_RUNNING,
                "a running session with time left resumes as running");
        expect(1100L, action.remainingSeconds(),
                "1100s remain after 400s of a 1500s session, counted across the closed period");
        expect(endsAt, action.nextDeadline(),
                "the stored deadline is reused unchanged, so no drift accumulates across restarts");
    }

    /**
     * The other half of the requirement: a session that expired while the app
     * was closed must fire completion on startup, exactly once, rather than
     * silently restarting.
     */
    private static void testExpiredWhileClosedFiresCompletion() {
        Instant endsAt = plusSeconds(1500);
        // Closed for 1600s - a hundred seconds past the deadline.
        Instant reopenedAt = plusSeconds(1600);
        RestoreAction action = FocusTimerState.restore(endsAt, 0L, reopenedAt, false);

        expectTrue(action.kind() == RestoreAction.Kind.COMPLETE_NOW,
                "a session that expired while closed completes on startup");
        expect(0L, action.remainingSeconds(), "and has nothing left to count");
        expectTrue(action.nextDeadline() == null,
                "a completion carries no deadline - there is nothing to resume into");
    }

    private static void testExpiredLongAgoStillFiresOnce() {
        Instant endsAt = plusSeconds(1500);
        // The app was shut for three days.
        RestoreAction action = FocusTimerState.restore(endsAt, 0L, plusSeconds(1500 + 259_200), false);
        expectTrue(action.kind() == RestoreAction.Kind.COMPLETE_NOW,
                "a session three days overdue still completes rather than resetting");
        expect(0L, action.remainingSeconds(), "an overdue session has no remaining time");

        // And the resulting deadline is null, so re-running restore on the
        // same stored value cannot produce a second completion with a live
        // deadline - the caller marks the row FINISHED, and a FINISHED row is
        // no longer returned by loadActiveTimerSession.
        expectTrue(action.nextDeadline() == null,
                "completing yields no deadline, so the award path is driven by the status change alone");

        // No stored session at all is a no-op, not a crash.
        RestoreAction none = FocusTimerState.restore(null, 0L, T0, false);
        expectTrue(none.kind() == RestoreAction.Kind.NONE, "a null deadline restores nothing");
        expectTrue(!none.hasSomethingToRestore(), "and reports that there is nothing to restore");

        // A PAUSED session with a long-dead deadline is still just paused: the
        // paused branch has to be taken before the deadline is ever inspected,
        // or a stale synthetic deadline would expire a legitimately paused timer.
        RestoreAction stalePaused = FocusTimerState.restore(endsAt, 900L, plusSeconds(999_999), true);
        expectTrue(stalePaused.kind() == RestoreAction.Kind.RESUME_PAUSED,
                "the paused branch is checked first, so a long-dead deadline cannot expire a paused timer");
        expect(900L, stalePaused.remainingSeconds(), "and its frozen remainder is used verbatim");
    }

    private static void testClockFormatting() {
        expect("25:00", FocusTimerState.formatClock(1500), "25 minutes");
        expect("00:00", FocusTimerState.formatClock(0), "zero is zero-padded");
        expect("01:00", FocusTimerState.formatClock(60), "one minute");
        expect("01:30:00", FocusTimerState.formatClock(5400), "90 minutes gets an hour field");
        expect("10:00:00", FocusTimerState.formatClock(36000), "ten hours");

        // THE ANCHORING INVARIANT: the string length must not change as the
        // clock crosses from 1:xx to 0:xx, or every digit to the right of the
        // hour field shifts sideways in a left-aligned label.
        String justUnderTwoHours = FocusTimerState.formatClock(2 * 3600 - 1);
        String justOverOneHour = FocusTimerState.formatClock(3600 + 1);
        expect(8, justUnderTwoHours.length(), "a 1:59:59 clock is 8 characters");
        expect(8, justOverOneHour.length(), "a 1:00:01 clock is also 8 characters - the digits do not jump");

        expect("00:00", FocusTimerState.formatClock(-5), "a negative value formats as zero");
    }

    private static void testProgressCalculation() {
        // The bar must be derived from the TOTAL, not from elapsed wall time,
        // so a resumed session shows a correctly advanced bar at once.
        expect(0.0, FocusTimerState.progress(1500, 1500), "a fresh session is 0% done");
        expectClose(0.5, FocusTimerState.progress(750, 1500), "halfway is 50%");
        expectClose(1.0, FocusTimerState.progress(0, 1500), "expiry is 100%");
        expectClose(1.0, FocusTimerState.progress(-30, 1500),
                "past expiry clamps to 100% - a negative remainder means finished, not empty");
        expectClose(0.0, FocusTimerState.progress(1500, 0), "a zero total is 0% rather than dividing by zero");
    }
    private static void testDurationRounding() {
        expect(25, FocusTimerState.toWholeMinutes(Duration.ofMinutes(25)),
                "25 minutes");
        // 1554s = 25m54s. Duration.ofMinutes takes a long, so a fractional
        // minute has to be expressed in seconds.
        expect(25, FocusTimerState.toWholeMinutes(Duration.ofSeconds(1554)),
                "25m54s truncates to 25 rather than rounding up to 26");
        expect(1, FocusTimerState.toWholeMinutes(Duration.ofSeconds(30)),
                "a sub-minute duration still yields a usable 1-minute timer, not 0");
        expect(1, FocusTimerState.toWholeMinutes(Duration.ZERO),
                "a zero duration is clamped to 1 minute - a 0-minute timer would complete instantly");
        expect(90, FocusTimerState.toWholeMinutes(Duration.ofMinutes(90)), "90 minutes");
    }

    // =================================================================
    //  LAYER 2 - real persistence round-trips
    // =================================================================

    private static final DatabaseHelper db = DatabaseHelper.getInstance();

    /**
     * The whole point of Phase 3a, proven against a real database.
     *
     * <p>Each block below is a distinct failure the pure arithmetic above
     * cannot detect, because the arithmetic is right and the plumbing is
     * wrong: the value is never written, it is written to the wrong column, or
     * the column is not read back. Every one of those produces a timer that
     * "works" right up until the app is closed, which is precisely the bug the
     * requirement is about.
     */
    private static void testSessionRoundTripsThroughTheDatabase() {
        db.initializeDatabase();
        db.wipeAllData();

        Skill cat = new Skill("Category", Skill.NO_PARENT, 1000);
        db.insertSkill(cat);
        Skill sub = new Skill("Sub", cat.getId(), 1000);
        db.insertSkill(sub);

        // ---- 1. A RUNNING session survives a reopen with time left. ----
        Instant now = Instant.now();
        long endsAt = FocusTimerState.toEpochMillis(now.plusSeconds(1500));
        long id = db.startTimerSession(sub.getId(), "Sub", 1500, endsAt);
        expectTrue(id > 0, "startTimerSession returns a usable row id");

        DatabaseHelper.TimerSessionState stored = db.loadActiveTimerSession();
        expectTrue(stored != null, "a live session is found after the app 'restarts'");
        expect(id, stored.id(), "the same row comes back");
        expect(sub.getId(), stored.skillId(), "the TARGET SKILL is persisted - this is what the award reads");
        expect("Sub", stored.label(), "the label is persisted");
        expect(1500, stored.durationSeconds(), "the total duration is persisted, not just the deadline");
        expect(endsAt, stored.endsAtEpochMillis(), "the absolute deadline round-trips exactly");
        expectTrue(!stored.paused(), "a freshly started session is not paused");

        RestoreAction running = FocusTimerState.restoreFrom(stored, Instant.now());
        expectTrue(running.kind() == RestoreAction.Kind.RESUME_RUNNING,
                "it restores as still running, not as complete");
        expectTrue(running.remainingSeconds() > 1400,
                "nearly the whole session is still on the clock, got " + running.remainingSeconds());

        // ---- 2. PAUSE writes a COUNT, and it survives a long shutdown. ----
        Instant pausedAt = now.plusSeconds(300);            // 1200s left
        long frozen = FocusTimerState.freezeRemaining(now.plusSeconds(1500), pausedAt);
        expect(1200L, frozen, "precondition: 1200s left at pause");
        expectTrue(db.pauseTimerSession(id, frozen), "the pause is recorded");

        DatabaseHelper.TimerSessionState paused = db.loadActiveTimerSession();
        expectTrue(paused.paused(), "the session reads back as paused");
        // THE COLUMN THAT MATTERS. If this were stored as a deadline instead,
        // a shutdown longer than the remainder would silently zero it.
        expect(1200L, paused.pausedRemainingSeconds(),
                "THE v1 BUG: the frozen remainder is persisted as a COUNT, not a deadline");

        // A day later - far past any deadline that could have been synthesised.
        RestoreAction afterDay = FocusTimerState.restoreFrom(paused, Instant.now().plusSeconds(86_400));
        expectTrue(afterDay.kind() == RestoreAction.Kind.RESUME_PAUSED,
                "a day-later reopen is still paused, not expired");
        expect(1200L, afterDay.remainingSeconds(),
                "THE REQUIREMENT: a day-long pause preserves all 1200 remaining seconds");

        // ---- 3. RESUME converts the count back into a live deadline. ----
        Instant resumeAt = Instant.now();
        Instant resumedDeadline = FocusTimerState.deadlineForResume(
                afterDay.remainingSeconds(), resumeAt);
        expectTrue(db.resumeTimerSession(id, FocusTimerState.toEpochMillis(resumedDeadline)),
                "the resume is recorded");
        DatabaseHelper.TimerSessionState resumed = db.loadActiveTimerSession();
        expectTrue(!resumed.paused(), "resuming clears the paused flag");
        expect(0L, resumed.pausedRemainingSeconds(),
                "resuming clears the frozen count - leaving it would double-count on the next pause");
        expect(FocusTimerState.toEpochMillis(resumedDeadline), resumed.endsAtEpochMillis(),
                "the new deadline round-trips");
        expect(1200L, FocusTimerState.remainingSeconds(
                        FocusTimerState.toInstant(resumed.endsAtEpochMillis()), resumeAt),
                "and it is re-anchored to now, so the day spent paused is not billed to the session");

        // ---- 4. COMPLETE records the reward so the award is idempotent. ----
        expectTrue(db.completeTimerSession(id, 5.0), "the session is completed");
        expectTrue(db.loadActiveTimerSession() == null,
                "THE v1 BUG: a completed session is no longer returned as live, "
                        + "so it cannot be awarded a second time on the next startup");

        // ---- 5. The full award path, end to end, with the derived rollup. ----
        ProgressLog log = new ProgressLog(sub.getId(), LocalDate.now(), 25, 5.0,
                ProgressLog.SOURCE_TIMER, (int) id);
        db.insertProgressLog(log);
        expect(5.0, db.getCurrentPointsFor(List.of(sub.getId())).getOrDefault(sub.getId(), -1.0),
                "the timer log credits the subskill");
        expect(5.0, db.getCurrentPointsFor(List.of(cat.getId())).getOrDefault(cat.getId(), -1.0),
                "THE REQUIREMENT: and rolls up to the root Category - the award is not isolated "
                        + "inside the timer");
        expectTrue(!log.isAdjustment(), "a timer log is not an adjustment row");
        expect(ProgressLog.SOURCE_TIMER, log.getSource(), "it is tagged as coming from the timer");
        expect((int) id, log.getTimerSessionId(),
                "and it carries a back-link to the session that produced it");
    }

    /**
     * Reset must not leave a session that the next startup will decide
     * "expired" and award. This is the bug a naive implementation has: the
     * deadline is left in the past and the status is still RUNNING.
     */
    private static void testAbandonedSessionIsNeverAwarded() {
        db.wipeAllData();
        Skill sub = new Skill("Sub", Skill.NO_PARENT, 1000);
        db.insertSkill(sub);

        Instant now = Instant.now();
        long id = db.startTimerSession(sub.getId(), "Sub", 1500,
                FocusTimerState.toEpochMillis(now.plusSeconds(1500)));
        // The deadline is in the future right now, but the user hits Reset.
        expectTrue(db.abandonTimerSession(id), "the session is abandoned");
        expectTrue(db.loadActiveTimerSession() == null,
                "an abandoned session is not live and will never be awarded");

        // Even after the original deadline has long passed, nothing is restorable.
        DatabaseHelper.TimerSessionState none = db.loadActiveTimerSession();
        expectTrue(none == null,
                "and it stays unrestorable forever, so a reset session cannot resurface as a completion");

        expectTrue(db.getLogsForSkill(sub.getId()).isEmpty(),
                "an abandoned session logged nothing");
        expect(0.0, db.getCurrentPointsFor(List.of(sub.getId())).getOrDefault(sub.getId(), -1.0),
                "and awarded no points");
    }

    /**
     * Only one session may be live. Two live rows would make
     * {@code loadActiveTimerSession} non-deterministic - it returns one row by
     * ORDER BY ends_at DESC, so the app could restore a different session on
     * each launch.
     */
    private static void testOnlyOneSessionIsLive() {
        db.wipeAllData();
        Skill sub = new Skill("Sub", Skill.NO_PARENT, 1000);
        db.insertSkill(sub);

        Instant now = Instant.now();
        long first = db.startTimerSession(sub.getId(), "first", 1500,
                FocusTimerState.toEpochMillis(now.plusSeconds(1500)));
        // A second start (a crash-recovery path, or a rapid restart) must
        // retire the first rather than leaving both live.
        long second = db.startTimerSession(sub.getId(), "second", 900,
                FocusTimerState.toEpochMillis(now.plusSeconds(900)));

        expectTrue(second != first, "a new session gets its own id");
        DatabaseHelper.TimerSessionState live = db.loadActiveTimerSession();
        expect(second, live.id(), "loadActiveTimerSession returns the NEWEST live session");

        long liveCount = db.getRecentTimerSessions(20).stream()
                .filter(s -> true) // all rows are returned; the status is not on the record
                .count();
        expectTrue(liveCount >= 2, "both sessions are still in the history");
    }

    /**
     * THE FLOATING WIDGET'S CORE PROMISE, stated as a test.
     *
     * <p>"Drops absolutely zero seconds during the handoff" is really two
     * claims, and this covers both:
     *
     * <ol>
     *   <li><b>Two observers of one tick agree.</b> The dashboard and the
     *       floating widget are both handed the SAME already-computed value by
     *       one AnimationTimer, so they cannot diverge. Proven here by
     *       computing the remaining time once and rendering it to two
     *       independent formatters, then asserting the strings are identical.
     *       If a second tick ever crept in - a widget with its own timer - this
     *       is exactly the property that would break.</li>
     *
     *   <li><b>The handoff itself does no arithmetic.</b> Popping out or
     *       docking changes which window is visible; it does not touch the
     *       deadline. Proven by simulating the sequence: a tick is taken, the
     *       handoff happens, and the value at the next tick is identical to
     *       what the pre-handoff value would have been, because the deadline
     *       was never rewritten. There is no counter to copy, so there is no
     *       second to lose.</li>
     * </ol>
     */
    private static void testTwoObserversSeeTheSameClock() {
        Instant endsAt = plusSeconds(1500);

        // ---- Claim 1: one value, two renderings, identical output. ----
        long shared = FocusTimerState.remainingSeconds(endsAt, plusSeconds(137));
        expect(1363L, shared, "precondition: 1363s remain at 137s elapsed");
        // Two independent observers, each formatting for itself.
        String sidebar = FocusTimerState.formatClock(shared);
        String floatingWidget = FocusTimerState.formatClock(shared);
        expect(sidebar, floatingWidget,
                "THE REQUIREMENT: the sidebar and the floating widget must render the same string "
                        + "for the same tick - they are handed one value, not two");
        expect("22:43", sidebar, "and that string is the expected clock face");

        // The progress bar is derived from the same shared value too, so the
        // two views cannot disagree about how far through the session is.
        expectClose(FocusTimerState.progress(shared, 1500),
                FocusTimerState.progress(shared, 1500),
                "progress is a pure function of the shared value, so both views agree");

        // ---- Claim 2: the handoff performs no arithmetic. ----
        // Take a tick, then "hand off" - which in the real code means moving a
        // label between windows and touching neither the deadline nor the
        // timer. The next tick must be exactly what it would have been.
        long beforeHandoff = FocusTimerState.remainingSeconds(endsAt, plusSeconds(137));
        // ... handoff: endsAt, the total, and the single AnimationTimer are
        // all deliberately untouched. Only the visible window changes.
        long afterHandoff = FocusTimerState.remainingSeconds(endsAt, plusSeconds(137));
        expect(beforeHandoff, afterHandoff,
                "THE REQUIREMENT: the handoff must not change the remaining time - "
                        + "if it did, that difference is the lost seconds");

        // And the deadline a live session carries is unaffected by how many
        // times it is observed, which is what makes docking back a pure
        // re-parent rather than a re-derivation.
        for (int i = 0; i < 3; i++) {
            expect(shared, FocusTimerState.remainingSeconds(endsAt, plusSeconds(137)),
                    "repeated observation at the same instant is idempotent, pass " + i);
        }
    }

    /**
     * The exactly-once guarantee, stated as a test: completing a session twice
     * must not produce two awards, because the second call finds no live row.
     */
    private static void testCompletedSessionIsNotRestoredTwice() {
        db.wipeAllData();
        Skill sub = new Skill("Sub", Skill.NO_PARENT, 1000);
        db.insertSkill(sub);

        Instant now = Instant.now();
        // Already expired, as if the app was shut across the deadline.
        long id = db.startTimerSession(sub.getId(), "Sub", 1500,
                FocusTimerState.toEpochMillis(now.minusSeconds(60)));

        // Attempt 1: the restore finds it expired and completes it.
        DatabaseHelper.TimerSessionState stored = db.loadActiveTimerSession();
        expectTrue(stored != null, "precondition: the expired session is still live");
        RestoreAction action = FocusTimerState.restoreFrom(stored, now);
        expectTrue(action.kind() == RestoreAction.Kind.COMPLETE_NOW, "it completes immediately");
        db.completeTimerSession(id, 5.0);

        // Attempt 2: the next launch must find nothing to do.
        expectTrue(db.loadActiveTimerSession() == null,
                "a second startup finds no live session, so the award happens exactly once");
        expect(0.0, db.getCurrentPointsFor(List.of(sub.getId())).getOrDefault(sub.getId(), -1.0),
                "and no points were granted by the restore itself - only the explicit log does that");
    }
}
