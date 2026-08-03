# 🚀 Uni Tracker v1.1.0 - The Productivity & UI Overhaul

Welcome to the biggest update Uni Tracker has ever had.

Version 1.0 gave you a place to record what you learned. Version 1.1.0 gives you a reason to come back tomorrow. This release adds a full Focus Timer with one-click session logging, a GitHub-style contribution heatmap, streak tracking, and two brand-new charts for spotting your long-term momentum. Underneath all of that, points you log on a single subskill now roll all the way up your hierarchy on their own, so your Category totals finally tell the truth without any manual bookkeeping.

We also spent a serious amount of this sprint on the parts you touch every day: the sidebar collapses, the dividers are actually grabbable, the charts scale cleanly from a 1080p laptop to a 4K display, and the canvas zooms with your scroll wheel.

Still zero dependencies on the cloud. Still your data, in a local SQLite file, offline, forever.

---

## ✨ New Features & Gamification

### 🌱 Bottom-Up Point Accumulation
Points logged against a Subskill now propagate automatically to its parent Main Skill and Category. Log 30 minutes of "Recursion" and watch it credit "Data Structures" and "Computer Science" in the same click.

The rollup applies a *relative delta* up the tree rather than recomputing from a snapshot, which means undoing a session removes exactly what it added. A sibling skill logged in between keeps its contribution intact.

### ⏱️ Focus Timer with Auto-Log
A real countdown timer lives in the sidebar now.

- **Nine preset durations** - 15, 25, 30, 40, 45, 50, 60, 90, and 120 minutes
- Point rewards scale with the duration you actually sat down for, rounded to the nearest half point, and the per-duration reward is fully customizable
- A notification sound when the session ends
- A **pre-filled log dialog** the moment the timer finishes, with the skill, the minutes, the points, and today's date already filled in. Confirm and it is logged. You can still correct the skill or the points first, because a session filed against the wrong skill is worse than one that costs an extra click.

### 🔥 Streaks, Heatmaps, and Decay
Three habit-tracking features that work together:

- **📅 Calendar Heatmap** - a GitHub-style contribution grid coloured by points earned per day. Your consistency, at a glance.
- **📈 Streak Counter** - current and longest streaks, computed directly in SQL. A streak survives one idle day so it does not reset at midnight before you have had a chance to use it.
- **💤 Stalled Status** - any skill left unpracticed for 14 days is automatically flagged. Decay is quiet, so the tracker is not.

### 📊 Two New Visualizations

**🥧 Time Split** - a pie chart showing where your effort actually went, as opposed to where you think it went.

**⚡ Velocity** - a line chart of points earned per day, and the headline feature of this release:

| Range | Detail |
|---|---|
| 7 days, 30 days | Quick check-ins |
| 2, 4, 6, 8, 10, 12 months | Long-term momentum |
| **Custom** | Pick any two dates you like |

Selecting **Custom** opens a lightweight dialog with Start Date and End Date pickers. It validates as you type, tells you how many days you are about to plot, and restores your previous selection if you cancel.

Month ranges use true calendar arithmetic (`LocalDate.minusMonths`), not a multiple of 30. Twelve months back from an August date in a leap year is a 366-day span, and the chart now knows that.

### 🔖 Advanced Sticky Notes
- **Global `#tag` search** across every note you have written
- A **Universal / Pinned** view for the notes you want in front of you regardless of context

---

## 🎨 UI/UX Enhancements

### 📐 Collapsible Left Sidebar
The Calendar, Focus Timer, and Sticky Notes panels are now collapsible and resizable via Accordion and SplitPane logic. If you are on a 1080p monitor, you no longer have to choose between seeing your calendar and seeing your notes. Collapse what you are not using and the rest expands to fill the space.

### ⌚ Focus Timer Redesign
The timer was rebuilt as a horizontal split:

```
 01:30:00              Lv.7
                  [ Start ]  ↺
```

- Large, **unclipped** timer text on the left
- **Leading zeros** on the hour, so it reads `01:30:00` and never `1:30:00`. Digits stay anchored instead of sliding sideways as the clock crosses from 1:xx to 0:xx.
- A compact **Start** button and **Level Badge** stacked on the right, with the reset icon sitting immediately beside Start
- The clock font scales smoothly with the sidebar width, so all eight glyphs stay on screen at any window size

### 🖼️ Chart Panel Layout
Rebuilt VBox and AnchorPane constraints so the canvas scales cleanly from 1080p all the way to 4K. No overlapping, no squishing, no panels escaping their container. Chart content is now auto-centered by default.

### 🎯 Grabbable SplitPane Dividers
An aggressive transparent CSS padding trick widens the divider's hitbox well beyond its visible line. The divider looks just as thin, but you can actually grab it on the first try.

### 🔍 Canvas Controls
- **Scroll-to-zoom** on the chart canvas
- Smoothed **H-Spacing** and **V-Spacing** sliders for gradual, polished adjustment instead of jumpy increments

### 🔤 Header & Typography
- Header buttons reordered: **Mute** on the far left, then **Tips**
- The speaker icon is now perfectly circular
- Removed the redundant "Uni Tracker" text from the header (the window title already says it)
- Every em dash in the UI has been replaced with a standard hyphen

---

## 🐛 Bug Fixes & Technical Improvements

### Fixed
- **Focus Timer overflow on narrow windows.** The timer panel used to break out of the sidebar whenever the window was un-maximized or opened on a smaller display. A rigid minimum width on the clock label was forcing the panel wider than its column could ever be. The label now shrinks gracefully and the font scales to match.
- **Window icon** now displays correctly in the OS title bar and taskbar.
- **Window title** cleaned up to use a standard hyphen.
- **Dropdown hierarchy.** Categories, Skills, and Subskills are now visually distinct in the selector, so you always know which level you are logging against.
- **"Clear Cache" renamed to "Refresh."** It always just reloaded the view. The name now says so.

### Performance
- **The Velocity chart no longer reads your entire log history.** It previously loaded every row in `progress_logs` even to draw a 7-day view. Queries are now bounded with `WHERE log_date BETWEEN ? AND ?`.
- **New database index** on `progress_logs(log_date)`. A 12-month range used to mean a full table scan. It is now an index range scan.
- **Smarter chart rendering.** Data point symbols are suppressed past 90 days, where they stop being readable and start being the single largest cost of drawing the chart. Axis tick density scales with the selected range.

### Under the Hood
- Date storage uses ISO-8601 text, which sorts chronologically and lexicographically in the same order. That is what makes range queries both correct and index-friendly.
- Core logic ships with a runnable assertion-based self-check covering streak grouping, point rollup (including the undo-after-sibling-log case), skill tree depth, timer point scaling, clock font scaling, and every Velocity date range.

---

## 📦 Upgrading from v1.0

No action required. Launch the new version and your existing database migrates in place:

- The new `progress_logs` index is created automatically on first run
- All existing skills, logs, and notes are preserved
- Your database stays exactly where it has always been, at `~/.unitracker/unitracker.db`

As always, Uni Tracker is fully offline. No account, no telemetry, no network calls.

---

## 💬 Feedback

Found something broken, or thought of a feature that should exist? [Open an issue](../../issues). This release was shaped almost entirely by real usage friction, and the next one will be too.

**Happy tracking. Go build a streak. 🔥**
