package com.unitracker.command;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.CalendarNote;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deletes a skill, and everything beneath it.
 *
 * <p>V2.0 - TWO REAL BUGS FIXED HERE, both of which the v1 version had.
 *
 * <p>BUG 1: THE DELETE SILENTLY FAILED FOR ANY NODE WITH CHILDREN.
 * v1 called {@code db.deleteSkill(id)}, which issues a plain
 * {@code DELETE FROM skills WHERE id=?}. Because {@code parent_id} is
 * declared without {@code ON DELETE CASCADE} (deliberately - see
 * DatabaseHelper), SQLite rejects that delete with a foreign key error the
 * moment the node has any children. The catch turned it into a quiet
 * {@code return false}, and the caller ignored the return value - so the row
 * was never deleted, yet {@code skillsList.remove(skill)} still ran. The user
 * watched the Category vanish from the UI while the database kept it, and the
 * next Refresh resurrected it. This now calls
 * {@link DatabaseHelper#deleteSkillCascade(int)} and, critically, only touches
 * the in-memory list if the database actually confirmed the delete.
 *
 * <p>BUG 2: UNDO RESTORED ONLY THE TOP NODE.
 * v1 snapshotted one skill's logs and one skill's linked notes. Deleting a
 * Category therefore cascaded away all its descendants and their sessions, and
 * undo brought back an empty Category with nothing under it - the user's data
 * was gone, with a working Undo button sitting right there implying otherwise.
 * This snapshots the WHOLE subtree up front, each node's logs and each node's
 * linked note ids, and restores all of it.
 *
 * <p>WHY THE SNAPSHOT IS TAKEN BEFORE THE DELETE: the whole point is to have
 * a copy of rows that are about to be cascade-deleted. Once the delete runs
 * they are gone, and re-inserting from memory would mean holding every log
 * object in the subtree in RAM.
 *
 * <p>POINT INTEGRITY: no delta arithmetic is needed on either side any more.
 * Deleting removes the rows, and {@link DatabaseHelper} re-derives every
 * ancestor total from what remains, so the Categories above the deleted node
 * are corrected automatically. Undo puts the rows back and the totals re-derive
 * upward again. Neither direction can leave a stale total, which was the
 * original defect.
 *
 * <p>KNOWN SCOPE LIMIT: this restores DATA perfectly. It does not restore UI
 * SELECTION state (e.g. "the ComboBox had skill X highlighted right before
 * this delete") - after an undo, DashboardController falls back to a sensible
 * default selection. Tracking exact selection history through every command
 * would add real complexity for very little practical benefit.
 */
public class DeleteSkillCommand implements Command {

    private final DatabaseHelper db;
    private final ObservableList<Skill> skillsList;
    private final Skill skill;
    private final List<Skill> subtreeSnapshot;
    private final Map<Integer, List<ProgressLog>> logsBySkillSnapshot = new LinkedHashMap<>();
    private final Map<Integer, List<Integer>> linkedNoteIdsBySkillSnapshot = new LinkedHashMap<>();

    public DeleteSkillCommand(DatabaseHelper db, ObservableList<Skill> skillsList, Skill skill) {
        this.db = db;
        this.skillsList = skillsList;
        this.skill = skill;

        // Parents BEFORE children. getSubtree() returns descendants
        // parents-first, and restore order must match, because restoring a
        // child while its parent row is missing would violate the very
        // foreign key that made the delete need a cascade in the first place.
        this.subtreeSnapshot = new ArrayList<>(db.getSubtree(skill.getId()));

        for (Skill node : subtreeSnapshot) {
            logsBySkillSnapshot.put(node.getId(), db.getLogsForSkill(node.getId()));
            List<Integer> noteIds = new ArrayList<>();
            for (CalendarNote note : db.getNotesForSkill(node.getId())) {
                noteIds.add(note.getId());
            }
            linkedNoteIdsBySkillSnapshot.put(node.getId(), noteIds);
        }
    }

    /** How many nodes this delete will remove, for the confirmation dialog. */
    public int subtreeSize() {
        return subtreeSnapshot.size();
    }

    /** Total sessions across the subtree - the real cost of this delete. */
    public int totalSessionCount() {
        int total = 0;
        for (List<ProgressLog> logs : logsBySkillSnapshot.values()) {
            total += logs.size();
        }
        return total;
    }

    private boolean executed = false;

    @Override
    public void execute() {
        if (!db.deleteSkillCascade(skill.getId())) {
            // The database refused. Do NOT touch the in-memory list - removing
            // the row here while the database still holds it is precisely the
            // v1 bug (UI and database silently diverge until the next
            // Refresh). Leaving the model alone keeps them consistent.
            System.err.println("[DeleteSkillCommand] Database refused to delete skill "
                    + skill.getId() + " (\"" + skill.getName()
                    + "\"); the in-memory list was left unchanged.");
            return;
        }
        for (Skill node : subtreeSnapshot) {
            skillsList.remove(node);
        }
        executed = true;
    }

    /**
     * Whether the database actually confirmed the delete.
     *
     * <p>DashboardController must consult this before telling the user
     * "Deleted X - press Ctrl+Z to undo". A command that quietly declined to act
     * while the status bar claimed success is precisely the class of
     * misleading UI this rewrite exists to remove, so the success message is
     * now gated on it.
     *
     * <p>Also read by {@link CommandManager}: a command that did nothing must
     * not be pushed onto the undo stack, or Ctrl+Z would "undo" a delete that
     * never happened and restore nodes that were never removed.
     */
    public boolean didDelete() {
        return executed;
    }

    @Override
    public void undo() {
        // Restore parents first, then each node's sessions, then re-link the
        // notes that pointed at them (ON DELETE SET NULL preserved the notes
        // themselves but dropped the link).
        for (Skill node : subtreeSnapshot) {
            db.restoreSkill(node);
        }
        for (Map.Entry<Integer, List<ProgressLog>> entry : logsBySkillSnapshot.entrySet()) {
            for (ProgressLog log : entry.getValue()) {
                db.restoreProgressLog(log); // same trick - keeps its original id too
            }
        }
        for (Map.Entry<Integer, List<Integer>> entry : linkedNoteIdsBySkillSnapshot.entrySet()) {
            for (int noteId : entry.getValue()) {
                db.relinkNoteToSkill(noteId, entry.getKey());
            }
        }
        for (Skill node : subtreeSnapshot) {
            if (!skillsList.contains(node)) {
                skillsList.add(node);
            }
        }
        // One final derive so the restored rows are reflected in every
        // ancestor total, including Categories above the deleted node that
        // were never themselves restored.
        db.recomputeAllPoints();
    }
}
