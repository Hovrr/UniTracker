package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.Skill;

/**
 * Applies an edit made through the "Edit Skill" dialog - this is also the
 * path used for manually overriding Current Points without going through
 * "Log Session" (per the PRD request). Captures the full before/after field
 * set as a single atomic step, so one Ctrl+Z reverts every field the user
 * changed in that dialog at once, not just the points.
 */
public class EditSkillCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final SkillSnapshot before;
    private final SkillSnapshot after;

    public EditSkillCommand(DatabaseHelper db, Skill skill, SkillSnapshot before, SkillSnapshot after) {
        this.db = db;
        this.skill = skill;
        this.before = before;
        this.after = after;
    }

    @Override
    public void execute() {
        after.applyTo(skill);
        db.updateSkill(skill);
    }

    @Override
    public void undo() {
        before.applyTo(skill);
        db.updateSkill(skill);
    }
}
