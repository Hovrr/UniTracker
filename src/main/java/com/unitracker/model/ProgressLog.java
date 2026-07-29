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
 */
public class ProgressLog {

    private final IntegerProperty id = new SimpleIntegerProperty(this, "id", -1);
    private final IntegerProperty skillId = new SimpleIntegerProperty(this, "skillId", -1);
    private final ObjectProperty<LocalDate> logDate = new SimpleObjectProperty<>(this, "logDate", LocalDate.now());
    private final IntegerProperty minutesSpent = new SimpleIntegerProperty(this, "minutesSpent", 0);
    private final DoubleProperty pointsEarned = new SimpleDoubleProperty(this, "pointsEarned", 0.0);
    private final StringProperty note = new SimpleStringProperty(this, "note", "");

    public ProgressLog() {
        // No-arg constructor required by DatabaseHelper row-mapping.
    }

    public ProgressLog(int skillId, LocalDate logDate, int minutesSpent, double pointsEarned) {
        this.skillId.set(skillId);
        this.logDate.set(logDate);
        this.minutesSpent.set(minutesSpent);
        this.pointsEarned.set(pointsEarned);
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
}
