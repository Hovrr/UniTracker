package com.unitracker.util;

import com.unitracker.db.DatabaseHelper;
import com.unitracker.model.CalendarNote;
import com.unitracker.model.ProgressLog;
import com.unitracker.model.Skill;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the app's custom ".utrack" backup format: a simple,
 * human-diffable, pipe-delimited text file. No JSON/XML library is required
 * since the PRD only asks for a "custom format".
 *
 * Free-text fields (titles, note bodies) are Base64-encoded so Markdown
 * content can safely contain "|", newlines, etc. without breaking the
 * delimiter-based parsing below.
 *
 * HIERARCHY REFACTOR NOTE: a skill line's 3rd field used to be a free-text
 * category; it is now the id (within THIS FILE) of its parent skill, or -1
 * (Skill.NO_PARENT) for a root Category. Since insertSkill() always lets
 * SQLite assign a fresh id on import - and a .utrack file is not guaranteed
 * to list parents before their children - import runs skills through a
 * small topological pass first, building an old-id -> new-id map, before
 * touching notes/logs (which also get their skill_id references remapped
 * through the same table). Old .utrack files exported before this refactor
 * do not have this field in the same position and will fail the column-
 * count check below rather than silently importing garbage.
 *
 * SCOPE NOTE: this is PoC-grade - complete enough to round-trip Skills,
 * CalendarNotes and ProgressLogs, but a production version would likely
 * move to a versioned, schema-checked format (e.g. JSON via Jackson) with
 * proper migrations between app/format versions.
 */
public final class UtrackFileUtil {

    private static final String HEADER = "UNITRACK_V1";
    private static final String SEC_SKILLS = "[SKILLS]";
    private static final String SEC_NOTES = "[NOTES]";
    private static final String SEC_LOGS = "[LOGS]";
    private static final String FOOTER = "END";
    private static final String SPLIT_REGEX = "\\|";

    private UtrackFileUtil() {
        // Static utility class - no instances.
    }

    public static void export(File file, List<Skill> skills, List<CalendarNote> notes,
                               List<ProgressLog> logs) throws IOException {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {

            w.write(HEADER);
            w.newLine();

            w.write(SEC_SKILLS);
            w.newLine();
            for (Skill s : skills) {
                w.write(String.join("|",
                        String.valueOf(s.getId()), b64(s.getName()), String.valueOf(s.getParentId()),
                        s.getStructureType(), s.getStatus(), s.getColorHex(),
                        String.valueOf(s.getTargetPoints()), String.valueOf(s.getCurrentPoints())));
                w.newLine();
            }

            w.write(SEC_NOTES);
            w.newLine();
            for (CalendarNote n : notes) {
                w.write(String.join("|",
                        String.valueOf(n.getId()),
                        n.getSkillId() == null ? "-1" : String.valueOf(n.getSkillId()),
                        n.getNoteDate().toString(), b64(n.getTitle()), n.getColorHex(),
                        n.getStatus(), n.isCompleted() ? "1" : "0", b64(n.getContentMarkdown())));
                w.newLine();
            }

            w.write(SEC_LOGS);
            w.newLine();
            for (ProgressLog l : logs) {
                w.write(String.join("|",
                        String.valueOf(l.getId()), String.valueOf(l.getSkillId()),
                        l.getLogDate().toString(), String.valueOf(l.getMinutesSpent()),
                        String.valueOf(l.getPointsEarned()), b64(l.getNote())));
                w.newLine();
            }

            w.write(FOOTER);
            w.newLine();
        }
    }

    /** Imports a .utrack file directly into the SQLite database via DatabaseHelper. */
    public static void importInto(File file, DatabaseHelper db) throws IOException {
        List<String> skillLines = new ArrayList<>();
        List<String> noteLines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line = r.readLine();
            if (line == null || !line.trim().equals(HEADER)) {
                throw new IOException("Not a valid .utrack file (missing header)");
            }

            String section = "";
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals(SEC_SKILLS) || line.equals(SEC_NOTES) || line.equals(SEC_LOGS)) {
                    section = line;
                    continue;
                }
                if (line.equals(FOOTER)) break;

