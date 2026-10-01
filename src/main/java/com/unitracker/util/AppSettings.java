package com.unitracker.util;

import com.unitracker.db.DatabaseHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A typed, in-memory cache over the {@code app_settings} key/value table
 * (v2.0).
 *
 * <h2>WHY THIS EXISTS</h2>
 * v1 read settings straight from SQLite on every access. That was invisible
 * almost everywhere, because a setting is read a handful of times per user
 * action. It was NOT invisible in one place: the Focus Timer's
 * {@code AnimationTimer} called {@code timerMinutes()} once per whole second
 * of every countdown, and that method performed a {@code SELECT} against the
 * database file. That is sixty needless file-backed queries per minute of
 * focus time, driven from the render loop, for a value that had not changed
 * since the user last opened the dropdown.
 *
 * <p>This class fixes that by reading once and caching. Writes go through this
 * class too, so the cache is updated in the same call and can never drift from
 * the table.
 *
 * <h2>CACHING AND CORRECTNESS</h2>
 * The cache is authoritative for the lifetime of the process, and the ONLY way
 * to change a setting is through {@link #set}, which updates both the table
 * and the cache together. That is what makes a stale cache impossible here in
 * the same way the derived-points model makes a stale total impossible: by not
 * leaving a second write path.
 *
 * <p>{@link #reload()} exists for the one case that genuinely needs it - a
 * settings file or database edited underneath a running process - and is not
 * called on any normal path.
 *
 * <h2>THREAD SAFETY</h2>
 * Reads happen on the JavaFX application thread, but the WebKit JavaScript
 * thread can also reach settings through CheckboxBridge. A
 * {@link ConcurrentHashMap} is therefore used rather than a plain HashMap, and
 * the DatabaseHelper access behind it is already synchronized.
 *
 * <h2>TYPING</h2>
 * Values are stored as TEXT, because that is what a hand-editable settings
 * table is. Every read here is therefore parsed defensively and falls back to
 * the supplied default rather than throwing - a corrupted value must never be
 * able to stop the application from starting.
 */
public final class AppSettings {

    // ---- Keys. Centralised so a typo is a compile error rather than a
    // ---- silently-defaulted setting that nobody ever notices. ----

    // Focus Timer
    public static final String KEY_TIMER_MINUTES = "timer.minutes";
    public static final String KEY_TIMER_DEFAULT_POINTS = "timer.defaultPoints";
    public static final String KEY_TIMER_POINTS_PREFIX = "timer.points.";
    public static final String KEY_POINTS_PER_LEVEL = "level.pointsPerLevel";

    // Audio
    public static final String KEY_MUTED = "sound.muted";

    // Sidebar pane expansion, one row per pane; the suffix is the fx:id.
    public static final String KEY_SIDEBAR_PREFIX = "sidebar.expanded.";

    /** v2.0 - master switch for every transition in the app. */
    public static final String KEY_ANIMATIONS_ENABLED = "ui.animationsEnabled";
    /** v2.0 - UI scale tier: AUTO | COMPACT | DEFAULT | LARGE | EXTRA_LARGE. */
    public static final String KEY_UI_SCALE = "ui.scale";
    /** v2.0 - whether the floating timer widget is open. */
    public static final String KEY_FLOATING_TIMER_OPEN = "ui.floatingTimerOpen";
    /** v2.0 - whether the session-complete toast offers Undo. */
    public static final String KEY_TIMER_TOAST = "ui.timerToast";
    /** v2.0 - id of the mastery-curve preset offered by default when creating
     *  a new skill. -1 means "no curve, use the plain target spinner". */
    public static final String KEY_DEFAULT_CURVE_ID = "presets.defaultCurveId";

    // Defaults, mirrored from DashboardController's old constants.
    public static final int DEFAULT_TIMER_MINUTES = 25;
    public static final double DEFAULT_TIMER_POINTS = 5.0;
    public static final double DEFAULT_POINTS_PER_LEVEL = 100.0;
    public static final boolean DEFAULT_ANIMATIONS_ENABLED = true;
    public static final String DEFAULT_UI_SCALE = UiScale.Scale.AUTO.name();

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private AppSettings() {
    }

    private static DatabaseHelper db() {
        return DatabaseHelper.getInstance();
    }

    /** Marks the cache as needing a full read. Called by the app at startup
     *  once the database exists, and by {@link #reload()}. */
    public static synchronized void load() {
        CACHE.clear();
        loaded = true;
    }

    /** Discards the cache and re-reads everything. For the rare case of the
     *  database being modified by something other than this class. */
    public static synchronized void reload() {
        CACHE.clear();
    }

    // ----------------------------------------------------------------
    //  Typed reads
    // ----------------------------------------------------------------

    /**
     * Raw string read.
     *
     * <p>A NON-NULL default is cached, so a setting the user has never changed
     * still costs one database read for the whole session rather than one per
     * access. That matters for {@link #uiScale()}, which the clock-font
     * listener calls on every pane resize.
     *
     * <p>A null default is deliberately NOT cached, because
     * {@link ConcurrentHashMap} forbids null values and attempting it throws a
     * NullPointerException from inside a typed getter - which is precisely
     * what happened the first time {@code getInt(key, default)} routed through
     * here with a null default. The typed accessors below sidestep the issue
     * by caching the RESOLVED default as text via {@link #fetch}.
     */
    public static String getString(String key, String defaultValue) {
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String stored = db().getSetting(key, null);
        if (stored == null && defaultValue == null) {
            return null;
        }
        String value = stored != null ? stored : defaultValue;
        CACHE.put(key, value);
        return value;
    }

    public static int getInt(String key, int defaultValue) {
        return parseInt(fetch(key, defaultValue), key, defaultValue);
    }

    public static double getDouble(String key, double defaultValue) {
        return parseDouble(fetch(key, defaultValue), key, defaultValue);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return parseBoolean(fetch(key, defaultValue), defaultValue);
    }

    /**
     * The single cache-miss path shared by the typed accessors.
     *
     * <p>On a miss it reads from the database, and if nothing is stored it
     * caches the caller's default RENDERED AS TEXT. That is what makes the
     * second read free on a fresh install, where most settings have never been
     * written - without it every typed access would be a database round trip,
     * which is the problem this whole class exists to solve.
     *
     * @return never null; either the stored value or the rendered default
     */
    private static String fetch(String key, Object defaultValue) {
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String stored = db().getSetting(key, null);
        if (stored != null) {
            CACHE.put(key, stored);
            return stored;
        }
        String rendered = String.valueOf(defaultValue);
        CACHE.put(key, rendered);
        return rendered;
    }

    private static int parseInt(String raw, String key, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException malformed) {
            System.err.println("[AppSettings] '" + key + "' is not a number ('" + raw
                    + "') - using default " + defaultValue);
            return defaultValue;
        }
    }

    private static double parseDouble(String raw, String key, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException malformed) {
            System.err.println("[AppSettings] '" + key + "' is not a number ('" + raw
                    + "') - using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    // ----------------------------------------------------------------
    //  Writes - always update the table and the cache together
    // ----------------------------------------------------------------

    public static void set(String key, String value) {
        String safe = value == null ? "" : value;
        CACHE.put(key, safe);
        db().setSetting(key, safe);
    }

    public static void set(String key, int value) {
        set(key, String.valueOf(value));
    }

    public static void set(String key, double value) {
        set(key, String.valueOf(value));
    }

    public static void set(String key, boolean value) {
        set(key, String.valueOf(value));
    }

    /** Writes only if the value differs, so a slider that fires on every drag
     *  tick does not hammer the database with identical writes. */
    public static void setIfChanged(String key, String value) {
        if (getString(key, "").equals(value == null ? "" : value)) {
            return;
        }
        set(key, value);
    }

    // ----------------------------------------------------------------
    //  Domain-specific accessors, so call sites read as intent
    // ----------------------------------------------------------------

    /** Currently selected timer length, clamped to something usable so a
     *  hand-edited value cannot produce a zero-length or week-long session. */
    public static int timerMinutes() {
        return clamp(getInt(KEY_TIMER_MINUTES, DEFAULT_TIMER_MINUTES), 1, 600);
    }

    /**
     * Points awarded for a completed session of {@code minutes}.
     *
     * <p>An explicit per-duration override wins; otherwise the reward scales
     * pro-rata from the configurable baseline, so a user who only ever sets
     * "5 points for 25 minutes" still gets a sensible 12 for an hour instead
     * of a flat 5.
     *
     * <p>Mirrored - and asserted - by SqlLogicCheck#checkPointsForDuration, so
     * the arithmetic here and the test cannot drift apart.
     */
    public static double pointsForDuration(int minutes) {
        double override = getDouble(KEY_TIMER_POINTS_PREFIX + minutes, -1);
        if (override >= 0) {
            return override;
        }
        double perDefaultBlock = getDouble(KEY_TIMER_DEFAULT_POINTS, DEFAULT_TIMER_POINTS);
        double scaled = perDefaultBlock * minutes / (double) DEFAULT_TIMER_MINUTES;
        return roundToHalf(scaled);
    }

    /** Nearest half point. Keeps the reward readable in the UI, where
     *  "4.375 points" is a number nobody wants to look at. */
    private static double roundToHalf(double value) {
        return Math.round(value * 2) / 2.0;
    }

    /** Points per level, floored at 1 so a hand-edited 0 cannot divide by
     *  zero when the level is computed. */
    public static double pointsPerLevel() {
        return Math.max(1, getDouble(KEY_POINTS_PER_LEVEL, DEFAULT_POINTS_PER_LEVEL));
    }

    public static boolean isMuted() {
        return getBoolean(KEY_MUTED, false);
    }

    /**
     * THE v2.0 SETTINGS SWITCH. Every transition in the app routes through
     * {@link Anim}, which becomes a no-op when this is false - so a user who
     * wants raw performance gets genuinely static UI in one place, without
     * each animation call site having to remember to check.
     */
    public static boolean animationsEnabled() {
        return getBoolean(KEY_ANIMATIONS_ENABLED, DEFAULT_ANIMATIONS_ENABLED);
    }

    public static UiScale.Scale uiScale() {
        String raw = getString(KEY_UI_SCALE, DEFAULT_UI_SCALE);
        try {
            return UiScale.Scale.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            System.err.println("[AppSettings] unknown UI scale '" + raw + "' - falling back to AUTO");
            return UiScale.Scale.AUTO;
        }
    }

    public static boolean floatingTimerWasOpen() {
        return getBoolean(KEY_FLOATING_TIMER_OPEN, false);
    }

    public static boolean timerToastEnabled() {
        return getBoolean(KEY_TIMER_TOAST, true);
    }

    /** Id of the mastery curve pre-selected when creating a new skill, or -1. */
    public static long defaultCurveId() {
        return getInt(KEY_DEFAULT_CURVE_ID, -1);
    }

    // ----------------------------------------------------------------
    //  Misc
    // ----------------------------------------------------------------

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Test/debug aid: how many settings are currently cached. */
    public static int cachedCount() {
        return CACHE.size();
    }

    /**
     * Drops a key from the cache without writing anything. Used by the
     * devcheck harnesses to force a re-read, and by the .utrack import path
     * after it replaces settings wholesale.
     */
    public static void invalidate(String key) {
        CACHE.remove(key);
    }

    /** Drops every cached key except the ones named. */
    public static void retainOnly(Set<String> keys) {
        CACHE.keySet().removeIf(k -> !keys.contains(k));
    }

    /** Guards against using the cache before the database exists. */
    static void markLoaded() {
        loaded = true;
    }

    static boolean isLoaded() {
        return loaded;
    }
}
