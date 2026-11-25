# PocketBoot Development Progress

## Current Milestone: v4.6.7 (Writable Auto-Mount, Hidden UEFI:NTFS, Editable 1-Line Labels, Clean Flush, Linux FAT32 Boot Fixes & Brand Scrub)
- **Fixed Windows Setup "Install driver to show hardware" & Missing Explorer Drive (`MainActivity.kt`, `WindowsImageBuilder.kt`, `VentoyImageBuilder.kt`):**
  - Switched post-build auto-mount for Windows USB and Multi-Boot drives (and Multi-Boot recommended mount mode) from Read-Only USB (`USB_RO`) to **Writable USB (`USB_RW`)** so Windows/WinPE `ntfs.sys` can mount fresh Linux-created NTFS volumes cleanly.
  - Added shared `BuildThermalHelper.unmountAndDetachClean()` (up to 10 × 1 s `sync` + real `umount` retry before lazy fallback + `losetup -d` + final `sync`) across Windows, Linux, and Multi-Boot builders to prevent dirty/unflushed NTFS handoffs.
- **Hidden `UEFI:NTFS` Helper Partition (`DiskPartitioner.kt`):**
  - Updated GPT Partition 2 (`UEFI:NTFS`) attributes from `0x9000000000000000` to **`0xD000000000000001`** (`PLATFORM_REQUIRED | READ_ONLY | HIDDEN | NO_DRIVE_LETTER`) so the 1 MB boot partition no longer appears as an extra drive letter in Windows Explorer while UEFI still boots it and Windows Setup still ignores it.
- **Hybrid Editable 1-Line Volume Labels (`IsoInspector.kt`, `WindowsImageBuilder.kt`, `LinuxImageBuilder.kt`, `VentoyImageBuilder.kt`):**
  - **Windows USB:** Defaults to 1-line Explorer-safe `POCKETBOOT-WIN 11` (`WIN 10`, `WIN 8.1`, `WIN 7`, `WIN SRV`) on NTFS (`PB-WIN11` on FAT32), with an editable Volume Label box and live >20-char Explorer wrap warning.
  - **Multi-Boot:** Defaults to **`POCKETBOOT Multi ISO`** (NTFS, 20 chars) / **`PB MultiISO`** (exFAT, 11 chars) and filename **`POCKETBOOT Multi ISO.img`**, editable, with `-c 4096` NTFS clusters, label preservation on Resize Path B, and instant spaced-name recognition in `IsoInspector.quickGuessByName()`.
  - **Linux & Tool (Extracted FAT32):** Defaults to the **original ISO-9660 disc label trimmed to 11 chars** (editable), patches `CDLABEL=` / `archisolabel=` / `misolabel=` case-insensitively across boot configs, deduplicates persistence boot flags, and writes standard LBA CHS (`FE FF FF`) MBR entries via `DiskPartitioner.buildLinuxFat32MbrSector()`.
- **Clean Edition Choice with Full Unattended OOBE (`WindowsImageBuilder.kt`, `WindowsCustomization.kt`):**
  - Kept `Full Unattended OOBE Skip` + `"Ask during Setup"` on `Panther\unattend.xml` (preserving *"Previous version of Setup"*, Edition picker & EULA while skipping OOBE); only pre-selecting an edition chip injects `/Autounattend.xml` into `boot.wim`.
- **App-Wide Third-Party Brand Scrub:**
  - Removed all user-facing `IDM`, `Ventoy`, and `Rufus` references from UI strings, badges, progress states, dialogs, and Activity Logs across the app while keeping proper attribution solely inside **Settings → Open-Source Credits**.
- **Post-Audit `v4.6.7` Hardening (`BuildThermalHelper.kt`, `VentoyImageBuilder.kt`, `LinuxImageBuilder.kt`, `WindowsImageBuilder.kt`):**
  - Escaped spaces to `\040` before matching `/proc/mounts` in `unmountAndDetachClean()` so clean-flush retries work inside folders with spaces.
  - Replaced `blkid -s LABEL -o value` with Toybox-compatible `blkid` regex parsing in Multi-Boot Resize Path B, passed empty `volumeLabel` when untouched in `Auto Select` so exFAT properly gets `PB MultiISO`, deleted partial `.img` on `buildVentoyImage()` exception, and added PID-tracked cancel + clean unmount in Resize Path B.
  - Extended Linux Extracted FAT32 boot-config syncing to cover `root=live:LABEL=` and GRUB `search ... -l/--label`.
  - Auto-upgraded unedited `POCKETBOOT-WINDOWS` labels once `install.wim` editions load for generically named Windows ISOs, and tidied `wimBin` lookup.
