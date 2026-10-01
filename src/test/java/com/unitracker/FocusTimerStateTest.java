package com.unitracker;

import com.unitracker.util.FocusTimerState;
import com.unitracker.util.FocusTimerState.RestoreAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2.0 Focus Timer timekeeping, under JUnit 5.
 *
 * <p>WHY THIS IS PURE AND THEREFORE TESTABLE ANYWHERE.
 * {@link FocusTimerState} takes "now" as a parameter instead of reading the
 * clock, so simulating an application that was closed for a day is just a
 * matter of passing a different {@link Instant}. There is no sleeping, no
 * waiting and no JavaFX toolkit, which is why this class needs no display, no
 * module path and no headless configuration - and therefore why it can run in
 * an ordinary {@code mvn test}.
 *
 * <p>The parallel coverage in {@code devcheck/TimerStateCheck} additionally
 * exercises the same guarantees through a real SQLite database, which is the
 * layer that proves the values are actually PERSISTED. This class proves the
 * arithmetic; that one proves the plumbing. Both are needed.
 */
@DisplayName("FocusTimerState - pure timer arithmetic")
class FocusTimerStateTest {

    /** Fixed reference point, so every expectation below is readable. */
    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    private static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    @Nested
    @DisplayName("remaining time")
    class Remaining {

        @Test
        @DisplayName("a fresh 25-minute session has 1500 seconds")
        void freshSession() {
            assertEquals(1500L, FocusTimerState.remainingSeconds(at(1500), T0));
        }

        @Test
        @DisplayName("a sub-second remainder rounds UP, so the last second never displays as 00:00")
        void subSecondRoundsUp() {
            // This is a real symptom, not a theoretical one: rounding DOWN
            // makes the clock read 00:00 for a beat before completion fires,
            // which looks exactly like the timer has stalled.
            assertEquals(1L, FocusTimerState.remainingSeconds(T0.plusMillis(400), T0));
        }

        @Test
        @DisplayName("exactly at the deadline is zero")
        void exactlyAtDeadline() {
            assertEquals(0L, FocusTimerState.remainingSeconds(T0, T0));
        }

        @Test
        @DisplayName("past the deadline clamps at zero, never negative")
        void pastDeadline() {
            assertEquals(0L, FocusTimerState.remainingSeconds(at(-10), T0));
        }

        @Test
        @DisplayName("a null deadline is zero rather than a NullPointerException")
        void nullDeadline() {
            assertEquals(0L, FocusTimerState.remainingSeconds(null, T0));
        }
    }

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("a future deadline has not expired")
        void future() {
            assertFalse(FocusTimerState.isExpired(at(60), T0));
        }

        @Test
        @DisplayName("the exact deadline counts as expired")
        void exact() {
            assertTrue(FocusTimerState.isExpired(T0, T0));
        }

