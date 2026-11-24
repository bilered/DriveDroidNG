# Changelog

## v4.5.0-v26.3 (2026-11-24)
- **MTP Truncation Fix (3-layer):** Pre-pack MediaProvider purge before `xorriso` writes, atomic `.tmp` ISO packing + rename, and `Gate 2C` MediaStore `_size` verification & force-sync.
- **UUP Package Retention Fix:** Persisted `keepUupPackagesAfterBuild` (default `true`) in `AppPrefs.kt`, synced live Build Monitor toggle to `pb_build.json`, protected existing cached builds on re-runs, and preserved `isCachedReady` folders during temporary cache cleanup.
- **Offline Microsoft Edge (`Edge.wim`):** Added `Edge.wim` manifest support and automatic Step 3/4 `install.wim` integration when present.
- **Documentation:** Added `PROGRESS.md`, `TODO.md`, `KNOWN_ISSUES.md`, and `CHANGELOG.md`.

## v4.5.0-v26.2 (2026-11-24)
- Fixed OOBE *"Why did my PC restart?"* loop by keeping `Winlogon\Shell = explorer.exe` during OOBE and polling `OOBEInProgress = 0` before servicing.
- Fixed non-elevated `PocketBoot_WaitScreen.cmd` watchdog check.
- Added `Gate 2A` ISO-9660 Primary Volume Descriptor exact byte-size audit.
