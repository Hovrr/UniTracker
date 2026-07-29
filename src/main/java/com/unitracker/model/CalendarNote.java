package com.unitracker.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDate;

/**
 * A Markdown-capable sticky note attached to a specific calendar date and
 * optionally linked to a Skill. Supports GitHub-style to-do checklists via
 * standard Markdown syntax, e.g. "- [ ] finish chapter 3".
 */
public class CalendarNote {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STALLED = "STALLED";

    private final IntegerProperty id = new SimpleIntegerProperty(this, "id", -1);
    /** Nullable - a note doesn't have to belong to a specific skill. */
    private final ObjectProperty<Integer> skillId = new SimpleObjectProperty<>(this, "skillId", null);
    private final ObjectProperty<LocalDate> noteDate = new SimpleObjectProperty<>(this, "noteDate", LocalDate.now());
    private final StringProperty title = new SimpleStringProperty(this, "title", "");
    private final StringProperty contentMarkdown = new SimpleStringProperty(this, "contentMarkdown", "");
    private final StringProperty colorHex = new SimpleStringProperty(this, "colorHex", "#414F6C");
    private final StringProperty status = new SimpleStringProperty(this, "status", STATUS_ACTIVE);
    private final BooleanProperty completed = new SimpleBooleanProperty(this, "completed", false);

    public CalendarNote() {
        // No-arg constructor required by DatabaseHelper row-mapping.
    }

    public CalendarNote(LocalDate noteDate, String title, String contentMarkdown) {
        this.noteDate.set(noteDate);
        this.title.set(title);
        this.contentMarkdown.set(contentMarkdown);
    }

    // ---------------------------------------------------------------
    // Standard JavaFX bean accessors
    // ---------------------------------------------------------------

    public int getId() { return id.get(); }
    public void setId(int value) { id.set(value); }
    public IntegerProperty idProperty() { return id; }

    public Integer getSkillId() { return skillId.get(); }
    public void setSkillId(Integer value) { skillId.set(value); }
    public ObjectProperty<Integer> skillIdProperty() { return skillId; }

    public LocalDate getNoteDate() { return noteDate.get(); }
    public void setNoteDate(LocalDate value) { noteDate.set(value); }
    public ObjectProperty<LocalDate> noteDateProperty() { return noteDate; }

    public String getTitle() { return title.get(); }
    public void setTitle(String value) { title.set(value); }
    public StringProperty titleProperty() { return title; }

    public String getContentMarkdown() { return contentMarkdown.get(); }
    public void setContentMarkdown(String value) { contentMarkdown.set(value); }
    public StringProperty contentMarkdownProperty() { return contentMarkdown; }

    public String getColorHex() { return colorHex.get(); }
    public void setColorHex(String value) { colorHex.set(value); }
    public StringProperty colorHexProperty() { return colorHex; }

    public String getStatus() { return status.get(); }
    public void setStatus(String value) { status.set(value); }
    public StringProperty statusProperty() { return status; }

    public boolean isCompleted() { return completed.get(); }
    public void setCompleted(boolean value) { completed.set(value); }
    public BooleanProperty completedProperty() { return completed; }
}
