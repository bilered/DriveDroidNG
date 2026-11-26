# PocketBoot Development Progress

## Current Milestone: v4.6.8 (Dedicated Full-Screen System Tweaks, 1:1 Catalog Parity, Search History, Custom Presets, Store-Safe Windows Update Pause & Optional Undo Script)
- **Dedicated Full-Screen `SystemTweaksScreen.kt`:**
  - Full-screen edge-to-edge UI titled **"System Tweaks"** with collapsible sections (`Essential (21)`, `Customize Preferences (21)`, `Advanced — Caution (29)`, `Performance Plans (1)`), live active counts, **"Active Only"** filter, unsaved-changes Back guard, and inline `.cmd` preview (`Specialize.cmd`, `SetupComplete.cmd`, `Undo.cmd`).
  - Live **Search Bar with Persistent Search History** (up to 8 recent searches in `AppPrefs.kt`, starter suggestion chips, per-chip delete, and Clear).
  - Per-tweak **`ⓘ` Info Dialog** on every single tweak and MPO/Undo option detailing exact registry keys, services, tasks, impact tags, and side effects.
  - Built-in **`Standard`**, **`Minimal`**, **`Advanced`**, **`Desktop`**, and **`Laptop`** presets + **`+ Save Preset`** custom System Tweaks presets backed up in Settings JSON v3 + live preset match / `(Modified)` indicator.
  - Optional **"Add Undo Script"** badge toggle (OFF by default, excluded from active count & preset match): drops auto-elevated `Undo_PocketBoot_Tweaks.cmd` to `%PUBLIC%\Desktop\` only when checked and tweaks are active.
- **1:1 Tweaks Parity & All 9 Pre-Build Audit Fixes:**
  - Moved **Classic Right-Click Menu** out of Pane 1 into **System Tweaks → Advanced** with automatic legacy preset & Multi-Boot metadata migration.
  - Added Store-Safe **Disable Windows Update** (2099 UX pause + `AUOptions=2`/`NoAutoUpdate=1` policy guard; preserves Store, Game Pass, Optional Features & Windows Update drivers), **Security-Only Updates**, **Disable Windows Security (Defender)**, 3-state **MPO** selector, and expandable **AppX Debloat Sub-Picker** (`excludedAppxPackages`).
  - Upgraded Multi-Boot self-contained XML to GZip-chunked (~1,200-char) stagers supporting `PocketBoot_Specialize.cmd`, `SetupComplete.cmd`, and `Undo_PocketBoot_Tweaks.cmd`.
  - Replaced interactive `cleanmgr` with headless DISM/cache cleanup and added VSS-ready Restore Point creation in `SetupComplete.cmd`.
  - Scrubbed all remaining user-facing `WinUtil` strings and credited **Chris Titus Tech WinUtil (MIT)** exclusively in **Settings → Open-Source Credits**.
