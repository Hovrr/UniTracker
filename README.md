# UniTracker

A JavaFX desktop dashboard with Dark Mode Glassmorphism UI for tracking multi-skill learning progress.

UniTracker is an offline desktop application for tracking many skills at once: programming languages, instruments, crafts, or anything else you are learning. It organises your work as a hierarchical `Category` > `Main Skill` > `Subskill` tree, links every log entry to a calendar date, and renders your progress as charts. Built with Java 25, JavaFX 25 and SQLite, it requires no network access at runtime.

This document describes **v2.0.0**.

---

## Key Features

### Data Integrity

- **Derived Point Rollups**
  Skill points are never accumulated in mutable application state. Every total is derived from the SQLite log table on demand using recursive CTEs, so a subskill's points reach its parent skill and its category by construction. There is no cached total that can drift from the rows it was computed from.

- **Safe Mutations with Undo and Redo**
  Editing a skill, deleting one, or logging progress are command objects, so any of them can be reversed. Because the cache is derived rather than stored, undo cannot leave a stale figure behind.

- **Completion Estimates**
  Each skill projects an estimated completion date from recent activity velocity, so you can see the consequence of the current pace rather than only the progress so far.

### Focus Timer

- **Deadline-Based Timing**
  The Focus Timer counts down against an absolute `Instant` deadline instead of decrementing a counter once per frame, so the session length does not drift with frame rate or a busy event loop.

- **Crash Resilience**
  Timer state is persisted on every transition across `IDLE`, `RUNNING` and `PAUSED`. If the application is terminated unexpectedly, an active or expired session is recovered at the next launch rather than lost.

- **Floating Widget**
  An always-on-top, transparent Floating Timer window can be detached from the Dashboard. Both windows are driven by a single central ticker, so the clock cannot diverge between them when you switch focus.

- **Non-Modal Completion**
  Finishing a session commits it immediately and offers undo. There is no modal dialog waiting to be dismissed, which previously interrupted whatever you were doing.

### Progress Presets and Mastery Curves

- **Progress Presets** define what a unit of effort is worth: set a duration and the points it awards once, instead of per log entry.
- **Mastery Curves** convert a skill target into a ladder of milestones at fixed ratios, such as basic proficiency and working competence.
- Both are integrated directly into the Add Skill workflow and remain fully editable afterwards.

### Appearance and Scaling

- **Live Settings Dialog**
  A write-through settings dialog covers appearance, focus timer durations, custom point rewards and mastery curves. Changes apply immediately with no restart.
- **Resolution-Aware Scaling**
  The interface scales across four tiers: `Compact`, `Default (1080p)`, `Large (1440p)` and `Extra large (4K)`, plus an `Automatic` setting that picks a tier from the display diagonal. Scaling is applied to every open window at once.
- **Motion Toggle**
  A global animation switch can disable transitions and effects. When motion is off, end-state properties are applied directly so the interface stays fully usable without animation.

### Visualizations

- **Comb-Shaped and Radar** diagrams with hierarchy depth filters and automatic text wrapping.
- **Skill-Tree** diagram: a recursive branching map of categories and subskills to unlimited depth.
- **Velocity, Time Split, Curve and I-Shaped** charts for single-skill progress.
- Per-chart **H-Spacing** and **V-Spacing** controls, a **Rotate Text** option, zoom, and auto-center.
- A comfortable vertical drag target on the chart divider, so the chart panel can be resized without hunting for a hairline.

### Logging and Notes

- **Calendar Logging**: log minutes and points against any date, including missed sessions and planned work. Dates with history are marked.
- **Batch Log Session**: right-click the Log Session button to record many sessions across a date range in one dialog.
- **Advanced Log**: a dedicated modal for detailed history, with a dark-themed calendar popup, non-editable click-to-open date fields and vertically stacked batch range pickers.
- **Rich-Text Sticky Notes**: linked to the selected date, with Markdown formatting, interactive checkboxes that survive formatting, and drag-and-drop reordering.

### Output

- **PDF Report Export** of the progress summary and charts, with automatic word wrap.
- **Custom colours** per skill through a dark-theme colour picker, and a completion animation when a skill reaches 100 percent.

---

## Tech Stack

| Concern | Library | Version |
|---|---|---|
| UI framework | JavaFX (controls, fxml, web, swing, media) | 25.0.1 |
| Local storage | SQLite via `org.xerial:sqlite-jdbc` | 3.53.2.0 |
| Markdown rendering | Flexmark-Java (`flexmark-all`) | 0.64.8 |
| PDF export | Apache PDFBox | 3.0.7 |
| Windows packaging | Launch4j Maven Plugin | 2.7.0 |
| Build tool | Maven with `javafx-maven-plugin` | 0.0.8 |
| Test framework | JUnit 5 (Jupiter) | 5.11.4 |

