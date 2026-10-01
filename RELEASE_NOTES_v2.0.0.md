# UniTracker v2.0.0

A JavaFX desktop dashboard with Dark Mode Glassmorphism UI for tracking multi-skill learning progress.

## Overview

UniTracker v2.0.0 is a major architectural and UI/UX overhaul focused on data integrity, crash resilience, resolution-aware scaling, and runtime performance.

## Key Changes and New Features

### 1. Source-of-Truth Data Architecture

- Replaced mutable in-memory point accumulation with a derived-from-source-of-truth SQLite model using recursive CTE rollups.
- Eliminated stale point state and data corruption risks across skill hierarchies, edits, deletions, and undo/redo operations.
- Added estimated completion date (ETA) projections based on recent activity velocity.

### 2. Crash-Resilient Focus Timer and Floating Widget

- Rebuilt the Focus Timer around absolute `Instant` deadlines rather than frame-dependent countdown counters.
- Persisted timer state on every state transition (`IDLE`, `RUNNING`, `PAUSED`) to survive unexpected application termination and automatically recover active or expired sessions on startup.
- Added an always-on-top, transparent Floating Timer widget driven by a single central ticker to prevent clock drift during window handoffs.
- Removed blocking modal dialogs upon timer completion in favor of immediate session commits with non-modal Undo support.

### 3. Live Settings and Resolution Scaling

- Added a live write-through Settings dialog covering appearance, focus timer duration presets, custom point rewards, and mastery curves.
- Implemented `em`-based CSS scaling (`Compact`, `Default (1080p)`, `Large (1440p)`, `Extra large (4K)`, and `Automatic`) that updates all open windows in real time without requiring an application restart.
- Added a global animation and transition toggle that applies end-state properties immediately when motion is disabled.
- Added customizable Progress Presets and Mastery Curves with ratio-based milestones integrated directly into the Add Skill workflow.

### 4. UI/UX Refinements and Layout Stability

- Redesigned the Advanced Log modal (right-click on Log Session) with a full dark-theme calendar popup, non-editable click-to-open date fields, vertically stacked Batch (Date Range) pickers, and anchored delta window resizing.
- Enlarged the vertical resize hit-box for multi-skill charts (`Comb-Shaped`, `Skill-Tree`, `Radar`) while preserving full-height layout restoration when switching back to single-skill charts (`Velocity`, `Time Split`, `Curve`, `I-Shaped`).
- Protected 1080p vertical layouts so expanding sidebar sections never crushes the main skill ProgressBar or clips action controls.
- Preserved active skill ComboBox selection across manual Refresh actions.

### 5. Performance, Memory Management, and Verification

- Mitigated JavaFX WebView memory retention in Sticky Notes by severing JS-bridge references, unloading documents to `about:blank`, and debouncing search queries.
- Marshaled WebKit checkbox bridge database writes onto the JavaFX application thread to eliminate JDBC concurrency hazards.
- Eliminated N+1 SQL queries in stalled-status batch updates, PDF exports, and monthly calendar aggregations.
- Coalesced SplitPane divider resize events and removed canvas scrollbar feedback loops.
- Added a comprehensive automated verification suite covering SQL rollups, timer state transitions, FXML layout invariants, and JUnit 5 headless tests.

## Verification

| Suite | Result |
|---|---|
| `mvn test` (JUnit 5) | 67 tests, 0 failures |
| `SqlLogicCheck` | all assertions passed |
| `DerivedPointsCheck` | 121 checks passed |
| `TimerStateCheck` | 121 checks passed |
| `devcheck-xml.py` | 4 of 4 XML files valid |
| `devcheck-fxml.py` | 18 layout, scaling and interaction checks passed |

## Download

- `UniTracker-2.0.0-windows-x64.zip`: the Windows x64 bundle. Extract it and run `UniTracker.exe`. A Java 25 runtime is required.