# PocketBoot Development Progress

## Current Milestone: v4.7.2 — Wave 3 (Driver Pack Library & Dual-LUN Quick-Mount Combos)
- **Wave 3 Complete (`v4.7.2`, `versionCode = 38`):**
  - **Storage & Wi-Fi Driver Pack Manager (`DriverPackManager.kt`, `WindowsImageBuilder.kt`, `VentoyImageBuilder.kt`, `SettingsScreen.kt`):** Reusable folder/`.zip`/`.7z` driver library with `.inf` validation, auto-categorization, multi-pack selection in Windows USB & Multi-Boot builders, and Settings library sheet.
  - **Dual-LUN Quick-Mount Combos (`AppPrefs.kt`, `UsbGadgetEngine.kt`, `MainActivity.kt`):** Atomic single-rebind `mountDualLunCombo()`, backup JSON persistence, and Home quick-mount bar + combo creator sheet.
  - **Wave 3 JVM Unit Tests (`Wave3FeaturesTest.kt`):** Unit tests covering Driver Pack sanitization/categorization, Dual-LUN backup round-trip, and Dual-LUN path validation.
  - *(Opt-In Wi-Fi Auto-Connect excluded per project owner request.)*

## Previous Milestone: v4.7.1 — Wave 2 (Multi-Boot Power Features & On-Phone `.img` Explorer)
- **Wave 2 Complete (`v4.7.1`, `versionCode = 37`):**
  - **Linux Persistence Inside Multi-Boot Drives (`VentoyPersistenceHelper.kt`, `VentoyImageBuilder.kt`):** Per-ISO 1–16 GB ext4 `.dat` persistence (`casper-rw` / `persistence.conf`) wired into `ventoy.json` with fail-closed space checks and clean removal.
  - **Boot Menu Aliases, Timeout & Built-In GRUB Themes (`VentoyThemeCatalog.kt`, `VentoyImageBuilder.kt`):** Per-ISO `menu_alias`, `VTOY_MENU_TIMEOUT`, and Stock / PocketBoot Dark / High-Contrast GRUB2 themes preserving all sibling JSON sections.
  - **Per-ISO Multi-Template Windows Tweak Profiles (`VentoyImageBuilder.kt`):** Assign 0 (Stock), 1, or multiple built-in/custom presets per Windows 10/11 ISO selectable at PC boot time.
  - **Direct `.img` File Explorer (`ImageLoopExplorer.kt`, `MainActivity.kt`):** On-phone GPT/MBR/Raw Partition 1 file manager (browse, import, export, mkdir, delete) with mounted-to-PC guard & guaranteed cleanup.
  - **Wave 2 JVM Unit Tests (`VentoyWave2Test.kt`):** 5 new test cases covering Wave 2 engines.

## Previous Milestone: v4.7.0 — Wave 1 (Background Build Service, Live Telemetry, In-App Updater & JVM CI Tests)
- **Wave 1 Complete (`v4.7.0`, `versionCode = 36`):**
  - **Foreground USB Build Service (`UsbBuildService.kt`, `NotificationHelper.kt`, `MainActivity.kt`):** App-scoped `UsbBuildManager` + `UsbBuildService` (`specialUse` FGS + `PARTIAL_WAKE_LOCK`) with live notification, notification Cancel button (`UsbBuildActionReceiver`), completion notification, and compact Home progress banner.
  - **Upgraded Live USB Transfer Telemetry (`MainActivity.kt`):** Tracks session transferred bytes and peak MB/s on top of existing `readUsbActivitySample()` polling.
  - **In-App Release Update Checker (`AppUpdateChecker.kt`, `SettingsScreen.kt`, `DownloadScreen.kt`):** Fixed repo URLs to `https://github.com/bilered/PocketBoot` (`DISTROS_MANIFEST_URL`, GitHub button, and GitHub Releases API updater with SemVer check + release notes dialog).
  - **Automated JVM Unit Tests & CI Gate (`app/src/test`, `app/build.gradle.kts`, `build-apk.yml`):** 4 JUnit test classes (`DiskPartitionerTest`, `WindowsCustomizationTest`, `AppPrefsBackupTest`, `FilenameAndVersionTest`) running via `gradle testDebugUnitTest` before `assembleDebug`.
- **Upcoming Planned Waves:**
  - **Wave 4 (`v4.8.0`):** Compressed Image Engine (`.xz`/`.gz`/`.zst`), Physical USB-C OTG Pen Drive Flasher, and USB HID BIOS Hotkey Helper (`f_hid`).
