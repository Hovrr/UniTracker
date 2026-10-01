package com.unitracker.model;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDate;

/**
 * A single logged study/practice session for a Skill - the atomic unit
 * behind "Real-Time Multi-Skill Tracking" and the Curve graph view.
 *
 * <p>v2.0: rows now carry WHERE they came from, in {@link #source}, and an
 * optional back-link to the {@code timer_sessions} row that produced them.
 * This is what lets the app (a) show a session history that distinguishes a
 * 25-minute focus block from a hand-typed entry, and (b) implement the
 * v2.0 requirement that a completed Focus Timer routes its points through
 * the real hierarchy - the timer writes a TIMER row rather than bumping a
 * counter, so the routing is the ordinary rollup path and is undoable and
 * auditable like any other log.
 */
public class ProgressLog {

    /** Typed into the Log Session form by the user. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** Produced by a completed Focus Timer session. */
    public static final String SOURCE_TIMER = "TIMER";
    /**
     * A manual correction of a skill's total, expressed as the difference
     * between the requested and derived value. Under the v2.0 derived-points
     * model this is a REAL row, not an overwrite, so it rolls up to the
     * ancestors and can be undone - which is precisely what the old
     * hand-editable "Current Points" field could not do.
     */
    public static final String SOURCE_ADJUSTMENT = "ADJUSTMENT";

    private final IntegerProperty id = new SimpleIntegerProperty(this, "id", -1);
    private final IntegerProperty skillId = new SimpleIntegerProperty(this, "skillId", -1);
    private final ObjectProperty<LocalDate> logDate = new SimpleObjectProperty<>(this, "logDate", LocalDate.now());
    private final IntegerProperty minutesSpent = new SimpleIntegerProperty(this, "minutesSpent", 0);
    private final DoubleProperty pointsEarned = new SimpleDoubleProperty(this, "pointsEarned", 0.0);
    private final StringProperty note = new SimpleStringProperty(this, "note", "");
    private final StringProperty source = new SimpleStringProperty(this, "source", SOURCE_MANUAL);
    private final IntegerProperty timerSessionId = new SimpleIntegerProperty(this, "timerSessionId", -1);

    public ProgressLog() {
        // No-arg constructor required by DatabaseHelper row-mapping.
    }

    public ProgressLog(int skillId, LocalDate logDate, int minutesSpent, double pointsEarned) {
        this.skillId.set(skillId);
        this.logDate.set(logDate);
        this.minutesSpent.set(minutesSpent);
        this.pointsEarned.set(pointsEarned);
    }

    /** Convenience for the timer path, which always produces a TIMER row. */
    public ProgressLog(int skillId, LocalDate logDate, int minutesSpent,
                       double pointsEarned, String source, int timerSessionId) {
        this(skillId, logDate, minutesSpent, pointsEarned);
        this.source.set(source);
        this.timerSessionId.set(timerSessionId);
    }

    /** True for a correction row, which the UI should label differently from
     *  a real study session. */
    public boolean isAdjustment() {
        return SOURCE_ADJUSTMENT.equals(getSource());
    }

    // ---------------------------------------------------------------
    // Standard JavaFX bean accessors
    // ---------------------------------------------------------------

    public int getId() { return id.get(); }
    public void setId(int value) { id.set(value); }
    public IntegerProperty idProperty() { return id; }

    public int getSkillId() { return skillId.get(); }
    public void setSkillId(int value) { skillId.set(value); }
    public IntegerProperty skillIdProperty() { return skillId; }

    public LocalDate getLogDate() { return logDate.get(); }
    public void setLogDate(LocalDate value) { logDate.set(value); }
    public ObjectProperty<LocalDate> logDateProperty() { return logDate; }

    public int getMinutesSpent() { return minutesSpent.get(); }
    public void setMinutesSpent(int value) { minutesSpent.set(value); }
    public IntegerProperty minutesSpentProperty() { return minutesSpent; }

    public double getPointsEarned() { return pointsEarned.get(); }
    public void setPointsEarned(double value) { pointsEarned.set(value); }
    public DoubleProperty pointsEarnedProperty() { return pointsEarned; }

    public String getNote() { return note.get(); }
    public void setNote(String value) { note.set(value); }
    public StringProperty noteProperty() { return note; }

    public String getSource() { return source.get(); }
    public void setSource(String value) { source.set(value == null ? SOURCE_MANUAL : value); }
    public StringProperty sourceProperty() { return source; }

    /** -1 when the log did not come from a Focus Timer. */
    public int getTimerSessionId() { return timerSessionId.get(); }
    public void setTimerSessionId(int value) { timerSessionId.set(value); }
    public IntegerProperty timerSessionIdProperty() { return timerSessionId; }
}