Data is stored in `~/.unitracker/unitracker.db`, created automatically on first launch.

---

## Requirements

- Windows 10 or later for the bundled executable.
- A Java 25 runtime. The Windows bundle does not embed a JVM, so Java must already be installed.

---

## Installation

1. Go to the [Releases](https://github.com/Hovrr/UniTracker/releases) page.
2. Download `UniTracker-2.0.0-windows-x64.zip`.
3. Extract the archive to a folder on your machine. Keep `UniTracker.exe` and `uni-tracker-windows.jar` in the same directory: the executable resolves the jar by name at launch.
4. Run `UniTracker.exe`.

The same release also publishes `UniTracker.exe` and `uni-tracker-windows.jar` as separate assets if you prefer to place them yourself.

---

## Building from Source

Requirements: JDK 25 and Maven. JavaFX 25 does not run on anything older than JDK 23.

```bash
# Standard build: produces target/uni-tracker-2.0.0.jar
mvn clean package

# Windows executable bundle: produces target/UniTracker.exe
# and target/uni-tracker-windows.jar
mvn clean package -Pwindows-exe

# Development run
mvn javafx:run
```

The `windows-exe` profile is separate on purpose so that `mvn javafx:run` keeps working normally during development. It forces the `win` classifier JavaFX binaries regardless of the host operating system.

NetBeans users can open this folder directly as a Maven project. `nbactions.xml` binds Run to `javafx:run` with the module flags the project needs, so no manual configuration is required.

---

## Verification and Tests

```bash
mvn test                # JUnit 5: 67 tests covering the data model, timer and presets
python devcheck-xml.py  # every XML and FXML file parses
python build-check.py   # all main and test sources compile
python run-tests.py     # SQL, derived-point and timer-state harnesses
python devcheck-fxml.py # headless JavaFX layout, scaling and interaction checks
```

| Suite | What it proves |
|---|---|
| `mvn test` | Derived-point rollups, timer state transitions and preset serialisation behave as specified. |
| `SqlLogicCheck` | Schema and query behaviour against a real throwaway SQLite database. |
| `DerivedPointsCheck` | Recursive rollup arithmetic, including reparenting and undo interleaving. |
| `TimerStateCheck` | Deadline arithmetic, expiry, pause and resume, and window handoff. |
| `devcheck-fxml.py` | Every view loads and wires up; layout invariants hold at each scale tier; the chart divider is reachable; DatePicker contrast and action-row labels are verified against measured layout. |

`build-check.py` must be run before `devcheck-fxml.py`, because the layout harness executes compiled classes. The Python harnesses run with assertions enabled and against a temporary `user.home`, so they cannot open your real database.

---

## Project Structure

```text
UniTracker/
├── pom.xml
├── nbactions.xml                        NetBeans Run/Debug bindings
├── build-check.py                       compile gate
├── run-tests.py                         non-GUI harness entry point
├── devcheck-xml.py                      XML and FXML parse gate
├── RELEASE_NOTES_v2.0.0.md
└── src/
    ├── main/java/com/unitracker/
    │   ├── MainApp.java                 entry point
    │   ├── command/                     Undo/Redo command objects and snapshots
    │   ├── controller/
    │   │   ├── DashboardController.java main view controller
    │   │   ├── FloatingTimerController.java
    │   │   ├── FloatingTimerWindow.java
    │   │   ├── SettingsDialogController.java
    │   │   └── CurveEditor.java
    │   ├── db/DatabaseHelper.java       schema, hierarchical CRUD, recursive rollups
    │   ├── devcheck/                    headless verification harnesses
    │   ├── model/                       Skill, ProgressLog, CalendarNote, ProgressPreset
    │   └── util/                        UiScale, FocusTimerState, AppSettings, Anim,
    │                                    MarkdownUtil, PdfExportUtil, VisualizationRenderer
    └── resources/com/unitracker/
        ├── view/                        Dashboard.fxml, FloatingTimer.fxml,
        │                                SettingsDialog.fxml
        ├── css/styles.css
        └── fonts/                       Poppins and Space Grotesk (SIL Open Font License)
```

---

## License

Released under the MIT License. Bundled fonts are licensed under the SIL Open Font License; see `src/main/resources/com/unitracker/fonts/OFL.txt`.