package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

/**
 * Logs a study/practice session. Undo removes the log row and reverts the
 * skill's currentPoints to exactly what it was before - captured up front
 * so this works correctly even if the skill's target/points have been
 * edited again in between (undo restores the ORIGINAL value, not just
 * "subtract the points" - avoiding drift if other edits happened between
 * execute() and a much-later undo()).
 */
public class LogProgressCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final ProgressLog log;
    private final double previousPoints;

    public LogProgressCommand(DatabaseHelper db, Skill skill, ProgressLog log) {
        this.db = db;
        this.skill = skill;
        this.log = log;
        this.previousPoints = skill.getCurrentPoints();
    }

    @Override
    public void execute() {
        db.insertProgressLog(log); // assigns log.id via the generated key
        skill.setCurrentPoints(skill.getCurrentPoints() + log.getPointsEarned());
        db.updateSkill(skill);
    }

    @Override
    public void undo() {
        if (log.getId() > 0) {
            db.deleteProgressLog(log.getId());
        }
        skill.setCurrentPoints(previousPoints);
        db.updateSkill(skill);
    }
}
