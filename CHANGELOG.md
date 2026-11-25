## v4.6.3 (2026-11-25)
- **Android 15 Lifecycle & Edge-to-Edge Fixes (`AndroidManifest.xml`, `FastIsoDownloader.kt`, all 10 UI files):**
  - Preserved `MainActivity` across orientation/screen-size/keyboard config changes so rotating the phone never kills active USB or Ventoy builds.
  - Added `TurboDownloadService.onTimeout(startId, fgsType)` for Android 15 `dataSync` foreground service compliance (pauses cleanly with `.pbmeta` state saved).
  - Applied `.navigationBarsPadding().imePadding()` to all 12 `ModalBottomSheet`s so primary CTA buttons and text inputs never hide behind 3-button navigation bars or software keyboards.
- **Unified Thermal Control, PID-Tree Cancel & Conflict Guards (`LinuxImageBuilder.kt`, `VentoyImageBuilder.kt`, `MainActivity.kt`, `SettingsScreen.kt`):**
  - Wired `BuildThermalHelper` thermal limits, cooldown checks, and immediate PID-tree process termination on Cancel into both Linux and Ventoy builders.
  - Blocked concurrent USB/Ventoy builds and locked **Settings → Clean Temp** while any build or download is active.
- **Data Safety & Dual-LUN Fixes (`UsbGadgetEngine.kt`, `MainActivity.kt`, `BrowseAndBrowserSheets.kt`, `WindowsImageBuilder.kt`, `CreateBlankSheet.kt`):**
  - Fixed Dual-LUN deletion in both the Drives screen and Root File Browser so deleting a mounted image only unmounts its specific LUN and keeps `UsbKeepAliveService` alive if the other LUN is still mounted.
  - Added `UsbGadgetEngine.sanitizeOutputFileName()`, mounted-target & source-ISO overwrite guards, inline overwrite warning banners across all 4 image builders, `extraDriversDir` sizing in `WindowsImageBuilder.kt`, and `syncMediaStoreFileSize()` in `createBlankImage()`.
- **Network Thread Safety, Copy & Code Hygiene (`WindowsDirectCatalog.kt`, `FastIsoDownloader.kt`, `distros.json`, `AppPrefs.kt`):**
  - Replaced unsynchronized WebView flags with `AtomicBoolean`, isolated Microsoft CDN cookies to a local `CookieManager` (no global `CookieHandler.setDefault` mutation), updated all 6 stale `'All Channels'` error strings, synced catalog version to `463`, and removed all 38 unused imports across 7 Kotlin files.

## v4.6.2 (2026-11-25)
- **Removed Windows ISO Slimmer (`WindowsIsoSlimmer.kt`):** Completely removed `WindowsIsoSlimmer.kt`, its File Info action card, bottom sheet, and coroutine state in `MainActivity.kt`, plus unused `cleanStock()` in `WindowsCustomization.kt`.
- **Dropped Unused `GNU xorriso` Binary (`build-apk.yml`, `BuildThermalHelper.kt`, `DiagnosticsSheet.kt`, `SettingsScreen.kt`):** Removed `libxorriso.so` cross-compilation and `XOR_SMOKE` from GitHub Actions CI, removed `xorriso` from open-source credits and diagnostics (`Windows Imaging Tools` now checks `7zzs` and `wimlib`), and simplified `BuildThermalHelper.kt` for **Windows USB Builder**.

## v4.6.1 (2026-11-25)
- **ISO Slimmer Official-ISO Parity (`WindowsIsoSlimmer.kt`):**
  - Added case-insensitive resolution for `sources/`, `efi/`, and `boot/` folders plus full split-WIM (`install.swm` `--ref`) source support.
  - Defaulted ISO Slimmer to **Clean Official Stock** (all tweaks OFF unless enabled), auto-enabled `sources/ei.cfg` when trimming editions, decoupled `useCa2023Bootloaders` from `autounattend.xml`, and auto-detected `uiLang` from `lang.ini`.
  - Switched UEFI boot image preference to official `efisys.bin` (preventing Setup reboot loops), added >3.95 GiB multi-edition ESD auto-fallback to split WIM (`install*.swm`), added Win 7 UEFI fix, preserved source ISO-9660 volume IDs, and added atomic `.tmp.iso` packing with PVD size + `7zzs -tiso` verification.
- **Windows USB Builder & Customization Fixes (`WindowsImageBuilder.kt`, `WindowsCustomization.kt`):**
  - Made `sources/`, `efi/`, CA 2023, and Win 7 UEFI snippets case-safe and `.swm`-aware; updated `inspectEditionsViaBundledTools()` to detect `install.swm`.
  - Separated `Panther/unattend.xml` (`includeWindowsPePass = false`) from root `autounattend.xml` and passed auto-detected `uiLang`.
- **Reliable Cancel & MTP Sync (`BuildThermalHelper.kt`, `MainActivity.kt`, `FastIsoDownloader.kt`, `LinuxImageBuilder.kt`, `VentoyImageBuilder.kt`, `UsbGadgetEngine.kt`):**
  - Added native PID-tree tracking and immediate process termination when cancelling ISO Slimmer or Windows USB Builder.
  - Wired `UsbGadgetEngine.syncMediaStoreFileSize()` into all ISO/IMG builders and downloads so overwritten images sync byte-exact over USB MTP, and removed stale `pb_winbuild_cache` temp stat counting.

