package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

import java.util.List;

/**
 * Logs a study/practice session. Undo removes the log row and subtracts the
 * points back out again.
 * <p>
 * Points accumulate BOTTOM-UP: logging against a Subskill credits the same
 * amount to its parent Main Skill and on up to the root Category, so a
 * Category's % completion reflects everything logged beneath it. See
 * {@link PointRollup} for why this is a relative delta rather than the
 * previousPoints snapshot this class used to take.
 */
public class LogProgressCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final List<Skill> allSkills;
    private final ProgressLog log;

    public LogProgressCommand(DatabaseHelper db, Skill skill, List<Skill> allSkills, ProgressLog log) {
        this.db = db;
        this.skill = skill;
        this.allSkills = allSkills;
        this.log = log;
    }

    @Override
    public void execute() {
        db.insertProgressLog(log); // assigns log.id via the generated key
        PointRollup.apply(db, allSkills, skill.getId(), log.getPointsEarned());
    }

    @Override
    public void undo() {
        if (log.getId() > 0) {
            db.deleteProgressLog(log.getId());
        }
        PointRollup.apply(db, allSkills, skill.getId(), -log.getPointsEarned());
    }
}
