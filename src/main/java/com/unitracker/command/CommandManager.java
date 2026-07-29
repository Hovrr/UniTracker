package com.unitracker.command;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Classic two-stack undo/redo history (Command Pattern). Every mutation the
 * user makes through the UI - log a session, edit a skill, delete a skill -
 * is executed through here instead of being applied directly, which is what
 * lets Ctrl+Z / Ctrl+Shift+Z reverse/replay any of them uniformly.
 *
 * canUndoProperty()/canRedoProperty() are exposed so the toolbar's Undo/Redo
 * buttons can just bind their disableProperty() reactively instead of the
 * controller manually flipping them after every action.
 */
public class CommandManager {

    // Capped so a very long editing session can't grow this unboundedly -
    // 100 steps of history is far more than a PoC realistically needs.
    private static final int MAX_HISTORY = 100;

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    private final ReadOnlyBooleanWrapper canUndo = new ReadOnlyBooleanWrapper(this, "canUndo", false);
    private final ReadOnlyBooleanWrapper canRedo = new ReadOnlyBooleanWrapper(this, "canRedo", false);

    /** Runs the command, records it for undo, and clears the redo branch -
     *  exactly like every other editor's undo history (once you make a new
     *  change, the old "future" you could have redone into is gone). */
    public void execute(Command command) {
        command.execute();
        undoStack.push(command);
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
        redoStack.clear();
        syncProperties();
    }

    /** @return true if something was actually undone. */
    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        Command command = undoStack.pop();
        command.undo();
        redoStack.push(command);
        syncProperties();
        return true;
    }

    /** @return true if something was actually redone. */
    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        Command command = redoStack.pop();
        command.execute();
        undoStack.push(command);
        syncProperties();
        return true;
    }

    /** Wipes both stacks - used by "Refresh & Clear Cache". Note: with
     *  MAX_HISTORY already capping growth at 100 steps, this isn't fixing a
     *  genuine memory leak (there wasn't one) - it's giving the user an
     *  explicit, visible way to reset to a clean slate on demand. */
    public void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        syncProperties();
    }

    private void syncProperties() {
        canUndo.set(!undoStack.isEmpty());
        canRedo.set(!redoStack.isEmpty());
    }

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo.getReadOnlyProperty(); }
}
