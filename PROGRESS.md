# PocketBoot Development Progress

## Current Milestone: v4.6.4 (Official Rufus-Parity Windows USB Builder)
- **Offline `boot.wim` Index 2 `LabConfig` Patching (`WindowsCustomization.kt`, `WindowsImageBuilder.kt`, `build-apk.yml`):**
  - Re-bundled static ARM64 `libchntpw.so` (`reged-static`, ~200 KB) in CI and Pre-Flight Diagnostics (`Windows Imaging Tools`).
  - Hardware bypasses (`BypassTPMCheck`, `BypassSecureBootCheck`, `BypassRAMCheck`, `BypassCPUCheck`, `BypassStorageCheck`) are now written directly into `sources/boot.wim` Index 2's offline `SYSTEM` registry hive—matching official Rufus (`pbatard/rufus/src/wue.c`).
- **Genuine Microsoft WinPE Setup Flow Preserved:**
  - Removed automatic USB-root `autounattend.xml` injection for standard builds; OOBE/Specialize tweaks now live strictly in `sources/$OEM$/$$/Panther/unattend.xml` (no `windowsPE` pass).
  - Windows 11 24H2/25H2/26H2 Setup now shows the complete official Microsoft wizard: Language → Select Setup Option (**with *"Previous version of Setup"* visible**) → Product Key → Edition → **License Terms (*"I accept"*)** → Select Disk.
  - USB-root `autounattend.xml` is only written if the user explicitly picks a Pre-Selected Edition, enables Full Unattended Skip OOBE, or if offline `boot.wim` patching is unavailable.
- **Clean Pure-XML Rufus Defaults & Builder Polish:**
  - Defaulted `qolImprovements = false` and separated `needsSpecializeScript()` so pure Rufus-style builds stage **zero `.cmd` scripts** (`PocketBoot_Specialize.cmd` / `SetupComplete.cmd` are only staged when QoL/Classic Menu/Sponsored Bloat/WinUtil/Custom Script is toggled ON).
  - Gated `<HideEULAPage>true</HideEULAPage>` strictly to `skipOobe`, wired bundled `libmkfs_fat.so` into the FAT32 fallback, tracked PID on loop-mount fallback copy, and removed dead `WindowsBuildConfig` fields & unused `xmlEscape()`.