                switch (section) {
                    case SEC_SKILLS -> skillLines.add(line);
                    case SEC_NOTES -> noteLines.add(line);
                    case SEC_LOGS -> logLines.add(line);
                    default -> { /* content outside a known section is ignored */ }
                }
            }
        }

        Map<Integer, Integer> oldToNewSkillId = importSkillsWithRemapping(skillLines, db);
        for (String line : noteLines) importNoteLine(line, db, oldToNewSkillId);
        for (String line : logLines) importLogLine(line, db, oldToNewSkillId);
    }

    /**
     * Inserts every skill line, remapping old (in-file) ids to whatever new
     * ids SQLite assigns. Runs in topological (parent-before-child) order
     * regardless of the order lines appear in the file - a node is only
     * inserted once its parent (if it has one) has already been mapped.
     * Any line whose parent never resolves (corrupt file, or a genuine
     * cycle) is inserted as a root Category instead of being dropped, so a
     * partially-bad file still recovers as much as possible rather than
     * losing data silently.
     */
    private static Map<Integer, Integer> importSkillsWithRemapping(List<String> lines, DatabaseHelper db) {
        record RawSkill(int oldId, int oldParentId, String name, String structureType,
                         String status, String colorHex, double target, double current) {
        }

        List<RawSkill> pending = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(SPLIT_REGEX, -1);
            if (p.length < 8) continue;
            try {
                pending.add(new RawSkill(
                        Integer.parseInt(p[0]), Integer.parseInt(p[2]), unb64(p[1]),
                        p[3], p[4], p[5], Double.parseDouble(p[6]), Double.parseDouble(p[7])));
            } catch (NumberFormatException malformed) {
                // Skip this one line rather than aborting the whole import.
            }
        }

        Map<Integer, Integer> oldToNewId = new HashMap<>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            Iterator<RawSkill> it = pending.iterator();
            while (it.hasNext()) {
                RawSkill rs = it.next();
                boolean parentReady = rs.oldParentId() == Skill.NO_PARENT || oldToNewId.containsKey(rs.oldParentId());
                if (!parentReady) continue;

                int resolvedParentId = rs.oldParentId() == Skill.NO_PARENT
                        ? Skill.NO_PARENT : oldToNewId.get(rs.oldParentId());
                oldToNewId.put(rs.oldId(), insertRawSkill(db, rs.name(), resolvedParentId,
                        rs.structureType(), rs.status(), rs.colorHex(), rs.target(), rs.current()));
                it.remove();
                progressed = true;
            }
            if (!progressed) {
                // Dangling/cyclic parent reference - insert whatever's left
                // as roots so the file still recovers instead of hanging.
                for (RawSkill rs : pending) {
                    oldToNewId.put(rs.oldId(), insertRawSkill(db, rs.name(), Skill.NO_PARENT,
                            rs.structureType(), rs.status(), rs.colorHex(), rs.target(), rs.current()));
                }
                pending.clear();
            }
        }
        return oldToNewId;
    }

    private static int insertRawSkill(DatabaseHelper db, String name, int parentId, String structureType,
                                       String status, String colorHex, double target, double current) {
        Skill s = new Skill();
        s.setName(name);
        s.setParentId(parentId);
        s.setStructureType(structureType);
        s.setStatus(status);
        s.setColorHex(colorHex);
        s.setTargetPoints(target);
        s.setCurrentPoints(current);
        db.insertSkill(s);
        return s.getId();
    }

    private static void importNoteLine(String line, DatabaseHelper db, Map<Integer, Integer> oldToNewSkillId) {
        String[] p = line.split(SPLIT_REGEX, -1);
        if (p.length < 8) return;
        CalendarNote n = new CalendarNote();
        int oldSkillId = Integer.parseInt(p[1]);
        Integer newSkillId = oldSkillId == -1 ? null : oldToNewSkillId.get(oldSkillId);
        n.setSkillId(newSkillId);
        n.setNoteDate(LocalDate.parse(p[2]));
        n.setTitle(unb64(p[3]));
        n.setColorHex(p[4]);
        n.setStatus(p[5]);
        n.setCompleted("1".equals(p[6]));
        n.setContentMarkdown(unb64(p[7]));
        db.insertNote(n);
    }

    private static void importLogLine(String line, DatabaseHelper db, Map<Integer, Integer> oldToNewSkillId) {
        String[] p = line.split(SPLIT_REGEX, -1);
        if (p.length < 6) return;
        Integer newSkillId = oldToNewSkillId.get(Integer.parseInt(p[1]));
        if (newSkillId == null) return; // orphaned log with no resolvable skill - drop rather than guess
        ProgressLog l = new ProgressLog();
        l.setSkillId(newSkillId);
        l.setLogDate(LocalDate.parse(p[2]));
        l.setMinutesSpent(Integer.parseInt(p[3]));
        l.setPointsEarned(Double.parseDouble(p[4]));
        l.setNote(unb64(p[5]));
        db.insertProgressLog(l);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
