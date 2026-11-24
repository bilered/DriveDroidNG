# Changelog

## v4.5.0-v26.3.2 (2026-11-24)
- **Gate 2B (`7zzs`) Fix:** Renamed Step 4 atomic temp ISO from `.iso.tmp` to `.tmp.iso` and passed `-tiso` explicitly so `7zzs` never misidentifies or deletes a valid freshly packed ISO; cleaned orphan temp ISOs on cancel/failure.
- **MTP / MediaStore Sync Hardening (`Gate 2C`):** Added `--user 0` to all root `content` CLI calls, forced FUSE `/storage/emulated/0` dentry revalidation before scanning, and replaced the blind 600 ms sleep with a blocking `CountDownLatch` `MediaScannerConnection` callback.
- **UUP Retention & Cache UX Fixes:** Replaced GNU `\|` regex in `updateConversionKeepPackages()` with Toybox-compatible `sed -e` expressions; honored explicit OFF toggles in both Download and Offline Rebuild sheets; excluded kept `isCachedReady` `PKGS/` folders from Settings *Temp & Build Cache* size stats.
- **Offline `Edge.wim` Merge Fix:** Exported `WIMLIB_IMAGEX_IGNORE_CASE=1` and passed `--no-acls` on `wimlib-imagex update` so Edge overlays cleanly without case-duplicate directories on Android/Linux.

## v4.5.0-v26.3.1 (2026-11-24)
- Fixed Kotlin compilation errors in `WindowsBuildEngine.kt` (raw-string escaping for `sed`/`grep` commands and removed duplicate `qOutIso` declaration; credited existing ISO size in Step 4 free-space check).

## v4.5.0-v26.3 (2026-11-24)
- **MTP Truncation Fix (3-layer):** Pre-pack MediaProvider purge before `xorriso` writes, atomic `.tmp` ISO packing + rename, and `Gate 2C` MediaStore `_size` verification & force-sync.
- **UUP Package Retention Fix:** Persisted `keepUupPackagesAfterBuild` (default `true`) in `AppPrefs.kt`, synced live Build Monitor toggle to `pb_build.json`, protected existing cached builds on re-runs, and preserved `isCachedReady` folders during temporary cache cleanup.
- **Offline Microsoft Edge (`Edge.wim`):** Added `Edge.wim` manifest support and automatic Step 3/4 `install.wim` integration when present.
- **Documentation:** Added `PROGRESS.md`, `TODO.md`, `KNOWN_ISSUES.md`, and `CHANGELOG.md`.

## v4.5.0-v26.2 (2026-11-24)
- Fixed OOBE *"Why did my PC restart?"* loop by keeping `Winlogon\Shell = explorer.exe` during OOBE and polling `OOBEInProgress = 0` before servicing.
- Fixed non-elevated `PocketBoot_WaitScreen.cmd` watchdog check.
- Added `Gate 2A` ISO-9660 Primary Volume Descriptor exact byte-size audit.
