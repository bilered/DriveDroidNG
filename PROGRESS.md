# PocketBoot Development Progress

## Current Milestone: v4.6.1 (Official-ISO Parity & Hardening)
- **Windows ISO Slimmer Official-ISO Parity (`WindowsIsoSlimmer.kt`):**
  - Dynamically resolves extracted `sources/`, `efi/`, and `boot/` directory casing (supports uppercase `SOURCES/` & `EFI/` official/OEM ISOs).
  - Supports split-WIM (`install.swm`) source ISOs via `wimlib-imagex export --ref`.
  - Defaults to **Clean Official Stock** (all tweaks OFF unless opted in), auto-enables `sources/ei.cfg` when trimming editions (OEM Home-key safe), decouples CA 2023 bootloaders from `autounattend.xml`, and auto-detects `uiLang` from `lang.ini`.
  - Uses official `efisys.bin` (*"Press any key to boot..."*) to prevent Setup reboot loops, guards multi-edition ESDs >3.95 GiB via automatic split-WIM fallback, applies Win 7 UEFI fix, preserves source ISO-9660 volume IDs, packs atomically to `.tmp.iso`, and runs full PVD + `7zzs -tiso` verification.
- **Windows USB Builder & App-Wide Reliability (`WindowsImageBuilder.kt`, `BuildThermalHelper.kt`, `WindowsCustomization.kt`):**
  - Case-safe `sources/` and `efi/` handling for CA 2023 / Win 7 UEFI bootloaders and `install.swm` edition inspection.
  - Separate `Panther/unattend.xml` (`includeWindowsPePass = false`) + auto `uiLang` detection.
  - Active native binary PID-tree termination on Cancel for both ISO Slimmer and Windows USB Builder.
  - Wired `UsbGadgetEngine.syncMediaStoreFileSize()` across all ISO/IMG writers so USB MTP never reports stale sizes on overwritten images.
