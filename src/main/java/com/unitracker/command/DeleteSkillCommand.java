package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletes a skill. The database's foreign keys mean this cascades in two
 * different ways that this command has to account for so undo is fully
 * correct, not just "close enough":
 *   - progress_logs has ON DELETE CASCADE  -> every session is WIPED.
 *   - calendar_notes has ON DELETE SET NULL -> notes survive, but lose
 *     their link to this skill.
 *
 * So before deleting, this command snapshots both the full log list AND
 * which note ids were linked to the skill. On undo it restores the skill
 * using {@link DatabaseHelper#restoreSkill} - which re-uses the EXACT same
 * row id instead of letting SQLite assign a new one - then restores the
 * logs the same way, then re-points the snapshotted notes back at that id.
 * The end result is indistinguishable from the delete never having happened.
 *
 * KNOWN SCOPE LIMIT: this restores DATA perfectly. It does not try to
 * restore UI SELECTION state (e.g. "the ComboBox had skill X highlighted
 * right before this delete") - after an undo, DashboardController simply
 * falls back to a sensible default selection. Tracking exact selection
 * history through every command would add real complexity for very little
 * practical benefit in a PoC.
 */
public class DeleteSkillCommand implements Command {

    private final DatabaseHelper db;
    private final ObservableList<Skill> skillsList;
    private final Skill skill;
    private final List<ProgressLog> logsSnapshot;
    private final List<Integer> linkedNoteIdsSnapshot;

    public DeleteSkillCommand(DatabaseHelper db, ObservableList<Skill> skillsList, Skill skill) {
        this.db = db;
        this.skillsList = skillsList;
        this.skill = skill;
        this.logsSnapshot = new ArrayList<>(db.getLogsForSkill(skill.getId()));
        this.linkedNoteIdsSnapshot = db.getNotesForSkill(skill.getId()).stream()
                .map(note -> note.getId())
                .toList();
    }

    @Override
    public void execute() {
        db.deleteSkill(skill.getId()); // cascades: wipes its logs, unlinks its notes
        skillsList.remove(skill);
    }

    @Override
    public void undo() {
        db.restoreSkill(skill); // same id as before deletion
        for (ProgressLog log : logsSnapshot) {
            db.restoreProgressLog(log); // same trick - keeps its original id too
        }
        for (int noteId : linkedNoteIdsSnapshot) {
            db.relinkNoteToSkill(noteId, skill.getId());
        }
        skillsList.add(skill);
    }
}
