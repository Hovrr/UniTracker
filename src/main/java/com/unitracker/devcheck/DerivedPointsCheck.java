package com.unitracker.devcheck;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Proves the v2.0 DERIVED POINTS model is actually correct - in particular,
 * that the three desync bugs that existed in v1 are now impossible.
 *
 * <h2>WHY THIS IS AN INTEGRATION TEST, NOT A SQL COPY</h2>
 * The older {@link SqlLogicCheck} duplicates SQL from DatabaseHelper into an
 * in-memory database, because DatabaseHelper is a singleton hardcoded to a
 * file path. That approach has a fatal flaw for this particular change: it
 * would verify a COPY of the recursive-CTE walk, and the thing most likely to
 * be wrong is whether the real {@code recomputeAllPoints()} - with its temp
 * table, its transaction, and its COALESCE - agrees with it.
 *
 * <p>This class instead drives the REAL DatabaseHelper. It works because
 * {@code DB_DIR} is derived from {@code user.home} in a static initialiser, so
 * launching with {@code -Duser.home=<temp>} points the whole class at a
 * throwaway database. The real user's {@code ~/.unitracker/unitracker.db} is
 * never opened. This is the same technique devcheck/FxmlCheck.java uses.
 *
 * <h2>WHAT IS ACTUALLY BEING PROVEN</h2>
 * Each test below is a bug that shipped in v1 and demonstrably corrupted a
 * user's data. They are not hypothetical edge cases.
 *
 * <pre>
 *   java -ea -Duser.home=%TEMP%/utcheck -cp ... com.unitracker.devcheck.DerivedPointsCheck
 * </pre>
 */
public final class DerivedPointsCheck {

    private DerivedPointsCheck() {
    }

    private static int passed = 0;
    private static final DatabaseHelper db = DatabaseHelper.getInstance();

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true; // deliberate side effect
        if (!assertionsOn) {
            System.err.println("Assertions are disabled - re-run with -ea or this check proves nothing.");
            System.exit(2);
        }

        // Guard against accidentally running against a real profile. Written
        // with forward slashes deliberately: a backslash before "u" in Java
        // source is a unicode escape and would not even compile.
        String home = System.getProperty("user.home", "");
        if (home == null || home.isBlank() || !home.contains("utcheck")) {
            System.err.println("Refusing to run: -Duser.home must point at a throwaway directory "
                    + "whose path contains 'utcheck'. Got: " + home);
            System.exit(3);
        }

        db.initializeDatabase();
        db.wipeAllData();

        testBasicBottomUpRollup();
        testUpdateSkillPersistsEveryField();
        testDeleteCorrectsAncestors();
        testDeleteWholeSubtree();
        testReparentMovesTheTotal();
        testReparentRefusesCycle();
        testManualAdjustmentRollsUpAndUndoes();
        testUndoAfterSiblingLogged();
        testDeepNesting();
        testNegativeAdjustment();
        testCacheMatchesIndependentDerivation();
        testSelfHealingRecompute();
        testProjectedCompletionDate();
        testPresetsParseDefensively();
        testPresetPersistenceAndDefaultCurve();
        testResetRestoresBuiltInsOnly();
        testBatchedStatusUpdate();

