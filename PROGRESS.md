# PocketBoot Development Progress

## Current Milestone: v4.7.0 — Wave 1 (Background Build Service, Live Telemetry, In-App Updater & JVM CI Tests)
- **Wave 1 Complete (`v4.7.0`, `versionCode = 36`):**
  - **Foreground USB Build Service (`UsbBuildService.kt`, `NotificationHelper.kt`, `MainActivity.kt`):** App-scoped `UsbBuildManager` + `UsbBuildService` (`specialUse` FGS + `PARTIAL_WAKE_LOCK`) with live notification, notification Cancel button (`UsbBuildActionReceiver`), completion notification, and compact Home progress banner.
  - **Upgraded Live USB Transfer Telemetry (`MainActivity.kt`):** Tracks session transferred bytes and peak MB/s on top of existing `readUsbActivitySample()` polling.
  - **In-App Release Update Checker (`AppUpdateChecker.kt`, `SettingsScreen.kt`, `DownloadScreen.kt`):** Fixed repo URLs to `https://github.com/bilered/PocketBoot` (`DISTROS_MANIFEST_URL`, GitHub button, and GitHub Releases API updater with SemVer check + release notes dialog).
  - **Automated JVM Unit Tests & CI Gate (`app/src/test`, `app/build.gradle.kts`, `build-apk.yml`):** 4 JUnit test classes (`DiskPartitionerTest`, `WindowsCustomizationTest`, `AppPrefsBackupTest`, `FilenameAndVersionTest`) running via `gradle testDebugUnitTest` before `assembleDebug`.
- **Upcoming Planned Waves:**
  - **Wave 2 (`v4.7.1`):** Multi-Boot Linux Persistence `.dat`, Boot Menu Aliases & Themes, Multi-Template Per-ISO Tweak Profiles, and Direct `.img` File Explorer.
  - **Wave 3 (`v4.7.2`):** Storage/Wi-Fi Driver Pack Manager, Opt-In Wi-Fi Auto-Connect Injection, and Dual-LUN Quick-Mount Combos.
  - **Wave 4 (`v4.8.0`):** Compressed Image Engine (`.xz`/`.gz`/`.zst`), Physical USB-C OTG Pen Drive Flasher, and USB HID BIOS Hotkey Helper (`f_hid`).
