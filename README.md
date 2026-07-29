# 🚀 Uni Tracker — Multi-Skill Progress Dashboard

**Uni Tracker** is an offline desktop app for tracking multiple skills at once (programming languages, instruments, anything). Featuring a **Dark Mode Glassmorphism UI**, it is designed to help ambitious learners, self-taught developers, and designers map, track, and visualize their progress in a structured way. 

It utilizes a hierarchical Category → Skill → Subskill tree, calendar-linked sticky notes, real-time progress tracking, and several ways to visualize how you're doing. Built with Java 25 + JavaFX 25 + SQLite, it requires no network access at runtime.

---

## ✨ Key Features

- 🌳 **Multi-Level Skill Hierarchy (Subskills)**  
  Manage skills from top-level categories down to granular details (`Category` > `Main Skill` > `Subskill 1` > `Subskill 2`, etc.) to track your learning progress with precision.
  
- 📊 **Interactive Visualization Diagrams**  
  Supports various chart types:
  - **Comb-Shaped** & **Radar Chart** (with hierarchy depth filters and automatic text word-wrap).
  - **Skill-Tree Diagram** (a genuinely recursive branching map of categories and subskills reading to an unlimited depth).
  - **Curve** & **I-Shaped**.
  - Options to *Toggle Rotate Text* and custom *H-Spacing / V-Spacing* settings (double-click to input values).

- 🖐️ **Canvas Navigation (Photoshop-like Hand Tool)**  
  - Freely pan the chart area using click & drag.
  - Quick *Zoom In / Zoom Out* using buttons or **CTRL + Scroll**.
  - *Auto-Center* feature to instantly bring the chart back to the middle of the screen.

- 📅 **Calendar & Time-Travel Logging**  
  - **Log Session**: Record daily learning time (Minutes & Points).
  - **Right-Click Date Selection**: Choose any date on the calendar as the "Active Date" to log missed sessions or future schedules.
  - **Batch Log Session**: Right-click the *Log Session* button to log multiple sessions at once within a specific *Date Range*.
  - **History Indicator**: Dates with logged histories are visually marked on the calendar.
  - **Sync Date**: A custom button to reset the active date back to the system's current date.

- 📝 **Rich-Text Sticky Notes**  
  - Linked directly to the selected calendar date.
  - Equipped with a Markdown & Rich Text toolbar: **Bold (Ctrl+B)**, **Italic (Ctrl+I)**, **Headlines**, **Bullet**, and **Numbering**.
  - **Interactive Checkbox `[ ]`**: Clickable checkboxes inside notes that don't break text formatting.
  - Reorder notes easily with **Drag & Drop**.

- 🎨 **Customization & Gamification**  
  - Custom color palettes for each skill using a *Color Picker* adapted for the Dark Mode theme.
  - **Celebration Effect**: Interactive confetti particle animations when a skill's completion progress reaches **100%**.

- 📄 **PDF Report Export**  
  Export your entire skill progress summary and visualization charts into a cleanly formatted PDF document with automatic word-wrap.

---

## 🛠️ Tech Stack

| Concern                | Library                             | Version  |
|-------------------------|--------------------------------------|----------|
| UI framework            | JavaFX (controls, fxml, web, swing) | 25.0.1 |
| Local storage            | SQLite via `org.xerial:sqlite-jdbc` | 3.53.2.0 |
| Markdown rendering       | Flexmark-Java (`flexmark-all`) | 0.64.8 |
| PDF export               | Apache PDFBox | 3.0.7 |
| Build tool               | Maven + `javafx-maven-plugin` | 0.0.8 |

---

## 📥 How to Use (Portable Executable)

For Windows users who want to run the application directly without compiling the source code:

1. Go to the **[Releases](../../releases)** section in this repository.
2. Download the latest `.zip` release package (e.g., `UniTracker-v1.0.0-Windows.zip`).
3. Extract the `.zip` file to a local folder on your computer.
4. Run **`UniTracker.exe`**.

> **Note:** If your computer does not have Java 25 installed, ensure the bundled `jre` folder remains in the exact same directory as `UniTracker.exe`.

---

## 💻 Compilation Guide & Quick Setup

If you want to compile and build the project from the source code:

1. Install **JDK 25** (JavaFX 25 will not run on anything older than JDK 23).
2. Install **NetBeans IDE** (any recent version — this is a plain Maven project, which NetBeans 12+ opens natively: no separate NetBeans-project conversion needed)[cite: 3].
3. `File → Open Project…` → select this `UniTracker` folder[cite: 3].
4. Right-click the project → **Clean and Build** (downloads dependencies — the one step that needs internet; the app itself is fully offline afterward)[cite: 3].
5. Click the green **Run** button[cite: 3]. `nbactions.xml` already binds it to `javafx:run` with the `--add-modules javafx.web,javafx.swing` flags the project needs, so no manual "Run Maven Goals…" step is required[cite: 3].

First launch creates `~/.unitracker/unitracker.db` automatically and seeds three example skills (each under its own Category) so the UI isn't empty[cite: 3].

---

## 📂 Project Structure

```text
UniTracker/
├── pom.xml[cite: 3]
├── nbactions.xml                       NetBeans Run/Debug → javafx:run[cite: 3]
├── README.md[cite: 3]
└── src/main/[cite: 3]
    ├── java/com/unitracker/[cite: 3]
    │   ├── MainApp.java                    entry point[cite: 3]
    │   ├── command/                        Undo/Redo (Command Pattern)[cite: 3]
    │   │   ├── Command.java[cite: 3]
    │   │   ├── CommandManager.java[cite: 3]
    │   │   ├── SkillSnapshot.java[cite: 3]
    │   │   ├── LogProgressCommand.java[cite: 3]
    │   │   ├── EditSkillCommand.java[cite: 3]
    │   │   └── DeleteSkillCommand.java[cite: 3]
    │   ├── controller/DashboardController.java[cite: 3]
    │   ├── db/DatabaseHelper.java          SQLite schema + hierarchical CRUD[cite: 3]
    │   ├── model/[cite: 3]
    │   │   ├── Skill.java                  tree node (Category/Skill/Subskill N)[cite: 3]
    │   │   ├── CalendarNote.java[cite: 3]
    │   │   └── ProgressLog.java[cite: 3]
    │   └── util/[cite: 3]
    │       ├── MarkdownUtil.java           Flexmark wrapper[cite: 3]
    │       ├── PdfExportUtil.java          PDFBox report generator[cite: 3]
    │       ├── UtrackFileUtil.java         .utrack import/export[cite: 3]
    │       └── VisualizationRenderer.java  all Canvas chart drawing[cite: 3]
    └── resources/com/unitracker/[cite: 3]
        ├── view/Dashboard.fxml[cite: 3]
        ├── css/styles.css[cite: 3]
        └── fonts/  (see PLACE_FONTS_HERE.txt)[cite: 3]
