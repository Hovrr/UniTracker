package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.Skill;

import java.util.List;
import java.util.Map;

/**
 * Shared bottom-up point accumulation used by every command that logs or
 * removes sessions: points logged against a Subskill also credit its Main
 * Skill, and then its root Category, all the way up the parent_id chain.
 *
 * <p>V2.0 - REWRITTEN. The v1 version applied a relative {@code +delta} /
 * {@code -delta} to {@code skills.current_points} on the skill and each
 * ancestor. That was a correct way to move a delta, but it assumed the stored
 * values were already right, and three separate paths could leave them wrong
 * with no way to notice (see the class javadoc on DatabaseHelper). This
 * version does not apply any delta at all: the log row the command inserted
 * or deleted IS the change, and the totals are re-derived from the log table
 * by {@link DatabaseHelper#recomputeAllPoints()}.
 *
 * <p>WHAT THIS ACTUALLY FIXES. Because the command no longer owns the
 * arithmetic:
 * <ul>
 *   <li>Undoing a log after a sibling was logged in between is correct by
 *       construction - there is no snapshot to go stale and no ancestor total
 *       to restore, because the totals are recomputed from whatever rows
 *       exist at that moment.</li>
 *   <li>Deleting a subskill no longer strands its points on the ancestors,
 *       because the cascade-deleted rows simply stop being counted.</li>
 *   <li>Re-parenting a skill no longer leaves its history pointing at the old
 *       Category, because the derive follows the current parent_id.</li>
 *   <li>There is no clamp-at-zero hack and therefore no non-invertible
 *       rounding case: a negative adjustment is an ordinary row that sums
 *       like any other.</li>
 * </ul>
 *
 * <p>WHY THE DB DOES THE WALK: the controller's flat skill list comes from
 * getAllSkills(), whose Skill objects have a null {@code parent} field - only
 * getSkillTree() wires those up. So the chain has to be resolved via parent_id
 * in SQL rather than by walking Skill#getParent() in memory.
 *
 * <p>COST NOTE: this re-derives the WHOLE hierarchy rather than just the
 * affected chain. That is deliberate. A partial update would be faster and
 * would reintroduce exactly the class of bug this class exists to prevent -
 * a partial update is only correct if the caller computed the affected set
 * perfectly, which is the assumption that produced the original bugs. A full
 * derive is idempotent, so it cannot be made wrong by a missed edge.
 */
final class PointRollup {

    private PointRollup() {
        // Static helper - not instantiable.
    }

    /**
     * Re-derives every total from the log table, then copies the
     * authoritative values back into whichever in-memory Skill objects the UI
     * is bound to, so the progress bars and % labels update without a full
     * tree reload.
     *
     * <p>Call this AFTER the log rows have been written or removed - it reads
     * the database, it does not change it.
     *
     * @param allSkills the controller's live list; ancestors not present in it
     *                  are simply skipped (their DB rows are still updated).
     */
    static void apply(DatabaseHelper db, List<Skill> allSkills, int skillId, double delta) {
        // The delta parameter is retained so existing call sites keep
        // compiling and so the intent stays readable at the call site, but it
        // is deliberately IGNORED: the authoritative change is the log row the
        // caller wrote, not this number. Passing a value here can no longer
        // desynchronise anything, which is the point.
        List<Integer> affected = db.getRollupTargets(skillId);
        Map<Integer, Double> fresh = db.refreshPointsFor(affected);
        if (allSkills == null) return;
        for (Skill s : allSkills) {
            Double updated = fresh.get(s.getId());
            if (updated != null) {
                s.setCurrentPoints(updated);
            }
        }
    }
}
