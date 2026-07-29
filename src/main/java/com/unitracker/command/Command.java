package com.unitracker.command;

/**
 * A single reversible user action (Command Pattern). Every action that
 * should be undoable - logging a session, editing a skill, deleting a
 * skill - is wrapped in a Command instead of mutating state directly, so
 * {@link CommandManager} can push it onto a history stack and reverse it
 * later with {@link #undo()}.
 */
public interface Command {

    /** Performs the action. Called once when the user first triggers it,
     *  and again on redo(). */
    void execute();

    /** Reverses exactly what {@link #execute()} did. */
    void undo();
}
