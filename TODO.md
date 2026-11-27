# PocketBoot Pending Tasks

1. **GitHub Actions CI Verification (`v4.10.0`, `versionCode = 41`):** Push Wave 6 and verify `gradle testDebugUnitTest --stacktrace` (all 9 JUnit suites) and `gradle assembleDebug --stacktrace` pass green.
2. **On-Device Smoke Tests (Wave 6 — NTriver Direct Windows Archive):**
   - Open **Download → Windows** → verify all 6 cards appear (**Windows 11**, **Windows 10**, **Windows 10 / 11 LTSC**, **Windows 8.1**, **Windows 7 SP1**, **Windows Server**).
   - Tap **Windows 11** → confirm **NTriver Archive** loads latest monthly 26H2/25H2 builds with Build badge, SHA-256 checksum, and `MS CDN` tab still accessible.
   - Tap **Turbo Download** → verify fresh URL resolves, multi-connection download starts, Pause + Resume continues cleanly, and post-download SHA-256 verification matches green.
2. **On-Device Smoke Tests (Wave 5 — Direct-Mode OTG):**
   - Plug a spare USB pen drive via USB-C OTG → open **Home → OTG Flash** → confirm target displays `512 B verified` and **4 mode tabs** (`Windows`, `Linux`, `Multi-Boot`, `Raw DD`).
   - **Mode 1 (Windows):** Select a Windows 11 ISO → toggle GPT/NTFS + Tweaks + a Driver Pack → type `FLASH` → verify direct extraction & `boot.wim` patching finish without using phone `.img` storage and the pen drive boots cleanly on PC.
   - **Mode 2 (Linux):** Select a Linux ISO → pick Extracted FAT32 + ext4 Persistence → verify persistence size scales to the drive's real remaining space and boots with persistence working.
   - **Mode 3 (Multi-Boot):** Switch to `Multi-Boot` → flash full-capacity Multi-Boot pen drive (with or without initial ISO) → confirm full pen-drive capacity and clean PC boot.
