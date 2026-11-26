# PocketBoot v4.7.0

Rooted Android USB Gadget ISO/IMG boot utility, multi-boot Ventoy drive manager, official OS downloader, and Rufus-style Windows & Linux bootable USB builder.

## Core Capabilities
- **USB Gadget Booting (`UsbGadgetEngine.kt`):** Mount any ISO or disk `.img` over USB ConfigFS as a bootable CD-ROM or Mass Storage drive, with Quick Settings tile and foreground keep-alive watchdog.
- **Direct Official Windows ISOs (`WindowsDirectCatalog.kt`):** Download official Microsoft Retail ISOs for Windows 11 (26H2 / 25H2 / 24H2), Windows 10 (22H2), and Windows 8.1 directly from Microsoft CDN — with full Edge, Store Apps, WinRE, and OOBE working out of the box.
- **Linux & Rescue Distro Catalog (`DownloadScreen.kt`, `FastIsoDownloader.kt`):** Multi-connection resumable turbo downloads with SHA-256 verification and FAT32 Linux USB image creation.
- **Rufus-Style Windows USB Builder (`WindowsImageBuilder.kt`):** Convert any Windows ISO into a bootable GPT/MBR NTFS or UEFI:NTFS `.img` with TPM/RAM/Secure Boot bypass, local account creation, WinUtil tweaks, custom drivers, CA 2023 bootloaders, and Win 7 UEFI fix.
- **Foreground USB Build Service & Thermal Control (`UsbBuildService.kt`, `BuildThermalHelper.kt`):** Background-safe foreground service with wake lock, live notification + Cancel action, and Cool / Balanced / Max Speed CPU core-affinity & Auto Thermal Cooldown.
- **In-App Release Updater & Automated JVM Tests (`AppUpdateChecker.kt`, `app/src/test`):** HTTPS-enforced GitHub release checks against `bilered/PocketBoot` and JUnit regression tests in CI.
- **Ventoy Multi-Boot & Disk Tools (`VentoyImageBuilder.kt`, `DiskPartitioner.kt`):** Create, resize, and manage Ventoy multi-boot virtual drives directly on-device.
