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
 * The batch total rolls up to every ancestor in one shot via
 * {@link PointRollup}, and undo applies the exact negation.
 */
public class BatchLogProgressCommand implements Command {

    private final DatabaseHelper db;
    private final Skill skill;
    private final List<Skill> allSkills;
    private final List<ProgressLog> logs;

    public BatchLogProgressCommand(DatabaseHelper db, Skill skill, List<Skill> allSkills, List<ProgressLog> logs) {
        this.db = db;
        this.skill = skill;
        this.allSkills = allSkills;
        this.logs = logs;
    }

    @Override
    public void execute() {
        db.insertProgressLogBatch(logs); // one SQLite transaction - all land, or none do
        PointRollup.apply(db, allSkills, skill.getId(), totalPoints());
    }

    @Override
    public void undo() {
        for (ProgressLog log : logs) {
            if (log.getId() > 0) {
                db.deleteProgressLog(log.getId());
            }
        }
        PointRollup.apply(db, allSkills, skill.getId(), -totalPoints());
    }

    private double totalPoints() {
        double total = 0;
        for (ProgressLog log : logs) {
            total += log.getPointsEarned();
        }
        return total;
    }
}
