# PocketBoot Development Progress

## Current Milestone: v4.9.0 — Wave 5 (Direct-Mode USB-C OTG Upgrade — 4 Modes)
- **Wave 5 Complete (`v4.9.0`, `versionCode = 40`):**
  - **Direct 4-Mode USB-C OTG Flasher (`OtgUsbFlasher.kt`, `DiskPartitioner.kt`, `WindowsImageBuilder.kt`, `LinuxImageBuilder.kt`, `VentoyImageBuilder.kt`):** Direct-to-pen-drive **Direct Windows USB** (full tweaks, `boot.wim` bypass, Driver Packs), **Direct Linux + Persistence** (Extracted FAT32 / Hybrid DD + dynamic ext4 persistence), **Direct Multi-Boot USB** (full-capacity physical Multi-Boot + tweaks/drivers), and **Raw / Streaming DD**, with **0 GB phone `.img` overhead**.
  - **9-Layer Anti-Brick Protection:** Adds `512 B` sector guard, multi-namespace `sm unmount`, `4 MiB` head + `2 MiB` tail wipe, silent `losetup` + `rereadpt` fallback, `2 TiB` MBR lock, live disconnect watchdog, and safe-to-unplug write-cache flush.
  - **Wave 5 JVM Unit Tests (`Wave5OtgRufusTest.kt`):** 8th JUnit suite covering fixed-disk GPT/MBR layouts, 2 TiB/4Kn guards, and `sm` volume isolation.

## Previous Milestone: v4.8.0 — Wave 4 (Compressed Image Engine & Physical USB-C OTG Pen Drive Flasher)
- **Wave 4 Complete (`v4.8.0`, `versionCode = 39`):**
  - **Compressed Image Engine (`CompressedImageEngine.kt`, `build-apk.yml`):** Bundled static ARM64 `libzstd.so` + `lib7zzs.so` background extraction for `.img`/`.iso` `.xz`/`.gz`/`.zst` archives with gzip wrap guard, live 256 MB storage floor monitor, atomic temp-file swap, and mount/combo isolation.
  - **Physical USB-C OTG Pen Drive Flasher (`OtgUsbFlasher.kt`, `UsbBuildService.kt`, `MainActivity.kt`):** 7-layer anti-brick sysfs + `/proc/mounts` lock, clean `vold` unmount gate, raw + streaming compressed flash, `/sys/block/<dev>/stat` progress, Head+Tail / Full SHA-256 read-back verification, and typed `FLASH` confirmation.
  - **Wave 4 JVM Unit Tests (`Wave4FeaturesTest.kt`):** Unit tests for compressed format/size parsing and 10-case OTG anti-brick matrix.
  - *(USB HID BIOS Hotkey Helper omitted per project owner decision.)*

## Previous Milestone: v4.7.2 — Wave 3 (Driver Pack Library & Dual-LUN Quick-Mount Combos)
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
