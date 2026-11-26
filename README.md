# PocketBoot v4.7.2

Rooted Android USB Gadget ISO/IMG boot utility, multi-boot Ventoy drive manager, official OS downloader, and Rufus-style Windows & Linux bootable USB builder.

## Core Capabilities
- **USB Gadget Booting & Dual-LUN Combos (`UsbGadgetEngine.kt`):** Mount any ISO or disk `.img` over USB ConfigFS as a bootable CD-ROM or Mass Storage drive, save 1-tap atomic Dual-LUN (LUN 0 + LUN 1) combos, with Quick Settings tile and foreground keep-alive watchdog.
- **Direct Official Windows ISOs (`WindowsDirectCatalog.kt`):** Download official Microsoft Retail ISOs for Windows 11 (26H2 / 25H2 / 24H2), Windows 10 (22H2), and Windows 8.1 directly from Microsoft CDN — with full Edge, Store Apps, WinRE, and OOBE working out of the box.
- **Linux & Rescue Distro Catalog (`DownloadScreen.kt`, `FastIsoDownloader.kt`):** Multi-connection resumable turbo downloads with SHA-256 verification and FAT32 Linux USB image creation.
- **Rufus-Style Windows USB Builder & Driver Pack Library (`WindowsImageBuilder.kt`, `DriverPackManager.kt`):** Convert any Windows ISO into a bootable GPT/MBR NTFS or UEFI:NTFS `.img` with TPM/RAM/Secure Boot bypass, local account creation, WinUtil tweaks, reusable `.inf`/`.zip`/`.7z` Storage & Wi-Fi driver packs, CA 2023 bootloaders, and Win 7 UEFI fix.
- **Foreground USB Build Service & Thermal Control (`UsbBuildService.kt`, `BuildThermalHelper.kt`):** Background-safe foreground service with wake lock, live notification + Cancel action, and Cool / Balanced / Max Speed CPU core-affinity & Auto Thermal Cooldown.
- **In-App Release Updater & Automated JVM Tests (`AppUpdateChecker.kt`, `app/src/test`):** HTTPS-enforced GitHub release checks against `bilered/PocketBoot` and JUnit regression tests in CI.
- **Ventoy Multi-Boot & Disk Tools (`VentoyImageBuilder.kt`, `VentoyPersistenceHelper.kt`, `VentoyThemeCatalog.kt`, `ImageLoopExplorer.kt`, `DiskPartitioner.kt`):** Create, resize, reclaim sparse space, attach per-ISO Linux ext4 persistence `.dat` volumes, customize boot menu aliases/timeouts/GRUB themes, assign per-ISO multi-template Windows tweak profiles, and explore/edit Partition 1 of any `.img` directly on-phone.
