package com.unitracker.devcheck;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable self-check for the two pieces of new SQL that are non-trivial enough
 * to be wrong in a way you would not notice by clicking around:
 *
 * <ol>
 *   <li><b>Streak grouping</b> - getStreaks() finds consecutive-day runs with the
 *       {@code julianday - ROW_NUMBER()} gaps-and-islands trick. If that grouping
 *       is off by one the app still renders a perfectly plausible number, just
 *       the wrong one. It also relies on window functions actually being
 *       compiled into the bundled sqlite-jdbc, which is worth proving once.</li>
 *   <li><b>Bottom-up rollup</b> - addPointsWithRollup() walks parent_id
 *       recursively and applies a relative delta. The case that matters is undo
 *       after a sibling was logged in between: a snapshot-based implementation
 *       silently wipes the sibling's contribution to the shared parent, and the
 *       UI shows a total that looks reasonable. See PointRollup's javadoc.</li>
 * </ol>
 *
 * <p>Runs against an in-memory SQLite database rather than the real one, so it
 * touches no user data. DatabaseHelper is a singleton hardcoded to a file path,
 * so the SQL below is duplicated from it rather than called through it -
 * <b>if you change getStreaks() or getAncestorIds(), change it here too.</b>
 *
 * <p>Run with assertions enabled:
 * <pre>
 *   mvn -q compile
 *   java -ea -cp target/classes:$(ls ~/.m2/repository/org/xerial/sqlite-jdbc/*&#47;*.jar | head -1) \
 *        com.unitracker.devcheck.SqlLogicCheck
 * </pre>
 * (On Windows use {@code ;} as the classpath separator.)
 */
public final class SqlLogicCheck {

    private SqlLogicCheck() {
    }

    // Copied verbatim from DatabaseHelper#getStreaks.
    private static final String STREAK_SQL = """
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

    // Copied verbatim from DatabaseHelper#getAncestorIds.
    private static final String ANCESTOR_SQL = """
            WITH RECURSIVE chain(id, parent_id, depth) AS (
                SELECT id, parent_id, 0 FROM skills WHERE id = ?
                UNION ALL
                SELECT s.id, s.parent_id, chain.depth + 1
                FROM skills s JOIN chain ON s.id = chain.parent_id
                WHERE chain.depth < 64
            )
            SELECT id FROM chain WHERE depth > 0 ORDER BY depth ASC;
            """;

    public static void main(String[] args) throws Exception {
        boolean assertionsOn = false;
        assert assertionsOn = true; // deliberate side effect
        if (!assertionsOn) {
            System.err.println("Assertions are disabled - re-run with -ea or this check proves nothing.");
            System.exit(2);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createSchema(conn);
            checkStreaks(conn);
            checkRollup(conn);
            checkSkillDepths(conn);
            checkPointsForDuration();
        }
        checkClockFontScaling(); // pure arithmetic, no DB
        checkVelocityRanges();   // pure calendar arithmetic, no DB
        checkEmScaling();        // guards styles.css against a hard-coded px regression
        System.out.println("SqlLogicCheck: all assertions passed.");
    }

    // ---------------------------------------------------------------- em scaling

    /**
     * Guards the v2.0 resolution-scaling invariant in {@code styles.css}.
     *
     * <p>THE INVARIANT, and why it is worth an automated check rather than a
     * code-review convention. JavaFX CSS has no {@code var()} and no
     * {@code calc()}, so the only native way to rescale a stylesheet at runtime
     * is the {@code em} unit on {@code -fx-font-size}, which resolves against
     * the INHERITED font size (JDK-8205473). That works only under a strict
     * discipline:
     *
     * <ol>
     *   <li>{@code .root} is the ONLY selector carrying an absolute px font size -
     *       it is the single knob the generated scale stylesheet turns.</li>
     *   <li>Every OTHER font size is in {@code em}.</li>
     *   <li><b>No font-size selector is an ancestor of another font-size
     *       selector.</b> This is the trap: {@code em} compounds, so a child rule
     *       in {@code em} is relative to its parent's computed size, not to the
     *       root's. A stray rule setting a size on a container would make
     *       everything inside it multiply - and it would look perfectly correct
     *       at the default tier while being wrong at every other one, which is
     *       the least visible way possible to break resolution scaling.</li>
     * </ol>
     *
     * <p>This parses the stylesheet as text and checks all three. It uses no CSS
     * parser - none is exposed by JavaFX - and does not need one, because the
     * properties involved have a single unambiguous syntax.
     */
    private static void checkEmScaling() {
        // Comments are stripped ONCE, up front, and every scan below works on
        // the stripped text. The stylesheet's own header documents the
        // technique and therefore necessarily writes out example declarations
        // such as "-fx-font-size: 12px" - inside a comment. Scanning the raw
        // file would report those as live px rules, and the check would fail
        // on its own documentation.
        String css = stripComments(readStylesheet());

        // ---- 1. .root carries the one absolute size. ----
        int rootBaseCount = countOccurrences(css, "-fx-font-size: 13px");
        assert rootBaseCount == 1
                : "expected exactly one absolute base font size (-fx-font-size: 13px) on .root, found "
                        + rootBaseCount;

        // ---- 2. Nothing outside .root uses px. ----
        // A px FONT SIZE anywhere else is unpinned to the scale tier: it would
        // keep its absolute size while everything around it resized, which is
        // the exact "one element does not scale" artefact the conversion exists
        // to prevent.
        //
        // The match is on the font-size DECLARATION's unit, not on "px appears
        // somewhere in the block". A rule such as
        //   .accent-button { -fx-font-size: 0.92em; -fx-padding: 6px 14px; }
        // is perfectly correct - structural lengths legitimately stay in px,
        // because em does not scale them (JDK-8205473). Only the font size is
        // constrained.
        List<String> pxOffenders = new ArrayList<>();
        java.util.regex.Pattern fontDecl =
                java.util.regex.Pattern.compile("-fx-font-size:\\s*([0-9.]+)(px|em)");
        java.util.regex.Matcher matcher = fontDecl.matcher(css);
        while (matcher.find()) {
            if (!"px".equals(matcher.group(2))) {
                continue;
            }
            // Find which rule this declaration belongs to, by looking back for
            // the nearest selector that precedes it.
            String before = css.substring(0, matcher.start());
            int lastBrace = before.lastIndexOf('}');
            String selector = stripComments(
                    before.substring(lastBrace + 1)).trim();
            if (!selector.contains(".root")) {
                pxOffenders.add(selector.isEmpty() ? "(unknown selector)" : selector
                        + " -> " + matcher.group());
            }
        }
        assert pxOffenders.isEmpty()
                : "these rules use a px FONT SIZE, so they will NOT scale with the UI size "
                        + "setting. Convert them to em (structural lengths may stay px): " + pxOffenders;

        // ---- 3. No font-size rule is an ancestor of another (the em trap). ----
        List<String> fontSelectors = fontSizeSelectors(css);
        for (String parent : fontSelectors) {
            for (String child : fontSelectors) {
                if (parent.equals(child)) {
                    continue;
                }
                // Only COMPOUND selectors are checked. A bare type selector
                // (".label") being an ancestor of a class selector
                // (".panel-title") cannot be proven from the text alone, so the
                // check is deliberately conservative and flags only the
                // unambiguous case: a selector that IS itself a font-size rule
                // and is an explicit descendant of another one.
                if (!child.contains(" ")) {
                    continue;
                }
                String[] parts = child.trim().split("\\s+");
                StringBuilder ancestor = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) {
                        ancestor.append(' ');
                    }
                    ancestor.append(parts[i]);
                    if (ancestor.toString().equals(parent)) {
                        assert false : "selector \"" + child + "\" sets a font size and is a descendant of \""
                                + parent + "\", which also sets one. em compounds down the tree, so "
                                + "the sizes multiply rather than both referring to the root.";
                    }
                }
            }
        }

        // ---- 4. Sanity: the em values must resolve to the sizes the v1 layout
        //         was calibrated against. A typo in the divisor would silently
        //         rescale the entire interface, and this is the only assertion
        //         that would notice.
        assert close(emToPx("0.85em"), 11.0) : "0.85em should be ~11px, got " + emToPx("0.85em");
        assert close(emToPx("0.92em"), 12.0) : "0.92em should be ~12px, got " + emToPx("0.92em");
        assert close(emToPx("1em"), 13.0) : "1em should be exactly the 13px base";
        assert close(emToPx("2em"), 26.0) : "2em should be the 26px metric value";
        assert close(emToPx("3.85em"), 50.0) : "3.85em should be the 50px focus-timer clock";
        assert close(emToPx("4.62em"), 60.0) : "4.62em should be the 60px headline percentage";
        boolean hasBaseFamily = css.contains("-fx-font-family: \"Poppins\";");
        assert hasBaseFamily
                : "the root must still declare the base font family, or em has no base to scale from";
    }

    /** Resolves an {@code em} font size against the 13px root base. */
    private static double emToPx(String em) {
        return Double.parseDouble(em.replace("em", "").trim()) * 13.0;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.15;
    }

    private static String readStylesheet() {
        try (java.io.InputStream is = SqlLogicCheck.class
                .getResourceAsStream("/com/unitracker/css/styles.css")) {
            if (is == null) {
                throw new AssertionError("styles.css is missing from the classpath");
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read styles.css: " + e.getMessage());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /**
     * Every selector that declares a font size, de-duplicated.
     *
     * <p>A conservative text scan: it walks rule blocks and treats the text
     * before each opening brace as that block's selector, with CSS comments
     * stripped first so a commented-out rule cannot be mistaken for a live one.
     */
    private static List<String> fontSizeSelectors(String css) {
        List<String> found = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int open = css.indexOf('{', cursor);
            if (open < 0) {
                break;
            }
            int close = css.indexOf('}', open);
            if (close < 0) {
                break;
            }
            String selector = stripComments(css.substring(cursor, open)).trim();
            String body = css.substring(open, close);
            if (body.contains("-fx-font-size") && !selector.isEmpty()) {
                for (String one : selector.split(",")) {
                    String trimmed = one.trim();
                    if (!trimmed.isEmpty() && !found.contains(trimmed)) {
                        found.add(trimmed);
                    }
                }
            }
            cursor = close + 1;
        }
        return found;
    }

    /** Removes CSS comments from a fragment, so a commented-out rule cannot be
     *  mistaken for a live one. */
    private static String stripComments(String fragment) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < fragment.length()) {
            int start = fragment.indexOf("/*", i);
            if (start < 0) {
                out.append(fragment, i, fragment.length());
                break;
            }
            int end = fragment.indexOf("*/", start);
            if (end < 0) {
                break;
            }
            out.append(fragment, i, start).append(' ');
            i = end + 2;
        }
        return out.toString();
    }

    private static String ruleBody(String css, String selector) {
        int idx = css.indexOf(selector + " {");
        if (idx < 0) {
            return null;
        }
        int open = css.indexOf('{', idx);
        int close = css.indexOf('}', open);
        return open < 0 || close < 0 ? null : css.substring(open, close);
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE skills (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        parent_id      INTEGER REFERENCES skills(id),
                        name           TEXT NOT NULL,
                        current_points REAL NOT NULL DEFAULT 0.0
                    );
                    """);
            st.execute("""
                    CREATE TABLE progress_logs (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        skill_id      INTEGER NOT NULL,
                        log_date      TEXT NOT NULL,
                        points_earned REAL NOT NULL DEFAULT 0.0
                    );
                    """);
        }
    }

    // ---------------------------------------------------------------- streaks

    private static void checkStreaks(Connection conn) throws SQLException {
        // A 5-day run in July, a gap, then a 3-day run ending 2026-08-02.
        // Two logs on 08-01 prove the DISTINCT actually collapses same-day rows -
        // without it a busy day would inflate the streak by one per session.
        String[] dates = {
                "2026-07-01", "2026-07-02", "2026-07-03", "2026-07-04", "2026-07-05",
                "2026-07-31", "2026-08-01", "2026-08-01", "2026-08-02"
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO progress_logs(skill_id, log_date, points_earned) VALUES (1, ?, 5.0);")) {
            for (String d : dates) {
                ps.setString(1, d);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        int[] onLastDay = streaks(conn, LocalDate.of(2026, 8, 2));
        assert onLastDay[0] == 3 : "current streak on the last logged day should be 3, got " + onLastDay[0];
        assert onLastDay[1] == 5 : "longest streak should be the 5-day July run, got " + onLastDay[1];

        // Logged yesterday but not yet today: the streak is not broken until a
        // full day passes, otherwise it would reset every midnight before use.
        int[] nextDay = streaks(conn, LocalDate.of(2026, 8, 3));
        assert nextDay[0] == 3 : "streak should survive one idle day, got " + nextDay[0];
        assert nextDay[1] == 5 : "longest must not change with today, got " + nextDay[1];

        // Two days idle: broken.
        int[] twoDaysLater = streaks(conn, LocalDate.of(2026, 8, 4));
        assert twoDaysLater[0] == 0 : "streak should be broken after two idle days, got " + twoDaysLater[0];
        assert twoDaysLater[1] == 5 : "longest must persist after a break, got " + twoDaysLater[1];
    }

    /** Mirrors DatabaseHelper#getStreaks: returns {current, longest}. */
    private static int[] streaks(Connection conn, LocalDate today) throws SQLException {
        int longest = 0;
        int current = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(STREAK_SQL)) {
            while (rs.next()) {
                int length = rs.getInt("length");
                LocalDate lastDay = LocalDate.parse(rs.getString("last_day"));
                longest = Math.max(longest, length);
                if (lastDay.equals(today) || lastDay.equals(today.minusDays(1))) {
                    current = length;
                }
            }
        }
        return new int[]{current, longest};
    }

    // ---------------------------------------------------------------- rollup

    private static void checkRollup(Connection conn) throws SQLException {
        // Category(1) > Skill(2) > Subskill(3), plus sibling Subskill(4).
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO skills(id, parent_id, name) VALUES (1, NULL, 'Category');");
            st.execute("INSERT INTO skills(id, parent_id, name) VALUES (2, 1, 'Skill');");
            st.execute("INSERT INTO skills(id, parent_id, name) VALUES (3, 2, 'Subskill A');");
            st.execute("INSERT INTO skills(id, parent_id, name) VALUES (4, 2, 'Subskill B');");
        }

        assert ancestors(conn, 3).equals(List.of(2, 1))
                : "ancestors of a subskill should be [skill, category] nearest-first, got " + ancestors(conn, 3);
        assert ancestors(conn, 1).isEmpty() : "a root category has no ancestors";

        // Log 10 to the deepest node: every level above it gains 10.
        addPointsWithRollup(conn, 3, 10.0);
        assert points(conn, 3) == 10.0 : "subskill should hold its own points";
        assert points(conn, 2) == 10.0 : "parent skill should accumulate from below";
        assert points(conn, 1) == 10.0 : "root category should accumulate from below";
        assert points(conn, 4) == 0.0 : "an untouched sibling must not gain anything";

        // The case a snapshot-based undo gets wrong: sibling logs in between.
        addPointsWithRollup(conn, 4, 5.0);
        assert points(conn, 2) == 15.0 : "shared parent should hold both children, got " + points(conn, 2);

        addPointsWithRollup(conn, 3, -10.0); // undo the first log
        assert points(conn, 3) == 0.0 : "undone subskill should be back to zero";
        assert points(conn, 4) == 5.0 : "sibling must be untouched by the undo";
        assert points(conn, 2) == 5.0
                : "undo must leave the sibling's contribution intact, got " + points(conn, 2);
        assert points(conn, 1) == 5.0 : "root must stay consistent with its subtree";

        // MAX(0, ...) clamp: an over-large negative delta floors at zero rather
        // than rendering a negative progress bar.
        addPointsWithRollup(conn, 4, -999.0);
        assert points(conn, 4) == 0.0 : "current_points must clamp at 0, got " + points(conn, 4);
    }

    /** Mirrors DatabaseHelper#getAncestorIds. */
    private static List<Integer> ancestors(Connection conn, int skillId) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(ANCESTOR_SQL)) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getInt("id"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- skill depths

    /**
     * Verifies that the recursive CTE in getSkillDepths() returns the correct
     * tree depth for every node. If you change DatabaseHelper#getSkillDepths(),
     * change the SQL here too.
     */
    private static void checkSkillDepths(Connection conn) throws SQLException {
        // Build: root (id 10, depth 0) -> child (11, depth 1) -> grandchild (12, depth 2).
        // parent_id = -1 for the root, same convention as NO_PARENT in DatabaseHelper.
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO skills (id, parent_id, name) VALUES (10, -1, 'Root')");
            st.execute("INSERT INTO skills (id, parent_id, name) VALUES (11, 10, 'Child')");
            st.execute("INSERT INTO skills (id, parent_id, name) VALUES (12, 11, 'Grandchild')");
        }

        // This is the exact SQL from DatabaseHelper#getSkillDepths, reproduced
        // verbatim so any schema drift shows up as a compilation error here rather
        // than a silent runtime divergence.
        String depthSQL = """
                WITH RECURSIVE tree(id, depth) AS (
                    SELECT id, 0 FROM skills WHERE parent_id IS NULL OR parent_id = -1
                    UNION ALL
                    SELECT s.id, tree.depth + 1
                    FROM skills s JOIN tree ON s.parent_id = tree.id
                    WHERE tree.depth < 64
                )
                SELECT id, depth FROM tree;
                """;

        int rootDepth = -1, childDepth = -1, grandchildDepth = -1;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(depthSQL)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                int depth = rs.getInt("depth");
                if (id == 10) rootDepth = depth;
                else if (id == 11) childDepth = depth;
                else if (id == 12) grandchildDepth = depth;
            }
        }

        assert rootDepth == 0 : "root depth should be 0, got " + rootDepth;
        assert childDepth == 1 : "child depth should be 1, got " + childDepth;
        assert grandchildDepth == 2 : "grandchild depth should be 2, got " + grandchildDepth;
    }

    // ---------------------------------------------------------------- timer scaling

    /**
     * Verifies the points-per-duration scaling arithmetic: a baseline reward for
     * the default duration, proportional scaling for others, rounding to the
     * nearest 0.5. Pure arithmetic, no DB.
     */
    private static void checkPointsForDuration() {
        final int DEFAULT_TIMER_MINUTES = 25;
        final double DEFAULT_TIMER_POINTS = 5.0;

        // baseline = 25 min yields 5.0
        double for25 = scale(25, DEFAULT_TIMER_MINUTES, DEFAULT_TIMER_POINTS);
        assert Math.abs(for25 - 5.0) < 0.01 : "25 min -> 5.0, got " + for25;

        // double the time = double the points (before rounding)
        double for50 = scale(50, DEFAULT_TIMER_MINUTES, DEFAULT_TIMER_POINTS);
        assert Math.abs(for50 - 10.0) < 0.01 : "50 min -> 10.0, got " + for50;

        // 12 min: 12/25 * 5 = 2.4, rounds to 2.5
        double for12 = scale(12, DEFAULT_TIMER_MINUTES, DEFAULT_TIMER_POINTS);
        assert Math.abs(for12 - 2.5) < 0.01 : "12 min -> 2.5, got " + for12;

        // 90 min: 90/25 * 5 = 18.0
        double for90 = scale(90, DEFAULT_TIMER_MINUTES, DEFAULT_TIMER_POINTS);
        assert Math.abs(for90 - 18.0) < 0.01 : "90 min -> 18.0, got " + for90;
    }

    /** Mimics pointsForDuration's scaling + rounding, minus the DB override. */
    private static double scale(int minutes, int defaultMinutes, double perDefaultBlock) {
        double scaled = perDefaultBlock * minutes / (double) defaultMinutes;
        return Math.round(scaled * 2) / 2.0; // nearest half point
    }

    // ---------------------------------------------------------------- clock scaling

    /**
     * Verifies the focus-timer clock's responsive font sizing.
     *
     * <p>REWRITTEN FOR v2.0. The old version mirrored a pure width-to-size
     * interpolation. The size is now computed by
     * {@code UiScale.clockFontSize}, which additionally factors in the active
     * scale tier, so a user who has chosen "Extra large" for a 4K display gets
     * a correspondingly larger clock. A mirror of that is reproduced below.
     *
     * <p>WHAT MATTERS HERE IS THE CLAMP, not the exact curve. The value is fed
     * straight into an {@code -fx-font-size} string, so anything outside the
     * calibrated band is either an unreadable clock or one that overflows the
     * sidebar again. Whether 35px genuinely fits "01:30:00" at the 300px floor
     * is a font-metric question no assertion can answer - that one is verified
     * by eye.
     */
    private static void checkClockFontScaling() {
        final double MIN = 35.0, MAX = 50.0;

        // At the DEFAULT tier (base font 13.0px) the formula reduces to the
        // original behaviour, so the previously calibrated anchors must still
        // hold. This is the regression guard for the scale refactor.
        assert Math.abs(clockFont(300, 13.0) - 35.0) < 0.01
                : "at the column floor -> 35px, got " + clockFont(300, 13.0);
        assert Math.abs(clockFont(420, 13.0) - 50.0) < 0.01
                : "at the column ceiling -> 50px, got " + clockFont(420, 13.0);
        assert Math.abs(clockFont(360, 13.0) - 42.5) < 0.01
                : "midpoint -> 42.5px, got " + clockFont(360, 13.0);
        assert Math.abs(clockFont(380, 13.0) - 45.0) < 0.01
                : "at the default width -> 45px, got " + clockFont(380, 13.0);

        // A LARGER tier must produce a LARGER clock, or the whole point of the
        // scale setting is lost on the one widget that is pure text.
        assert clockFont(380, 15.5) > clockFont(380, 13.0)
                : "a larger scale tier must enlarge the clock";
        assert clockFont(380, 11.5) <= clockFont(380, 13.0)
                : "a compact tier must not enlarge the clock";
        assert clockFont(380, 18.0) > clockFont(380, 15.5)
                : "the tiers must be strictly increasing in base font size";

        // THE CLAMP PINS BOTH ENDS, AND THAT IS CORRECT. The band is what
        // physically fits in a 300px sidebar, so tiers below DEFAULT collapse
        // onto 35px at the floor and tiers above DEFAULT collapse onto 50px at
        // the ceiling. The meaningful claims are therefore:
        //   - strictly increasing wherever the clamp is NOT already binding;
        //   - non-decreasing everywhere;
        //   - both endpoints pinned at the extremes.
        double previous = Double.NEGATIVE_INFINITY;
        for (double base : new double[]{11.5, 13.0, 15.5, 18.0}) {
            double size = clockFont(300, base);
            assert size >= previous : "tiers must not decrease at the column floor, base " + base;
            assert size >= 35.0 : "the floor is never breached, got " + size;
            previous = size;
        }
        // At the floor, everything from DEFAULT upward has headroom, so those
        // three must genuinely separate.
        assert clockFont(300, 13.0) < clockFont(300, 15.5)
                && clockFont(300, 15.5) < clockFont(300, 18.0)
                : "tiers at or above DEFAULT must strictly separate at the column floor";
        assert Math.abs(clockFont(300, 11.5) - 35.0) < 0.01
                : "the smallest tier is pinned to the band minimum at the floor";

        previous = Double.NEGATIVE_INFINITY;
        for (double base : new double[]{11.5, 13.0, 15.5, 18.0}) {
            double size = clockFont(420, base);
            assert size >= previous : "tiers must not decrease at the column ceiling, base " + base;
            assert size <= 50.0 : "the ceiling is never breached, got " + size;
            previous = size;
        }
        // At the ceiling, everything up to and including DEFAULT has headroom.
        assert clockFont(420, 11.5) < clockFont(420, 13.0)
                : "tiers at or below DEFAULT must separate at the column ceiling";
        assert Math.abs(clockFont(420, 18.0) - 50.0) < 0.01
                : "the largest tier is pinned to the band maximum at the ceiling";

        // THE CLAMP is load-bearing across EVERY tier. A collapsed TitledPane
        // reports width 0 and a maximized window can overshoot the nominal
        // ceiling; neither may produce an out-of-band size no matter which
        // tier is active. This is the assertion that would have caught a
        // 4K user getting an 80px clock.
        for (double base : new double[]{11.5, 13.0, 15.5, 18.0}) {
            for (double w : new double[]{-50, 0, 1, 299, 421, 1000, 10000}) {
                double size = clockFont(w, base);
                assert size >= MIN && size <= MAX
                        : "width " + w + " at base " + base
                        + " produced an out-of-band font size: " + size;
            }
        }

        // Monotonic in width, for every tier: a wider pane never yields a
        // smaller clock.
        for (double base : new double[]{11.5, 13.0, 15.5, 18.0}) {
            double widest = 0;
            for (double w = 0; w <= 600; w += 7) {
                double size = clockFont(w, base);
                assert size >= widest : "font size must not shrink as the pane widens, "
                        + "at width " + w + " base " + base;
                widest = size;
            }
        }
    }

    /**
     * Mirrors {@code UiScale#clockFontSize}.
     *
     * <p>Reproduced rather than called because this harness runs with only
     * sqlite-jdbc on the classpath, while UiScale lives in a class that imports
     * javafx.scene.Scene and javafx.stage.Screen. Calling it would drag the
     * whole toolkit into a check that otherwise needs nothing.
     */
    private static double clockFont(double paneWidth, double baseFontSize) {
        final double COLUMN_MIN_WIDTH = 300, COLUMN_MAX_WIDTH = 420;
        final double CLOCK_FONT_MIN = 35, CLOCK_FONT_MAX = 50;
        final double CALIBRATION_BASE_FONT = 13.0;
        // The v1 curve, exactly: interpolate across the sidebar column's clamp.
        double span = COLUMN_MAX_WIDTH - COLUMN_MIN_WIDTH;
        double t = (paneWidth - COLUMN_MIN_WIDTH) / span;
        double interpolated = CLOCK_FONT_MIN + t * (CLOCK_FONT_MAX - CLOCK_FONT_MIN);
        // The tier is an OFFSET after the interpolation, not folded into it.
        // Folding it in looks tidier and quietly moves the 1K clock, which is
        // exactly the regression the anchor assertions above exist to catch.
        double scaled = interpolated + (baseFontSize - CALIBRATION_BASE_FONT) * 1.6;
        return Math.max(CLOCK_FONT_MIN, Math.min(CLOCK_FONT_MAX, scaled));
    }

    // ---------------------------------------------------------------- velocity ranges

    /** Mirrors DashboardController.Range. */
    private record Range(LocalDate start, LocalDate end) {
        long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }
    }

    private static final String VELOCITY_CUSTOM = "Custom";

    /** Mirrors DashboardController#VELOCITY_OPTIONS, same order. */
    private static final List<String> VELOCITY_OPTIONS = List.of(
            "7 days", "30 days", "2 months", "4 months", "6 months",
            "8 months", "10 months", "12 months", VELOCITY_CUSTOM);

    /**
     * Verifies the velocity combo's label-to-date-range resolution. Mirrors
     * DashboardController#velocityRangeFor - <b>if you change that, change this
     * too.</b> Duplicated for the same reason as clockFont: this check runs with
     * only sqlite-jdbc on the classpath.
     *
     * <p>The two things worth proving are the off-by-ones. Both bounds are
     * inclusive, so a span's length is a subtraction plus one, and every option
     * has its own way of getting that wrong: days needs {@code minusDays(n-1)},
     * months needs {@code minusMonths(n).plusDays(1)}. And the whole reason for
     * calendar arithmetic over {@code n * 30} is that month lengths and leap
     * years do not divide evenly - so the month cases are checked against dates
     * where they actually differ.
     */
    private static void checkVelocityRanges() {
        LocalDate today = LocalDate.of(2026, 8, 3);

        // Day presets: inclusive of today, so "7 days" plots 7 points, not 8.
        Range week = velocityRangeFor("7 days", today, null);
        assert week.end().equals(today) : "the range must end today, got " + week.end();
        assert week.start().equals(LocalDate.of(2026, 7, 28)) : "7 days back from 08-03 is 07-28, got " + week.start();
        assert week.days() == 7 : "\"7 days\" must span 7 days, got " + week.days();
        assert velocityRangeFor("30 days", today, null).days() == 30
                : "\"30 days\" must span 30 days, got " + velocityRangeFor("30 days", today, null).days();

        // Month presets land on the same day-of-month, one day in so both ends
        // stay inclusive: 2 months back from 08-03 is 06-03, plot from 06-04.
        Range twoMonths = velocityRangeFor("2 months", today, null);
        assert twoMonths.start().equals(LocalDate.of(2026, 6, 4))
                : "2 months back from 08-03 should start 06-04, got " + twoMonths.start();
        assert twoMonths.days() == 61 : "Jun 4 -> Aug 3 inclusive is 61 days, got " + twoMonths.days();

        // "10 months" must not be read as "1". The substring parse takes
        // everything before the space, so a broken split shows up here.
        assert velocityRangeFor("10 months", today, null).start().equals(LocalDate.of(2026, 10, 4).minusYears(1))
                : "10 months back from 2026-08-03 should start 2025-10-04, got "
                  + velocityRangeFor("10 months", today, null).start();

        // The reason for minusMonths over n*30: across a leap year a 12-month
        // span is 366 days, and 30-day multiples are off by more than a week.
        Range year = velocityRangeFor("12 months", LocalDate.of(2024, 8, 3), null);
        assert year.start().equals(LocalDate.of(2023, 8, 4))
                : "12 months back from 2024-08-03 should start 2023-08-04, got " + year.start();
        assert year.days() == 366 : "that span crosses Feb 2024, so it is 366 days, got " + year.days();
        assert year.days() != 12 * 30 : "a 30-day-per-month approximation would be wrong here";

        // Month-end clamping is java.time's, not ours, but the chart still has
        // to cope: 6 months back from 31 Aug clamps to 28 Feb (2026 is common).
        Range fromMonthEnd = velocityRangeFor("6 months", LocalDate.of(2026, 8, 31), null);
        assert fromMonthEnd.start().equals(LocalDate.of(2026, 3, 1))
                : "6 months back from 08-31 clamps to 02-28, so it starts 03-01, got " + fromMonthEnd.start();

        // "Custom" is a sentinel: no range of its own, it defers to the dialog.
        assert velocityRangeFor(VELOCITY_CUSTOM, today, null) == null
                : "\"Custom\" with no dates chosen must return null so the caller opens the dialog";
        Range chosen = new Range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assert velocityRangeFor(VELOCITY_CUSTOM, today, chosen).equals(chosen)
                : "\"Custom\" must pass the chosen range straight through";
        assert chosen.days() == 31 : "a custom January is 31 days, got " + chosen.days();

        // Unknown and null labels fall back to 30 days rather than throwing -
        // the combo is populated from code, but a stale persisted value or a
        // renamed option must not take the chart down with it.
        assert velocityRangeFor(null, today, null).days() == 30 : "a null label must fall back to 30 days";
        assert velocityRangeFor("banana", today, null).days() == 30 : "an unknown label must fall back to 30 days";

        // Every real option resolves, ends today, and is non-empty. Catches a
        // typo in the list that no individual assertion above happens to name.
        long previousDays = 0;
        for (String option : VELOCITY_OPTIONS) {
            if (VELOCITY_CUSTOM.equals(option)) continue;
            Range r = velocityRangeFor(option, today, null);
            assert r != null : "option \"" + option + "\" resolved to null";
            assert r.end().equals(today) : "option \"" + option + "\" must end today, got " + r.end();
            assert !r.start().isAfter(r.end()) : "option \"" + option + "\" starts after it ends";
            // The list is ordered shortest-first, so each span must grow.
            assert r.days() > previousDays
                    : "option \"" + option + "\" (" + r.days() + " days) must span more than the one before it ("
                      + previousDays + ") - is the list out of order, or a label mis-parsed?";
            previousDays = r.days();
        }
    }

    /** Mirrors DashboardController#velocityRangeFor. */
    private static Range velocityRangeFor(String label, LocalDate today, Range customRange) {
        if (label == null) return new Range(today.minusDays(29), today);
        if (VELOCITY_CUSTOM.equals(label)) return customRange;

        if (label.endsWith("days")) {
            int days = Integer.parseInt(label.substring(0, label.indexOf(' ')));
            return new Range(today.minusDays(days - 1L), today);
        }
        if (label.endsWith("months")) {
            int months = Integer.parseInt(label.substring(0, label.indexOf(' ')));
            return new Range(today.minusMonths(months).plusDays(1), today);
        }
        return new Range(today.minusDays(29), today);
    }

    /** Mirrors DatabaseHelper#addPointsWithRollup. */
    private static void addPointsWithRollup(Connection conn, int skillId, double delta) throws SQLException {
        List<Integer> affected = new ArrayList<>();
        affected.add(skillId);
        affected.addAll(ancestors(conn, skillId));
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE skills SET current_points = MAX(0, current_points + ?) WHERE id = ?;")) {
            for (Integer id : affected) {
                ps.setDouble(1, delta);
                ps.setInt(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static double points(Connection conn, int skillId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT current_points FROM skills WHERE id = ?;")) {
            ps.setInt(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : Double.NaN;
            }
        }
    }
}
