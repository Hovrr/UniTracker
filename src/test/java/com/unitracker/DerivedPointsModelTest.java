package com.unitracker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The v2.0 DERIVED POINTS model, against a real SQLite database.
 *
 * <p>Surefire is configured in pom.xml with a throwaway {@code user.home}, and
 * DatabaseHelper derives its path from that property in a static initialiser -
 * so this class never opens the real
 * {@code ~/.unitracker/unitracker.db}. It also asserts that it was redirected,
 * so a misconfigured build fails loudly rather than quietly operating on a
 * developer's real data.
 *
 * <p><b>Why the database and not a SQL copy.</b> The older
 * {@code devcheck/SqlLogicCheck} deliberately duplicates SQL into an in-memory
 * database. That approach is fine for proving an isolated query, but it is the
 * wrong tool here: the thing most likely to be wrong about
 * {@code recomputeAllPoints()} is whether the REAL method - with its temp
 * table, its transaction and its COALESCE - agrees with any copy of it. So
 * these tests drive the actual class.
 *
 * <p>Each nested group corresponds to one of the three permanent desync bugs
 * that existed in v1, all of which are now unrepresentable.
 */
@DisplayName("DatabaseHelper - derived points are a pure function of the log table")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DerivedPointsModelTest {

    /**
     * Deliberately NOT a static final initialised inline.
     *
     * <p>Class initialisers run when the class is first touched, which is
     * BEFORE {@code @BeforeAll}. An inline {@code Database.open()} would
     * therefore create and migrate the database file - writing to
     * {@code ~/.unitracker} - before the safety guard had a chance to run. So
     * the guard goes first and the handle is assigned inside it.
     */
    private static Database db;

    /** Throws rather than skips, so a misconfigured build fails loudly. */
    @BeforeAll
    static void refuseToRunAgainstARealProfile() {
        String home = System.getProperty("user.home", "");
        assertTrue(home != null && (home.contains("surefire") || home.contains("utcheck")),
                "Refusing to run: -Duser.home must point at a throwaway directory. Got: " + home
                + " - see the surefire systemPropertyVariables in pom.xml.");
        db = Database.open();
    }

    // ----------------------------------------------------------------
    //  Test doubles, so the assertions below read as intent
    // ----------------------------------------------------------------

    /** Thin wrapper adding the assertions a test wants inline. */
    static final class Database {
        private final com.unitracker.db.DatabaseHelper helper =
                com.unitracker.db.DatabaseHelper.getInstance();

        static Database open() {
            Database d = new Database();
            d.helper.initializeDatabase();
            d.helper.wipeAllData();
            return d;
        }

        void reset() {
            helper.wipeAllData();
        }

        Skill skill(String name, int parentId) {
            // The nested double, not com.unitracker.model.Skill: the double is
            // what fixes targetPoints at 1000 so these assertions stay about
            // rollup arithmetic rather than about progress percentages.
            Skill s = new Skill(name, parentId);
            helper.insertSkill(s);
            return s;
        }

        void log(int skillId, double points) {
            helper.insertProgressLog(new com.unitracker.model.ProgressLog(
                    skillId, LocalDate.of(2026, 8, 1), 25, points));
        }

        double cached(int skillId) {
            return helper.getCurrentPointsFor(List.of(skillId)).getOrDefault(skillId, -1.0);
        }

        /** The independent, recursive derivation - what the cache must equal. */
        double derived(int skillId) {
            return helper.getDerivedPointsFor(skillId);
        }

        void rawUpdate(String sql, int... ids) throws SQLException {
            try (PreparedStatement ps = helper.getConnection().prepareStatement(sql)) {
                for (int i = 0; i < ids.length; i++) {
                    ps.setInt(i + 1, ids[i]);
                }
                ps.executeUpdate();
            }
        }

        /** @return how many rows the derive rewrote, so a test can assert on it */
        int recompute() {
            return helper.recomputeAllPoints();
        }

        com.unitracker.db.DatabaseHelper raw() {
            return helper;
        }
    }

    /** Alias so the nested tests read cleanly. */
    private static final class Skill extends com.unitracker.model.Skill {
        Skill(String name, int parentId) {
            super(name, parentId, 1000.0);
        }
    }

    @Nested
    @DisplayName("bottom-up accumulation")
    @Order(1)
    class BottomUp {

        @Test
        @DisplayName("a Subskill's points reach its Main Skill and its Category")
        void reachesEveryAncestor() {
            db.reset();
            var cat = db.skill("Category", -1);
            var main = db.skill("Main", cat.getId());
            var sub = db.skill("Sub", main.getId());

            db.log(sub.getId(), 10);

            assertEquals(10.0, db.cached(sub.getId()), 1e-9, "the subskill holds its own points");
            assertEquals(10.0, db.cached(main.getId()), 1e-9, "the main skill accumulates from below");
            assertEquals(10.0, db.cached(cat.getId()), 1e-9, "the root category accumulates from below");
        }

        @Test
        @DisplayName("eight levels of nesting all receive the points")
        void deepNesting() {
            db.reset();
            var node = db.skill("L0", -1);
            var root = node;
            for (int i = 1; i <= 8; i++) {
                node = db.skill("L" + i, node.getId());
            }
            db.log(node.getId(), 7);

            assertEquals(7.0, db.cached(node.getId()), 1e-9);
            assertEquals(7.0, db.cached(root.getId()), 1e-9);
            assertEquals(9, db.raw().getRollupTargets(node.getId()).size(),
                    "the rollup names the skill plus all eight ancestors");
        }
    }

    @Nested
    @DisplayName("DESYNC BUG 1: deleting a subskill used to strand its points on the ancestors")
    @Order(2)
    class DeleteDesync {

        @Test
        @DisplayName("deleting a subskill subtracts it from the shared category")
        void deleteSubtractsFromAncestors() {
            db.reset();
            var cat = db.skill("Category", -1);
            var a = db.skill("A", cat.getId());
            var b = db.skill("B", cat.getId());

            db.log(a.getId(), 100);
            db.log(b.getId(), 50);
            assertEquals(150.0, db.cached(cat.getId()), 1e-9);

            // Deleting the row cascades away its progress_logs, and the derive
            // must now exclude them. In v1 the category stayed at 150 forever.
            int logId = db.raw().getLogsForSkill(a.getId()).get(0).getId();
            assertTrue(db.raw().deleteProgressLog(logId));
            assertEquals(50.0, db.cached(cat.getId()), 1e-9,
                    "THE v1 BUG: the shared category must be corrected");
            assertEquals(0.0, db.cached(a.getId()), 1e-9);
            assertEquals(50.0, db.cached(b.getId()), 1e-9, "the sibling is untouched");
        }

        @Test
        @DisplayName("a whole cascaded subtree delete zeroes every ancestor")
        void cascadeDelete() {
            db.reset();
            var cat = db.skill("Category", -1);
            var sub = db.skill("Sub", cat.getId());
            db.log(sub.getId(), 42);
            assertEquals(42.0, db.cached(cat.getId()), 1e-9);

            assertTrue(db.raw().deleteSkillCascade(cat.getId()));
            assertTrue(db.raw().getAllSkills().stream().noneMatch(s -> s.getId() == cat.getId()));
            assertTrue(db.raw().getLogsForSkill(sub.getId()).isEmpty(),
                    "the subtree's sessions went with it");

            var fresh = db.skill("Category", -1);
            assertEquals(0.0, db.cached(fresh.getId()), 1e-9,
                    "recreating the category must start at zero, not the deleted total");
        }
    }

    @Nested
    @DisplayName("DESYNC BUG 2: re-parenting never reached the database")
    @Order(3)
    class ReparentDesync {

        @Test
        @DisplayName("a re-parent moves the total from the old chain to the new one")
        void movesTheTotal() {
            db.reset();
            var oldCat = db.skill("Old Category", -1);
            var newCat = db.skill("New Category", -1);
            var sub = db.skill("Movable", oldCat.getId());
            db.log(sub.getId(), 30);

            assertTrue(db.raw().reparentSkill(sub.getId(), newCat.getId()));

            assertEquals(0.0, db.cached(oldCat.getId()), 1e-9,
                    "THE v1 BUG: the old category must release points it no longer owns");
            assertEquals(30.0, db.cached(newCat.getId()), 1e-9,
                    "THE v1 BUG: the new category must receive the history it now owns");
            assertEquals(30.0, db.cached(sub.getId()), 1e-9);
        }

        @Test
        @DisplayName("the new parent is PERSISTED, not just held in memory")
        void parentIsPersisted() {
            // In v1, updateSkill's SQL omitted parent_id entirely, so the UI
            // showed the new arrangement, rollups followed the stale chain, and
            // the whole edit vanished on restart.
            db.reset();
            var oldCat = db.skill("Old", -1);
            var newCat = db.skill("New", -1);
            var sub = db.skill("Movable", oldCat.getId());

            assertTrue(db.raw().reparentSkill(sub.getId(), newCat.getId()));

            var reloaded = db.raw().getAllSkills().stream()
                    .filter(s -> s.getId() == sub.getId()).findFirst().orElseThrow();
            assertEquals(newCat.getId(), reloaded.getParentId(),
                    "the re-parent must survive a reload, or it reverts on restart");
        }

        @Test
        @DisplayName("a cycle is refused and the tree stays readable")
        void refusesCycles() {
            db.reset();
            var root = db.skill("Root", -1);
            var mid = db.skill("Mid", root.getId());
            var leaf = db.skill("Leaf", mid.getId());

            assertFalse(db.raw().reparentSkill(root.getId(), leaf.getId()),
                    "a category must not become a descendant of its own child");
            assertFalse(db.raw().reparentSkill(mid.getId(), mid.getId()));
            assertEquals(0.0, db.cached(root.getId()), 1e-9,
                    "a refused re-parent leaves the tree derivable");
        }
    }

    @Nested
    @DisplayName("DESYNC BUG 3: 'Current Points' used to be a bare overwrite")
    @Order(4)
    class AdjustmentDesync {

        @Test
        @DisplayName("a manual correction rolls up and appears in the session history")
        void adjustmentRollsUpAndIsAuditable() {
            db.reset();
            var cat = db.skill("Category", -1);
            var sub = db.skill("Sub", cat.getId());
            db.log(sub.getId(), 20);

            assertTrue(db.raw().applyPointAdjustment(
                    sub.getId(), 75, LocalDate.of(2026, 8, 2), "typo fix"));

            assertEquals(75.0, db.cached(sub.getId()), 1e-9, "the requested total is reached");
            assertEquals(75.0, db.cached(cat.getId()), 1e-9,
                    "THE v1 BUG: a correction must also reach the ancestors");
            assertEquals(2, db.raw().getLogsForSkill(sub.getId()).size(),
                    "THE v1 BUG: it must be visible in history, not invisible");

            var adjustment = db.raw().getLogsForSkill(sub.getId()).stream()
                    .filter(com.unitracker.model.ProgressLog::isAdjustment).findFirst().orElseThrow();
            assertEquals(55.0, adjustment.getPointsEarned(), 1e-9,
                    "the row records the DIFFERENCE, not the total");
        }

        @Test
        @DisplayName("removing the adjustment reverts cleanly")
        void adjustmentUndoes() {
            db.reset();
            var sub = db.skill("Sub", -1);
            db.log(sub.getId(), 20);
            db.raw().applyPointAdjustment(sub.getId(), 75, LocalDate.of(2026, 8, 2), "fix");

            var adjustment = db.raw().getLogsForSkill(sub.getId()).stream()
                    .filter(com.unitracker.model.ProgressLog::isAdjustment).findFirst().orElseThrow();
            assertTrue(db.raw().deleteProgressLog(adjustment.getId()));
            assertEquals(20.0, db.cached(sub.getId()), 1e-9);
        }

        @Test
        @DisplayName("requesting the total it already has adds no pointless zero row")
        void noOpAdjustment() {
            db.reset();
            var sub = db.skill("Sub", -1);
            db.log(sub.getId(), 20);
            int before = db.raw().getLogsForSkill(sub.getId()).size();

            assertTrue(db.raw().applyPointAdjustment(
                    sub.getId(), 20, LocalDate.of(2026, 8, 2), "no-op"));
            assertEquals(before, db.raw().getLogsForSkill(sub.getId()).size());
        }

        @Test
        @DisplayName("a negative total is stored faithfully, NOT clamped")
        void negativeTotalIsNotClamped() {
            // v1 used MAX(0, current_points + delta), which made the operation
            // non-invertible precisely in this case. A correction that is a
            // credit is ordinary data and must round-trip.
            db.reset();
            var cat = db.skill("Category", -1);
            var sub = db.skill("Sub", cat.getId());
            db.log(sub.getId(), 100);

            assertTrue(db.raw().applyPointAdjustment(
                    sub.getId(), -25, LocalDate.of(2026, 8, 4), "credit note"));
            assertEquals(-25.0, db.cached(sub.getId()), 1e-9, "stored faithfully, not clamped");
            assertEquals(-25.0, db.cached(cat.getId()), 1e-9, "and it rolls up unchanged");
        }
    }

    @Nested
    @DisplayName("undo interleaved with a sibling's log")
    @Order(5)
    class UndoInterleaving {

        @Test
        @DisplayName("undoing A leaves B's contribution intact")
        void undoAfterSiblingLogged() {
            // This is the case the v1 relative-delta design was written FOR, and
            // it is the case a snapshot-based undo gets wrong: the snapshot goes
            // stale and wipes the sibling's contribution to the shared parent.
            db.reset();
            var cat = db.skill("Category", -1);
            var mid = db.skill("Mid", cat.getId());
            var a = db.skill("A", mid.getId());
            var b = db.skill("B", mid.getId());

            var logA = new com.unitracker.model.ProgressLog(
                    a.getId(), LocalDate.of(2026, 8, 1), 25, 10);
            db.raw().insertProgressLog(logA);
            db.log(b.getId(), 5);
            assertEquals(15.0, db.cached(mid.getId()), 1e-9);

            assertTrue(db.raw().deleteProgressLog(logA.getId()));

            assertEquals(0.0, db.cached(a.getId()), 1e-9);
            assertEquals(5.0, db.cached(b.getId()), 1e-9, "the sibling is untouched");
            assertEquals(5.0, db.cached(mid.getId()), 1e-9,
                    "a snapshot-based undo would have wiped the sibling's 5 points");
            assertEquals(5.0, db.cached(cat.getId()), 1e-9);
        }
    }

    @Nested
    @DisplayName("the cache can never drift from the derivation")
    @Order(6)
    class CacheCannotDrift {

        @Test
        @DisplayName("for every skill, the cache equals an independent derivation")
        void cacheMatchesDerivation() {
            db.reset();
            var cat = db.skill("Category", -1);
            var mid = db.skill("Mid", cat.getId());
            var a = db.skill("A", mid.getId());
            var b = db.skill("B", mid.getId());
            var lonely = db.skill("Lonely", -1);

            db.log(a.getId(), 11);
            db.log(b.getId(), 7);
            db.log(cat.getId(), 3);
            db.log(lonely.getId(), 2);
            db.raw().applyPointAdjustment(mid.getId(), 99, LocalDate.of(2026, 8, 5), "correction");

            for (com.unitracker.model.Skill s : db.raw().getAllSkills()) {
                assertEquals(db.derived(s.getId()), db.cached(s.getId()), 1e-9,
                        "cache drifted from derivation for \"" + s.getName() + "\"");
            }
        }

        @Test
        @DisplayName("a corrupted cache is repaired by recomputing - the startup self-heal")
        void selfHealing() {
            db.reset();
            var cat = db.skill("Category", -1);
            var sub = db.skill("Sub", cat.getId());
            db.log(sub.getId(), 25);
            assertEquals(25.0, db.cached(cat.getId()), 1e-9);

            // Corrupt it behind the derive's back, exactly as the v1 bugs did.
            // This is the only way to reproduce the old damage now that
            // updateSkill no longer writes current_points.
            try {
                db.rawUpdate("UPDATE skills SET current_points=9999 WHERE id=?", cat.getId());
            } catch (SQLException e) {
                fail("could not stage the corruption: " + e.getMessage());
            }
            assertEquals(9999.0, db.cached(cat.getId()), 1e-9, "precondition");

            assertTrue(db.recompute() > 0, "the recompute reports how many rows it rewrote");
            assertEquals(25.0, db.cached(cat.getId()), 1e-9, "the self-heal restored the truth");
        }

        @Test
        @DisplayName("updateSkill does NOT write the derived total")
        void updateSkillCannotClobberTotals() {
            // The failure this guards is severe and silent: a long-lived Skill
            // object whose currentPoints is stale would otherwise write that
            // stale value over the fresh derivation, destroying points, with no
            // error and a "success" return.
            db.reset();
            var sub = db.skill("Sub", -1);
            db.log(sub.getId(), 25);

            // A freshly constructed object has currentPoints == 0, which is
            // exactly the stale value that used to be persisted.
            var stale = new com.unitracker.model.Skill("Sub", -1, 500);
            stale.setId(sub.getId());
            assertTrue(db.raw().updateSkill(stale));
            assertEquals(25.0, db.cached(sub.getId()), 1e-9,
                    "an attribute update must not be able to destroy derived points");
        }
    }

    @Nested
    @DisplayName("batched status writes")
    @Order(7)
    class BatchedStatus {

        @Test
        @DisplayName("touches only the given ids, and only the status column")
        void writesOnlyStatus() {
            db.reset();
            var a = db.skill("A", -1);
            var b = db.skill("B", -1);
            var untouched = db.skill("Untouched", -1);
            db.log(a.getId(), 10);

            // Corrupt other columns behind the model's back, so a batch that
            // wrote more than `status` would be caught.
            try {
                db.rawUpdate("UPDATE skills SET target_points=7777, sort_order=99 WHERE id=?", b.getId());
            } catch (SQLException e) {
                fail("could not stage the corruption: " + e.getMessage());
            }

            assertEquals(2, db.raw().setSkillStatuses(List.of(a.getId(), b.getId()), "STALLED"));

            var reread = db.raw().getAllSkills().stream()
                    .filter(s -> s.getId() == b.getId()).findFirst().orElseThrow();
            assertEquals("STALLED", reread.getStatus());
            assertEquals(7777.0, reread.getTargetPoints(), 1e-9,
                    "the batch must not rewrite target_points from a stale model");
            assertEquals(99, reread.getSortOrder(), "nor sort_order");

            var other = db.raw().getAllSkills().stream()
                    .filter(s -> s.getId() == untouched.getId()).findFirst().orElseThrow();
            assertEquals("ACTIVE", other.getStatus(), "a skill outside the batch is untouched");
        }

        @Test
        @DisplayName("degenerate inputs are no-ops, not malformed SQL")
        void degenerateInputs() {
            db.reset();
            var a = db.skill("A", -1);
            assertEquals(0, db.raw().setSkillStatuses(List.of(), "STALLED"));
            assertEquals(0, db.raw().setSkillStatuses(null, "STALLED"));
            assertEquals(0, db.raw().setSkillStatuses(List.of(a.getId()), null));
        }
    }

    @Nested
    @DisplayName("projected completion")
    @Order(8)
    class Projection {

        @Test
        @DisplayName("a skill with no recent history has NO projection")
        void stalledSkillHasNoProjection() {
            // The important case. Dividing by a zero rate would produce a date
            // days in the past and tell the user a stalled skill is nearly
            // finished, which is a lie that happens to look encouraging.
            db.reset();
            var sub = db.skill("Sub", -1);
            var target = new com.unitracker.model.Skill("Sub", -1, 100);
            target.setId(sub.getId());
            db.raw().updateSkill(target);

            assertNullValue(db.raw().getProjectedCompletionDate(sub.getId(),
                    LocalDate.of(2026, 8, 3), 14),
                    "a skill with no recent history has no projection");
        }

        @Test
        @DisplayName("an active skill projects from its trailing rate")
        void activeSkillProjects() {
            db.reset();
            var sub = db.skill("Sub", -1);
            var target = new com.unitracker.model.Skill("Sub", -1, 100);
            target.setId(sub.getId());
            db.raw().updateSkill(target);
            db.raw().insertProgressLog(new com.unitracker.model.ProgressLog(
                    sub.getId(), LocalDate.of(2026, 7, 21), 10, 14)); // 14 over 14 days = 1/day

            var today = LocalDate.of(2026, 8, 3);
            var projected = db.raw().getProjectedCompletionDate(sub.getId(), today, 14);
            assertNotNull(projected);
            assertEquals(today.plusDays(86), projected, "1 pt/day, 86 to go");
        }

        @Test
        @DisplayName("a completed or targetless skill has no projection")
        void completeOrNoTarget() {
            db.reset();
            var sub = db.skill("Sub", -1);
            db.raw().insertProgressLog(new com.unitracker.model.ProgressLog(
                    sub.getId(), LocalDate.of(2026, 8, 2), 10, 50));

            var zero = new com.unitracker.model.Skill("Sub", -1, 0);
            zero.setId(sub.getId());
            db.raw().updateSkill(zero);
            assertNullValue(db.raw().getProjectedCompletionDate(sub.getId(), LocalDate.of(2026, 8, 3), 14),
                    "a zero target has no projection");

            var done = new com.unitracker.model.Skill("Sub", -1, 10);
            done.setId(sub.getId());
            db.raw().updateSkill(done);
            assertNullValue(db.raw().getProjectedCompletionDate(sub.getId(), LocalDate.of(2026, 8, 3), 14),
                    "an already-met target has nothing left to project");
        }
    }

    private static void assertNullValue(Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertNull(actual, message);
    }
}
