# PocketBoot Development Progress

## Current Milestone: v4.6.5 (Full Official Rufus Parity & PrepareSystemVolume Fix)
- **Fixed Windows Setup `PrepareSystemVolume` Error (`0x80004005`) (`DiskPartitioner.kt`):**
  - Changed GPT Partition 2 (`UEFI:NTFS`) on Windows USB images from `GUID_ESP` (`Type: System`) to **`GUID_BASIC_DATA`** (`EBD0A0A2-B9E5-4433-87C0-68B6B72699C7`) with GPT attributes **`0x9000000000000000`** (`NO_DRIVE_LETTER | READ_ONLY`) and name `UEFI:NTFS`, while preserving `GUID_ESP` explicitly for Ventoy `VTOYEFI`.
- **Phase 1 — Core Rufus Engine Parity (`WindowsCustomization.kt`, `WindowsImageBuilder.kt`):**
  - Exported `TMPDIR`, `HOME`, and `WIMLIB_IMAGEX_IGNORE_CASE=1` across all offline `wimlib-imagex` helpers (`patchBootWimLabConfigSnippet`, `injectBootWimAutounattendSnippet`, `applyCa2023BootloadersSnippet`, `applyWin7UefiFixSnippet`) and added `>= 4096 B` hive validation before/after `reged`.
  - Injected `/Autounattend.xml` directly into `sources/boot.wim` Index 2 root (official Rufus method) when WinPE unattended mode is needed, eliminating duplicate USB-root + `Panther/unattend.xml` conflicts.
  - Added `sanitizeWindowsUsername()` (Rufus `#2991` parity) to strip `&` and illegal characters and block reserved Windows names (`Administrator`, `Guest`, `CON`, `NUL`, etc.).
  - Updated `applyCa2023BootloadersSnippet()` to also patch `efi/microsoft/boot/bootmgfw.efi` when present and auto-enforce `useCa2023Bootloaders = true` whenever `applySkuSiPolicy` is enabled.
- **Phase 2 — Filesystem & OEM Compatibility (`WindowsImageBuilder.kt`):**
  - Formatted NTFS Partition 1 with explicit 4 KB clusters (`mkntfs -c 4096`) and preserved the source ISO's PVD volume label (fallback `POCKETBOOT`) on both NTFS and FAT32.
  - Upgraded `sources/ei.cfg` unlock template to include blank `[EditionID]` + `[Channel] Retail` + `[VL] 0` so OEM Home-keyed laptops reliably display all Windows editions.
- **Phase 3 — UI/UX Transparency (`WindowsCustomization.kt`, `WindowsImageBuilder.kt`):**
  - Added a live **Setup Mode Indicator** pill in `WindowsBuilderSheet` (*Pure Stock ISO* vs *Rufus Native Mode* vs *Unattended Mode*), a one-tap **"Stock (No Tweaks)"** chip, live username validation warnings, CA 2023 ↔ `SkuSiPolicy` toggle interlocking, and wired-Ethernet OOBE guidance on `Bypass Online Microsoft Account`.
