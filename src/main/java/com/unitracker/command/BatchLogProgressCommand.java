package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

import java.util.List;

/**
 * Same idea as LogProgressCommand, extended to a whole list of logs
 * inserted/reverted together as ONE undo/redo step. Backs the Advanced Log
 * dialog's date-range batch insert, so pressing Undo once after backfilling
 * 10 days removes all 10 - not just the last one, which is what looping
 * individual LogProgressCommands would have done.
 * <p>
 * previousPoints is captured once, up front, exactly like
 * LogProgressCommand - undo restores that exact original value rather than
 * subtracting the batch's total, so it stays correct even if the skill was
 * edited again in between execute() and a much-later undo().
 */
public class BatchLogProgressCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final List<ProgressLog> logs;
    private final double previousPoints;

    public BatchLogProgressCommand(DatabaseHelper db, Skill skill, List<ProgressLog> logs) {
        this.db = db;
        this.skill = skill;
        this.logs = logs;
        this.previousPoints = skill.getCurrentPoints();
    }

    @Override
    public void execute() {
        db.insertProgressLogBatch(logs); // one SQLite transaction - all land, or none do
        double total = 0;
        for (ProgressLog log : logs) {
            total += log.getPointsEarned();
        }
        skill.setCurrentPoints(skill.getCurrentPoints() + total);
        db.updateSkill(skill);
    }

    @Override
    public void undo() {
        for (ProgressLog log : logs) {
            if (log.getId() > 0) {
                db.deleteProgressLog(log.getId());
            }
        }
        skill.setCurrentPoints(previousPoints);
        db.updateSkill(skill);
    }
}
