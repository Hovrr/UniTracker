package com.unitracker.db;

import com.unitracker.model.CalendarNote;
import com.unitracker.model.ProgressLog;
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
 * SCHEMA: four tables now (was three before Phase 1):
 *   skills          - one row per node in the skill hierarchy (Category,
 *                     Skill, Subskill 1, Subskill 2, ...), self-referencing
 *                     via parent_id. NULL parent_id = root/Category.
 *   progress_logs   - one row per logged study/practice session.
 *   calendar_notes  - one row per Markdown sticky note, linked to a date
 *                     and optionally to a skill.
 *   app_settings    - generic key/value store; also now tracks whether the
 *                     one-time flat-category-to-hierarchy migration ran.
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

    public void initializeDatabase() {
        try {
            new File(DB_DIR).mkdirs();
            connect();
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute(CREATE_SKILLS_TABLE);
                st.execute(CREATE_PROGRESS_LOGS_TABLE);
                st.execute(CREATE_CALENDAR_NOTES_TABLE);
                st.execute(CREATE_APP_SETTINGS_TABLE);
            }
            migrateAddSortOrderColumnIfMissing();
            migrateAddParentIdColumnIfMissing();
            migrateFlatCategoriesToHierarchyIfNeeded();
            seedSampleDataIfEmpty();
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

    public Connection getConnection() {
        try {
            connect();
        } catch (SQLException e) {
            throw new RuntimeException("Could not obtain SQLite connection", e);
        }
        return connection;
    }

    public void closeConnection() {
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
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_id        INTEGER NOT NULL,
                log_date        TEXT NOT NULL,
                minutes_spent   INTEGER NOT NULL DEFAULT 0,
                points_earned   REAL NOT NULL DEFAULT 0.0,
                note            TEXT,
                FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE
            );
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
        java.setCurrentPoints(35);
        updateSkill(java);

        insertProgressLog(new ProgressLog(spanish.getId(), LocalDate.now().minusDays(5), 30, 10));
        spanish.setCurrentPoints(10);
        updateSkill(spanish);

        guitar.setCurrentPoints(60);
        updateSkill(guitar);

        insertNote(new CalendarNote(LocalDate.now(), "Welcome to Uni Tracker!",
                "- [x] Explore the calendar\n- [ ] Log your first session\n- [ ] Try the graph toggles on the right"));
    }

    // =================================================================
    //  SKILLS
    // =================================================================

    public List<Skill> getAllSkills() {
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
    public List<Skill> getSkillTree() {
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
    public List<Skill> getDescendants(int id) {
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

    public void updateSkillOrder(List<Skill> orderedSkills) {
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
    public List<String> getDistinctCategories() {
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

    public int insertSkill(Skill skill) {
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
    public boolean restoreSkill(Skill skill) {
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

    public boolean updateSkill(Skill skill) {
        String sql = """
                UPDATE skills SET name=?, structure_type=?, status=?,
                       color_hex=?, target_points=?, current_points=? WHERE id=?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, skill.getName());
            ps.setString(2, skill.getStructureType());
            ps.setString(3, skill.getStatus());
            ps.setString(4, skill.getColorHex());
            ps.setDouble(5, skill.getTargetPoints());
            ps.setDouble(6, skill.getCurrentPoints());
            ps.setInt(7, skill.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateSkill failed: " + e.getMessage());
            return false;
        }
    }

    /** Single-row delete - UNCHANGED behavior from before this refactor.
     *  Because parent_id has no ON DELETE CASCADE (see CREATE_SKILLS_TABLE
     *  note above), calling this on a node that still has children now
     *  fails safe: SQLite blocks it with a foreign key constraint error,
     *  which the catch below turns into a quiet "return false" - nothing is
     *  lost. Only deleteSkillCascade() below is allowed to remove a node
     *  that has children. */
    public boolean deleteSkill(int skillId) {
        String sql = "DELETE FROM skills WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, skillId);
            return ps.executeUpdate() > 0;
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
    public boolean deleteSkillCascade(int id) {
        List<Skill> descendants = getDescendants(id);
        Collections.reverse(descendants); // leaves first, walking back up to `id`
        for (Skill d : descendants) {
            if (!deleteSkill(d.getId())) return false;
        }
        return deleteSkill(id);
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

    public int insertProgressLog(ProgressLog log) {
        String sql = """
                INSERT INTO progress_logs(skill_id, log_date, minutes_spent, points_earned, note)
                VALUES (?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, log.getSkillId());
            ps.setString(2, log.getLogDate().toString());
            ps.setInt(3, log.getMinutesSpent());
            ps.setDouble(4, log.getPointsEarned());
            ps.setString(5, log.getNote());
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
     */
    public boolean insertProgressLogBatch(List<ProgressLog> logs) {
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = getConnection().getAutoCommit();
            getConnection().setAutoCommit(false);

            for (ProgressLog log : logs) {
                if (insertProgressLog(log) < 0) {
                    getConnection().rollback();
                    return false;
                }
            }
            getConnection().commit();
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

    public boolean deleteProgressLog(int logId) {
        String sql = "DELETE FROM progress_logs WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, logId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deleteProgressLog failed: " + e.getMessage());
            return false;
        }
    }

    public boolean restoreProgressLog(ProgressLog log) {
        String sql = """
                INSERT INTO progress_logs(id, skill_id, log_date, minutes_spent, points_earned, note)
                VALUES (?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, log.getId());
            ps.setInt(2, log.getSkillId());
            ps.setString(3, log.getLogDate().toString());
            ps.setInt(4, log.getMinutesSpent());
            ps.setDouble(5, log.getPointsEarned());
            ps.setString(6, log.getNote());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] restoreProgressLog failed: " + e.getMessage());
            return false;
        }
    }

    public List<ProgressLog> getLogsForSkill(int skillId) {
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

    public List<ProgressLog> getAllProgressLogs() {
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
     * B.5: every distinct date that has at least one logged session, for
     * the calendar's "history" marker. A plain DISTINCT query rather than
     * loading every ProgressLog row - the calendar only needs to know
     * WHICH days have history, not what was logged on them.
     */
    public Set<LocalDate> getDatesWithLogs() {
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

    private ProgressLog mapRowToLog(ResultSet rs) throws SQLException {
        ProgressLog log = new ProgressLog();
        log.setId(rs.getInt("id"));
        log.setSkillId(rs.getInt("skill_id"));
        log.setLogDate(LocalDate.parse(rs.getString("log_date")));
        log.setMinutesSpent(rs.getInt("minutes_spent"));
        log.setPointsEarned(rs.getDouble("points_earned"));
        log.setNote(rs.getString("note"));
        return log;
    }

    // =================================================================
    //  CALENDAR NOTES  (unchanged by this refactor)
    // =================================================================

    public int insertNote(CalendarNote note) {
        String sql = """
                INSERT INTO calendar_notes(skill_id, note_date, title, content_markdown, color_hex, status, is_completed)
                VALUES (?,?,?,?,?,?,?);
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (note.getSkillId() == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, note.getSkillId());
            ps.setString(2, note.getNoteDate().toString());
            ps.setString(3, note.getTitle());
            ps.setString(4, note.getContentMarkdown());
            ps.setString(5, note.getColorHex());
            ps.setString(6, note.getStatus());
            ps.setInt(7, note.isCompleted() ? 1 : 0);
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

    public boolean updateNote(CalendarNote note) {
        String sql = """
                UPDATE calendar_notes SET skill_id=?, note_date=?, title=?, content_markdown=?,
                       color_hex=?, status=?, is_completed=? WHERE id=?;
                """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            if (note.getSkillId() == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, note.getSkillId());
            ps.setString(2, note.getNoteDate().toString());
            ps.setString(3, note.getTitle());
            ps.setString(4, note.getContentMarkdown());
            ps.setString(5, note.getColorHex());
            ps.setString(6, note.getStatus());
            ps.setInt(7, note.isCompleted() ? 1 : 0);
            ps.setInt(8, note.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] updateNote failed: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteNote(int noteId) {
        String sql = "DELETE FROM calendar_notes WHERE id=?;";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, noteId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[DatabaseHelper] deleteNote failed: " + e.getMessage());
            return false;
        }
    }

    public List<CalendarNote> getNotesForMonth(YearMonth month) {
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

    public List<CalendarNote> getNotesForSkill(int skillId) {
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

    public boolean relinkNoteToSkill(int noteId, int skillId) {
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

    public List<CalendarNote> getNotesForDate(LocalDate date) {
        List<CalendarNote> list = new ArrayList<>();
        String sql = "SELECT * FROM calendar_notes WHERE note_date=? ORDER BY sort_order ASC, created_at ASC;";
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

    public void updateNoteOrder(List<CalendarNote> orderedNotes) {
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
    //  APP SETTINGS (generic key-value store)  (unchanged by this refactor)
    // =================================================================

    public String getSetting(String key, String defaultValue) {
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

    public void setSetting(String key, String value) {
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
        return n;
    }
}
