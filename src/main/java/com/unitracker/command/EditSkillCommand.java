package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

import java.time.LocalDate;

/**
 * Applies an edit made through the "Edit Skill" dialog. Captures the full
 * before/after field set as a single atomic step, so one Ctrl+Z reverts every
 * field the user changed in that dialog at once.
 *
 * <p>V2.0 - THE EDIT PATH WAS PREVIOUSLY A NO-OP FOR TWO OF ITS FIELDS, and
 * this class is where that is fixed.
 *
 * <p>BUG: RE-PARENTING NEVER REACHED THE DATABASE.
 * The dialog collects a new parent, and v1's {@code after.applyTo(skill)} set
 * it on the live model - so the UI instantly showed the skill at its new place
 * in the tree - but {@code DatabaseHelper.updateSkill} never wrote
 * {@code parent_id}, so the row kept the old value. Every subsequent rollup
 * walked the STALE chain (points kept landing on the old Category), the tree
 * rebuilt from the database disagreed with the tree on screen, and the change
 * reverted entirely on restart. The in-memory model and the database were
 * describing two different hierarchies.
 *
 * <p>BUG: "CURRENT POINTS" WAS A BARE OVERWRITE.
 * The spinner let the user type a total straight into
 * {@code skills.current_points}. That wrote an absolute number with no
 * supporting log row, so it was invisible in the session history, it never
 * touched the ancestors above it, and it left the skill disagreeing with
 * {@code SUM(points_earned)} - which is what the Level badge reads. Correcting
 * a typo could silently break the app's own totals.
 *
 * <p>THE FIX. The two fields that cannot be plain assignments are routed to
 * dedicated operations that understand their consequences:
 * <ul>
 *   <li>a parent change goes through
 *       {@link DatabaseHelper#reparentSkill(int, int)}, which refuses cycles
 *       and re-derives points so BOTH the old and the new ancestor chains end
 *       up correct;</li>
 *   <li>a points change goes through
 *       {@link DatabaseHelper#applyPointAdjustment}, which records the
 *       DIFFERENCE as a real ADJUSTMENT log - so it rolls up to the ancestors,
 *       appears in the session history, and undoes like any other log.</li>
 * </ul>
 * Every other field (name, structure, status, colour, target) is a genuine
 * per-row attribute and is written directly.
 *
 * <p>WHY ADJUSTMENT-AS-A-LOG MATTERS FOR UNDO: because the correction is a
 * row, undo deletes that row and the totals re-derive back to what they were.
 * There is no remembered "previous total" to restore, so an undo issued after
 * other sessions were logged in between still lands on the right number.
 */
public class EditSkillCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final SkillSnapshot before;
    private final SkillSnapshot after;
    private final boolean parentChanged;
    private final boolean pointsChanged;
    private final boolean targetChanged;

    public EditSkillCommand(DatabaseHelper db, Skill skill, SkillSnapshot before, SkillSnapshot after) {
        this.db = db;
        this.skill = skill;
        this.before = before;
        this.after = after;
        this.parentChanged = before.parentId() != after.parentId();
        this.pointsChanged = Math.abs(before.currentPoints() - after.currentPoints()) > 1e-9;
        this.targetChanged = Math.abs(before.targetPoints() - after.targetPoints()) > 1e-9;
    }

    @Override
    public void execute() {
        if (parentChanged) {
            // Route through reparentSkill so the derive happens and a cycle is
            // rejected. Falling back to a plain property write here is what
            // produced the silent divergence.
            if (!db.reparentSkill(skill.getId(), after.parentId())) {
                System.err.println("[EditSkillCommand] Re-parent refused for \""
                        + skill.getName() + "\"; parent left unchanged.");
            } else {
                skill.setParentId(after.parentId());
            }
        }

        if (pointsChanged) {
            // Expresses the change as the difference between the requested
            // total and the currently DERIVED total, so it is correct even
            // against a stale in-memory value.
            db.applyPointAdjustment(skill.getId(), after.currentPoints(), LocalDate.now(),
                    "Manual correction of \"" + after.name() + "\"");
        }

        // Write the plain per-row attributes. parent_id is deliberately NOT
        // applied from the snapshot here - the reparent above owns it, so
        // there is exactly one code path that can change a parent.
        SkillSnapshot toPersist = parentChanged
                ? new SkillSnapshot(after.name(), skill.getParentId(), after.structureType(),
                        after.status(), after.colorHex(), after.targetPoints(), skill.getCurrentPoints())
                : after;
        toPersist.applyTo(skill);
        // applyTo wrote currentPoints from the snapshot; re-read the truth so
        // the in-memory value matches what the adjustment actually produced
        // (which is the derived total, not necessarily the requested one).
        if (pointsChanged) {
            skill.setCurrentPoints(db.getCurrentPointsFor(java.util.List.of(skill.getId()))
                    .getOrDefault(skill.getId(), skill.getCurrentPoints()));
        }
        db.updateSkill(skill);
    }

    @Override
    public void undo() {
        if (parentChanged) {
            if (!db.reparentSkill(skill.getId(), before.parentId())) {
                System.err.println("[EditSkillCommand] Undo re-parent refused for \""
                        + skill.getName() + "\"; parent left at its current value.");
            } else {
                skill.setParentId(before.parentId());
            }
        }

        if (pointsChanged) {
            // Restoring "the old total" is a real adjustment row, not an
            // assignment - so it rolls up and stays consistent with history.
            db.applyPointAdjustment(skill.getId(), before.currentPoints(), LocalDate.now(),
                    "Reverted manual correction of \"" + before.name() + "\"");
        }

        SkillSnapshot toRestore = parentChanged
                ? new SkillSnapshot(before.name(), skill.getParentId(), before.structureType(),
                        before.status(), before.colorHex(), before.targetPoints(), skill.getCurrentPoints())
                : before;
        toRestore.applyTo(skill);
        if (pointsChanged) {
            skill.setCurrentPoints(db.getCurrentPointsFor(java.util.List.of(skill.getId()))
                    .getOrDefault(skill.getId(), skill.getCurrentPoints()));
        }
        db.updateSkill(skill);
    }

    /** True when the edit moved the skill, which the UI uses to decide
     *  whether the hierarchy needs a full rebuild. */
    public boolean didChangeParent() {
        return parentChanged;
    }

    /** True when the edit altered the points total, which the UI uses to
     *  decide whether to re-check for newly crossed milestones. */
    public boolean didChangePoints() {
        return pointsChanged;
    }

    /** True when the target moved, which can newly satisfy a milestone. */
    public boolean didChangeTarget() {
        return targetChanged;
    }
}
