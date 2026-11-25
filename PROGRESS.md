# PocketBoot Development Progress

## Current Milestone: v4.6.2 (Lean Production Focus — ISO Slimmer Removed)
- **Removed Windows ISO Slimmer:** Deleted `WindowsIsoSlimmer.kt` (46.8 KB), removed the ISO Slimmer UI action/sheet/state from `MainActivity.kt`, removed unused `GNU xorriso` cross-compilation and smoke-test steps from `.github/workflows/build-apk.yml`, and trimmed unused `xorriso`/WIM-thread fields from `BuildThermalHelper.kt`, `DiagnosticsSheet.kt`, `SettingsScreen.kt`, and `WindowsCustomization.kt`.
- **Preserved Core Capabilities:** Official Direct Microsoft Retail ISOs (`WindowsDirectCatalog.kt`), Rufus-style Windows USB Builder (`WindowsImageBuilder.kt` with case-safe paths, split-WIM inspection, CA 2023/Win 7 bootloaders, thermal control, PID-tree cancel, and MTP size sync), Ventoy multi-boot drives, Linux USB Builder, and USB Gadget booting remain 100% intact.
