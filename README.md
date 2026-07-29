# Uni Tracker (PoC)

An offline desktop app for tracking multiple skills at once (programming
languages, instruments, anything) — a hierarchical Category → Skill →
Subskill tree, calendar-linked sticky notes, real-time progress tracking,
and several ways to visualize how you're doing. Built with Java 25 +
JavaFX 25 + SQLite. No network access required at runtime.

## Tech stack

| Concern                | Library                             | Version  |
|-------------------------|--------------------------------------|----------|
| UI framework            | JavaFX (controls, fxml, web, swing)  | 25.0.1   |
| Local storage            | SQLite via `org.xerial:sqlite-jdbc`  | 3.53.2.0 |
| Markdown rendering       | Flexmark-Java (`flexmark-all`)       | 0.64.8   |
| PDF export               | Apache PDFBox                        | 3.0.7    |
| Build tool               | Maven + `javafx-maven-plugin`        | 0.0.8    |

## Recent refactor: flat skills → unlimited-depth hierarchy

The `skills` table and the `Skill` model used to be flat (one free-text
`category` string per skill). They are now a self-referencing tree —
Category → Skill → Subskill 1 → Subskill 2 → ... to unlimited depth via a
`parent_id` column — with an automatic, non-destructive one-time migration
of any pre-existing flat data into the new structure (old `category` values
become root Category rows; nothing is deleted).

**Done:**
- `Skill.java` / `DatabaseHelper.java` — hierarchical model + CRUD,
  `getSkillTree()`, cascade-safe delete (`deleteSkillCascade`, deliberately
  *not* implemented via `ON DELETE CASCADE` — see the note in
  `DatabaseHelper` for why that matters for Undo/Redo).
- `depthLevelComboBox` — Comb-Shaped/Radar depth-level filter, Curve's
  specific-descendant picker.
- Skill-Tree — genuinely recursive, reads `Skill#getChildren()`, unlimited
  depth.
- S-Curve chart type removed; Comb-Shaped's breadth-bar label now tracks
  the bar's real on-screen midpoint on both axes (was vertical-only before,
  and drifted horizontally whenever H-Spacing or panning changed it).
- `SkillSnapshot` (Undo/Redo), `UtrackFileUtil` (`.utrack` import/export,
  with proper old-id → new-id remapping so parent/child relations survive a
  round trip), and the Add/Edit Skill dialogs were all updated to match —
  required for the project to compile as a whole, not just optional polish.

**Still open / not yet done:**
- `.custom-color-dialog` contrast fix (ColorPicker's popup has light text on
  a light background in the current theme).
- Ctrl+Scroll-to-zoom on the chart canvas.
- "Log Session" still timestamps against today instead of the date selected
  on the calendar.
- `PdfExportUtil` still prints a skill's old flat category in the PDF table
  — needs a decision (full breadcrumb path? just the root Category?) rather
  than a mechanical rename.
- `DeleteSkillCommand` restores exactly the one node it deleted; deleting a
  Category (or any node with children) now fails safely instead of losing
  data (see the `deleteSkillCascade` note above), but Undo does not yet
  restore an entire deleted subtree in one step.

## Project structure

```
UniTracker/
├── pom.xml
├── nbactions.xml                       NetBeans Run/Debug → javafx:run
├── README.md
└── src/main/
    ├── java/com/unitracker/
    │   ├── MainApp.java                    entry point
    │   ├── command/                        Undo/Redo (Command Pattern)
    │   │   ├── Command.java
    │   │   ├── CommandManager.java
    │   │   ├── SkillSnapshot.java
    │   │   ├── LogProgressCommand.java
    │   │   ├── EditSkillCommand.java
    │   │   └── DeleteSkillCommand.java
    │   ├── controller/DashboardController.java
    │   ├── db/DatabaseHelper.java          SQLite schema + hierarchical CRUD
    │   ├── model/
    │   │   ├── Skill.java                  tree node (Category/Skill/Subskill N)
    │   │   ├── CalendarNote.java
    │   │   └── ProgressLog.java
    │   └── util/
    │       ├── MarkdownUtil.java           Flexmark wrapper
    │       ├── PdfExportUtil.java          PDFBox report generator
    │       ├── UtrackFileUtil.java         .utrack import/export
    │       └── VisualizationRenderer.java  all Canvas chart drawing
    └── resources/com/unitracker/
        ├── view/Dashboard.fxml
        ├── css/styles.css
        └── fonts/  (see PLACE_FONTS_HERE.txt)
```

## Quick setup

1. Install **JDK 25** (JavaFX 25 will not run on anything older than JDK 23).
2. Install **NetBeans IDE** (any recent version — this is a plain Maven
   project, which NetBeans 12+ opens natively: no separate NetBeans-project
   conversion needed).
3. `File → Open Project…` → select this `UniTracker` folder.
4. Right-click the project → **Clean and Build** (downloads dependencies —
   the one step that needs internet; the app itself is fully offline
   afterward).
5. Click the green **Run** button. `nbactions.xml` already binds it to
   `javafx:run` with the `--add-modules javafx.web,javafx.swing` flags the
   project needs, so no manual "Run Maven Goals…" step is required.

First launch creates `~/.unitracker/unitracker.db` automatically and seeds
three example skills (each under its own Category) so the UI isn't empty.

## Known limitations (by design, for a PoC)

- No user authentication / multi-profile support — it's a single local user.
- No automated tests included.
- `.utrack` is hand-rolled pipe-delimited text, not JSON/versioned.
- JavaFX CSS has no true `backdrop-filter` blur, so the "glass" look is
  approximated with translucent fills + borders + shadows (see the comment
  block at the top of `styles.css`).

