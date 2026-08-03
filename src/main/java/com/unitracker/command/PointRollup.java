package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.Skill;

import java.util.List;
import java.util.Map;

/**
 * Shared bottom-up point accumulation used by both LogProgressCommand and
 * BatchLogProgressCommand: points logged against a Subskill also credit its
 * Main Skill, and then its root Category, all the way up the parent_id chain.
 * <p>
 * WHY A DELTA, NOT AN ABSOLUTE VALUE: the two log commands used to snapshot
 * previousPoints and restore it on undo. That works for one skill, but a
 * snapshot of the whole ancestor chain would go stale the moment a sibling
 * subskill is logged - undoing would then silently wipe the sibling's
 * contribution to their shared parent. A relative +delta / -delta is its own
 * exact inverse no matter what else happened in between, so it stays correct
 * with an arbitrarily deep tree and interleaved edits.
 * <p>
 * WHY THE DB DOES THE WALK: the controller's flat skill list comes from
 * getAllSkills(), whose Skill objects have a null {@code parent} field - only
 * getSkillTree() wires those up. So the chain has to be resolved via parent_id
 * in SQL rather than by walking Skill#getParent() in memory.
 */
final class PointRollup {

    private PointRollup() {
        // Static helper - not instantiable.
    }

    /**
     * Applies {@code delta} to the skill and every ancestor in SQLite, then
     * copies the authoritative new totals back into whichever in-memory Skill
     * objects the UI is bound to, so the progress bars and % labels update
     * without a full tree reload.
     *
     * @param allSkills the controller's live list; ancestors not present in it
     *                  are simply skipped (their DB rows are still updated).
     */
    static void apply(DatabaseHelper db, List<Skill> allSkills, int skillId, double delta) {
        List<Integer> affected = db.addPointsWithRollup(skillId, delta);
        Map<Integer, Double> fresh = db.getCurrentPointsFor(affected);
        for (Skill s : allSkills) {
            Double updated = fresh.get(s.getId());
            if (updated != null) {
                s.setCurrentPoints(updated);
            }
        }
    }
}
