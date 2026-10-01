package com.unitracker.db;

import com.unitracker.model.CalendarNote;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.ProgressPreset;
import com.unitracker.model.Skill;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central data-access layer for Uni Tracker's SQLite database.
 *
 * WHY SQLITE: the PRD requires a fully offline desktop app. SQLite ships as
 * a single embedded file with no server process, so the app has zero
 * external dependencies at runtime.
 *
 * WHY A SINGLETON: a small desktop PoC like this only ever needs one
 * connection to one local file.
 *
 * THREAD SAFETY (v2.0): every public method is {@code synchronized}. The
 * single {@link Connection} is shared, and a JDBC Connection is explicitly
 * NOT thread-safe. DashboardController#CheckboxBridge#toggle used to write
 * from the WebKit JavaScript thread while the FX thread was querying, which
 * is a genuine data race that surfaces as intermittent "database is locked"
 * or lost writes. Synchronizing the whole public surface is cheap here (a
 * desktop app does a handful of queries per user action) and makes the
 * shared connection safe by construction rather than by convention.
 * The methods call each other freely; that is fine because Java monitors
 * are reentrant.
 *
 * ======================== SINGLE SOURCE OF TRUTH ========================
 * v2.0 CHANGED THE POINT MODEL. {@code progress_logs} is now the ONLY
 * authoritative record of earned points. {@code skills.current_points} is
 * a DERIVED CACHE, rebuilt from the logs by {@link #recomputeAllPoints()}
 * after every mutation.
 *
 * WHY: the v1 code kept {@code current_points} as a hand-maintained
 * counter mutated by relative deltas. That made three separate permanent
 * desync bugs possible, all now impossible by construction:
 *
 *   1. Deleting a subskill removed its {@code progress_logs} rows (FK
 *      ON DELETE CASCADE) but never decremented the ancestors, so a
 *      Category stayed inflated forever.
 *   2. {@link #updateSkill} did not write {@code parent_id} at all, so
 *      re-parenting a skill changed only the in-memory model. Rollups
 *      then followed the stale DB chain, and the change reverted on
 *      restart.
 *   3. "Edit Skill" let the user type Current Points directly, writing an
 *      absolute value with no backing log row, so the total silently
 *      stopped matching {@code SUM(points_earned)}.
 *
 * A relative-delta rollup cannot be made safe while the target of the
 * delta is an independently editable cache. Deriving it instead means the
 * invariant "a node's points equal the sum of every log in its subtree" is
 * not a convention somebody has to remember - it is what the code does.
 * The cost is one full recompute per mutation, which is a single indexed
 * aggregate over a few thousand rows: sub-millisecond, and it only runs on
 * an explicit user action, never per frame.
 *
 * A manual "Current Points" correction is still supported, but it is now
 * written as a real {@code progress_logs} row with
 * {@code source='adjustment'} rather than as a bare overwrite - so it
 * shows up in history, is undoable, and rolls up like everything else.
 *
 * SCHEMA (v2.0): seven tables.
 *   skills           - one row per node in the skill hierarchy (Category,
 *                      Skill, Subskill 1, ...), self-referencing via
 *                      parent_id. NULL parent_id = root/Category.
 *                      current_points is DERIVED - see above.
 *   progress_logs    - one row per logged study/practice session, and the
 *                      sole source of truth for points. {@code source}
 *                      distinguishes manual / timer / adjustment rows.
 *   calendar_notes   - one row per Markdown sticky note, linked to a date
 *                      and optionally to a skill.
 *   app_settings     - generic key/value store; also holds one-time
 *                      migration flags and the user's UI preferences.
 *   timer_sessions   - persisted Focus Timer state, so a running timer
 *                      survives closing the app (v2.0 floating timer).
 *   progress_presets - built-in and user-defined points-per-duration and
 *                      mastery-curve presets (v2.0).
 *   skill_milestones - per-skill level milestones derived from a mastery
 *                      curve, with the timestamp each was reached (v2.0).
 */
public class DatabaseHelper {

    private static DatabaseHelper instance;

    private static final String DB_DIR = System.getProperty("user.home") + File.separator + ".unitracker";
    private static final String DB_FILE = DB_DIR + File.separator + "unitracker.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_FILE;

    private Connection connection;

    private DatabaseHelper() {
        // Private constructor enforces the singleton pattern.
    }

    public static synchronized DatabaseHelper getInstance() {
        if (instance == null) {
            instance = new DatabaseHelper();
        }
        return instance;
    }

    // =================================================================
    //  LIFECYCLE
    // =================================================================

    public synchronized void initializeDatabase() {
        try {
            new File(DB_DIR).mkdirs();
            connect();
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                // WAL: lets the FX thread read while a write transaction is
                // open. Without it a recompute or export blocks every read.
                st.execute("PRAGMA journal_mode = WAL;");
                st.execute("PRAGMA busy_timeout = 5000;");
                st.execute(CREATE_SKILLS_TABLE);
                st.execute(CREATE_PROGRESS_LOGS_TABLE);
                st.execute(CREATE_CALENDAR_NOTES_TABLE);
                st.execute(CREATE_APP_SETTINGS_TABLE);
                st.execute(CREATE_TIMER_SESSIONS_TABLE);
                st.execute(CREATE_PROGRESS_PRESETS_TABLE);
                st.execute(CREATE_SKILL_MILESTONES_TABLE);
                st.execute(CREATE_PROGRESS_LOGS_DATE_INDEX);
                // v2.0: skill_id had NO index. getLogsForSkill() filters on it
                // and is called once per skill by the PDF export, so on a real
                // dataset that was a full table scan per skill.
                st.execute(CREATE_PROGRESS_LOGS_SKILL_INDEX);
                st.execute(CREATE_CALENDAR_NOTES_DATE_INDEX);
                st.execute(CREATE_CALENDAR_NOTES_SKILL_INDEX);
                st.execute(CREATE_CALENDAR_NOTES_PINNED_INDEX);
                st.execute(CREATE_TIMER_SESSIONS_ACTIVE_INDEX);
                st.execute(CREATE_SKILL_MILESTONES_SKILL_INDEX);
            }
            migrateAddSortOrderColumnIfMissing();
            migrateAddParentIdColumnIfMissing();
            migrateAddIsPinnedColumnIfMissing();
            migrateAddProgressLogSourceColumnIfMissing();
            migrateAddProgressLogCreatedAtColumnIfMissing();
            migrateAddProgressLogTimerSessionIdColumnIfMissing();
            migrateAddTimerPausedRemainingColumnIfMissing();
            migrateFlatCategoriesToHierarchyIfNeeded();
            seedSampleDataIfEmpty();
            seedBuiltinPresetsIfEmpty();
            backfillLogSources();
            // SELF-HEALING. Any v1 database that accumulated a desync (the
            // three bugs documented on this class) is silently repaired here,
            // the first time it is opened under v2.0. Derived state is
            // rebuildable by construction, so this is always safe and always
            // correct - it never needs a version marker or a one-shot guard.
            int repaired = recomputeAllPoints();
            if (repaired > 0) {
                System.out.println("[DatabaseHelper] Derived points rebuilt for " + repaired
                        + " skill(s) from " + getTotalLoggedPoints() + " logged point(s).");
            }
            System.out.println("[DatabaseHelper] SQLite ready at " + DB_FILE);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite database at " + DB_FILE, e);
        }
    }

    private void connect() throws SQLException {
        if (connection == null || connection.isClosed()) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("SQLite JDBC driver not found on classpath", e);
            }
            connection = DriverManager.getConnection(DB_URL);
        }
    }

    public synchronized Connection getConnection() {
        try {
            connect();
        } catch (SQLException e) {
            throw new RuntimeException("Could not obtain SQLite connection", e);
        }
        return connection;
    }

    public synchronized void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] Error closing connection: " + e.getMessage());
        }
    }

    // =================================================================
    //  SCHEMA
    // =================================================================

    private static final String CREATE_SKILLS_TABLE = """
            CREATE TABLE IF NOT EXISTS skills (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id       INTEGER REFERENCES skills(id),
                name            TEXT NOT NULL,
                category        TEXT,
                structure_type  TEXT NOT NULL DEFAULT 'I_SHAPED',
                status          TEXT NOT NULL DEFAULT 'ACTIVE',
                color_hex       TEXT NOT NULL DEFAULT '#A8EB12',
                target_points   REAL NOT NULL DEFAULT 100.0,
                current_points  REAL NOT NULL DEFAULT 0.0,
                sort_order      INTEGER NOT NULL DEFAULT 0,
                created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """;
    // parent_id is deliberately declared WITHOUT "ON DELETE CASCADE" - see
    // deleteSkillCascade() below for why. Short version: it keeps the OLD
    // single-row deleteSkill() failing SAFE (blocked by SQLite's own FK
    // check) instead of silently cascading through children that
    // DeleteSkillCommand's undo snapshot doesn't know about yet.
    //
    // `category` is kept as-is, not dropped. insertSkill/updateSkill no
    // longer write to it, but every pre-refactor row's original category
    // text stays right there as a free rollback safety net - no separate
    // backup table needed.

    private void migrateAddSortOrderColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE skills ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;");
        } catch (SQLException alreadyExists) {
            // Expected on every run after the first - the column is already there.
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE calendar_notes ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;");
        } catch (SQLException alreadyExists) {
            // Same as above, for the notes table.
        }
    }

    /** Same idiom as migrateAddSortOrderColumnIfMissing above, extended for
     *  the new hierarchy column. Fresh installs already get parent_id from
     *  CREATE_SKILLS_TABLE, so this only ever does real work on a database
     *  that existed before this refactor. */
    private void migrateAddParentIdColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE skills ADD COLUMN parent_id INTEGER REFERENCES skills(id);");
        } catch (SQLException alreadyExists) {
            // Expected after the first run - the column is already there.
        }
    }

    /** Same idiom again, for the "Universal / Pinned Notes" feature: a pinned
     *  note is shown above the day list no matter which calendar date is
     *  selected, so it needs its own flag rather than a magic note_date. */
    private void migrateAddIsPinnedColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE calendar_notes ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0;");
        } catch (SQLException alreadyExists) {
            // Expected after the first run - the column is already there.
        }
    }

    /**
     * v2.0: tags each log with where it came from - MANUAL (typed into the
     * Log Session form), TIMER (a completed Focus Timer session, which also
     * links back to its timer_sessions row), or ADJUSTMENT (a manual
     * correction of a skill's current points, now stored as a real log so it
     * rolls up and is undoable like everything else).
     *
     * <p>Defaults to 'MANUAL' rather than being nullable so no existing
     * reader has to handle null, and so an ALTER on a populated table is a
     * constant-time metadata change rather than a full rewrite.
     */
    private void migrateAddProgressLogSourceColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE progress_logs ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL';");
        } catch (SQLException alreadyExists) {
            // Expected after the first run.
        }
    }

    /** v2.0: distinguishes the instant a row was WRITTEN from the (editable)
     *  log_date it is filed under. The session history and the undo/redo
     *  timeline both need the write time, and log_date cannot serve because
     *  back-dated entries are a supported workflow. */
    private void migrateAddProgressLogCreatedAtColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE progress_logs ADD COLUMN created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP;");
        } catch (SQLException alreadyExists) {
            // Expected after the first run.
        }
    }

    /**
     * v2.0: links a timer-produced log back to the timer_sessions row it came
     * from, so a completed focus session can be traced (and, if ever needed,
     * re-attributed) without guessing from timestamps.
     */
    private void migrateAddProgressLogTimerSessionIdColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE progress_logs ADD COLUMN timer_session_id INTEGER;");
        } catch (SQLException alreadyExists) {
            // Expected after the first run.
        }
    }

    /**
     * v2.0: stamps any log that predates the {@code source} column.
     *
     * <p>Rows created before v2.0 are indistinguishable from manual entries
     * (the old code had no timer-at-all persistence, so nothing was lost by
     * labelling them MANUAL) but they do need a non-null timer_session_id to
     * satisfy the "TIMER rows must reference a session" invariant the
     * attribution code relies on. Cheap, and keeps every later read free of
     * special cases.
     */
    private void backfillLogSources() {
        try (Statement st = connection.createStatement()) {
            st.execute("UPDATE progress_logs SET source = 'MANUAL' "
                    + "WHERE source IS NULL OR source = '';");
            st.execute("UPDATE progress_logs SET created_at = log_date "
                    + "WHERE created_at IS NULL OR created_at = '';");
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] backfillLogSources failed: " + e.getMessage());
        }
    }

    /**
     * One-time data migration: turns every distinct old {@code category}
     * text value into a real root Category row, then re-parents every
     * pre-existing skill under the matching one. Tracked via app_settings so
     * it runs exactly once, ever, even across many future startups.
     * <p>
     * Deliberately done with UPDATE ... SET parent_id (same row ids
     * throughout) rather than recreating the table, so progress_logs.skill_id
     * and calendar_notes.skill_id never need remapping - they keep pointing
     * at exactly the ids they always did.
     */
    private void migrateFlatCategoriesToHierarchyIfNeeded() {
        if ("true".equals(getSetting("schema.skills_hierarchy_migrated", "false"))) {
            return;
        }
        System.out.println("[DatabaseHelper] Migrating flat 'category' text into the parent_id hierarchy...");

        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            // 1) Snapshot every existing (id -> category) pair BEFORE inserting
            //    any new rows, so the Category rows we're about to add can't
            //    accidentally get swept up into their own migration.
            Map<Integer, String> existing = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, category FROM skills;")) {
                while (rs.next()) {
                    existing.put(rs.getInt("id"), rs.getString("category"));
                }
            }

            if (existing.isEmpty()) {
                setSetting("schema.skills_hierarchy_migrated", "true");
                connection.commit();
                return; // brand-new / already-empty table, nothing to migrate
            }

            // 2) One root Category row per distinct category name (blank/NULL -> "Uncategorized").
            Map<String, Integer> categoryIdByName = new LinkedHashMap<>();
            List<String> distinctNames = existing.values().stream()
                    .map(c -> (c == null || c.isBlank()) ? "Uncategorized" : c)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            int order = 0;
            for (String catName : distinctNames) {
                Skill category = new Skill();
                category.setName(catName);
                category.setParentId(Skill.NO_PARENT);
                category.setSortOrder(order++);
                insertSkill(category);
                categoryIdByName.put(catName, category.getId());
            }

            // 3) Re-parent every pre-existing row under its matching Category.
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE skills SET parent_id = ? WHERE id = ?;")) {
                for (Map.Entry<Integer, String> e : existing.entrySet()) {
                    String catName = (e.getValue() == null || e.getValue().isBlank()) ? "Uncategorized" : e.getValue();
                    ps.setInt(1, categoryIdByName.get(catName));
                    ps.setInt(2, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            setSetting("schema.skills_hierarchy_migrated", "true");
            connection.commit();
            System.out.println("[DatabaseHelper] Migration finished: " + categoryIdByName.size()
                    + " categories created, " + existing.size() + " skills re-parented.");
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                System.err.println("[DatabaseHelper] Rollback also failed: " + rollbackEx.getMessage());
            }
            System.err.println("[DatabaseHelper] Hierarchy migration failed, rolled back: " + e.getMessage());
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                System.err.println("[DatabaseHelper] Could not restore autoCommit: " + e.getMessage());
            }
        }
    }

    private static final String CREATE_PROGRESS_LOGS_TABLE = """
            CREATE TABLE IF NOT EXISTS progress_logs (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id         INTEGER NOT NULL,
                log_date         TEXT NOT NULL,
                minutes_spent    INTEGER NOT NULL DEFAULT 0,
                points_earned    REAL NOT NULL DEFAULT 0.0,
                note             TEXT,
                source           TEXT NOT NULL DEFAULT 'MANUAL',
                timer_session_id INTEGER,
                created_at       TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
            );
            """;
    // source / timer_session_id / created_at are declared here so a FRESH
    // install gets them without a migration pass; migrateAdd*ColumnIfMissing
    // then no-ops on them. The same column list is written by the ALTERs above
    // for pre-v2.0 databases. The two paths must stay in step - a column in
    // one and not the other is exactly the class of bug the migrations exist
    // to prevent.
    //
    // NOTE the deliberately ABSENT ON DELETE CASCADE on timer_session_id. The
    // FK is declared on skills(id) instead, because a timer session outlives
    // the skill it was aimed at (see the table comment); the link is a
    // traceability hint, not a lifecycle dependency, and must never delete
    // either row.

    /** The velocity chart filters progress_logs by log_date (BETWEEN). SQLite has
     *  no implicit index on that column, so without this every range query is a
     *  full table scan - harmless at 100 rows, a real drag at a 12-month span
     *  with thousands of sessions. IF NOT EXISTS + standalone keeps it safe for
     *  existing databases, which are never re-created. */
    private static final String CREATE_PROGRESS_LOGS_DATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_progress_logs_log_date
            ON progress_logs(log_date);
            """;

    /** v2.0. getLogsForSkill() filters by skill_id and nothing indexed it -
     *  SQLite does NOT auto-create an index for a FOREIGN KEY (that is a
     *  MySQL behaviour), so this was a guaranteed full table scan. It is
     *  called once per skill by the PDF export, making that a textbook N+1. */
    private static final String CREATE_PROGRESS_LOGS_SKILL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_progress_logs_skill_id
            ON progress_logs(skill_id);
            """;

    /** buildCalendar() reads one month at a time; without this it scanned the
     *  whole note table on every calendar repaint. */
    private static final String CREATE_CALENDAR_NOTES_DATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_calendar_notes_note_date
            ON calendar_notes(note_date);
            """;

    /** DeleteSkillCommand snapshots linked notes on every delete. */
    private static final String CREATE_CALENDAR_NOTES_SKILL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_calendar_notes_skill_id
            ON calendar_notes(skill_id);
            """;

    /** getPinnedNotes() filters is_pinned=1. The index is on the flag alone,
     *  which is what lets SQLite seek straight to the handful of pinned rows
     *  instead of scanning every note. */
    private static final String CREATE_CALENDAR_NOTES_PINNED_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_calendar_notes_is_pinned
            ON calendar_notes(is_pinned);
            """;

    // =================================================================
    //  V2.0 TABLES
    // =================================================================

    /**
     * Persisted Focus Timer state.
     *
     * <p>WHY A TABLE AND NOT app_settings: the timer has to survive the whole
     * application being CLOSED and reopened, and it has to store a wall-clock
     * deadline. app_settings is a flat key/value bag with no place to hang
     * structured columns, and mixing "what the user configured" with "what is
     * happening right now" makes both harder to reason about. A row per
     * timer session also leaves an audit trail of focus sessions that expired
     * while the app was closed, which is genuinely useful when you come back
     * to it days later.
     *
     * <p>COLUMN NOTES:
     *   status          - RUNNING | PAUSED | FINISHED | ABANDONED. A RUNNING
     *                     row with an ends_at in the past is what
     *                     {@link #loadActiveTimerSession()} uses to fire the
     *                     completion logic exactly once on the next startup.
     *   ends_at_utc     - epoch MILLIS of the absolute deadline. Absolute, not
     *                     a remaining-seconds counter: a counter would have to
     *                     be decremented while the app is closed, which is
     *                     impossible, and would silently reset on restart.
     *                     With an absolute deadline, "how much is left" is
     *                     just now - ends_at, which is immune to the process
     *                     not existing for a while.
     *   skill_id        - where the points route on completion. NULL means
     *                     the session completed but was never attributed, and
     *                     is swept by {@link #reapUnattributedTimerSessions()}.
     *   awarded_points  - written when the session is committed, so awarding
     *                     is idempotent: a crash between "timer expired" and
     *                     "points committed" can be detected and finished.
     */
    private static final String CREATE_TIMER_SESSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS timer_sessions (
                id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id              INTEGER,
                label                 TEXT,
                duration_secs         INTEGER NOT NULL,
                ends_at_utc           INTEGER NOT NULL,
                started_at_utc        INTEGER NOT NULL,
                paused                INTEGER NOT NULL DEFAULT 0,
                paused_remaining_secs INTEGER NOT NULL DEFAULT 0,
                status                TEXT NOT NULL DEFAULT 'RUNNING',
                awarded_points        REAL NOT NULL DEFAULT 0.0,
                created_at            TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at          TEXT,
                FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE SET NULL
            );
            """;
    /** v2.0: adds the frozen pause remainder to a pre-v2.0 timer_sessions
     *  table. Kept as an ALTER rather than folded into the CREATE so an
     *  existing database gains the column instead of being ignored by the
     *  IF NOT EXISTS. */
    private void migrateAddTimerPausedRemainingColumnIfMissing() {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE timer_sessions "
                    + "ADD COLUMN paused_remaining_secs INTEGER NOT NULL DEFAULT 0;");
        } catch (SQLException alreadyExists) {
            // Expected after the first run.
        }
    }
    private static final String CREATE_TIMER_SESSIONS_ACTIVE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_timer_sessions_status
            ON timer_sessions(status, ends_at_utc);
            """;

    /**
     * Points-per-duration and mastery-curve presets.
     *
     * <p>ONE TABLE FOR BOTH KINDS, discriminated by {@code kind}, because
     * they share a lifecycle (built-in ones are seeded and must not be
     * deletable; user ones are CRUD) and because the Settings UI presents
     * them as one list. A row of kind TIME maps
     * {@code minutes -> points}; a row of kind CURVE maps a target
     * {@code target_points} plus its {@code milestones_json} list of
     * label/threshold pairs.
     *
     * <p>milestones_json is TEXT holding a compact JSON array rather than a
     * child table because it is always read and written as a whole with its
     * owning preset, never queried across presets. A normalised child table
     * would buy nothing here and cost a second join on every read.
     */
    private static final String CREATE_PROGRESS_PRESETS_TABLE = """
            CREATE TABLE IF NOT EXISTS progress_presets (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                kind           TEXT NOT NULL,
                name           TEXT NOT NULL,
                description    TEXT,
                minutes        INTEGER,
                points         REAL,
                target_points  REAL,
                curve_ratio    REAL NOT NULL DEFAULT 0.0,
                milestones_json TEXT,
                is_builtin     INTEGER NOT NULL DEFAULT 0,
                sort_order     INTEGER NOT NULL DEFAULT 0
            );
            """;

    /**
     * Per-skill level milestones with the time each was reached.
     *
     * <p>These are REACHED, not desired: a row exists only once the skill's
     * derived points cross the threshold, and {@code reached_at} is a real
     * timestamp so the UI can show "Basic Proficiency - reached 12 Mar"
     * instead of a bare tick. Keeping them in a table rather than recomputing
     * on every paint is what lets the reached dates be stable and lets a user
     * see their own history.
     */
    private static final String CREATE_SKILL_MILESTONES_TABLE = """
            CREATE TABLE IF NOT EXISTS skill_milestones (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id    INTEGER NOT NULL,
                label       TEXT NOT NULL,
                points      REAL NOT NULL,
                reached_at  TEXT NOT NULL,
                preset_id   INTEGER,
                FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
            );
            """;
    private static final String CREATE_SKILL_MILESTONES_SKILL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_skill_milestones_skill_id
            ON skill_milestones(skill_id, points);
            """;

    private static final String CREATE_CALENDAR_NOTES_TABLE = """
            CREATE TABLE IF NOT EXISTS calendar_notes (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id           INTEGER,
                note_date          TEXT NOT NULL,
                title              TEXT,
                content_markdown   TEXT,
                color_hex          TEXT NOT NULL DEFAULT '#414F6C',
                status             TEXT NOT NULL DEFAULT 'ACTIVE',
                is_completed       INTEGER NOT NULL DEFAULT 0,
                is_pinned          INTEGER NOT NULL DEFAULT 0,
                sort_order         INTEGER NOT NULL DEFAULT 0,
                created_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE SET NULL
            );
            """;

    /** Generic key-value store for small app-level preferences, and now
     *  also for one-time migration flags (see migrateFlatCategoriesToHierarchyIfNeeded). */
    private static final String CREATE_APP_SETTINGS_TABLE = """
            CREATE TABLE IF NOT EXISTS app_settings (
                key   TEXT PRIMARY KEY,
                value TEXT
            );
            """;

    /** Populates a few example rows - now built as real Category -> Skill
     *  hierarchies - on a brand-new database only. Safe every startup: a
     *  no-op once any skill exists. */
    private void seedSampleDataIfEmpty() {
        if (!getAllSkills().isEmpty()) return;

        Skill programming = new Skill();
        programming.setName("Programming");
        programming.setParentId(Skill.NO_PARENT);
        insertSkill(programming);

        Skill java = new Skill("Java", programming.getId(), 500);
        java.setStructureType(Skill.STRUCTURE_COMB);
        insertSkill(java);

        Skill language = new Skill();
        language.setName("Language");
        language.setParentId(Skill.NO_PARENT);
        insertSkill(language);

        Skill spanish = new Skill("Spanish", language.getId(), 300);
        spanish.setStructureType(Skill.STRUCTURE_I);
        spanish.setStatus(Skill.STATUS_STALLED);
        spanish.setColorHex("#414F6C");
        insertSkill(spanish);

        Skill music = new Skill();
        music.setName("Music");
        music.setParentId(Skill.NO_PARENT);
        insertSkill(music);

        Skill guitar = new Skill("Guitar", music.getId(), 200);
        guitar.setStructureType(Skill.STRUCTURE_COMB);
        guitar.setColorHex("#008793");
        insertSkill(guitar);

        insertProgressLog(new ProgressLog(java.getId(), LocalDate.now().minusDays(3), 60, 20));
        insertProgressLog(new ProgressLog(java.getId(), LocalDate.now().minusDays(1), 45, 15));
        // No setCurrentPoints/updateSkill dance for the totals. Those calls
        // were the v1 way of setting a cached value by hand; under the derived
        // model the totals come from the logs above, and initializeDatabase
        // finishes with a recompute that fills in every ancestor - including
        // the Programming Category, which nothing logs against directly.

        insertProgressLog(new ProgressLog(spanish.getId(), LocalDate.now().minusDays(5), 30, 10));

        // Guitar used to be seeded with a bare current_points=60 and no log at
        // all, which is exactly the inconsistency the derived model forbids -
        // a total that appears from nowhere and cannot be explained or undone.
        // It now has the sessions that account for it.
        insertProgressLog(new ProgressLog(guitar.getId(), LocalDate.now().minusDays(2), 90, 40));
        insertProgressLog(new ProgressLog(guitar.getId(), LocalDate.now().minusDays(9), 60, 20));

        insertNote(new CalendarNote(LocalDate.now(), "Welcome to Uni Tracker!",
                "- [x] Explore the calendar\n- [ ] Log your first session\n- [ ] Try the graph toggles on the right"));
    }

    // =================================================================
    //  SKILLS
    // =================================================================

    public synchronized List<Skill> getAllSkills() {
        List<Skill> list = new ArrayList<>();
        String sql = "SELECT * FROM skills ORDER BY sort_order ASC, name COLLATE NOCASE ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRowToSkill(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getAllSkills failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * Builds the actual hierarchy: loads every row once, then links them
     * into a forest of root Categories with nested children (any depth).
     * This - not getAllSkills() - is what Phase 2's rendering and the new
     * depthLevelComboBox will walk.
     */
    public synchronized List<Skill> getSkillTree() {
        List<Skill> flat = getAllSkills();
        Map<Integer, Skill> byId = new LinkedHashMap<>();
        for (Skill s : flat) byId.put(s.getId(), s);

        List<Skill> roots = new ArrayList<>();
        for (Skill s : flat) {
            if (s.getParentId() == Skill.NO_PARENT) {
                roots.add(s);
            } else {
                Skill parent = byId.get(s.getParentId());
                if (parent != null) {
                    parent.addChild(s); // wires s.getParent() too, and keeps parentId in sync
                } else {
                    // Orphaned row (parent missing) - surfaced as a root instead
                    // of silently dropped, so nothing vanishes from the UI.
                    roots.add(s);
                }
            }
        }
        return roots;
    }

    /** Every descendant of {@code id}, any depth, parents-before-children
     *  order. Intended for Phase 3's DeleteSkillCommand to snapshot a whole
     *  subtree before calling deleteSkillCascade. */
    public synchronized List<Skill> getDescendants(int id) {
        List<Skill> out = new ArrayList<>();
        collectDescendants(id, out);
        return out;
    }

    private void collectDescendants(int parentId, List<Skill> out) {
        String sql = "SELECT * FROM skills WHERE parent_id=? ORDER BY sort_order ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, parentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Skill child = mapRowToSkill(rs);
                    out.add(child);
                    collectDescendants(child.getId(), out);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] collectDescendants failed: " + e.getMessage());
        }
    }

    public synchronized void updateSkillOrder(List<Skill> orderedSkills) {
        String sql = "UPDATE skills SET sort_order=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            int order = 0;
            for (Skill s : orderedSkills) {
                ps.setInt(1, order);
                ps.setInt(2, s.getId());
                ps.addBatch();
                order++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateSkillOrder failed: " + e.getMessage());
        }
    }

    /** LEGACY (pre-hierarchy): reads the old free-text category column,
     *  which insertSkill/updateSkill no longer write to. Controller's
     *  Add/Edit dialogs currently build a category ComboBox from this - that
     *  needs to become a parent-picker over getSkillTree() instead
     *  (Phase 3). Left in place so this file keeps compiling meanwhile. */
    public synchronized List<String> getDistinctCategories() {
        List<String> list = new ArrayList<>();
        String sql = "SELECT DISTINCT category FROM skills WHERE category IS NOT NULL AND category <> '' ORDER BY category COLLATE NOCASE;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(rs.getString("category"));
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getDistinctCategories failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized int insertSkill(Skill skill) {
        String sql = """
                INSERT INTO skills(parent_id, name, structure_type, status, color_hex, target_points, current_points, sort_order)
                VALUES (?,?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindParentId(ps, 1, skill.getParentId());
            ps.setString(2, skill.getName());
            ps.setString(3, skill.getStructureType());
            ps.setString(4, skill.getStatus());
            ps.setString(5, skill.getColorHex());
            ps.setDouble(6, skill.getTargetPoints());
            ps.setDouble(7, skill.getCurrentPoints());
            ps.setInt(8, skill.getSortOrder());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    skill.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] insertSkill failed: " + e.getMessage());
        }
        return -1;
    }

    /** Explicit-id counterpart used by DeleteSkillCommand#undo() - same
     *  trick as before (re-uses the exact original row id instead of
     *  letting SQLite assign a new one), just carrying parent_id through
     *  too now. */
    public synchronized boolean restoreSkill(Skill skill) {
        String sql = """
                INSERT INTO skills(id, parent_id, name, structure_type, status, color_hex, target_points, current_points, sort_order)
                VALUES (?,?,?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skill.getId());
            bindParentId(ps, 2, skill.getParentId());
            ps.setString(3, skill.getName());
            ps.setString(4, skill.getStructureType());
            ps.setString(5, skill.getStatus());
            ps.setString(6, skill.getColorHex());
            ps.setDouble(7, skill.getTargetPoints());
            ps.setDouble(8, skill.getCurrentPoints());
            ps.setInt(9, skill.getSortOrder());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] restoreSkill failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates the human-editable attributes of a skill: name, structure,
     * status, colour, target, sort position and parent.
     *
     * <p>IT DELIBERATELY DOES NOT WRITE {@code current_points}.
     *
     * <p>In v1 this method wrote the current-points total, because the total
     * was the authoritative value and the Edit dialog was how you changed it.
     * Under the v2.0 derived model that is actively dangerous, and the
     * assertion harness caught it doing real damage: the controller holds a
     * long-lived Skill object whose {@code currentPoints} property is only
     * refreshed when something explicitly re-reads it. Calling this method
     * with that object therefore wrote a STALE total back over the freshly
     * derived one - silently losing every point logged since the object was
     * loaded, and doing so with no error and a "success" return.
     *
     * <p>So points have exactly one write path in this class: a
     * {@code progress_logs} row, followed by
     * {@link #recomputeAllPoints()}. {@link #applyPointAdjustment} is the
     * supported way to correct a total, and it records the difference as a log
     * so it is auditable and undoable. A total can no longer be set by a
     * caller that merely happened to hold a stale object.
     *
     * <p>v2.0 FIX - this method also previously omitted {@code parent_id},
     * which meant re-parenting a skill changed only the in-memory model: the
     * UI showed the new arrangement, rollups followed the stale chain, and the
     * whole edit reverted on restart. It is now written, with the -1 root
     * sentinel normalised to SQL NULL by {@link #bindParentId}. Reparenting
     * through the UI should still prefer {@link #reparentSkill}, which also
     * re-derives and refuses cycles - this method writing parent_id is a
     * backstop, not the recommended path.
     */
    public synchronized boolean updateSkill(Skill skill) {
        String sql = """
                UPDATE skills SET parent_id=?, name=?, structure_type=?, status=?,
                       color_hex=?, target_points=?, sort_order=? WHERE id=?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            bindParentId(ps, 1, skill.getParentId());
            ps.setString(2, skill.getName());
            ps.setString(3, skill.getStructureType());
            ps.setString(4, skill.getStatus());
            ps.setString(5, skill.getColorHex());
            ps.setDouble(6, skill.getTargetPoints());
            ps.setInt(7, skill.getSortOrder());
            // Eight placeholders in total: seven in SET plus the WHERE id.
            // Every one must be bound, or an unmatched one silently takes a
            // neighbouring value - and an UPDATE ... WHERE id=0 matches
            // nothing while reporting no error whatsoever.
            ps.setInt(8, skill.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateSkill failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the status of many skills at once - the batched form used by the
     * stalled/active recomputation.
     *
     * <p>Exists to collapse an N-statement loop into ONE statement and ONE
     * commit. The caller groups ids by their target status first, because a
     * single statement can only write a single value; since there are only two
     * statuses, a full recalculation costs at most two statements no matter how
     * many skills changed.
     *
     * <p>Deliberately touches ONLY the status column. It must not go through
     * {@link #updateSkill}, because that writes target_points, parent_id and
     * sort_order from a possibly-stale in-memory object - persisting a status
     * change would then silently also persist unrelated stale values.
     *
     * @return how many rows were updated
     */
    public synchronized int setSkillStatuses(List<Integer> skillIds, String status) {
        if (skillIds == null || skillIds.isEmpty() || status == null) {
            return 0;
        }
        // "?,?,?" - built once and reused for both the statement and the
        // binding, so the two can never drift out of step.
        StringBuilder inList = new StringBuilder();
        for (int i = 0; i < skillIds.size(); i++) {
            inList.append(i == 0 ? "?" : ",?");
        }
        String sql = "UPDATE skills SET status=? WHERE id IN (" + inList + ");";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, status);
            for (int i = 0; i < skillIds.size(); i++) {
                ps.setInt(i + 2, skillIds.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] setSkillStatuses failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * v2.0: moves a skill to a new parent, recomputing derived points for the
     * affected subtrees.
     *
     * <p>This exists as its own method rather than going through
     * {@link #updateSkill} because a re-parent is the one edit that changes
     * which ANCESTORS a skill's history belongs to. Under the v1 delta model
     * that was unrecoverable: the old parent kept every point the skill had
     * ever contributed and the new parent received none, with no log change to
     * drive a correction. Because points are now DERIVED from the logs
     * (which reference only the skill, not its ancestry), a simple recompute
     * resolves both chains correctly and automatically.
     *
     * <p>Also rejects a move that would create a cycle - a skill cannot
     * become its own ancestor, and without this check the recursive walk would
     * terminate on its depth cap but leave the tree in a state the UI could
     * not render.
     */
    public synchronized boolean reparentSkill(int skillId, int newParentId) {
        if (skillId == newParentId) return false;
        if (newParentId != Skill.NO_PARENT) {
            if (getAncestorIds(newParentId).contains(skillId)) {
                System.err.println("[DatabaseHelper] reparentSkill refused: " + skillId
                        + " is an ancestor of " + newParentId + " (would create a cycle).");
                return false;
            }
        }
        String sql = "UPDATE skills SET parent_id=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            bindParentId(ps, 1, newParentId);
            ps.setInt(2, skillId);
            if (ps.executeUpdate() == 0) return false;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] reparentSkill failed: " + e.getMessage());
            return false;
        }
        recomputeAllPoints();
        return true;
    }

    /**
     * The total a single skill currently derives, counted over its whole
     * subtree (its own logs plus every descendant's).
     *
     * <p>Reads through the same recursive walk the recompute uses rather than
     * trusting the cached column, so it is a genuine independent check: this
     * is what the assertion harness compares the cache against to prove the
     * two can never silently diverge.
     */
    public synchronized double getDerivedPointsFor(int skillId) {
        String sql = """
                WITH RECURSIVE subtree(node, depth) AS (
                    SELECT id, 0 FROM skills WHERE id = ?
                    UNION ALL
                    SELECT s.id, subtree.depth + 1
                    FROM skills s JOIN subtree ON s.parent_id = subtree.node
                    WHERE subtree.depth < 64
                )
                SELECT COALESCE(SUM(l.points_earned), 0)
                FROM subtree
                JOIN progress_logs l ON l.skill_id = subtree.node;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getDerivedPointsFor failed: " + e.getMessage());
        }
        return 0.0;
    }

    /** Single-row delete - UNCHANGED behavior from before this refactor.
     *  Because parent_id has no ON DELETE CASCADE (see CREATE_SKILLS_TABLE
     *  note above), calling this on a node that still has children now
     *  fails safe: SQLite blocks it with a foreign key constraint error,
     *  which the catch below turns into a quiet "return false" - nothing is
     *  lost. Only deleteSkillCascade() below is allowed to remove a node
     *  that has children. */
    public synchronized boolean deleteSkill(int skillId) {
        String sql = "DELETE FROM skills WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) {
                // The FK cascade has already removed this node's log rows, so
                // the ancestors' cached totals are now stale. Re-derive.
                recomputeAllPoints();
            }
            return deleted;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deleteSkill failed (node may still have children - use deleteSkillCascade instead): " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a node AND every descendant beneath it, deepest-first. Each
     * individual delete still correctly triggers the existing
     * progress_logs ON DELETE CASCADE / calendar_notes ON DELETE SET NULL
     * for every node removed, not just the top one.
     * <p>
     * PHASE 3 TODO: DeleteSkillCommand should call getDescendants(id) to
     * snapshot the whole subtree (each descendant's own logs + linked note
     * ids) BEFORE calling this, the same way it already does for a single
     * skill today - otherwise undo after a Category delete will only bring
     * the empty Category back, not what was under it.
     */
    public synchronized boolean deleteSkillCascade(int id) {
        List<Skill> descendants = getDescendants(id);
        Collections.reverse(descendants); // leaves first, walking back up to `id`
        for (Skill d : descendants) {
            if (!deleteSkill(d.getId())) return false;
        }
        return deleteSkill(id);
    }

    /**
     * The whole subtree under {@code id} - {@code id} included - as a flat
     * list, deepest last. Used by DeleteSkillCommand to snapshot EVERY node
     * before a cascading delete, which is what makes undo of a Category delete
     * restore the skills that were under it, not an empty shell.
     *
     * <p>Distinct from {@link #getDescendants(int)}, which excludes the node
     * itself. Mixing those two up is exactly the bug this separate method
     * avoids.
     */
    public synchronized List<Skill> getSubtree(int id) {
        List<Skill> out = new ArrayList<>();
        out.addAll(getDescendants(id));
        for (Skill s : getAllSkills()) {
            if (s.getId() == id) {
                out.add(s);
                break;
            }
        }
        return out;
    }

    private void bindParentId(PreparedStatement ps, int index, int parentId) throws SQLException {
        if (parentId == Skill.NO_PARENT) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, parentId);
    }

    private Skill mapRowToSkill(ResultSet rs) throws SQLException {
        Skill s = new Skill();
        s.setId(rs.getInt("id"));
        int rawParentId = rs.getInt("parent_id");
        s.setParentId(rs.wasNull() ? Skill.NO_PARENT : rawParentId);
        s.setName(rs.getString("name"));
        s.setStructureType(rs.getString("structure_type"));
        s.setStatus(rs.getString("status"));
        s.setColorHex(rs.getString("color_hex"));
        s.setTargetPoints(rs.getDouble("target_points"));
        s.setCurrentPoints(rs.getDouble("current_points"));
        s.setSortOrder(rs.getInt("sort_order"));
        return s;
    }

    // =================================================================
    //  PROGRESS LOGS  (unchanged by this refactor)
    // =================================================================

    /**
     * Inserts one session and re-derives every affected total.
     *
     * <p>THE DERIVE-ON-WRITE RULE. This - and every other method that inserts,
     * deletes or moves a log - recomputes before returning, rather than
     * trusting its caller to remember. That is what makes the derived model
     * bulletproof instead of merely disciplined: there is no sequence of
     * public API calls that can leave {@code current_points} disagreeing with
     * the log table, because there is no call that changes the log table
     * without also refreshing the totals. Callers do not need to know this
     * rule exists, so a future contributor cannot get it wrong by omission.
     */
    public synchronized int insertProgressLog(ProgressLog log) {
        int id = insertProgressLogRow(log);
        if (id > 0) {
            recomputeAllPoints();
        }
        return id;
    }

    /**
     * The insert itself, with NO recompute. Private, and used by the batch
     * path so a 30-day backfill performs exactly one derive rather than
     * thirty. Exposing this would reintroduce precisely the footgun the rule
     * above exists to remove, which is why it is not public.
     */
    private int insertProgressLogRow(ProgressLog log) {
        String sql = """
                INSERT INTO progress_logs(skill_id, log_date, minutes_spent, points_earned, note, source, timer_session_id)
                VALUES (?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, log.getSkillId());
            ps.setString(2, log.getLogDate().toString());
            ps.setInt(3, log.getMinutesSpent());
            ps.setDouble(4, log.getPointsEarned());
            ps.setString(5, log.getNote());
            ps.setString(6, log.getSource());
            // NULL rather than -1: a real foreign key value or no value at all.
            // Writing the sentinel would put a dangling reference in the table.
            if (log.getTimerSessionId() > 0) ps.setInt(7, log.getTimerSessionId());
            else ps.setNull(7, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    log.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] insertProgressLog failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Batch counterpart used by the Advanced Log dialog's date-range insert:
     * wraps every row in ONE SQLite transaction instead of N independently
     * auto-committed inserts, so a mid-batch failure rolls everything back
     * rather than leaving a half-inserted range in the database.
     *
     * <p>Uses {@link #insertProgressLogRow} - the non-deriving insert - and
     * performs exactly ONE {@link #recomputeAllPoints()} at the end. Going
     * through the public single-row insert here would re-derive the entire
     * hierarchy once per row, turning a 30-day backfill into 30 full
     * recomputes for no benefit.
     */
    public synchronized boolean insertProgressLogBatch(List<ProgressLog> logs) {
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = getConnection().getAutoCommit();
            getConnection().setAutoCommit(false);

            for (ProgressLog log : logs) {
                if (insertProgressLogRow(log) < 0) {
                    getConnection().rollback();
                    return false;
                }
            }
            getConnection().commit();
            // Only derive once the whole batch is durable, so the totals never
            // reflect a set of rows that was about to be rolled back.
            recomputeAllPoints();
            return true;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] insertProgressLogBatch failed, rolling back: " + e.getMessage());
            try {
                getConnection().rollback();
            } catch (SQLException rollbackEx) {
                System.err.println("[DatabaseHelper] Rollback also failed: " + rollbackEx.getMessage());
            }
            return false;
        } finally {
            try {
                getConnection().setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                System.err.println("[DatabaseHelper] Could not restore autoCommit: " + e.getMessage());
            }
        }
    }

    /**
     * Removes one session and re-derives.
     *
     * <p>THE FIX FOR DESYNC BUG #1. In v1 this deleted the row and left
     * {@code current_points} alone, so deleting a subskill removed its history
     * (via ON DELETE CASCADE) while every Category above it kept the points
     * that subskill had contributed - permanently, and invisibly, because the
     * resulting total still looked plausible. Deriving on write makes that
     * class of bug unrepresentable: the rows are gone, so the totals are
     * recomputed from what remains.
     */
    public synchronized boolean deleteProgressLog(int logId) {
        String sql = "DELETE FROM progress_logs WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, logId);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted) {
                recomputeAllPoints();
            }
            return deleted;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deleteProgressLog failed: " + e.getMessage());
            return false;
        }
    }

    /** Undo/redo counterpart: puts a session back and re-derives. */
    public synchronized boolean restoreProgressLog(ProgressLog log) {
        String sql = """
                INSERT INTO progress_logs(id, skill_id, log_date, minutes_spent, points_earned, note, source, timer_session_id)
                VALUES (?,?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, log.getId());
            ps.setInt(2, log.getSkillId());
            ps.setString(3, log.getLogDate().toString());
            ps.setInt(4, log.getMinutesSpent());
            ps.setDouble(5, log.getPointsEarned());
            ps.setString(6, log.getNote());
            ps.setString(7, log.getSource());
            if (log.getTimerSessionId() > 0) ps.setInt(8, log.getTimerSessionId());
            else ps.setNull(8, Types.INTEGER);
            boolean restored = ps.executeUpdate() > 0;
            if (restored) {
                recomputeAllPoints();
            }
            return restored;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] restoreProgressLog failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<ProgressLog> getLogsForSkill(int skillId) {
        List<ProgressLog> list = new ArrayList<>();
        String sql = "SELECT * FROM progress_logs WHERE skill_id=? ORDER BY log_date ASC, id ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToLog(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getLogsForSkill failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<ProgressLog> getAllProgressLogs() {
        List<ProgressLog> list = new ArrayList<>();
        String sql = "SELECT * FROM progress_logs ORDER BY log_date ASC, id ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(mapRowToLog(rs));
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getAllProgressLogs failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * v2.0: every skill's logs in ONE query, keyed by skill id.
     *
     * <p>REPLACES the PDF export's N+1 loop, which called
     * {@link #getLogsForSkill} once per skill in the tree. On a 60-skill
     * database that was 60 round trips to the SQLite file, all on the FX
     * thread, immediately before a render - which is why Export PDF froze the
     * window outright. One scan of the log table and an in-memory group-by is
     * the same data for a fraction of the cost.
     *
     * <p>Skills with no logs are simply absent from the map, so callers must
     * treat a missing key as "no sessions" rather than as an error.
     */
    public synchronized Map<Integer, List<ProgressLog>> getLogsGroupedBySkill() {
        Map<Integer, List<ProgressLog>> bySkill = new LinkedHashMap<>();
        // One pass, ordered exactly as the per-skill queries ordered them, so
        // the PDF's "most recent sessions" logic behaves identically.
        String sql = "SELECT * FROM progress_logs ORDER BY log_date ASC, id ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ProgressLog log = mapRowToLog(rs);
                bySkill.computeIfAbsent(log.getSkillId(), k -> new ArrayList<>()).add(log);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getLogsGroupedBySkill failed: " + e.getMessage());
        }
        return bySkill;
    }

    // =================================================================
    //  V2.0: PROJECTED COMPLETION DATE
    // =================================================================

    /**
     * Estimate the date a skill will reach its target, from its own recent
     * pace of progress.
     *
     * <p>WHY THIS IS THE MOST VALUABLE NUMBER IN THE APP: a percentage is
     * descriptive ("62%") and a target is aspirational ("500 pts"), but
     * neither tells you anything actionable. "Finishes around 14 Nov" is the
     * number that actually changes behaviour - it tells you whether you are on
     * track, and it makes an untouched skill visibly cost you something every
     * day, which a static percentage does not.
     *
     * <p>THE MODEL: a trailing window of {@code windowDays} (default 14), and
     * the skill's average POINTS PER DAY over it. Extrapolating
     * {@code (target - current) / rate} days from today gives the date. Points
     * rather than minutes because points are what the target is denominated
     * in - mixing the two would need a conversion constant the user has
     * already expressed as their points-per-hour preset.
     *
     * <p>WHY A TRAILING WINDOW RATHER THAN ALL TIME: an average over a skill's
     * whole life is dominated by a burst months ago and will confidently
     * predict a finish date months in the past for a skill that has since
     * gone quiet. The trailing window measures how the user is actually
     * behaving right now, which is the only thing worth predicting from.
     *
     * <p>Returns null when no honest projection exists:
     * <ul>
     *   <li>the target is already met (nothing left to project),</li>
     *   <li>the target is not positive (undefined), or</li>
     *   <li>the rate is zero - no logs at all in the window, or a net-zero one.
     *       This is the important case: returning null here is what stops the UI
     *       claiming "finishes in 3 days" for a skill nobody has touched in a
     *       month. A stalled skill is reported as stalled, not as imminent.</li>
     * </ul>
     * A non-positive rate is treated as stalled rather than projected, because
     * dividing by it would produce a negative or infinite day count and render
     * as a nonsensical date far in the past.
     */
    public synchronized LocalDate getProjectedCompletionDate(int skillId, LocalDate today, int windowDays) {
        if (windowDays <= 0) windowDays = 14;
        LocalDate start = today.minusDays(windowDays - 1L);
        String sql = """
                SELECT COALESCE(SUM(l.points_earned), 0)
                FROM progress_logs l
                JOIN skills s ON s.id = l.skill_id
                WHERE l.log_date BETWEEN ? AND ?
                  AND (s.id = ?
                       OR s.id IN (
                            WITH RECURSIVE up(node, depth) AS (
                                SELECT id, 0 FROM skills WHERE id = ?
                                UNION ALL
                                SELECT sk.parent_id, up.depth + 1
                                FROM skills sk JOIN up ON sk.id = up.node
                                WHERE sk.parent_id IS NOT NULL
                                  AND sk.parent_id <> -1
                                  AND up.depth < 64
                            )
                            SELECT node FROM up
                       ));
                """;
        double pointsInWindow = 0;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, start.toString());
            ps.setString(2, today.toString());
            ps.setInt(3, skillId);
            ps.setInt(4, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) pointsInWindow = rs.getDouble(1);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getProjectedCompletionDate failed: " + e.getMessage());
            return null;
        }

        double current = getCurrentPointsFor(List.of(skillId)).getOrDefault(skillId, 0.0);
        double target = getTargetPointsFor(skillId);
        if (target <= 0 || current >= target) {
            return null; // nothing meaningful to project
        }
        if (pointsInWindow <= 0) {
            return null; // no recent activity - a stalled skill is not "imminent"
        }

        double pointsPerDay = pointsInWindow / windowDays;
        if (pointsPerDay <= 0) {
            return null;
        }
        double daysRemaining = (target - current) / pointsPerDay;
        // A projection more than a decade out is arithmetic, not information:
        // at that range the trailing-window assumption has long since stopped
        // describing reality. Reporting "beyond the horizon" is honest.
        if (daysRemaining > 3650) {
            return null;
        }
        // Rounded up, so the displayed date is the day you actually reach the
        // target, not the day before it.
        long days = (long) Math.ceil(daysRemaining);
        return today.plusDays(days);
    }

    /** Target points for one skill, or 0 when the row is gone. */
    public synchronized double getTargetPointsFor(int skillId) {
        String sql = "SELECT target_points FROM skills WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getTargetPointsFor failed: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * B.5: every distinct date that has at least one logged session, for
     * the calendar's "history" marker. A plain DISTINCT query rather than
     * loading every ProgressLog row - the calendar only needs to know
     * WHICH days have history, not what was logged on them.
     */
    public synchronized Set<LocalDate> getDatesWithLogs() {
        Set<LocalDate> dates = new HashSet<>();
        String sql = "SELECT DISTINCT log_date FROM progress_logs;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                dates.add(LocalDate.parse(rs.getString("log_date")));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getDatesWithLogs failed: " + e.getMessage());
        }
        return dates;
    }

    // =================================================================
    //  BOTTOM-UP POINT ACCUMULATION
    // =================================================================

    /**
     * Every ancestor of {@code skillId}, nearest parent first, excluding the
     * skill itself. Walked in SQL via parent_id because the flat list the
     * controller holds (getAllSkills) has a null {@code parent} field - only
     * getSkillTree() wires those up, and the log dialog doesn't use the tree.
     *
     * <p>Guarded with a depth LIMIT so a corrupt parent_id cycle degrades to a
     * short list instead of hanging the UI thread forever.
     */
    public synchronized List<Integer> getAncestorIds(int skillId) {
        List<Integer> ancestors = new ArrayList<>();
        String sql = """
                WITH RECURSIVE chain(id, parent_id, depth) AS (
                    SELECT id, parent_id, 0 FROM skills WHERE id = ?
                    UNION ALL
                    SELECT s.id, s.parent_id, chain.depth + 1
                    FROM skills s JOIN chain ON s.id = chain.parent_id
                    WHERE chain.depth < 64
                )
                SELECT id FROM chain WHERE depth > 0 ORDER BY depth ASC;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ancestors.add(rs.getInt("id"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getAncestorIds failed: " + e.getMessage());
        }
        return ancestors;
    }

    // =================================================================
    //  DERIVED POINTS  (v2.0 - single source of truth)
    // =================================================================

    /**
     * The heart of the v2.0 point model. Rebuilds {@code current_points} for
     * EVERY skill from {@code progress_logs}, so that each node holds exactly
     * the sum of every log recorded anywhere in its own subtree.
     *
     * <p>HOW THE WALK WORKS. {@code logged} collapses the log table to
     * (skill, total) first - one row per skill that has any history at all.
     * {@code chain} then walks UP the parent_id links from each of those
     * skills, carrying the origin id and that skill's total, so one row of
     * {@code logged} fans out into one row per ancestor. The final GROUP BY
     * re-aggregates by node, which is what turns "skill B's 5 points" into
     * "5 points for B, 5 for B's parent, 5 for B's root".
     *
     * <p>Collapsing to {@code logged} BEFORE recursing is the important
     * detail: without it, a busy skill with 5000 logs would start 5000
     * separate recursive walks. With it, the recursion is bounded by
     * (number of skills that have logs x tree depth), not by log count.
     *
     * <p>WHY THIS REPLACES THE OLD RELATIVE DELTA. The old
     * {@code addPointsWithRollup} did {@code current_points += delta} on the
     * skill and each ancestor. That is a correct way to MOVE a delta, but it
     * assumes the stored value was already right beforehand. It was not, and
     * three separate paths (delete, re-parent, manual override) could all
     * leave it wrong with no way to notice. Deriving the value means the
     * stored state is a pure function of the log table, so it cannot drift:
     * the only way to change a total is to change a log.
     *
     * <p>COST. One pass over the log table plus one bounded walk plus one
     * indexed UPDATE per skill, in a single transaction. On the realistic
     * worst case here (a few thousand logs, a few hundred skills) that is
     * well under a millisecond, and it runs on an explicit user action -
     * never inside the animation loop. Correctness is worth far more than the
     * microseconds here.
     *
     * <p>NOTES ON EDGE CASES:
     * <ul>
     *   <li>A skill with no logs in its subtree is written to 0, via the
     *       COALESCE in the UPDATE. This is what makes an undo of the ONLY
     *       session under a Category reset that Category to 0 rather than
     *       leaving it stranded.</li>
     *   <li>Negative logs (an adjustment that reduces points) are ordinary
     *       rows and simply sum, so a correction flows upward as a negative
     *       contribution exactly as a positive one flows upward as positive.</li>
     *   <li>The depth cap of 64 means a corrupt parent_id cycle terminates
     *       instead of spinning. A cycle would otherwise let one log's points
     *       loop forever up the chain.</li>
     * </ul>
     *
     * @return how many skills were rewritten (i.e. how many rows the UPDATE
     *         touched), so callers can log a meaningful "N skills refreshed".
     */
    public synchronized int recomputeAllPoints() {
        // Materialise the derived totals into a temp table, then copy them
        // across. Doing it in two statements rather than a WITH inside the
        // UPDATE's subquery keeps it portable across SQLite builds and makes
        // the aggregate inspectable while debugging.
        String materialize = """
                CREATE TEMP TABLE IF NOT EXISTS points_recompute (
                    id    INTEGER PRIMARY KEY,
                    total REAL NOT NULL
                );
                """;
        String clear = "DELETE FROM temp.points_recompute;";
        String compute = """
                INSERT INTO temp.points_recompute (id, total)
                WITH RECURSIVE logged(skill_id, pts) AS (
                    SELECT skill_id, SUM(points_earned)
                    FROM progress_logs
                    GROUP BY skill_id
                ),
                chain(origin, node, pts, depth) AS (
                    SELECT skill_id, skill_id, pts, 0 FROM logged
                    UNION ALL
                    SELECT c.origin, s.parent_id, c.pts, c.depth + 1
                    FROM chain c
                    JOIN skills s ON s.id = c.node
                    WHERE s.parent_id IS NOT NULL
                      AND s.parent_id <> -1
                      AND c.depth < 64
                )
                SELECT node AS id, SUM(pts) AS total
                FROM chain
                GROUP BY node;
                """;
        // COALESCE against the (empty) temp table is what zeroes out any skill
        // with no logs beneath it. Without this a skill whose only session was
        // deleted would keep its stale total forever.
        String apply = """
                UPDATE skills
                SET current_points = COALESCE(
                    (SELECT pr.total FROM temp.points_recompute pr WHERE pr.id = skills.id),
                    0.0);
                """;

        boolean previousAutoCommit = true;
        try {
            Connection conn = getConnection();
            previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute(materialize);
                st.execute(clear);
                st.execute(compute);
                int touched = st.executeUpdate(apply);
                conn.commit();
                return touched;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] recomputeAllPoints failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * The ids a log against {@code skillId} would affect: the skill itself
     * plus every ancestor. Kept as its own method because the UI needs the
     * list to know which in-memory Skill objects to refresh, and because it
     * documents the shape of the rollup independently of how it is computed.
     */
    public synchronized List<Integer> getRollupTargets(int skillId) {
        List<Integer> targets = new ArrayList<>();
        targets.add(skillId);
        targets.addAll(getAncestorIds(skillId));
        return targets;
    }

    /**
     * @deprecated v2.0 - retained only so the old command classes still
     * compile during the transition. The delta it applied is now implied by
     * the log rows themselves, so this no longer mutates anything: callers
     * should insert/remove their {@link ProgressLog} and then call
     * {@link #recomputeAllPoints()}. Keeping the id list means existing
     * callers still refresh the right in-memory Skill objects.
     */
    @Deprecated
    public synchronized List<Integer> addPointsWithRollup(int skillId, double delta) {
        // No longer applied as a delta: the authoritative change is the log
        // row the caller inserted or deleted, and recomputeAllPoints() derives
        // the totals from it. Returning the affected ids keeps the contract.
        return getRollupTargets(skillId);
    }

    /**
     * Convenience for the command layer: refresh the derived totals, then hand
     * back the authoritative values for the given ids so the caller can push
     * them into its live Skill objects. This is the single call every
     * mutating command makes, which is what keeps the in-memory model and the
     * database from ever disagreeing.
     */
    public synchronized Map<Integer, Double> refreshPointsFor(List<Integer> skillIds) {
        recomputeAllPoints();
        return getCurrentPointsFor(skillIds);
    }

    /**
     * Records a manual correction of a skill's points as a real
     * {@code progress_logs} row, rather than overwriting the cached total.
     *
     * <p>THIS IS THE KEY BEHAVIOURAL CHANGE of the v2.0 model. In v1 the Edit
     * Skill dialog let the user type "Current Points" and that number was
     * written straight to {@code skills.current_points}, leaving no log and
     * no ancestor update - so a manual correction silently desynced the skill
     * from both its own history and every Category above it. Here the
     * correction is expressed as the DIFFERENCE between what the user wants
     * and what the subtree currently derives, filed as an ADJUSTMENT row, so
     * it rolls up, appears in history, and undoes like any other log.
     *
     * <p>The difference is computed from the freshly derived total rather than
     * the caller's possibly-stale in-memory value, which is what makes it
     * correct even if two adjustments are applied back to back.
     */
    public synchronized boolean applyPointAdjustment(int skillId, double desiredTotal, LocalDate date, String reason) {
        double current = getDerivedPointsFor(skillId);
        double delta = desiredTotal - current;
        if (Math.abs(delta) < 1e-9) {
            return true; // already at the requested total - nothing to record
        }
        ProgressLog adjustment = new ProgressLog(skillId, date, 0, delta);
        adjustment.setNote(reason == null || reason.isBlank()
                ? "Manual adjustment"
                : "Manual adjustment: " + reason);
        adjustment.setSource(ProgressLog.SOURCE_ADJUSTMENT);
        // insertProgressLog re-derives on write, so no explicit recompute here.
        return insertProgressLog(adjustment) > 0;
    }

    /** Re-reads current_points for a set of ids, so the in-memory Skill objects
     *  can be synced after a rollup without reloading the entire tree. */
    public synchronized Map<Integer, Double> getCurrentPointsFor(List<Integer> skillIds) {
        Map<Integer, Double> points = new LinkedHashMap<>();
        if (skillIds == null || skillIds.isEmpty()) return points;
        StringBuilder sql = new StringBuilder("SELECT id, current_points FROM skills WHERE id IN (");
        sql.append("?,".repeat(skillIds.size()));
        sql.setLength(sql.length() - 1);
        sql.append(");");
        try (PreparedStatement ps = getConnection().prepareStatement(sql.toString())) {
            for (int i = 0; i < skillIds.size(); i++) {
                ps.setInt(i + 1, skillIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) points.put(rs.getInt("id"), rs.getDouble("current_points"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getCurrentPointsFor failed: " + e.getMessage());
        }
        return points;
    }

    // =================================================================
    //  ANALYTICS  (heatmap, streaks, decay, velocity, level, pie)
    //  Every one of these is a plain local SQLite aggregate - no service,
    //  no cache, no background thread. They're cheap enough to just re-run
    //  on refresh, which is also why "Refresh" can be a one-liner.
    // =================================================================

    /**
     * Total points earned per calendar day, ascending. Single source for BOTH
     * the GitHub-style heatmap and the Velocity line chart - the heatmap reads
     * the whole map, Velocity slices the last N days off the end.
     *
     * <p>Deliberately SUM(points_earned) rather than COUNT(*): two 1-point
     * sessions and one 2-point session should shade the same.
     */
    /**
     * Same aggregate as {@link #getPointsPerDay()}, but bounded to a date range
     * so the velocity chart never loads more rows than it plots.
     *
     * <p>Both bounds are INCLUSIVE. log_date is stored as ISO-8601 TEXT
     * ('2026-08-03'), which sorts lexicographically in the same order it sorts
     * chronologically - that is what makes a plain BETWEEN on a TEXT column
     * both correct and index-friendly here.
     *
     * @param start first day to include, inclusive
     * @param end   last day to include, inclusive
     */
    public synchronized Map<LocalDate, Double> getPointsPerDay(LocalDate start, LocalDate end) {
        Map<LocalDate, Double> perDay = new LinkedHashMap<>();
        String sql = """
                SELECT log_date, SUM(points_earned) AS pts
                FROM progress_logs
                WHERE log_date BETWEEN ? AND ?
                GROUP BY log_date
                ORDER BY log_date ASC;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, start.toString());
            ps.setString(2, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    perDay.put(LocalDate.parse(rs.getString("log_date")), rs.getDouble("pts"));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getPointsPerDay(range) failed: " + e.getMessage());
        }
        return perDay;
    }

    /**
     * Every day that has logged points, unbounded.
     *
     * <p>Still used by the calendar heatmap, which colours whatever month the
     * user browses to and therefore cannot pre-declare a range. The velocity
     * chart uses the bounded overload above instead.
     */
    public synchronized Map<LocalDate, Double> getPointsPerDay() {
        Map<LocalDate, Double> perDay = new LinkedHashMap<>();
        String sql = """
                SELECT log_date, SUM(points_earned) AS pts
                FROM progress_logs
                GROUP BY log_date
                ORDER BY log_date ASC;
                """;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                perDay.put(LocalDate.parse(rs.getString("log_date")), rs.getDouble("pts"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getPointsPerDay failed: " + e.getMessage());
        }
        return perDay;
    }

    /**
     * Current and longest consecutive-day logging streak, as {current, longest}.
     *
     * <p>Classic gaps-and-islands: subtracting a row number from the date turns
     * every run of consecutive days into a constant, so GROUP BY that constant
     * yields one row per streak. Done in SQL (window function, SQLite 3.25+)
     * rather than by loading every log row into Java.
     *
     * @param today the reference day - passed in rather than assumed, because
     *              the calendar supports right-clicking to mock "today", and a
     *              streak that disagrees with the highlighted day looks broken.
     *              A streak still counts as "current" if the last logged day
     *              was yesterday - you haven't broken it until a day fully passes.
     */
    public synchronized int[] getStreaks(LocalDate today) {
        String sql = """
                WITH days AS (SELECT DISTINCT log_date FROM progress_logs),
                     grouped AS (
                         SELECT log_date,
                                julianday(log_date) - ROW_NUMBER() OVER (ORDER BY log_date) AS streak_key
                         FROM days
                     )
                SELECT COUNT(*) AS length, MAX(log_date) AS last_day
                FROM grouped
                GROUP BY streak_key;
                """;
        int longest = 0;
        int current = 0;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int length = rs.getInt("length");
                LocalDate lastDay = LocalDate.parse(rs.getString("last_day"));
                longest = Math.max(longest, length);
                if (lastDay.equals(today) || lastDay.equals(today.minusDays(1))) {
                    current = length;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getStreaks failed: " + e.getMessage());
        }
        return new int[]{current, longest};
    }

    /**
     * Last day of activity for every skill, where "activity" includes the
     * whole subtree beneath it - a Category isn't stalled just because you log
     * against its Subskills instead of the Category row itself.
     *
     * <p>Falls back to the skill's own created_at when nothing has ever been
     * logged, so a skill added two months ago and never touched correctly
     * reads as decayed instead of permanently fresh.
     *
     * @return skill id -> last activity date. Used by
     *         DashboardController#applyStalledStatuses to flip ACTIVE/STALLED.
     */
    public synchronized Map<Integer, LocalDate> getLastActivityPerSkill() {
        Map<Integer, LocalDate> lastActivity = new LinkedHashMap<>();
        String sql = """
                WITH RECURSIVE subtree(root_id, node_id) AS (
                    SELECT id, id FROM skills
                    UNION ALL
                    SELECT subtree.root_id, s.id
                    FROM skills s JOIN subtree ON s.parent_id = subtree.node_id
                )
                SELECT subtree.root_id AS skill_id,
                       COALESCE(MAX(l.log_date), date(MAX(sk.created_at))) AS last_day
                FROM subtree
                JOIN skills sk ON sk.id = subtree.root_id
                LEFT JOIN progress_logs l ON l.skill_id = subtree.node_id
                GROUP BY subtree.root_id;
                """;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String day = rs.getString("last_day");
                if (day == null) continue;
                lastActivity.put(rs.getInt("skill_id"), LocalDate.parse(day));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getLastActivityPerSkill failed: " + e.getMessage());
        }
        return lastActivity;
    }

    /** Total points ever logged - drives the Level / Badge milestones.
     *  Read from progress_logs rather than SUM(skills.current_points),
     *  which would double-count now that points roll up to ancestors. */
    public synchronized double getTotalLoggedPoints() {
        String sql = "SELECT COALESCE(SUM(points_earned), 0) AS total FROM progress_logs;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) return rs.getDouble("total");
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getTotalLoggedPoints failed: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * Minutes invested per ROOT category, for the "Where does my time go?"
     * pie chart. Same recursive walk as getLastActivityPerSkill, but rolled up
     * to roots only (parent_id IS NULL, or the -1 sentinel this schema also
     * accepts) so the pie has a handful of readable slices rather than one per
     * leaf.
     *
     * @return category name -> total minutes, biggest first. Categories with
     *         zero logged minutes are omitted - an empty slice is just noise.
     */
    public synchronized Map<String, Integer> getMinutesPerRootCategory() {
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        String sql = """
                WITH RECURSIVE subtree(root_id, node_id) AS (
                    SELECT id, id FROM skills WHERE parent_id IS NULL OR parent_id = -1
                    UNION ALL
                    SELECT subtree.root_id, s.id
                    FROM skills s JOIN subtree ON s.parent_id = subtree.node_id
                )
                SELECT sk.name AS category, COALESCE(SUM(l.minutes_spent), 0) AS minutes
                FROM subtree
                JOIN skills sk ON sk.id = subtree.root_id
                LEFT JOIN progress_logs l ON l.skill_id = subtree.node_id
                GROUP BY subtree.root_id
                HAVING minutes > 0
                ORDER BY minutes DESC;
                """;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                perCategory.put(rs.getString("category"), rs.getInt("minutes"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getMinutesPerRootCategory failed: " + e.getMessage());
        }
        return perCategory;
    }

    private ProgressLog mapRowToLog(ResultSet rs) throws SQLException {
        ProgressLog log = new ProgressLog();
        log.setId(rs.getInt("id"));
        log.setSkillId(rs.getInt("skill_id"));
        log.setLogDate(LocalDate.parse(rs.getString("log_date")));
        log.setMinutesSpent(rs.getInt("minutes_spent"));
        log.setPointsEarned(rs.getDouble("points_earned"));
        log.setNote(rs.getString("note"));
        // A pre-v2.0 row has source='MANUAL' from the column DEFAULT and no
        // timer link, so both of these degrade to a sane value rather than
        // needing a null check at every call site.
        log.setSource(rs.getString("source"));
        int sessionId = rs.getInt("timer_session_id");
        log.setTimerSessionId(rs.wasNull() ? -1 : sessionId);
        return log;
    }

    // =================================================================
    //  CALENDAR NOTES  (unchanged by this refactor)
    // =================================================================

    public synchronized int insertNote(CalendarNote note) {
        String sql = """
                INSERT INTO calendar_notes(skill_id, note_date, title, content_markdown, color_hex, status, is_completed, is_pinned)
                VALUES (?,?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (note.getSkillId() == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, note.getSkillId());
            ps.setString(2, note.getNoteDate().toString());
            ps.setString(3, note.getTitle());
            ps.setString(4, note.getContentMarkdown());
            ps.setString(5, note.getColorHex());
            ps.setString(6, note.getStatus());
            ps.setInt(7, note.isCompleted() ? 1 : 0);
            ps.setInt(8, note.isPinned() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    note.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] insertNote failed: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean updateNote(CalendarNote note) {
        String sql = """
                UPDATE calendar_notes SET skill_id=?, note_date=?, title=?, content_markdown=?,
                       color_hex=?, status=?, is_completed=?, is_pinned=? WHERE id=?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            if (note.getSkillId() == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, note.getSkillId());
            ps.setString(2, note.getNoteDate().toString());
            ps.setString(3, note.getTitle());
            ps.setString(4, note.getContentMarkdown());
            ps.setString(5, note.getColorHex());
            ps.setString(6, note.getStatus());
            ps.setInt(7, note.isCompleted() ? 1 : 0);
            ps.setInt(8, note.isPinned() ? 1 : 0);
            ps.setInt(9, note.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateNote failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Universal / "Pinned" notes: shown above the day list regardless of which
     * calendar date is selected, for yearly targets and learning vision.
     * Their note_date is still whatever day they were created on - pinning
     * only changes where they're displayed, so unpinning drops one straight
     * back into its original day without any date bookkeeping.
     */
    public synchronized List<CalendarNote> getPinnedNotes() {
        List<CalendarNote> list = new ArrayList<>();
        String sql = "SELECT * FROM calendar_notes WHERE is_pinned=1 ORDER BY sort_order ASC, created_at ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(mapRowToNote(rs));
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getPinnedNotes failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * Global note search across title + markdown body, used by the search bar
     * above the calendar. Typing "#java" finds every note tagged that way and
     * the dates they live on; tags are just inline text, so no tags table is
     * needed - which is also why this is a LIKE and not an index lookup.
     *
     * <p>The user's text is passed as a bound parameter (never concatenated),
     * and its own % / _ / \ characters are escaped so searching for a literal
     * "100%" doesn't turn into a match-everything wildcard.
     *
     * @return matching notes, newest date first. Empty for a blank query.
     */
    public synchronized List<CalendarNote> searchNotes(String query) {
        List<CalendarNote> list = new ArrayList<>();
        if (query == null || query.isBlank()) return list;

        String pattern = "%" + query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
        String sql = """
                SELECT * FROM calendar_notes
                WHERE title LIKE ? ESCAPE '\\' OR content_markdown LIKE ? ESCAPE '\\'
                ORDER BY note_date DESC, sort_order ASC;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToNote(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] searchNotes failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean deleteNote(int noteId) {
        String sql = "DELETE FROM calendar_notes WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, noteId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deleteNote failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<CalendarNote> getNotesForMonth(YearMonth month) {
        List<CalendarNote> list = new ArrayList<>();
        String sql = "SELECT * FROM calendar_notes WHERE note_date BETWEEN ? AND ? ORDER BY note_date ASC, sort_order ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, month.atDay(1).toString());
            ps.setString(2, month.atEndOfMonth().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToNote(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getNotesForMonth failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<CalendarNote> getNotesForSkill(int skillId) {
        List<CalendarNote> list = new ArrayList<>();
        String sql = "SELECT * FROM calendar_notes WHERE skill_id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToNote(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getNotesForSkill failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean relinkNoteToSkill(int noteId, int skillId) {
        String sql = "UPDATE calendar_notes SET skill_id=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            ps.setInt(2, noteId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] relinkNoteToSkill failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized List<CalendarNote> getNotesForDate(LocalDate date) {
        List<CalendarNote> list = new ArrayList<>();
        // is_pinned=0: a pinned note is rendered once in the Universal section
        // above, so excluding it here is what stops it showing twice on the
        // day it happens to have been created.
        String sql = "SELECT * FROM calendar_notes WHERE note_date=? AND is_pinned=0 ORDER BY sort_order ASC, created_at ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToNote(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getNotesForDate failed: " + e.getMessage());
        }
        return list;
    }

    public synchronized void updateNoteOrder(List<CalendarNote> orderedNotes) {
        String sql = "UPDATE calendar_notes SET sort_order=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            int order = 0;
            for (CalendarNote n : orderedNotes) {
                ps.setInt(1, order);
                ps.setInt(2, n.getId());
                ps.addBatch();
                order++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateNoteOrder failed: " + e.getMessage());
        }
    }

    // =================================================================
    //  V2.0: FOCUS TIMER SESSION STATE
    // =================================================================

    /**
     * Creates a RUNNING timer session and returns its id.
     *
     * <p>Call this the moment the user starts a focus block, with
     * {@code endsAtEpochMillis} as the ABSOLUTE deadline. That absolute value
     * is the whole trick behind the v2.0 "the timer must not reset when the
     * app is closed" requirement: if the app is not running there is no code
     * to tick a counter down, so a stored "remaining seconds" would be stale
     * the instant the process died. A wall-clock deadline, by contrast, is
     * answered by the clock itself - reopening the app a day later and asking
     * "how much of this is left" is just {@code now - endsAt}, and it is
     * correct whether the app was closed for a minute or a week.
     */
    public synchronized long startTimerSession(int skillId, String label, int durationSeconds,
                                              long endsAtEpochMillis) {
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO timer_sessions(skill_id, label, duration_secs, ends_at_utc, started_at_utc, paused, status)
                VALUES (?,?,?,?,?,0,'RUNNING');
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (skillId > 0) ps.setInt(1, skillId);
            else ps.setNull(1, Types.INTEGER);
            ps.setString(2, label);
            ps.setInt(3, durationSeconds);
            ps.setLong(4, endsAtEpochMillis);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    // Only one session may be RUNNING or PAUSED at a time.
                    // Any stragglers from a crash are closed out first, so the
                    // startup restore can never find two live timers.
                    finishOtherTimerSessions(id, "ABANDONED");
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] startTimerSession failed: " + e.getMessage());
        }
        return -1;
    }

    private void finishOtherTimerSessions(long keepId, String status) {
        String sql = "UPDATE timer_sessions SET status=?, completed_at=CURRENT_TIMESTAMP "
                + "WHERE id <> ? AND status IN ('RUNNING','PAUSED');";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, keepId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] finishOtherTimerSessions failed: " + e.getMessage());
        }
    }

    /**
     * Re-points a live session at a different skill - what happens when the
     * user changes their mind about where the focus is going while the timer
     * is already counting.
     */
    public synchronized boolean retargetTimerSession(long sessionId, int skillId) {
        String sql = "UPDATE timer_sessions SET skill_id=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            if (skillId > 0) ps.setInt(1, skillId);
            else ps.setNull(1, Types.INTEGER);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] retargetTimerSession failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Records a pause: freezes the remainder as a COUNT and clears the running
     * deadline's meaning.
     *
     * <p>{@code pausedRemainingSeconds} is passed in rather than computed here
     * because only the caller knows the authoritative deadline at that instant,
     * and {@link com.unitracker.util.FocusTimerState#freezeRemaining} is the
     * one place that arithmetic lives.
     */
    public synchronized boolean pauseTimerSession(long sessionId, long pausedRemainingSeconds) {
        String sql = "UPDATE timer_sessions SET paused=1, paused_remaining_secs=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, Math.max(0L, pausedRemainingSeconds));
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] pauseTimerSession failed: " + e.getMessage());
            return false;
        }
    }

    /** Clears the pause, moving the frozen count into a fresh live deadline. */
    public synchronized boolean resumeTimerSession(long sessionId, long newEndsAtEpochMillis) {
        String sql = "UPDATE timer_sessions SET paused=0, paused_remaining_secs=0, ends_at_utc=? WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, newEndsAtEpochMillis);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] resumeTimerSession failed: " + e.getMessage());
            return false;
        }
    }

    /** Rewrites the deadline of a running session. */
    public synchronized boolean updateTimerDeadline(long sessionId, long endsAtEpochMillis) {
        String sql = "UPDATE timer_sessions SET ends_at_utc=?, paused=0, paused_remaining_secs=0 WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, endsAtEpochMillis);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateTimerDeadline failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Marks a session finished and records how many points it was worth.
     *
     * <p>WRITING awarded_points HERE, rather than only when the log row is
     * inserted, is what makes awarding idempotent. If the process dies between
     * the timer expiring and the points being committed, the next startup sees
     * a FINISHED row with awarded_points still 0, and can finish the job
     * exactly once. Storing the amount at the moment of completion means the
     * reward is fixed to the settings in force when the work actually
     * happened, rather than to whatever the settings happen to say later.
     */
    public synchronized boolean completeTimerSession(long sessionId, double awardedPoints) {
        String sql = "UPDATE timer_sessions SET status='FINISHED', completed_at=CURRENT_TIMESTAMP, "
                + "awarded_points=?, paused=0 WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setDouble(1, awardedPoints);
            ps.setLong(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] completeTimerSession failed: " + e.getMessage());
            return false;
        }
    }

    /** User pressed Reset: the time is gone and nothing will be awarded. */
    public synchronized boolean abandonTimerSession(long sessionId) {
        String sql = "UPDATE timer_sessions SET status='ABANDONED', completed_at=CURRENT_TIMESTAMP, paused=0 "
                + "WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] abandonTimerSession failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * The single live session, or null when nothing is running.
     *
     * <p>This is the whole of the "resume on restart" feature. The caller does
     * the arithmetic - {@code Duration.between(now, endsAt)} - rather than this
     * method, so the time maths is pure and unit-testable without a database.
     * Note it does NOT filter on whether the deadline has passed: a session
     * that expired while the app was shut is still the live session, and
     * deciding what to do about that is deliberately the caller's job.
     */
    public synchronized TimerSessionState loadActiveTimerSession() {
        String sql = """
                SELECT id, skill_id, label, duration_secs, ends_at_utc, started_at_utc,
                       paused, paused_remaining_secs
                FROM timer_sessions
                WHERE status IN ('RUNNING','PAUSED')
                ORDER BY ends_at_utc DESC
                LIMIT 1;
                """;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                int skillId = rs.getInt("skill_id");
                return new TimerSessionState(
                        rs.getLong("id"),
                        rs.wasNull() ? -1 : skillId,
                        rs.getString("label"),
                        rs.getInt("duration_secs"),
                        rs.getLong("ends_at_utc"),
                        rs.getLong("started_at_utc"),
                        rs.getInt("paused") == 1,
                        rs.getLong("paused_remaining_secs"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] loadActiveTimerSession failed: " + e.getMessage());
        }
        return null;
    }

    /** Closes out any session left RUNNING/PAUSED by a crash. */
    public synchronized void reapOrphanedTimerSessions() {
        finishOtherTimerSessions(-1L, "ABANDONED");
    }

    /** Recent sessions for the timer history panel. */
    public synchronized List<TimerSessionState> getRecentTimerSessions(int limit) {
        List<TimerSessionState> out = new ArrayList<>();
        String sql = """
                SELECT id, skill_id, label, duration_secs, ends_at_utc, started_at_utc,
                       paused, paused_remaining_secs
                FROM timer_sessions
                ORDER BY started_at_utc DESC
                LIMIT ?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int skillId = rs.getInt("skill_id");
                    out.add(new TimerSessionState(
                            rs.getLong("id"),
                            rs.wasNull() ? -1 : skillId,
                            rs.getString("label"),
                            rs.getInt("duration_secs"),
                            rs.getLong("ends_at_utc"),
                            rs.getLong("started_at_utc"),
                            rs.getInt("paused") == 1,
                            rs.getLong("paused_remaining_secs")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getRecentTimerSessions failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Empties every user-data table, leaving the schema and the built-in
     * presets intact.
     *
     * <p>Exists for two callers: the "replace everything from a .utrack file"
     * import path, and the devcheck harnesses, which need a deterministic
     * starting state to assert against. Child tables go first so the foreign
     * keys never block a delete mid-cascade.
     *
     * <p>app_settings is NOT cleared: a settings wipe is a different, much
     * more destructive operation, and the devcheck runs would lose the
     * user.home sandbox path's own configuration for no benefit.
     */
    public synchronized void wipeAllData() {
        // progress_presets is in this list now. It was originally omitted, which
        // meant the devcheck harnesses accumulated a duplicate set of built-ins
        // on every wipe - so "Restore built-ins" appeared to create rows rather
        // than replace them, and the preset count grew without bound across a
        // test run. A method documented as emptying every user-data table has to
        // actually do that.
        String[] tables = {
                "skill_milestones", "progress_logs", "calendar_notes", "timer_sessions",
                "skills", "progress_presets"
        };
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF;");
            for (String table : tables) {
                st.execute("DELETE FROM " + table + ";");
                // Reset AUTOINCREMENT so ids restart at 1. Without this a
                // wipe would leave the sequence high and every subsequent
                // test/dev run would see unrelated, ever-growing ids.
                st.execute("DELETE FROM sqlite_sequence WHERE name = ?;".replace("?", "'" + table + "'"));
            }
            st.execute("PRAGMA foreign_keys = ON;");
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] wipeAllData failed: " + e.getMessage());
        }
    }

    /**
     * Immutable snapshot of a timer session as stored. A record because it is
     * pure data read straight out of a row - the same reasoning as
     * SkillSnapshot, and the same reason Skill itself is not a record.
     *
     * <p>{@code pausedRemainingSeconds} is the authoritative remaining time
     * whenever {@code paused} is true, and meaningless otherwise. It is a
     * plain count rather than a deadline precisely so that a pause survives an
     * arbitrarily long shutdown - see the table comment.
     */
    public record TimerSessionState(
            long id,
            int skillId,
            String label,
            int durationSeconds,
            long endsAtEpochMillis,
            long startedAtEpochMillis,
            boolean paused,
            long pausedRemainingSeconds
    ) {
    }

    // =================================================================
    //  V2.0: PROGRESS PRESETS  (points-per-time + mastery curves)
    // =================================================================

    /** Seeds the built-in presets on a fresh install. No-op once any exist. */
    private void seedBuiltinPresetsIfEmpty() {
        if (!getPresets().isEmpty()) return;
        insertBuiltinPresets();
    }

    /**
     * Restores the built-in presets to their shipped values, leaving any
     * user-created presets alone.
     *
     * <p>Used by the Settings dialog's "Restore built-ins" action. A user's own
     * curves are deliberately preserved: the button says it restores the
     * built-ins, and silently deleting someone's work because they asked to
     * reset defaults would be an unpleasant surprise.
     *
     * <p>Deleting first rather than skipping the seed when presets exist is
     * what makes this idempotent - a built-in the user has edited is replaced
     * with the shipped version, which is what "restore" means.
     */
    public synchronized int resetBuiltinPresets() {
        String delete = "DELETE FROM progress_presets WHERE is_builtin=1;";
        try (Statement st = connection.createStatement()) {
            st.execute(delete);
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] resetBuiltinPresets could not clear the built-ins: "
                    + e.getMessage());
            return 0;
        }
        return insertBuiltinPresets();
    }

    private int insertBuiltinPresets() {
        int inserted = 0;
        int order = 0;
        for (ProgressPreset p : ProgressPreset.builtIns()) {
            p.setSortOrder(order++);
            if (insertPreset(p) > 0) {
                inserted++;
            }
        }
        System.out.println("[DatabaseHelper] Seeded " + inserted + " built-in progress preset(s).");
        return inserted;
    }

    public synchronized List<ProgressPreset> getPresets() {
        List<ProgressPreset> out = new ArrayList<>();
        String sql = "SELECT * FROM progress_presets ORDER BY kind ASC, sort_order ASC, id ASC;";
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(mapRowToPreset(rs));
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getPresets failed: " + e.getMessage());
        }
        return out;
    }

    /** Convenience filter so the Settings UI can list one kind at a time. */
    public synchronized List<ProgressPreset> getPresetsOfKind(String kind) {
        List<ProgressPreset> out = new ArrayList<>();
        String sql = "SELECT * FROM progress_presets WHERE kind=? ORDER BY sort_order ASC, id ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRowToPreset(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getPresetsOfKind failed: " + e.getMessage());
        }
        return out;
    }

    public synchronized long insertPreset(ProgressPreset preset) {
        String sql = """
                INSERT INTO progress_presets(kind, name, description, minutes, points, target_points,
                                             curve_ratio, milestones_json, is_builtin, sort_order)
                VALUES (?,?,?,?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, preset.getKind());
            ps.setString(2, preset.getName());
            ps.setString(3, preset.getDescription());
            if (preset.getMinutes() != null) ps.setInt(4, preset.getMinutes());
            else ps.setNull(4, Types.INTEGER);
            if (preset.getPoints() != null) ps.setDouble(5, preset.getPoints());
            else ps.setNull(5, Types.INTEGER);
            if (preset.getTargetPoints() != null) ps.setDouble(6, preset.getTargetPoints());
            else ps.setNull(6, Types.INTEGER);
            ps.setDouble(7, preset.getCurveRatio());
            ps.setString(8, preset.milestonesJson());
            ps.setInt(9, preset.isBuiltIn() ? 1 : 0);
            ps.setInt(10, preset.getSortOrder());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    preset.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] insertPreset failed: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean updatePreset(ProgressPreset preset) {
        String sql = """
                UPDATE progress_presets SET kind=?, name=?, description=?, minutes=?, points=?,
                       target_points=?, curve_ratio=?, milestones_json=?, sort_order=? WHERE id=?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, preset.getKind());
            ps.setString(2, preset.getName());
            ps.setString(3, preset.getDescription());
            if (preset.getMinutes() != null) ps.setInt(4, preset.getMinutes());
            else ps.setNull(4, Types.INTEGER);
            if (preset.getPoints() != null) ps.setDouble(5, preset.getPoints());
            else ps.setNull(5, Types.INTEGER);
            if (preset.getTargetPoints() != null) ps.setDouble(6, preset.getTargetPoints());
            else ps.setNull(6, Types.INTEGER);
            ps.setDouble(7, preset.getCurveRatio());
            ps.setString(8, preset.milestonesJson());
            ps.setInt(9, preset.getSortOrder());
            ps.setLong(10, preset.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updatePreset failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Deletes a preset, refusing built-ins.
     *
     * <p>The guard is here rather than only in the UI so that a corrupt or
     * hand-edited database cannot end up with the mastery curves missing -
     * they are the app's default vocabulary, and losing them would silently
     * change what every new skill defaults to.
     */
    public synchronized boolean deletePreset(long presetId) {
        String builtinSql = "SELECT is_builtin FROM progress_presets WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(builtinSql)) {
            ps.setLong(1, presetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                if (rs.getInt(1) == 1) {
                    System.err.println("[DatabaseHelper] deletePreset refused: " + presetId
                            + " is a built-in preset.");
                    return false;
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deletePreset lookup failed: " + e.getMessage());
            return false;
        }
        String sql = "DELETE FROM progress_presets WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, presetId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deletePreset failed: " + e.getMessage());
            return false;
        }
    }

    private ProgressPreset mapRowToPreset(ResultSet rs) throws SQLException {
        ProgressPreset p = new ProgressPreset();
        p.setId(rs.getLong("id"));
        p.setKind(rs.getString("kind"));
        p.setName(rs.getString("name"));
        p.setDescription(rs.getString("description"));
        int minutes = rs.getInt("minutes");
        p.setMinutes(rs.wasNull() ? null : minutes);
        double points = rs.getDouble("points");
        p.setPoints(rs.wasNull() ? null : points);
        double target = rs.getDouble("target_points");
        p.setTargetPoints(rs.wasNull() ? null : target);
        p.setCurveRatio(rs.getDouble("curve_ratio"));
        p.setMilestonesJson(rs.getString("milestones_json"));
        p.setBuiltIn(rs.getInt("is_builtin") == 1);
        p.setSortOrder(rs.getInt("sort_order"));
        return p;
    }

    // =================================================================
    //  V2.0: SKILL MILESTONES
    // =================================================================

    /**
     * Records any milestone thresholds a skill has newly crossed, and returns
     * the ones actually crossed by this call.
     *
     * <p>Idempotent by construction: a milestone row already exists for
     * (skill, label), so re-running this after an unrelated log change inserts
     * nothing and reports nothing. That matters because this is called after
     * every mutation, and a user who logs three sessions in a row crosses a
     * milestone exactly once - not three times.
     *
     * <p>SQL rather than a read-then-write in Java specifically to avoid the
     * check and the insert racing: the NOT EXISTS predicate is evaluated
     * inside the same statement as the insert, so two concurrent calls cannot
     * both decide the milestone is missing.
     */
    public synchronized List<SkillMilestone> recordMilestonesCrossed(int skillId, LocalDate reachedOn) {
        List<SkillMilestone> crossed = new ArrayList<>();
        double current = getCurrentPointsFor(List.of(skillId)).getOrDefault(skillId, 0.0);

        // Threshold rows come from every preset attached to this skill (a skill
        // may follow more than one curve, or none at all).
        String selectSql = """
                SELECT m.label, m.points, m.preset_id
                FROM skill_milestones m
                WHERE m.skill_id = ?
                """;
        // Placeholder for the milestone source; the real insert is below.
        String candidatesSql = """
                SELECT p.id AS preset_id, p.name AS preset_name, p.milestones_json
                FROM skills s
                JOIN progress_presets p ON p.kind = ?
                WHERE s.id = ?;
                """;
        List<String> labels = new ArrayList<>();
        List<Double> thresholds = new ArrayList<>();
        List<Long> presetIds = new ArrayList<>();
        try (PreparedStatement ps = getConnection().prepareStatement(candidatesSql)) {
            ps.setString(1, ProgressPreset.KIND_CURVE);
            ps.setInt(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long presetId = rs.getLong("preset_id");
                    String json = rs.getString("milestones_json");
                    for (ProgressPreset.Milestone m : ProgressPreset.parseMilestones(json)) {
                        if (m.threshold() <= current + 1e-9) {
                            labels.add(m.label());
                            thresholds.add(m.threshold());
                            presetIds.add(presetId);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] recordMilestonesCrossed (candidates) failed: " + e.getMessage());
        }
        if (labels.isEmpty()) return crossed;

        Set<String> existing = new HashSet<>();
        try (PreparedStatement ps = getConnection().prepareStatement(selectSql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) existing.add(rs.getString("label"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] recordMilestonesCrossed (existing) failed: " + e.getMessage());
        }

        String insertSql = """
                INSERT INTO skill_milestones(skill_id, label, points, reached_at, preset_id)
                SELECT ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM skill_milestones WHERE skill_id = ? AND label = ?
                );
                """;
        String stamp = reachedOn == null ? LocalDate.now().toString() : reachedOn.toString();
        for (int i = 0; i < labels.size(); i++) {
            if (existing.contains(labels.get(i))) continue;
            try (PreparedStatement ps = getConnection().prepareStatement(insertSql)) {
                ps.setInt(1, skillId);
                ps.setString(2, labels.get(i));
                ps.setDouble(3, thresholds.get(i));
                ps.setString(4, stamp);
                ps.setLong(5, presetIds.get(i));
                ps.setInt(6, skillId);
                ps.setString(7, labels.get(i));
                if (ps.executeUpdate() > 0) {
                    crossed.add(new SkillMilestone(skillId, labels.get(i), thresholds.get(i), stamp));
                }
            } catch (SQLException e) {
                System.err.println("[DatabaseHelper] milestone insert failed: " + e.getMessage());
            }
        }
        return crossed;
    }

    /** Milestones for a skill, in the order they were reached. */
    public synchronized List<SkillMilestone> getMilestonesFor(int skillId) {
        List<SkillMilestone> out = new ArrayList<>();
        String sql = "SELECT skill_id, label, points, reached_at FROM skill_milestones "
                + "WHERE skill_id=? ORDER BY points ASC;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SkillMilestone(rs.getInt("skill_id"), rs.getString("label"),
                            rs.getDouble("points"), rs.getString("reached_at")));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getMilestonesFor failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Recomputes a skill's milestone history from scratch against its current
     * derived points, used when a curve preset is applied to an existing skill
     * (so a skill that already has 300 points immediately shows the
     * milestones it has in fact passed, rather than waiting for future logs).
     */
    public synchronized int rebuildMilestonesFor(int skillId) {
        String sql = "DELETE FROM skill_milestones WHERE skill_id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] rebuildMilestonesFor (clear) failed: " + e.getMessage());
        }
        return recordMilestonesCrossed(skillId, LocalDate.now()).size();
    }

    /** A milestone a skill has reached. A record: pure row data. */
    public record SkillMilestone(int skillId, String label, double points, String reachedAt) {
    }

    // =================================================================
    //  APP SETTINGS (generic key/value store)
    // =================================================================

    public synchronized String getSetting(String key, String defaultValue) {
        String sql = "SELECT value FROM app_settings WHERE key=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getSetting failed: " + e.getMessage());
        }
        return defaultValue;
    }

    public synchronized void setSetting(String key, String value) {
        String sql = """
                INSERT INTO app_settings(key, value) VALUES (?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] setSetting failed: " + e.getMessage());
        }
    }

    /**
     * Typed read of a numeric setting. Settings are stored as free text, and a
     * hand-edited or half-written value must not take down the dashboard on
     * startup - an unparseable value falls back to the default rather than
     * throwing out of initialize().
     */
    public synchronized double getSettingDouble(String key, double defaultValue) {
        String raw = getSetting(key, null);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("[DatabaseHelper] setting '" + key + "' is not a number ('"
                    + raw + "') - using default " + defaultValue);
            return defaultValue;
        }
    }

    /** @see #getSettingDouble(String, double) */
    public synchronized int getSettingInt(String key, int defaultValue) {
        return (int) Math.round(getSettingDouble(key, defaultValue));
    }

    /**
     * Depth of every skill, derived from parent_id in SQL.
     *
     * <p>WHY THIS EXISTS RATHER THAN Skill#getDepth(): the dashboard's skill
     * list comes from {@link #getAllSkills()}, whose Skill objects have a null
     * {@code parent} field - only {@link #getSkillTree()} wires those up. So
     * getDepth() returns 0 for every row on that list, and indenting the
     * ComboBox by it would silently do nothing. Same trap as the point rollup.
     *
     * <p>Depth-capped like getAncestorIds() so a corrupt parent_id cycle
     * degrades instead of hanging the UI thread.
     *
     * @return skill id -> 0 for a root Category, 1 for a Skill, 2+ for Subskills.
     */
    public synchronized Map<Integer, Integer> getSkillDepths() {
        Map<Integer, Integer> depths = new HashMap<>();
        String sql = """
                WITH RECURSIVE tree(id, depth) AS (
                    SELECT id, 0 FROM skills WHERE parent_id IS NULL OR parent_id = -1
                    UNION ALL
                    SELECT s.id, tree.depth + 1
                    FROM skills s JOIN tree ON s.parent_id = tree.id
                    WHERE tree.depth < 64
                )
                SELECT id, depth FROM tree;
                """;
        try (Statement st = getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                depths.put(rs.getInt("id"), rs.getInt("depth"));
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] getSkillDepths failed: " + e.getMessage());
        }
        return depths;
    }


    private CalendarNote mapRowToNote(ResultSet rs) throws SQLException {
        CalendarNote n = new CalendarNote();
        n.setId(rs.getInt("id"));
        int skillId = rs.getInt("skill_id");
        n.setSkillId(rs.wasNull() ? null : skillId);
        n.setNoteDate(LocalDate.parse(rs.getString("note_date")));
        n.setTitle(rs.getString("title"));
        n.setContentMarkdown(rs.getString("content_markdown"));
        n.setColorHex(rs.getString("color_hex"));
        n.setStatus(rs.getString("status"));
        n.setCompleted(rs.getInt("is_completed") == 1);
        n.setPinned(rs.getInt("is_pinned") == 1);
        return n;
    }
}