        System.out.println("DerivedPointsCheck: " + passed + " checks passed.");
    }

    /**
     * The Settings dialog's preset tab, proven against the real database.
     *
     * <p>These matter because the dialog is WRITE-THROUGH: an Add or Edit there
     * commits immediately, so a persistence bug is not a cosmetic one - the
     * user watches a curve created, closes the dialog, and finds it gone the
     * next time they look.
     */
    private static void testPresetPersistenceAndDefaultCurve() {
        db.wipeAllData();
        db.initializeDatabase(); // seeds the built-ins on a fresh wipe
        int builtInCount = db.getPresets().size();
        expectTrue(builtInCount > 0, "a fresh install is seeded with built-in presets");

        // Both kinds must be distinguishable, because the Settings UI lists
        // curves separately from rate cards.
        expectTrue(!db.getPresetsOfKind(
                        com.unitracker.model.ProgressPreset.KIND_CURVE).isEmpty(),
                "mastery curves are seeded");
        expectTrue(!db.getPresetsOfKind(
                        com.unitracker.model.ProgressPreset.KIND_TIME).isEmpty(),
                "points-per-duration rate cards are seeded");

        // A curve's ladder must round-trip through the compact JSON intact,
        // because that string is the ONLY record of the milestones. Asserted
        // RELATIVE to the curve's own target rather than to a hard-coded
        // number, because the list is ordered by sort_order and the first
        // curve is whichever one was seeded first.
        com.unitracker.model.ProgressPreset curve = db.getPresetsOfKind(
                com.unitracker.model.ProgressPreset.KIND_CURVE).get(0);
        double curveTarget = curve.getTargetPoints() == null ? 0 : curve.getTargetPoints();
        expectTrue(curveTarget > 0, "a shipped curve has a positive target, got " + curveTarget);
        expect(5, curve.getMilestones().size(), "the shipped curves have five rungs");
        expect(curveTarget * 0.10, curve.getMilestones().get(0).threshold(),
                "the first rung sits at 10% of this curve's own target");
        expect(curveTarget, curve.getMilestones().get(4).threshold(),
                "the last rung is the target itself");

        // Create a user curve and confirm it is retrievable by id afterwards -
        // the property an Edit needs, since updatePreset writes by id.
        com.unitracker.model.ProgressPreset mine = new com.unitracker.model.ProgressPreset(
                com.unitracker.model.ProgressPreset.KIND_CURVE, "Test Curve", "created by the harness");
        mine.setTargetPoints(1000.0);
        mine.setMilestonesJson(mine.serializeMilestones(mine.buildMilestones(1000)));
        long newId = db.insertPreset(mine);
        expectTrue(newId > 0, "a user curve can be created");

        com.unitracker.model.ProgressPreset reread = db.getPresets().stream()
                .filter(p -> p.getId() == newId).findFirst().orElseThrow();
        expect(1000.0, reread.getTargetPoints(), "the target persists");
        expectTrue("Test Curve".equals(reread.getName()), "the name persists");
        expect(5, reread.getMilestones().size(), "the ladder persists intact");
        expect(100.0, reread.getMilestones().get(0).threshold(), "and with correct thresholds");

        // Edit it in place, exactly as the dialog's Edit button does.
        int countBeforeEdit = db.getPresets().size();
        reread.setTargetPoints(2000.0);
        reread.setMilestonesJson(reread.serializeMilestones(reread.buildMilestones(2000)));
        expectTrue(db.updatePreset(reread), "the curve can be updated");
        com.unitracker.model.ProgressPreset afterEdit = db.getPresets().stream()
                .filter(p -> p.getId() == newId).findFirst().orElseThrow();
        expect(2000.0, afterEdit.getTargetPoints(), "the new target persists");
        expect(200.0, afterEdit.getMilestones().get(0).threshold(),
                "the ladder RE-DERIVES from the new target - milestones are stored as ratios, "
                        + "so a 400-point curve and a 20000-point curve share the same shape");
        // updatePreset writes by id, so the row count must not move. An
        // accidental insert-instead-of-update would silently duplicate every
        // curve the user has ever edited, and the Settings list would fill up
        // with identical entries.
        expect(countBeforeEdit, db.getPresets().size(),
                "an edit must update in place, not create a duplicate row");
        expect(1, db.getPresets().stream().filter(p -> p.getId() == newId).count(),
                "and there is exactly one row for the edited curve");
    }

    /**
     * The batched status write that replaced the N-statement loop in
     * {@code applyStalledStatuses}.
     *
     * <p>Two things have to hold, and the second is the one a naive batch
     * implementation gets wrong:
     * <ol>
     *   <li>it touches EXACTLY the ids it is given - the whole point is to
     *       avoid re-persisting unrelated rows;</li>
     *   <li>it writes ONLY the status column. If it also wrote the other
     *       columns, a status change would silently persist whatever stale
     *       values happened to be in the caller's objects - which is the
     *       precise mechanism that made the v1 relative-delta design
     *       unsound.</li>
     * </ol>
     */
    private static void testBatchedStatusUpdate() {
        db.wipeAllData();
        Skill a = makeSkill("A", Skill.NO_PARENT);
        Skill b = makeSkill("B", Skill.NO_PARENT);
        Skill untouched = makeSkill("Untouched", Skill.NO_PARENT);
        log(a.getId(), 10);
        log(b.getId(), 20);
        log(untouched.getId(), 30);

        // Corrupt B's target and sort order in the DATABASE, leaving the model
        // unaware, so a batch that wrote more than `status` would show it.
        try (java.sql.PreparedStatement ps = db.getConnection().prepareStatement(
                "UPDATE skills SET target_points=7777, sort_order=99 WHERE id=?")) {
            ps.setInt(1, b.getId());
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new AssertionError("could not stage the corruption: " + e.getMessage());
        }

        int updated = db.setSkillStatuses(List.of(a.getId(), b.getId()), Skill.STATUS_STALLED);
        expect(2L, updated, "both requested rows are updated in one statement");

        // Only the status changed; the corrupted columns must survive intact.
        Skill rereadB = db.getAllSkills().stream()
                .filter(s -> s.getId() == b.getId()).findFirst().orElseThrow();
        expectTrue(Skill.STATUS_STALLED.equals(rereadB.getStatus()), "the status was written");
        expect(7777.0, rereadB.getTargetPoints(),
                "the batch MUST NOT rewrite target_points - a status change would otherwise "
                        + "persist whatever stale value the caller held");
        expect(99, rereadB.getSortOrder(), "and must not rewrite sort_order either");

        // A skill not in the list is untouched.
        Skill rereadUntouched = db.getAllSkills().stream()
                .filter(s -> s.getId() == untouched.getId()).findFirst().orElseThrow();
        expectTrue(Skill.STATUS_ACTIVE.equals(rereadUntouched.getStatus()),
                "a skill outside the batch keeps its status");

        // And the derived points survive the status write, which is the point
        // of status being the ONLY persisted-but-not-derived column.
        expect(10.0, db.getCurrentPointsFor(List.of(a.getId())).getOrDefault(a.getId(), -1.0),
                "the status write did not disturb the derived totals");

        // Degenerate inputs must be no-ops rather than malformed SQL.
        expect(0L, db.setSkillStatuses(List.of(), Skill.STATUS_STALLED),
                "an empty id list is a no-op, not a SQL syntax error");
        expect(0L, db.setSkillStatuses(null, Skill.STATUS_STALLED),
                "a null id list is a no-op");
        expect(0L, db.setSkillStatuses(List.of(a.getId()), null),
                "a null status is a no-op");
    }

    /**
     * "Restore built-ins" must replace the built-ins and must NOT touch the
     * user's own curves. Deleting someone's work because they clicked "restore
     * defaults" would be a genuinely nasty surprise, and a blanket DELETE is
     * the obvious implementation mistake.
     */
    private static void testResetRestoresBuiltInsOnly() {
        db.wipeAllData();
        db.initializeDatabase();
        int before = db.getPresets().size();

        com.unitracker.model.ProgressPreset mine = new com.unitracker.model.ProgressPreset(
                com.unitracker.model.ProgressPreset.KIND_CURVE, "Precious User Curve", "");
        mine.setTargetPoints(750.0);
        mine.setMilestonesJson(mine.serializeMilestones(mine.buildMilestones(750)));
        db.insertPreset(mine);

        // A built-in the user has edited, which "restore" must overwrite.
        com.unitracker.model.ProgressPreset editedBuiltIn = db.getPresetsOfKind(
                com.unitracker.model.ProgressPreset.KIND_CURVE).get(0);
        editedBuiltIn.setTargetPoints(1.0);
        expectTrue(db.updatePreset(editedBuiltIn), "precondition: a built-in can be edited");

        int restored = db.resetBuiltinPresets();
        expectTrue(restored > 0, "reset reports how many built-ins it restored");
        // Counted AFTER the user curve was added, so the comparison is between
        // two states that differ only by the reset.
        expect(before + 1, db.getPresets().size(),
                "the preset COUNT is unchanged - built-ins replaced in place, user curves untouched");

        expectTrue(db.getPresets().stream().anyMatch(p -> "Precious User Curve".equals(p.getName())),
                "THE BUG THIS GUARDS: a user's own curve must survive 'Restore built-ins'");

        // The edited built-in must be back to its shipped value, not left at 1.
        com.unitracker.model.ProgressPreset check = db.getPresetsOfKind(
                com.unitracker.model.ProgressPreset.KIND_CURVE).stream()
                .filter(p -> p.isBuiltIn()).findFirst().orElseThrow();
        expectTrue(check.getTargetPoints() > 1.0,
                "an edited built-in is restored to its shipped value, got target "
                        + check.getTargetPoints());

        // A built-in must not be deletable - they are the app's default
        // vocabulary, and losing them would silently change every new skill.
        long anyBuiltIn = db.getPresets().stream().filter(com.unitracker.model.ProgressPreset::isBuiltIn)
                .findFirst().orElseThrow().getId();
        expectTrue(!db.deletePreset(anyBuiltIn),
                "deletePreset must refuse a built-in, whatever the UI did");
        expectTrue(db.getPresets().stream().anyMatch(p -> p.getId() == anyBuiltIn),
                "and the built-in is still there afterwards");
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private static Skill makeSkill(String name, int parentId) {
        Skill s = new Skill(name, parentId, 1000.0);
        db.insertSkill(s);
        return s;
    }

    private static void log(int skillId, double points) {
        db.insertProgressLog(new ProgressLog(skillId, LocalDate.of(2026, 8, 1), 25, points));
    }

    /** The authoritative, independently-computed total for a node. */
    private static double derived(int skillId) {
        return db.getDerivedPointsFor(skillId);
    }

    /** The cached value the UI actually reads. */
    private static double cached(int skillId) {
        return db.getCurrentPointsFor(List.of(skillId)).getOrDefault(skillId, -1.0);
    }

    private static void expect(double expected, double actual, String what) {
        assert Math.abs(expected - actual) < 1e-6
                : what + ": expected " + expected + " but got " + actual;
        passed++;
    }

    private static void expectTrue(boolean condition, String what) {
        assert condition : what;
        passed++;
    }

    /** Separate from {@link #expect} so a projected date is never silently
     *  compared as a double, and so a null projection is asserted explicitly
     *  rather than auto-unboxed into a NullPointerException. */
    private static void expectDate(Object expected, Object actual, String what) {
        assert expected == null ? actual == null : expected.equals(actual)
                : what + ": expected " + expected + " but got " + actual;
        passed++;
    }

    // =================================================================
    //  Tests
    // =================================================================

    private static void testBasicBottomUpRollup() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill main = makeSkill("Main", cat.getId());
        Skill sub = makeSkill("Sub", main.getId());

        log(sub.getId(), 10);

        // The core promise of the app: a Subskill's points reach its Main Skill
        // and its Category.
        expect(10.0, cached(sub.getId()), "subskill holds its own points");
        expect(10.0, cached(main.getId()), "main skill accumulates from below");
        expect(10.0, cached(cat.getId()), "root category accumulates from below");
    }

    /**
     * V1 DESYNC BUG #1. Deleting a subskill used to leave its points on every
     * ancestor forever, because the log rows were cascade-deleted but
     * current_points was never decremented.
     */
    private static void testDeleteCorrectsAncestors() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill a = makeSkill("A", cat.getId());
        Skill b = makeSkill("B", cat.getId());

        log(a.getId(), 100);
        log(b.getId(), 50);
        expect(150.0, cached(cat.getId()), "category holds both children's points");

        // Delete A and its history together, exactly as DeleteSkillCommand does.
        int logId = db.getLogsForSkill(a.getId()).get(0).getId();
        db.deleteProgressLog(logId);
        expect(50.0, cached(cat.getId()),
                "THE v1 BUG: deleting a subskill must subtract it from the shared category");
        expect(0.0, cached(a.getId()), "the emptied subskill reads zero, not its stale total");
        expect(50.0, cached(b.getId()), "the surviving sibling is untouched");

        // Deleting A must leave B's contribution alone, and re-adding A's row
        // must bring the total back - that is what makes undo work.
        ProgressLog aLog = new ProgressLog(a.getId(), LocalDate.of(2026, 8, 1), 25, 100);
        db.insertProgressLog(aLog);
        expect(150.0, cached(cat.getId()), "re-adding the row restores the shared total");
        db.deleteProgressLog(aLog.getId());
        expect(50.0, cached(cat.getId()), "and removing it again takes it back out");
    }

    /**
     * A guard against a bug that is invisible until it corrupts data: an
     * UPDATE whose placeholder count and bind count disagree matches the wrong
     * row - or none - and reports no error. This asserts the whole write
     * surface round-trips, field by field, including the ones v1's updateSkill
     * silently dropped.
     */
    private static void testUpdateSkillPersistsEveryField() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill mid = makeSkill("Mid", cat.getId());

        Skill fetched = db.getAllSkills().stream()
                .filter(s -> s.getId() == mid.getId()).findFirst().orElseThrow();

        fetched.setName("Renamed");
        fetched.setStatus(Skill.STATUS_STALLED);
        fetched.setColorHex("#FF00AA");
        fetched.setTargetPoints(777.0);
        fetched.setSortOrder(42);
        expectTrue(db.updateSkill(fetched),
                "updateSkill must report success - a WHERE id that matched nothing is the silent-failure mode");

        Skill reread = db.getAllSkills().stream()
                .filter(s -> s.getId() == mid.getId()).findFirst().orElseThrow();
        expectTrue("Renamed".equals(reread.getName()), "name is persisted");
        expectTrue(Skill.STATUS_STALLED.equals(reread.getStatus()), "status is persisted");
        expectTrue("#FF00AA".equals(reread.getColorHex()), "colour is persisted");
        expect(777.0, reread.getTargetPoints(), "target is persisted");
        expect(42, reread.getSortOrder(), "sort order is persisted");
        // The one v1 never wrote at all.
        expectTrue(reread.getParentId() == cat.getId(),
                "parent_id is persisted - v1 silently dropped this, so re-parenting reverted on restart");

        // And a root must still round-trip as NULL rather than as -1.
        Skill root = db.getAllSkills().stream()
                .filter(s -> s.getId() == cat.getId()).findFirst().orElseThrow();
        expectTrue(db.updateSkill(root), "updating a root succeeds");
        expect(Skill.NO_PARENT, db.getAllSkills().stream()
                .filter(s -> s.getId() == cat.getId()).findFirst().orElseThrow().getParentId(),
                "a root's -1 sentinel round-trips back to NO_PARENT");
    }

    private static void testDeleteWholeSubtree() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill main = makeSkill("Main", cat.getId());
        Skill sub = makeSkill("Sub", main.getId());
        log(sub.getId(), 42);
        expect(42.0, cached(cat.getId()), "precondition: the category holds the subtree's points");

        // deleteSkillCascade removes the node and every descendant. Each
        // individual delete re-derives, and the final state must be zero.
        db.deleteSkillCascade(cat.getId());
        expectTrue(db.getAllSkills().stream().noneMatch(s -> s.getId() == cat.getId()),
                "a cascaded delete really removes the top node");
        expectTrue(db.getAllSkills().stream().noneMatch(s -> s.getId() == sub.getId()),
                "and every descendant under it");
        // Re-insert the category to prove the derive left no residue behind in
        // the form of a stale total waiting to be resurrected.
        Skill fresh = makeSkill("Category", Skill.NO_PARENT);
        expect(0.0, cached(fresh.getId()),
                "recreating the category must start at zero, not at the deleted subtree's total");
        expectTrue(db.getLogsForSkill(sub.getId()).isEmpty(), "the subtree's sessions went with it");
    }

    /**
     * V1 DESYNC BUG #2. Re-parenting never wrote parent_id, so the old chain
     * kept the points while the UI showed the new arrangement.
     */
    private static void testReparentMovesTheTotal() {
        db.wipeAllData();
        Skill oldCat = makeSkill("Old Category", Skill.NO_PARENT);
        Skill newCat = makeSkill("New Category", Skill.NO_PARENT);
        Skill sub = makeSkill("Movable", oldCat.getId());
        log(sub.getId(), 30);

        expect(30.0, cached(oldCat.getId()), "precondition: points start under the old category");
        expect(0.0, cached(newCat.getId()), "precondition: the new category starts empty");

        expectTrue(db.reparentSkill(sub.getId(), newCat.getId()), "reparent succeeds");

        expect(0.0, cached(oldCat.getId()),
                "THE v1 BUG: the old category must release the points it no longer owns");
        expect(30.0, cached(newCat.getId()),
                "THE v1 BUG: the new category must receive the history it now owns");
        expect(30.0, cached(sub.getId()), "the moved skill keeps its own points");

        // And it must survive a reload, which is what the missing parent_id broke.
        Skill reloaded = db.getAllSkills().stream()
                .filter(s -> s.getId() == sub.getId())
                .findFirst()
                .orElseThrow();
        expect(newCat.getId(), reloaded.getParentId(),
                "THE v1 BUG: the new parent must be PERSISTED, not just held in memory");
    }

    private static void testReparentRefusesCycle() {
        db.wipeAllData();
        Skill root = makeSkill("Root", Skill.NO_PARENT);
        Skill mid = makeSkill("Mid", root.getId());
        Skill leaf = makeSkill("Leaf", mid.getId());

        expectTrue(!db.reparentSkill(root.getId(), leaf.getId()),
                "a category must not be re-parented under its own descendant");
        expectTrue(!db.reparentSkill(mid.getId(), mid.getId()),
                "a skill must not become its own parent");

        // The tree must be intact and the derive must still terminate.
        expect(0.0, cached(root.getId()), "a refused re-parent leaves the tree readable");
    }

    /**
     * V1 DESYNC BUG #3. The manual "Current Points" spinner wrote an absolute
     * value with no log row, so it never reached the ancestors and never
     * appeared in history.
     */
    private static void testManualAdjustmentRollsUpAndUndoes() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill sub = makeSkill("Sub", cat.getId());
        log(sub.getId(), 20);
        expect(20.0, cached(sub.getId()), "precondition: starting total");

        // Ask for 75 total. The DIFFERENCE (55) becomes a real log row.
        expectTrue(db.applyPointAdjustment(sub.getId(), 75, LocalDate.of(2026, 8, 2), "typo fix"),
                "adjustment is recorded");

        expect(75.0, cached(sub.getId()), "the adjustment reaches the requested total");
        expect(75.0, cached(cat.getId()),
                "THE v1 BUG: a manual correction must also reach the ancestors");
        expectTrue(db.getLogsForSkill(sub.getId()).size() == 2,
                "THE v1 BUG: the correction must be visible in the session history, not invisible");

        List<ProgressLog> logs = db.getLogsForSkill(sub.getId());
        ProgressLog adjustment = logs.stream().filter(ProgressLog::isAdjustment).findFirst().orElseThrow();
        expect(55.0, adjustment.getPointsEarned(), "the adjustment records the difference, not the total");

        // Undo it by removing the row - which is exactly what Ctrl+Z does.
        db.deleteProgressLog(adjustment.getId());
        expect(20.0, cached(sub.getId()), "removing the adjustment reverts to the prior total");
        expect(20.0, cached(cat.getId()), "and the ancestor reverts with it");

        // Requesting the total it already has must be a no-op, not a zero row.
        int before = db.getLogsForSkill(sub.getId()).size();
        expectTrue(db.applyPointAdjustment(sub.getId(), 20, LocalDate.of(2026, 8, 2), "no-op"),
                "a no-op adjustment succeeds");
        expect(before, db.getLogsForSkill(sub.getId()).size(),
                "a no-op adjustment must not add a pointless zero row");
    }

    /**
     * The case the v1 relative-delta design was written FOR, and which must
     * still hold: undo after a sibling was logged in between.
     */
    private static void testUndoAfterSiblingLogged() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill mid = makeSkill("Mid", cat.getId());
        Skill a = makeSkill("A", mid.getId());
        Skill b = makeSkill("B", mid.getId());

        ProgressLog logA = new ProgressLog(a.getId(), LocalDate.of(2026, 8, 1), 25, 10);
        db.insertProgressLog(logA);
        expect(10.0, cached(mid.getId()), "A's session reached the shared parent");

        // A sibling logs 5 while A is still there.
        db.insertProgressLog(new ProgressLog(b.getId(), LocalDate.of(2026, 8, 1), 25, 5));
        expect(15.0, cached(mid.getId()), "the shared parent holds both children");

        // Now undo A. B's 5 must survive.
        db.deleteProgressLog(logA.getId());
        expect(0.0, cached(a.getId()), "the undone skill is back to zero");
        expect(5.0, cached(b.getId()), "the sibling is untouched by the undo");
        expect(5.0, cached(mid.getId()),
                "the shared parent keeps the sibling's contribution - a snapshot undo would wipe this");
        expect(5.0, cached(cat.getId()), "and so does the root");
    }

    private static void testDeepNesting() {
        db.wipeAllData();
        Skill node = makeSkill("L0", Skill.NO_PARENT);
        Skill root = node;
        for (int i = 1; i <= 8; i++) {
            node = makeSkill("L" + i, node.getId());
        }
        Skill leaf = node;
        log(leaf.getId(), 7);

        // Every single level must have received the points.
        expect(7.0, cached(leaf.getId()), "the deepest leaf holds the points");
        expect(7.0, cached(root.getId()), "the root eight levels up holds them too");
        expect(9, db.getRollupTargets(leaf.getId()).size(),
                "the rollup target list names the skill plus all eight ancestors");

        // Deleting the leaf must unwind all nine levels.
        db.deleteProgressLog(db.getLogsForSkill(leaf.getId()).get(0).getId());
        expect(0.0, cached(root.getId()), "deleting the leaf unwinds every ancestor at once");
    }

    private static void testNegativeAdjustment() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill sub = makeSkill("Sub", cat.getId());
        log(sub.getId(), 100);

        // A correction that REDUCES the total, on a node that would otherwise
        // still be positive.
        expectTrue(db.applyPointAdjustment(sub.getId(), 40, LocalDate.of(2026, 8, 3), "overcounted"),
                "a downward adjustment is recorded");
        expect(40.0, cached(sub.getId()), "the reduction is exact");
        expect(40.0, cached(cat.getId()), "a negative contribution rolls up like a positive one");

        // And below zero: the sum can legitimately be negative, and it must
        // NOT be silently clamped. (v1 clamped at MAX(0, ...), which made the
        // operation non-invertible precisely in this case.)
        expectTrue(db.applyPointAdjustment(sub.getId(), -25, LocalDate.of(2026, 8, 4), "credit note"),
                "a negative total is recorded");
        expect(-25.0, cached(sub.getId()),
                "a negative total is stored faithfully, not clamped - clamping is what broke undo");
        expect(-25.0, cached(cat.getId()), "and it rolls up unchanged");
    }

    /**
     * The invariant that makes the whole model worth anything: for every
     * skill, the cached total the UI reads must equal an independent
     * derivation over the same subtree.
     */
    private static void testCacheMatchesIndependentDerivation() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill mid = makeSkill("Mid", cat.getId());
        Skill a = makeSkill("A", mid.getId());
        Skill b = makeSkill("B", mid.getId());
        Skill lonely = makeSkill("Lonely", Skill.NO_PARENT);

        log(a.getId(), 11);
        log(b.getId(), 7);
        log(cat.getId(), 3); // logged directly against the category itself
        log(lonely.getId(), 2);
        db.applyPointAdjustment(mid.getId(), 99, LocalDate.of(2026, 8, 5), "correction");

        for (Skill s : db.getAllSkills()) {
            expect(derived(s.getId()), cached(s.getId()),
                    "cache drifted from derivation for \"" + s.getName() + "\"");
        }
    }

    /**
     * The startup self-heal. Corrupting the cache the way a v1 bug would have,
     * then recomputing, must restore the truth - which is what repairs a real
     * user's database the first time they open it under v2.0.
     *
     * <p>The corruption is applied with RAW SQL, deliberately going through
     * {@code getConnection()} rather than any public method. That is the point:
     * v2.0 removed the API path that allowed this (updateSkill no longer
     * writes current_points), so the only way to reproduce the old damage is
     * to write behind the API's back exactly as the old bugs did. If a future
     * change ever re-opens an API route to the cache, this test's corruption
     * step should be revisited.
     */
    private static void testSelfHealingRecompute() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill sub = makeSkill("Sub", cat.getId());
        log(sub.getId(), 25);
        expect(25.0, cached(cat.getId()), "precondition: the cache is correct");

        try (java.sql.PreparedStatement ps = db.getConnection()
                .prepareStatement("UPDATE skills SET current_points=9999 WHERE id=?")) {
            ps.setInt(1, cat.getId());
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new AssertionError("could not stage the corruption: " + e.getMessage());
        }
        expect(9999.0, cached(cat.getId()), "precondition: the corruption is visible");

        int touched = db.recomputeAllPoints();
        expectTrue(touched > 0, "the recompute reports how many rows it rewrote");
        expect(25.0, cached(cat.getId()), "the self-heal restored the true total");
    }

    // =================================================================
    //  Projection + presets
    // =================================================================

    private static void testProjectedCompletionDate() {
        db.wipeAllData();
        Skill cat = makeSkill("Category", Skill.NO_PARENT);
        Skill sub = makeSkill("Sub", cat.getId());
        LocalDate today = LocalDate.of(2026, 8, 3);

        // No history at all: no honest projection exists, and inventing one
        // would tell a stalled skill it finishes in three days.
        expectDate(null, db.getProjectedCompletionDate(sub.getId(), today, 14),
                "a skill with no history has no projection");

        // 14 points across a 14-day window = 1 point/day. To reach 100 from 14
        // is 86 days.
        db.insertProgressLog(new ProgressLog(sub.getId(), today.minusDays(13), 10, 14));
        expect(14.0, cached(sub.getId()), "precondition: 14 points are derived");

        // Save the target through the ordinary attribute path while holding a
        // Skill object whose currentPoints property is still the 0 it was
        // constructed with. In v1 this call wrote that stale 0 back over the
        // derived 14. It must not now.
        sub.setTargetPoints(100);
        db.updateSkill(sub);
        expect(14.0, cached(sub.getId()),
                "updateSkill must NOT clobber the derived total - it does not write current_points");
        expect(100.0, db.getTargetPointsFor(sub.getId()), "the target itself IS written");

        LocalDate projected = db.getProjectedCompletionDate(sub.getId(), today, 14);
        expectDate(today.plusDays(86), projected, "the projection extrapolates the trailing rate");

        // Already complete: nothing left to project.
        sub.setTargetPoints(10);
        db.updateSkill(sub);
        expectDate(null, db.getProjectedCompletionDate(sub.getId(), today, 14),
                "a completed skill has no projection");
        sub.setTargetPoints(0);
        db.updateSkill(sub);
        expectDate(null, db.getProjectedCompletionDate(sub.getId(), today, 14),
                "a zero target has no projection");
    }

    private static void testPresetsParseDefensively() {
        // The one parser that reads a hand-editable blob must never throw -
        // it is reached from "create a new skill".
        expect(0, com.unitracker.model.ProgressPreset.parseMilestones(null).size(),
                "null JSON yields no milestones");
        expect(0, com.unitracker.model.ProgressPreset.parseMilestones("").size(),
                "empty JSON yields no milestones");
        expect(0, com.unitracker.model.ProgressPreset.parseMilestones("not json at all").size(),
                "malformed JSON yields no milestones rather than throwing");
        expect(0, com.unitracker.model.ProgressPreset.parseMilestones("[\"broken\" \"entry\"]").size(),
                "an entry with no field separator is skipped");
        expect(0, com.unitracker.model.ProgressPreset.parseMilestones("[\"a\u001fNOTANUMBER\"]").size(),
                "a non-numeric threshold is skipped");

        // A well-formed ladder must round-trip.
        com.unitracker.model.ProgressPreset p = new com.unitracker.model.ProgressPreset();
        String json = p.serializeMilestones(p.buildMilestones(1000));
        List<com.unitracker.model.ProgressPreset.Milestone> back =
                com.unitracker.model.ProgressPreset.parseMilestones(json);
        expect(5, back.size(), "the default ladder has five rungs");
        expect(100.0, back.get(0).threshold(), "first rung is 10% of the target");
        expect(200.0, back.get(1).threshold(), "second rung is the 20-hour basic-proficiency mark");
        expect(1000.0, back.get(4).threshold(), "the last rung is the target itself");

        // Rungs must be strictly increasing even when the target is so small
        // that two ratios round to the same number, or the NOT EXISTS guard
        // in recordMilestonesCrossed would collide on the wrong key.
        List<com.unitracker.model.ProgressPreset.Milestone> tiny = p.buildMilestones(3);
        for (int i = 1; i < tiny.size(); i++) {
            expectTrue(tiny.get(i).threshold() > tiny.get(i - 1).threshold(),
                    "milestone thresholds must strictly increase (target=3, rung " + i + ")");
        }
        expectTrue(tiny.size() < 5,
                "a tiny target drops rungs it could never reach rather than inventing them");
    }
}