## v4.6.0 (2026-11-25)
- **Removed On-Phone UUP ISO Builder:** Removed `WindowsBuildEngine.kt`, `MicrosoftUupClient.kt`, `VirtualEditionsPlugin.kt`, `WindowsBuildMonitorSheet.kt`, `sync-uup-builds.yml`, `uup-builds.json`, UUP settings/caches, and `IsoBuilderService` / `IsoBuilderActionReceiver`. Eliminates OOBE servicing loops, offline Edge/AppX issues, and ~400 KB of high-maintenance code.
- **Standalone Build Thermal Helper (`BuildThermalHelper.kt`):** Preserved **Cool / Balanced / Max Speed** thermal profiles (`taskset` core affinity, `nice`/`ionice`, thread limits) and **Auto Thermal Cooldown** and wired them into both **Windows ISO Slimmer** and **Windows USB Builder** — with zero impact on normal USB Gadget ISO/IMG mounting.
- **Streamlined Official Windows Downloads (`WindowsDownloadSheet.kt`, `WindowsDirectCatalog.kt`):** Tapping Windows 11, 10, or 8.1 opens straight into Official Microsoft Retail ISOs with Release, Architecture, Language search, Copy Link, and Turbo Download.
- **Leaner CI & Cleaner Diagnostics (`build-apk.yml`, `DiagnosticsSheet.kt`, `UsbGadgetEngine.kt`):** Dropped unused `cabextract` and `chntpw` cross-compilation steps, updated Pre-Flight Diagnostics for `7zzs`/`wimlib`/`xorriso`, and simplified temp cache cleanup to reclaim any old `.pocketboot_tmp` UUP folders.

## v4.5.0-v26.4.1 (2026-11-25)
- **OOBE "Why did my PC restart?" Hotfix:** Removed the premature `Winlogon\Shell` overwrite and `taskkill /im explorer.exe` from the top of `PocketBoot_Updates.cmd` (which fired ~3 s after `SetupComplete.cmd` before `OOBEInProgress` flipped to `0x1`, crashing `defaultuser0` OOBE).
- **Safe Fullscreen Pre-Desktop Overlay on Boot 1 & Boot 2:** Boot 1 launches `PocketBoot_WaitScreen.ps1` directly from `HKLM\...\Run` (zero CMD flash, fullscreen `TopMost` dark overlay after OOBE without killing `explorer.exe`), while Boot 2 sets `Winlogon\Shell` only *after* Phase 1 DISM completes and verifies `OOBEInProgress`, `WWAHost.exe`, and `msoobe.exe` are all inactive.
- **Reliable Offline Edge MSI Install:** Deferred `MicrosoftEdgeEnterprise.msi` installation to `PocketBoot_Updates.cmd` (`SystemSetupInProgress=0`) when updates are queued so Windows Installer (`msiserver`) installs it cleanly on the first attempt.

## v4.5.0-v26.4 (2026-11-25)
- **All Inbox Store Apps Default ON + UI Simplification:** Removed extra 4-way Store App sub-options in Download & Offline Rebuild sheets; replaced with a single default-ON *Include All Inbox Store Apps* toggle (always visible for Win 11 builds).
- **Bulletproof Store Apps Fetcher (Zero Silent Loss):** Added 2.2 s UUPdump pacing, fixed `MicrosoftUupClient.kt` to always merge embedded `<Files>` from `GetExtendedUpdateInfo2`, added zero-rate-limit Direct Microsoft Store App failover, pre-build manifest audit logging, and a hard guard that aborts instead of building a 0-app ISO.
- **Guaranteed Offline Microsoft Edge:** Added automatic fallback to official Microsoft Edge Enterprise Stable MSI (`edgeupdates.microsoft.com/api/products?view=enterprise` + Microsoft fwlink fallback) cached in `PKGS/` and installed silently during `SetupComplete.cmd` (with post-boot retry) when `Edge.wim` is absent.
- **Restored "Previous version of Setup":** Patched the 5 `LabConfig` hardware bypass keys directly into `boot.wim` Index 2's offline `SYSTEM` hive (`chntpw`) and omitted root `autounattend.xml` while keeping `$OEM$\$$\Panther\unattend.xml` (Microsoft Implicit Search Order #3).
- **Full Pre-Desktop Installation & Dark-Mode Progress Screen:** Locked `Winlogon\Shell` immediately after `OOBEInProgress=0x0` (before Phase 1 DISM starts), suppressed early `explorer.exe`, and added a fullscreen dark-mode WinForms overlay (`PocketBoot_WaitScreen.ps1`) with live phase indicator, single progress bar, elapsed timer, and anti-lockout watchdog so the user never sees a `24H2` desktop before `26H2` finishes.
- **Servicing Reliability & Polish:** Checked CBS `RebootInProgress` before burning servicing boot attempts, extended post-LCU `TiWorker` settle wait, fixed `/ResetBase` via a self-deleting post-reboot background task, skipped known-failing `MicrosoftSolitaireCollection` retries, fixed unescaped `&` in batch `echo` lines, and auto-saved `PocketBoot_LastBuild.log` on every successful build.

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
