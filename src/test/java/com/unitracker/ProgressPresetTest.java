package com.unitracker;

import com.unitracker.model.ProgressPreset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2.0 progress-preset model: the mastery-curve ladder and its
 * hand-editable serialisation.
 *
 * <p>This is the one parser in the application that reads a value a human can
 * edit, and it is reached from "create a new skill". Every failure path is
 * therefore required to degrade rather than throw - a malformed value must
 * produce a skill with no milestones rather than an exception that stops the
 * user creating one at all.
 */
@DisplayName("ProgressPreset - mastery curves and milestone serialisation")
class ProgressPresetTest {

    @Nested
    @DisplayName("the milestone ladder")
    class Ladder {

        @Test
        @DisplayName("five rungs, ending exactly at the target")
        void fiveRungs() {
            ProgressPreset p = new ProgressPreset();
            List<ProgressPreset.Milestone> ladder = p.buildMilestones(1000);
            assertEquals(5, ladder.size());
            assertEquals(100.0, ladder.get(0).threshold(), 1e-9, "10% - initial contact");
            assertEquals(200.0, ladder.get(1).threshold(), 1e-9, "20% - the 20-hour proficiency mark");
            assertEquals(500.0, ladder.get(2).threshold(), 1e-9, "50% - working competence");
            assertEquals(750.0, ladder.get(3).threshold(), 1e-9, "75% - advanced");
            assertEquals(1000.0, ladder.get(4).threshold(), 1e-9, "mastery is the target itself");
        }

        @Test
        @DisplayName("the ladder keeps its SHAPE at any scale - this is why milestones are ratios")
        void scalesWithTarget() {
            // A 400-point beginner skill and a 20,000-point mastery goal share
            // the same ladder shape at their own scale, because the rungs are
            // stored as ratios and re-derived against whatever target the user
            // actually chose.
            ProgressPreset p = new ProgressPreset();
            List<ProgressPreset.Milestone> small = p.buildMilestones(400);
            List<ProgressPreset.Milestone> large = p.buildMilestones(20000);

            assertEquals(small.size(), large.size(), "same number of rungs");
            for (int i = 0; i < small.size(); i++) {
                assertEquals(small.get(i).label(), large.get(i).label());
                assertEquals(small.get(i).threshold() / 400.0,
                        large.get(i).threshold() / 20000.0, 0.01,
                        "rung " + i + " keeps its proportional position");
            }
        }

        @Test
        @DisplayName("thresholds are strictly increasing, even for a tiny target")
        void strictlyIncreasing() {
            // With a 3-point target, the 10% and 20% rungs would both round to
            // 0. Two milestones sharing a threshold would produce duplicate
            // rows, which recordMilestonesCrossed's NOT EXISTS guard would
            // then treat as a conflict on the wrong key.
            ProgressPreset p = new ProgressPreset();
            for (double target : new double[]{1, 2, 3, 5, 8, 13}) {
                List<ProgressPreset.Milestone> ladder = p.buildMilestones(target);
                for (int i = 1; i < ladder.size(); i++) {
                    assertTrue(ladder.get(i).threshold() > ladder.get(i - 1).threshold(),
                            "target=" + target + " rung " + i + " must exceed the previous one");
                }
            }
        }

        @Test
        @DisplayName("a target too small for the standard ladder drops rungs rather than inventing them")
        void dropsUnreachableRungs() {
            ProgressPreset p = new ProgressPreset();
            List<ProgressPreset.Milestone> tiny = p.buildMilestones(3);
            assertTrue(tiny.size() < 5, "a 3-point target cannot satisfy five rungs");
            for (ProgressPreset.Milestone m : tiny) {
                assertTrue(m.threshold() <= 3, "no rung may sit beyond the target");
            }
        }

        @Test
        @DisplayName("a zero or negative target still yields a usable ladder")
        void degenerateTarget() {
            ProgressPreset p = new ProgressPreset();
            assertFalse(p.buildMilestones(0).isEmpty(), "must not return an empty ladder");
            assertFalse(p.buildMilestones(-50).isEmpty());
        }
    }

    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("a ladder round-trips exactly")
        void roundTrip() {
            ProgressPreset p = new ProgressPreset();
            String json = p.serializeMilestones(p.buildMilestones(1000));
            List<ProgressPreset.Milestone> back = ProgressPreset.parseMilestones(json);

            assertEquals(5, back.size());
            for (int i = 0; i < back.size(); i++) {
                assertEquals(p.buildMilestones(1000).get(i).label(), back.get(i).label());
                assertEquals(p.buildMilestones(1000).get(i).threshold(),
                        back.get(i).threshold(), 1e-9);
            }
        }

        @Test
        @DisplayName("a label containing a comma or a quote survives the round trip")
        void awkwardLabels() {
            ProgressPreset p = new ProgressPreset();
            List<ProgressPreset.Milestone> awkward = List.of(
                    new ProgressPreset.Milestone("R&D, phase \"one\"", 12.0),
                    new ProgressPreset.Milestone("Plain", 34.0));
            String json = p.serializeMilestones(awkward);
            List<ProgressPreset.Milestone> back = ProgressPreset.parseMilestones(json);

            assertEquals(2, back.size());
            assertEquals("R&D, phase \"one\"", back.get(0).label());
            assertEquals(12.0, back.get(0).threshold(), 1e-9);
        }

