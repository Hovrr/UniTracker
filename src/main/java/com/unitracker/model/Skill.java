package com.unitracker.model;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single node in the skill hierarchy: Category -> Skill ->
 * Subskill 1 -> Subskill 2 -> ... (unlimited depth).
 *
 * REFACTOR NOTE (Phase 1 - Model/Database): this used to be a flat entity
 * with a free-text "category" field. It is now a self-referencing tree
 * node: every level - Category, Skill, Subskill 1, Subskill 2, and so on -
 * is the SAME Skill entity, distinguished only by {@code parentId}. There
 * is no separate "type"/"level" column; type is purely a function of depth
 * in the tree (see getDepth()), which is what makes the hierarchy unlimited
 * instead of capped at a fixed number of levels.
 *
 * DESIGN NOTE (unchanged from before the refactor): this stays a mutable
 * JavaFX-property-backed class rather than a Java record, for the same
 * reason as always - JavaFX controls need a mutable, observable Property to
 * bind to so the UI updates itself instantly, with no manual refresh code.
 */
public class Skill {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STALLED = "STALLED";

    public static final String STRUCTURE_I = "I_SHAPED";
    public static final String STRUCTURE_COMB = "COMB_SHAPED";

    /** Sentinel meaning "root / Category level, no parent" - mirrors the
     *  existing convention where id defaults to -1 meaning "not yet
     *  persisted", so no nullable Integer boxing is needed anywhere. */
    public static final int NO_PARENT = -1;

    private final IntegerProperty id = new SimpleIntegerProperty(this, "id", -1);
    private final IntegerProperty parentId = new SimpleIntegerProperty(this, "parentId", NO_PARENT);
    private final StringProperty name = new SimpleStringProperty(this, "name", "");
    private final StringProperty structureType = new SimpleStringProperty(this, "structureType", STRUCTURE_I);
    private final StringProperty status = new SimpleStringProperty(this, "status", STATUS_ACTIVE);
    private final StringProperty colorHex = new SimpleStringProperty(this, "colorHex", "#A8EB12");
    private final DoubleProperty targetPoints = new SimpleDoubleProperty(this, "targetPoints", 100.0);
    private final DoubleProperty currentPoints = new SimpleDoubleProperty(this, "currentPoints", 0.0);

    /** Sibling sort position (mirrors the `sort_order` column). Plain field,
     *  not a Property - nothing in the UI binds to "my own list position",
     *  it's persistence bookkeeping written by DatabaseHelper. */
    private int sortOrder;

    // ---- Transient hierarchy links. Populated ONLY by
    //      DatabaseHelper#getSkillTree() once rows are loaded; never
    //      persisted as columns of their own. ----
    private Skill parent;
    private final ObservableList<Skill> children = FXCollections.observableArrayList();

    /** 0.0-1.0 completion ratio. Created ONCE and reused, so every listener
     *  attached to it (e.g. ProgressBar.progressProperty().bind(...)) keeps
     *  working correctly for the lifetime of this Skill instance. */
    private final DoubleBinding progress = Bindings.createDoubleBinding(
            () -> targetPoints.get() <= 0 ? 0.0 : Math.min(1.0, currentPoints.get() / targetPoints.get()),
            currentPoints, targetPoints
    );

    public Skill() {
        // No-arg constructor required by DatabaseHelper row-mapping.
    }

    /** Pass {@link #NO_PARENT} to create a new root/Category node. */
    public Skill(String name, int parentId, double targetPoints) {
        this.name.set(name);
        this.parentId.set(parentId);
        this.targetPoints.set(targetPoints);
    }

    public DoubleBinding progressProperty() {
        return progress;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(getStatus());
    }

    // ---------------------------------------------------------------
    // Standard JavaFX bean accessors (getter / setter / xxxProperty)
    // ---------------------------------------------------------------

    public int getId() { return id.get(); }
    public void setId(int value) { id.set(value); }
    public IntegerProperty idProperty() { return id; }

    public int getParentId() { return parentId.get(); }
    public void setParentId(int value) { parentId.set(value); }
    public IntegerProperty parentIdProperty() { return parentId; }

    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value); }
    public StringProperty nameProperty() { return name; }

    public String getStructureType() { return structureType.get(); }
    public void setStructureType(String value) { structureType.set(value); }
    public StringProperty structureTypeProperty() { return structureType; }

    public String getStatus() { return status.get(); }
    public void setStatus(String value) { status.set(value); }
    public StringProperty statusProperty() { return status; }

    public String getColorHex() { return colorHex.get(); }
    public void setColorHex(String value) { colorHex.set(value); }
    public StringProperty colorHexProperty() { return colorHex; }

    public double getTargetPoints() { return targetPoints.get(); }
    public void setTargetPoints(double value) { targetPoints.set(value); }
    public DoubleProperty targetPointsProperty() { return targetPoints; }

    public double getCurrentPoints() { return currentPoints.get(); }
    public void setCurrentPoints(double value) { currentPoints.set(value); }
    public DoubleProperty currentPointsProperty() { return currentPoints; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int value) { this.sortOrder = value; }

    // ---------------------------------------------------------------
    // Hierarchy: transient parent/children links + helpers
    // ---------------------------------------------------------------

    public Skill getParent() { return parent; }

    public ObservableList<Skill> getChildren() { return children; }

    /** Links {@code child} under this node in memory - used by
     *  DatabaseHelper#getSkillTree() to assemble flat rows into a tree -
     *  and keeps child's parentId in sync with this node's id at the
     *  same time. */
    public void addChild(Skill child) {
        child.parent = this;
        child.setParentId(this.getId());
        children.add(child);
    }

    public boolean isRoot() { return getParentId() == NO_PARENT; }

    public boolean isLeaf() { return children.isEmpty(); }

    /** 0 = Category, 1 = Skill, 2 = Subskill 1, 3 = Subskill 2, ... (unlimited). */
    public int getDepth() {
        int depth = 0;
        Skill p = parent;
        while (p != null) {
            depth++;
            p = p.parent;
        }
        return depth;
    }

    /** e.g. "Category", "Skill", "Subskill 1" - exactly what depthLevelComboBox
     *  (Phase 2) will list. */
    public static String labelForDepth(int depth) {
        if (depth <= 0) return "Category";
        if (depth == 1) return "Skill";
        return "Subskill " + (depth - 1);
    }

    // ---------------------------------------------------------------
    // Static tree utilities - for the depthLevelComboBox filter (Phase 2)
    // and anything that needs a plain flat list from a forest of roots.
    // ---------------------------------------------------------------

    public static List<Skill> flatten(List<Skill> roots) {
        List<Skill> out = new ArrayList<>();
        for (Skill root : roots) flattenInto(root, out);
        return out;
    }

    private static void flattenInto(Skill node, List<Skill> out) {
        out.add(node);
        for (Skill child : node.children) flattenInto(child, out);
    }

    public static List<Skill> collectAtDepth(List<Skill> roots, int depth) {
        List<Skill> out = new ArrayList<>();
        for (Skill n : flatten(roots)) if (n.getDepth() == depth) out.add(n);
        return out;
    }

    public static int maxDepth(List<Skill> roots) {
        int max = 0;
        for (Skill n : flatten(roots)) max = Math.max(max, n.getDepth());
        return max;
    }

    @Override
    public String toString() {
        // Drives the display text inside skillComboBox (ComboBox<Skill>)
        return getName();
    }
}
