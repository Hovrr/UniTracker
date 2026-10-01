package com.unitracker.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable progress goal definition (v2.0).
 *
 * <p>WHY PRESETS EXIST. Before v2.0, creating a skill meant inventing a
 * target number from nothing: the Add Skill dialog offered a bare
 * "Target Points" spinner defaulting to 100, and a "20-hour basic
 * proficiency" goal or a "10,000 hours" goal had to be worked out by hand
 * from the user's own points-per-hour setting. That is a small amount of
 * arithmetic standing between the user and a well-formed goal, and it is
 * arithmetic they have to redo for every single skill. A preset collapses it
 * to a choice.
 *
 * <p>TWO KINDS, ONE TABLE, DISCRIMINATED BY {@link #kind}:
 * <ul>
 *   <li>{@link #KIND_TIME} - "1 hour = X points". A rate card. Fields used:
 *       {@code minutes}, {@code points}. One row per duration the user wants
 *       to price. This is what the Focus Timer reads to decide what a session
 *       is worth, so a user can set "30 min = 8 pts" once instead of
 *       hand-tuning the per-duration overrides every time they use the
 *       timer.</li>
 *   <li>{@link #KIND_CURVE} - a mastery curve. Fields used:
 *       {@code targetPoints}, {@code curveRatio}, {@code milestonesJson}.
 *       Sets a whole skill's target and its level ladder at once.</li>
 * </ul>
 *
 * <p>WHY THE CURVE HAS A RATIO. {@code curveRatio} is the fraction of the
 * final target that a milestone of "basic proficiency" corresponds to. It is
 * stored as a ratio rather than baked into each milestone's absolute points
 * precisely so the same curve can be applied to a 200-point beginner skill
 * and a 200,000-point mastery goal - the ladder keeps its shape at any scale.
 * See {@link #buildMilestones(double)} for how the absolute thresholds are
 * derived from it.
 *
 * <p>WHY milestones_json IS TEXT. The milestone list is always read and
 * written as a whole with its owning preset and never queried across presets,
 * so a normalised child table would add a join to every read to buy nothing.
 * It is parsed by {@link #parseMilestones(String)}, which is defensive
 * enough to return an empty list rather than throw on malformed input - a
 * hand-edited or truncated value must not be able to stop a skill from being
 * created.
 *
 * <p>Like {@link Skill}, this is a mutable property-backed bean rather than a
 * record, because the Settings dialog binds its fields directly to these
 * properties for live validation.
 */
public class ProgressPreset {

    /** Rate card: "this many minutes is worth this many points". */
    public static final String KIND_TIME = "TIME";
    /** Mastery curve: a target plus an ordered ladder of level milestones. */
    public static final String KIND_CURVE = "CURVE";

    private final LongProperty id = new SimpleLongProperty(this, "id", -1);
    private final StringProperty kind = new SimpleStringProperty(this, "kind", KIND_TIME);
    private final StringProperty name = new SimpleStringProperty(this, "name", "");
    private final StringProperty description = new SimpleStringProperty(this, "description", "");
    private final IntegerProperty minutes = new SimpleIntegerProperty(this, "minutes", 25);
    private final DoubleProperty points = new SimpleDoubleProperty(this, "points", 5.0);
    private final DoubleProperty targetPoints = new SimpleDoubleProperty(this, "targetPoints", 100.0);
    private final DoubleProperty curveRatio = new SimpleDoubleProperty(this, "curveRatio", 0.2);
    private final BooleanProperty builtIn = new SimpleBooleanProperty(this, "builtIn", false);
    private final IntegerProperty sortOrder = new SimpleIntegerProperty(this, "sortOrder", 0);

    /** Raw JSON as stored/loaded; parsed lazily via {@link #getMilestones()}. */
    private String milestonesJson = "";

    public ProgressPreset() {
        // No-arg constructor required by DatabaseHelper row-mapping.
    }

    public ProgressPreset(String kind, String name, String description) {
        this.kind.set(kind);
        this.name.set(name);
        this.description.set(description);
    }

    // ---------------------------------------------------------------
    // Bean accessors
    // ---------------------------------------------------------------

    public long getId() { return id.get(); }
    public void setId(long value) { id.set(value); }
    public LongProperty idProperty() { return id; }

    public String getKind() { return kind.get(); }
    public void setKind(String value) { kind.set(value); }
    public StringProperty kindProperty() { return kind; }

    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value); }
    public StringProperty nameProperty() { return name; }

    public String getDescription() { return description.get(); }
    public void setDescription(String value) { description.set(value); }
    public StringProperty descriptionProperty() { return description; }

    /** Minutes for a TIME preset; null for a CURVE. */
    public Integer getMinutes() { return minutes.get(); }
    public void setMinutes(Integer value) { minutes.set(value == null ? null : value.intValue()); }
    public IntegerProperty minutesProperty() { return minutes; }

    /** Points for a TIME preset; null for a CURVE. */
    public Double getPoints() { return points.get(); }
    public void setPoints(Double value) { points.set(value == null ? null : value.doubleValue()); }
    public DoubleProperty pointsProperty() { return points; }

    /** Target total for a CURVE preset; null for a TIME. */
    public Double getTargetPoints() { return targetPoints.get(); }
    public void setTargetPoints(Double value) { targetPoints.set(value == null ? null : value.doubleValue()); }
    public DoubleProperty targetPointsProperty() { return targetPoints; }

    /** Fraction of the target corresponding to the first milestone. */
    public double getCurveRatio() { return curveRatio.get(); }
    public void setCurveRatio(double value) { curveRatio.set(value); }
    public DoubleProperty curveRatioProperty() { return curveRatio; }

    public boolean isBuiltIn() { return builtIn.get(); }
    public void setBuiltIn(boolean value) { builtIn.set(value); }
    public BooleanProperty builtInProperty() { return builtIn; }

    public int getSortOrder() { return sortOrder.get(); }
    public void setSortOrder(int value) { sortOrder.set(value); }
    public IntegerProperty sortOrderProperty() { return sortOrder; }

    public String milestonesJson() { return milestonesJson; }
    public void setMilestonesJson(String value) { milestonesJson = value == null ? "" : value; }

    // ---------------------------------------------------------------
    // Milestones
    // ---------------------------------------------------------------

    /** A single rung of the ladder: a name and the point threshold for it. */
    public record Milestone(String label, double threshold) {
    }

    /**
     * The default ladder SHAPE, as fractions of a skill's final target.
     *
     * <p>These ratios are not arbitrary. They follow the shape of
     * deliberate-practice research (the 10,000-hour rule) and the
     * conventional language-learning proficiency scale, mapped onto a
     * progress bar:
     * <ul>
     *   <li>0.10 - initial contact: enough to know whether you want to continue</li>
     *   <li>0.20 - "20-hour basic proficiency": the widely cited threshold for
     *       functional ability in a second language or a new instrument, and
     *       the first rung most people abandon</li>
     *   <li>0.50 - working competence: usable under real conditions</li>
     *   <li>0.75 - advanced: the point past which further gains get expensive</li>
     *   <li>1.00 - mastery: the target itself</li>
     * </ul>
     *
     * <p>Expressed as ratios so they scale to any target - a 500-point skill
     * and a 100,000-hour skill get the same ladder shape at their own scale.
     */
    public static final double[] DEFAULT_MILESTONE_RATIOS = {0.10, 0.20, 0.50, 0.75, 1.00};

    private static final String[] DEFAULT_MILESTONE_LABELS = {
            "Initial Contact",
            "Basic Proficiency",
            "Working Competence",
            "Advanced",
            "Mastery"
    };

    /**
     * Turns the ratio ladder into absolute point thresholds for a concrete
     * target, and labels each rung.
     *
     * <p>Each threshold is ROUNDED to a whole point. A milestone at exactly
     * 37.5 points can never be "reached" by a session that awards half
     * points, and a milestone that can never be crossed is a permanently
     * unticked box - visibly broken. Whole points sidestep the whole
     * floating-point comparison problem.
     *
     * <p>Thresholds are forced strictly increasing. If a target is small
     * enough that two ratios round to the same number (a 5-point target makes
     * 0.10 and 0.20 both round to 0 or 1), the later rung is nudged up by
     * one point. Two milestones sharing a threshold would otherwise produce
     * duplicate rows that the NOT EXISTS guard in recordMilestonesCrossed
     * would treat as a conflict on the wrong key.
     */
    public List<Milestone> buildMilestones(double target) {
        List<Milestone> out = new ArrayList<>();
        double safeTarget = Math.max(1.0, target);
        double previous = -1;
        for (int i = 0; i < DEFAULT_MILESTONE_RATIOS.length; i++) {
            double threshold = Math.round(safeTarget * DEFAULT_MILESTONE_RATIOS[i]);
            if (threshold <= previous) threshold = previous + 1;
            if (threshold > safeTarget) {
                // Past the target there is nothing to reach; stop rather than
                // inventing a milestone the skill can never satisfy.
                break;
            }
            out.add(new Milestone(DEFAULT_MILESTONE_LABELS[i], threshold));
            previous = threshold;
        }
        return out;
    }

    /** Serialises the ladder to the compact form stored in milestones_json. */
    public String serializeMilestones(List<Milestone> milestones) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < milestones.size(); i++) {
            Milestone m = milestones.get(i);
            if (i > 0) sb.append(',');
            // \u001f is the ASCII unit separator: it cannot appear in a label a
            // human would type, so it is a safe field delimiter here, and
            // unlike a comma or a semicolon it needs no escaping logic.
            sb.append('"').append(escape(m.label())).append('\u001f')
                    .append(m.threshold()).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escape(String raw) {
        String safe = raw == null ? "" : raw;
        return safe.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Parses the stored ladder back out.
     *
     * <p>TOTAL DEFENSIVENESS IS DELIBERATE. This is the one parser in the app
     * that reads a hand-editable blob, and it is reached from "create a new
     * skill". A malformed value must degrade to an empty ladder - the user
     * still gets a working skill with a target - rather than propagate an
     * exception out of the Add Skill dialog. Every failure path returns an
     * empty list instead of throwing.
     */
    public static List<Milestone> parseMilestones(String json) {
        List<Milestone> out = new ArrayList<>();
        if (json == null) return out;
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return out;
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) return out;
        for (String entry : splitTopLevel(body)) {
            String field = entry.trim();
            if (field.length() < 2 || field.charAt(0) != '"' || field.charAt(field.length() - 1) != '"') {
                continue; // skip a malformed entry, keep the rest
            }
            String inner = field.substring(1, field.length() - 1);
            int sep = inner.indexOf('\u001f');
            if (sep <= 0) continue;
            String label = inner.substring(0, sep).replace("\\\"", "\"").replace("\\\\", "\\");
            try {
                out.add(new Milestone(label, Double.parseDouble(inner.substring(sep + 1).trim())));
            } catch (NumberFormatException malformed) {
                // A non-numeric threshold is a corrupt row, not a reason to
                // fail the whole skill creation that triggered this read.
                continue;
            }
        }
        return out;
    }

    /**
     * Splits the ladder body on the commas BETWEEN entries, ignoring commas
     * inside a quoted label.
     *
     * <p>{@code String.split(",")} cannot be used here. A label is free text,
     * so "R&amp;D, phase one" is a perfectly ordinary thing for a user to type,
     * and a naive split cuts that entry in half: neither half starts and ends
     * with a quote, so both are silently dropped and the milestone disappears.
     * Worse, it disappears quietly - the ladder simply comes back shorter, which
     * reads as the app losing the user's data rather than as a parse error.
     *
     * <p>So the split is done by hand, tracking whether the cursor is inside a
     * quoted label and whether the previous character was a backslash. Only a
     * comma encountered outside quotes is a separator.
     */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    /** Lazily-parsed ladder for this preset. */
    public List<Milestone> getMilestones() {
        return parseMilestones(milestonesJson);
    }

    // ---------------------------------------------------------------
    // Built-in catalogue
    // ---------------------------------------------------------------

    /**
     * The presets seeded into a fresh database.
     *
     * <p>All flagged built-in, which {@code deletePreset} refuses to remove.
     * The point of seeding them is that the common goals are already correct,
     * so the user picks rather than computes - but the numbers are all
     * editable, because "10,000 hours" and "20 hours to basic proficiency" are
     * conventions with wide error bars, not laws, and a user with a different
     * definition of mastery should be able to say so.
     */
    public static List<ProgressPreset> builtIns() {
        List<ProgressPreset> out = new ArrayList<>();

        // --- Rate cards: 1 hour = X points ---
        out.add(timePreset("Steady Learner", "1 hour = 20 points", 60, 20.0,
                "A balanced default: rewards long uninterrupted blocks and scales evenly."));
        out.add(timePreset("Marathon", "1 hour = 50 points", 60, 50.0,
                "Heavy weighting for deep-work sessions over quick reviews."));
        out.add(timePreset("Sprinter", "1 hour = 10 points", 60, 10.0,
                "Light weighting - suits frequent short sessions and daily review habits."));
        out.add(timePreset("Pomodoro Standard", "25 min = 5 points", 25, 5.0,
                "The classic Pomodoro rate. Useful as a baseline to compare other presets against."));

        // --- Mastery curves ---
        out.add(curvePreset("20-Hour Basic Proficiency",
                "The widely cited threshold for functional ability in a new language or instrument.",
                400.0, "The first rung most people do not reach. Set this and the whole ladder follows."));

        out.add(curvePreset("10,000-Hour Rule",
                "The deliberate-practice figure popularized by Anders Ericsson.",
                20000.0, "Deliberately daunting - the point is that the intermediate rungs are the work."));

        out.add(curvePreset("1,000-Hour Working Competence",
                "A realistic ceiling for independent, professional-level ability.",
                2000.0, "Shorter than the 10,000-hour figure and reachable on a part-time schedule."));

        return out;
    }

    private static ProgressPreset timePreset(String name, String description, int minutes, double points, String detail) {
        ProgressPreset p = new ProgressPreset(KIND_TIME, name, description + " " + detail);
        p.setMinutes(minutes);
        p.setPoints(points);
        p.setBuiltIn(true);
        return p;
    }

    private static ProgressPreset curvePreset(String name, String description, double target, String detail) {
        ProgressPreset p = new ProgressPreset(KIND_CURVE, name, description + " " + detail);
        p.setTargetPoints(target);
        p.setCurveRatio(DEFAULT_MILESTONE_RATIOS[1]); // ladder starts at "Basic Proficiency"
        p.setMilestonesJson(p.serializeMilestones(p.buildMilestones(target)));
        p.setBuiltIn(true);
        return p;
    }

    /**
     * Human summary for a ComboBox cell: "25 min = 5 pts" or
     * "400 pts, 5 milestones". Compact enough for a dropdown row.
     *
     * <p>This string is user-visible in the preset dropdown, so the hour unit
     * is pluralised properly: "1 hr = 20 pts" but "2 hrs = 40 pts". The
     * previous version appended a bare "hr" to the hour count, which rendered
     * "2 hr = 40 pts" for every preset of two hours or more.
     */
    public String summary() {
        if (KIND_CURVE.equals(getKind())) {
            double target = getTargetPoints() == null ? 0 : getTargetPoints();
            return Math.round(target) + " pts - " + getMilestones().size() + " milestones";
        }
        int mins = getMinutes() == null ? 0 : getMinutes();
        double pts = getPoints() == null ? 0 : getPoints();
        String duration;
        if (mins >= 60) {
            int hours = mins / 60;
            int remainder = mins % 60;
            duration = hours + " hr" + (hours == 1 ? "" : "s")
                    + (remainder == 0 ? "" : " " + remainder + " min");
        } else {
            duration = mins + " min";
        }
        return duration + " = " + trimPoints(pts) + " pts";
    }

    /** Points without a trailing ".0", so "5 pts" not "5.0 pts". */
    public static String trimPoints(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.valueOf(Math.round(value * 100) / 100.0);
    }

    @Override
    public String toString() {
        return getName();
    }
}