        @Test
        @DisplayName("an empty ladder round-trips as empty")
        void emptyRoundTrip() {
            ProgressPreset p = new ProgressPreset();
            assertTrue(ProgressPreset.parseMilestones(
                    p.serializeMilestones(List.of())).isEmpty());
        }
    }

    @Nested
    @DisplayName("defensive parsing - this reads a hand-editable value")
    class DefensiveParsing {

        @Test
        @DisplayName("every malformed input degrades to an empty ladder, never an exception")
        void neverThrows() {
            // Each of these is reachable from "create a new skill". A throw here
            // would stop the user creating a skill at all because someone
            // hand-edited a row months ago.
            for (String bad : new String[]{
                    null, "", "   ", "not json", "[", "]", "[unterminated",
                    "[\"broken\" \"entry\"]", "[{\"label\":\"x\"}]",
                    "[\"a\\u001fNOTANUMBER\"]", "[\"\"]", "[[[[[[[[[[",
            }) {
                List<ProgressPreset.Milestone> parsed = ProgressPreset.parseMilestones(bad);
                assertTrue(parsed == null || parsed.isEmpty(),
                        "input " + bad + " should yield no milestones, not throw");
            }
        }

        @Test
        @DisplayName("a malformed ENTRY is skipped but its valid siblings survive")
        void partialRecovery() {
            // Built by concatenation rather than as one literal, because the
            // separator is the real U+001F character. Written as "\\u001f" it
            // would be a backslash followed by "u001f", which is not what
            // serializeMilestones writes - the fixture was describing a format
            // the app never produces, and every entry correctly failed to parse.
            char sep = '\u001f';
            String json = "[\"good" + sep + "10\",\"bad-with-no-separator\",\"also good"
                    + sep + "20\"]";
            List<ProgressPreset.Milestone> parsed = ProgressPreset.parseMilestones(json);
            assertEquals(2, parsed.size(), "one bad entry must not discard the good ones");
            assertEquals("good", parsed.get(0).label());
            assertEquals(10.0, parsed.get(0).threshold(), 1e-9);
            assertEquals("also good", parsed.get(1).label());
        }
    }

    @Nested
    @DisplayName("built-in catalogue")
    class BuiltIns {

        @Test
        @DisplayName("every built-in curve has a positive target and a valid ladder")
        void curvesAreWellFormed() {
            List<ProgressPreset> curves = ProgressPreset.builtIns().stream()
                    .filter(p -> ProgressPreset.KIND_CURVE.equals(p.getKind()))
                    .toList();
            assertFalse(curves.isEmpty(), "there must be at least one shipped mastery curve");

            for (ProgressPreset curve : curves) {
                assertTrue(curve.isBuiltIn(), curve.getName() + " must be flagged built-in");
                assertTrue(curve.getTargetPoints() != null && curve.getTargetPoints() > 0,
                        curve.getName() + " must have a positive target");
                assertFalse(curve.getMilestones().isEmpty(),
                        curve.getName() + " must ship with a ladder");
                for (ProgressPreset.Milestone m : curve.getMilestones()) {
                    assertTrue(m.threshold() > 0 && m.threshold() <= curve.getTargetPoints(),
                            curve.getName() + " rung " + m.label() + " is out of range");
                }
            }
        }

        @Test
        @DisplayName("every built-in rate card prices a positive number of minutes")
        void rateCardsAreWellFormed() {
            List<ProgressPreset> cards = ProgressPreset.builtIns().stream()
                    .filter(p -> ProgressPreset.KIND_TIME.equals(p.getKind()))
                    .toList();
            assertFalse(cards.isEmpty());

            for (ProgressPreset card : cards) {
                assertTrue(card.getMinutes() != null && card.getMinutes() > 0,
                        card.getName() + " must price a positive duration");
                assertTrue(card.getPoints() != null && card.getPoints() > 0,
                        card.getName() + " must award a positive number of points");
            }
        }

        @Test
        @DisplayName("built-in names are unique, or the Settings list shows duplicates")
        void namesAreUnique() {
            List<String> names = ProgressPreset.builtIns().stream()
                    .map(ProgressPreset::getName).toList();
            assertEquals(names.size(), names.stream().distinct().count(),
                    "duplicate built-in preset names: " + names);
        }
    }

    @Nested
    @DisplayName("summary text")
    class Summary {

        @ParameterizedTest(name = "a {0}-minute card worth {1} points reads as \"{2}\"")
        @CsvSource({
                "25,  5,  25 min = 5 pts",
                "60,  20, 1 hr = 20 pts",
                "90,  25, 1 hr 30 min = 25 pts",
                "120, 40, 2 hrs = 40 pts",
        })
        @DisplayName("rate cards describe themselves in a single readable line")
        void rateCardSummary(int minutes, double points, String expected) {
            ProgressPreset card = new ProgressPreset(ProgressPreset.KIND_TIME, "Test", "");
            card.setMinutes(minutes);
            card.setPoints(points);
            assertEquals(expected, card.summary());
        }

        @Test
        @DisplayName("whole points are not shown as 5.0 - a stray decimal reads as imprecision")
        void wholePointsAreTrimmed() {
            assertEquals("5", ProgressPreset.trimPoints(5.0));
            assertEquals("5.5", ProgressPreset.trimPoints(5.5));
        }
    }

    @Test
    @DisplayName("the two kinds are distinguishable - the Settings UI filters on them")
    void kindConstants() {
        assertEquals("TIME", ProgressPreset.KIND_TIME);
        assertEquals("CURVE", ProgressPreset.KIND_CURVE);
        assertNotEquals(ProgressPreset.KIND_TIME, ProgressPreset.KIND_CURVE,
                "a preset must fall into exactly one of the two lists, or the Settings "
                        + "tabs would show it in both or in neither");
    }

    @Test
    @DisplayName("a preset's default kind is a rate card, and the constructor can set either")
    void defaultKind() {
        assertEquals(ProgressPreset.KIND_TIME, new ProgressPreset().getKind());
        assertEquals(ProgressPreset.KIND_CURVE,
                new ProgressPreset(ProgressPreset.KIND_CURVE, "x", "").getKind());
    }
}