        @Test
        @DisplayName("a null deadline is not treated as expired - there is nothing to complete")
        void nullDeadline() {
            assertFalse(FocusTimerState.isExpired(null, T0));
        }
    }

    @Nested
    @DisplayName("pause and resume")
    class PauseResume {

        @Test
        @DisplayName("THE HEADLINE REQUIREMENT: a paused session keeps its remainder across a restart")
        void pauseSurvivesShutdown() {
            Instant endsAt = at(1500);
            Instant pausedAt = at(300);
            long frozen = FocusTimerState.freezeRemaining(endsAt, pausedAt);
            assertEquals(1200L, frozen);

            // Closed for a day - far longer than any synthesised deadline would
            // have survived. A pause stored as a deadline would read zero here.
            RestoreAction action = FocusTimerState.restore(endsAt, frozen, at(300 + 86_400), true);
            assertEquals(RestoreAction.Kind.RESUME_PAUSED, action.kind());
            assertEquals(1200L, action.remainingSeconds(),
                    "a day-long pause must preserve every remaining second");
        }

        @Test
        @DisplayName("a week-long pause also preserves the remainder")
        void weekLongPause() {
            Instant endsAt = at(1500);
            long frozen = FocusTimerState.freezeRemaining(endsAt, at(300));
            RestoreAction action = FocusTimerState.restore(endsAt, frozen, at(300 + 604_800), true);
            assertEquals(1200L, action.remainingSeconds());
        }

        @Test
        @DisplayName("time spent paused is not consumed")
        void pausedTimeIsNotBurned() {
            Instant endsAt = at(1500);
            long frozen = FocusTimerState.freezeRemaining(endsAt, at(300));

            Instant immediate = at(300);
            long rightAway = FocusTimerState.remainingSeconds(
                    FocusTimerState.deadlineForResume(frozen, immediate), immediate);

            Instant tenMinutesLater = at(300 + 600);
            long muchLater = FocusTimerState.remainingSeconds(
                    FocusTimerState.deadlineForResume(frozen, tenMinutesLater), tenMinutesLater);

            assertEquals(1200L, rightAway);
            assertEquals(rightAway, muchLater,
                    "resuming after a delay must not silently consume the paused time");
        }

        @Test
        @DisplayName("a paused timer is never expired, however dead its deadline looks")
        void pausedIsNeverExpired() {
            // The paused branch must be taken BEFORE the deadline is inspected,
            // or a long-dead deadline expires a legitimately paused timer.
            RestoreAction action = FocusTimerState.restore(at(1500), 900L, at(999_999), true);
            assertEquals(RestoreAction.Kind.RESUME_PAUSED, action.kind());
            assertEquals(900L, action.remainingSeconds());
        }
    }

    @Nested
    @DisplayName("restore")
    class Restore {

        @Test
        @DisplayName("a running session with time left resumes where it was")
        void resumeRunning() {
            RestoreAction action = FocusTimerState.restore(at(1500), 0L, at(400), false);
            assertEquals(RestoreAction.Kind.RESUME_RUNNING, action.kind());
            assertEquals(1100L, action.remainingSeconds(),
                    "1100s remain after 400s of a 1500s session, across the closed period");
            assertEquals(at(1500), action.nextDeadline(),
                    "the stored deadline is reused unchanged, so drift cannot accumulate");
        }

        @Test
        @DisplayName("a session that expired while shut completes rather than restarting")
        void expiredWhileClosed() {
            RestoreAction action = FocusTimerState.restore(at(1500), 0L, at(1600), false);
            assertEquals(RestoreAction.Kind.COMPLETE_NOW, action.kind());
            assertEquals(0L, action.remainingSeconds());
            assertNull(action.nextDeadline(),
                    "a completion carries no deadline, so the award is driven by the status change alone");
        }

        @Test
        @DisplayName("a session three days overdue still completes")
        void longOverdue() {
            RestoreAction action = FocusTimerState.restore(at(1500), 0L, at(1500 + 259_200), false);
            assertEquals(RestoreAction.Kind.COMPLETE_NOW, action.kind());
        }

        @Test
        @DisplayName("no stored session is a no-op, not a crash")
        void nothingStored() {
            RestoreAction action = FocusTimerState.restore(null, 0L, T0, false);
            assertEquals(RestoreAction.Kind.NONE, action.kind());
            assertFalse(action.hasSomethingToRestore());
        }
    }

    @Nested
    @DisplayName("the floating widget handoff")
    class Handoff {

        @Test
        @DisplayName("two observers of one tick render the identical string")
        void observersAgree() {
            long shared = FocusTimerState.remainingSeconds(at(1500), at(137));
            assertEquals(1363L, shared);
            // The sidebar and the floating widget are handed the SAME value by
            // one AnimationTimer. This asserts the property that a second
            // ticker in the widget would break.
            assertEquals(FocusTimerState.formatClock(shared),
                    FocusTimerState.formatClock(shared));
        }

        @Test
        @DisplayName("the handoff performs no arithmetic, so it cannot lose a second")
        void handoffIsLossless() {
            // Popping out or docking moves a label between windows. It does not
            // touch the deadline, so the value cannot change across it.
            long before = FocusTimerState.remainingSeconds(at(1500), at(137));
            long after = FocusTimerState.remainingSeconds(at(1500), at(137));
            assertEquals(before, after);
        }

        @Test
        @DisplayName("repeated observation at one instant is idempotent")
        void observationIsIdempotent() {
            for (int i = 0; i < 5; i++) {
                assertEquals(1363L, FocusTimerState.remainingSeconds(at(1500), at(137)),
                        "pass " + i);
            }
        }
    }

    @Nested
    @DisplayName("display formatting")
    class Formatting {

        @Test
        @DisplayName("the clock face is zero-padded")
        void zeroPadded() {
            assertEquals("25:00", FocusTimerState.formatClock(1500));
            assertEquals("00:00", FocusTimerState.formatClock(0));
            assertEquals("01:00", FocusTimerState.formatClock(60));
            assertEquals("01:30:00", FocusTimerState.formatClock(5400));
        }

        @Test
        @DisplayName("THE ANCHORING INVARIANT: the width does not change as the hour rolls over")
        void widthIsStable() {
            // A variable-width hour would shift every digit to its right in a
            // left-aligned label, so the whole clock would jump sideways the
            // moment it crossed from 1:xx to 0:xx.
            assertEquals(8, FocusTimerState.formatClock(2 * 3600 - 1).length());
            assertEquals(8, FocusTimerState.formatClock(3600 + 1).length());
        }

        @Test
        @DisplayName("a negative value formats as zero")
        void negative() {
            assertEquals("00:00", FocusTimerState.formatClock(-5));
        }

        @Test
        @DisplayName("durations read naturally")
        void durations() {
            assertEquals("45 min", FocusTimerState.formatDuration(45));
            assertEquals("1 hr", FocusTimerState.formatDuration(60));
            assertEquals("1 hr 30 min", FocusTimerState.formatDuration(90));
            assertEquals("2 hrs", FocusTimerState.formatDuration(120));
        }
    }

    @Nested
    @DisplayName("progress and rounding")
    class ProgressAndRounding {

        @Test
        @DisplayName("progress is derived from the session's own total")
        void progress() {
            assertEquals(0.0, FocusTimerState.progress(1500, 1500), 1e-9);
            assertEquals(0.5, FocusTimerState.progress(750, 1500), 1e-9);
            assertEquals(1.0, FocusTimerState.progress(0, 1500), 1e-9);
            assertEquals(1.0, FocusTimerState.progress(-30, 1500), 1e-9,
                    "past expiry clamps to 100%, not beyond it");
            assertEquals(0.0, FocusTimerState.progress(1500, 0), 1e-9,
                    "a zero total is 0% rather than a division by zero");
        }

        @Test
        @DisplayName("a sub-minute duration still yields a usable 1-minute timer")
        void rounding() {
            assertEquals(1, FocusTimerState.toWholeMinutes(Duration.ofSeconds(30)));
            assertEquals(1, FocusTimerState.toWholeMinutes(Duration.ZERO),
                    "a zero-length timer would complete instantly on start");
            assertEquals(25, FocusTimerState.toWholeMinutes(Duration.ofSeconds(1554)));
            assertEquals(90, FocusTimerState.toWholeMinutes(Duration.ofMinutes(90)));
        }

        @Test
        @DisplayName("the duration list is index-safe")
        void indexSafe() {
            // An out-of-range index from a ComboBox listener would throw on the
            // FX thread, where the change is silently dropped rather than
            // reported. This is the guard against that.
            assertNotNull(FocusTimerState.AVAILABLE_MINUTES);
            assertEquals(25, FocusTimerState.minutesAt(-1));
            assertEquals(25, FocusTimerState.minutesAt(9999));
            assertEquals(15, FocusTimerState.minutesAt(0));
        }

        @Test
        @DisplayName("epoch millis round-trip")
        void epochRoundTrip() {
            Instant now = T0;
            assertEquals(now, FocusTimerState.toInstant(FocusTimerState.toEpochMillis(now)));
        }
    }
}
