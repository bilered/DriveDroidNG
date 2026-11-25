# PocketBoot Development Progress

## Current Milestone: v4.6.6 (100% Rufus Parity Polish & Versioned USB Volume Labels)
- **Versioned PocketBoot USB Volume Labels (`WindowsImageBuilder.kt`):**
  - Automatically probes the Windows ISO's WIM XML header (`<BUILD>` + `<SPBUILD>` + OS version, with filename & `boot.wim` fallbacks) and formats the NTFS Partition 1 label as **`POCKETBOOT- Win 11 -26300.9974`** (clamped safely to 32 chars; FAT32 fallback `PB11-26300`, <= 11 chars).
- **Exact OOBE & Local Admin Account Parity (`WindowsCustomization.kt`):**
  - Decoupled **Bypass Online Microsoft Account** (`BypassNRO`) from **Skip Wi-Fi Setup** (`HideWirelessSetupInOOBE`) and gated `HideOnlineAccountScreens` strictly to pre-created local accounts / `skipOobe` (never emits an empty `<OOBE>` block).
  - Added `<HideLocalAccountScreen>true</HideLocalAccountScreen>` and `<FirstLogonCommands>` (`net accounts /maxpwage:unlimited`) for pre-created local admin accounts, and reordered `Microsoft-Windows-Shell-Setup` and `<OOBE>` elements into strict Microsoft XSD alphabetical order.
- **Single-Pass `boot.wim` Updates & Tracked Bootloader Helpers (`WindowsCustomization.kt`, `WindowsImageBuilder.kt`):**
  - Merged `LabConfig` hive patching and `/Autounattend.xml` injection into a single `wimlib-imagex update` pass (`patchBootWimSnippet`), eliminating double rewrites on Unattended Mode builds and clarifying fallback logs.
  - Routed Win 7 UEFI and CA 2023 bootloader extraction through `BuildThermalHelper.runIsolatedRootScript(..., trackPid = true)` for instant cancellation, and added `sources/boot.wim` Index 2 fallback for CA 2023 extraction.
  - Passed `selectedEditionIndex` into `WindowsTwoPaneCustomizer` so **Preview XML** reflects edition-locked `boot.wim\Autounattend.xml` accurately.
- **Post-Audit Edge-Case Hardening (`WindowsImageBuilder.kt`, `WindowsCustomization.kt`):**
  - Fixed Unattended Mode `LabConfig` fallback so if offline `reged` fails while `Autounattend.xml` succeeds, the replacement `Autounattend.xml` (containing `LabConfig`) overwrites `/Autounattend.xml` inside `boot.wim` (or falls back to USB root) instead of being skipped.
  - Prevented OOBE dead-end when `Full Unattended OOBE Skip` is toggled ON without a Local Admin username (kept account screens visible + added inline UI hint).
  - Sorted inner child elements of `<AutoLogon>`, `<FirstLogonCommands>`, and `<LocalAccount>` into strict Microsoft XSD alphabetical order, routed `/Drivers` copy and Step 3 `7zzs` label fallback through `BuildThermalHelper.runIsolatedRootScript(..., trackPid = true)`, clarified Pure Stock **Preview XML**, and fixed the `Part2 UEFI:NTFS` log label.
