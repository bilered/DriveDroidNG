# PocketBoot Pending Tasks

1. **GitHub Actions CI Verification (`v4.7.1`, `versionCode = 37`):** Push Wave 2 and verify `gradle testDebugUnitTest --stacktrace` (all 5 JUnit suites) and `gradle assembleDebug --stacktrace` pass green.
2. **On-Device Smoke Tests (Wave 2):**
   - Multi-Boot Manager → Linux ISO → **+ Persist** (create 2 GB ext4 `.dat`, verify `ventoy.json` + space check + removal).
   - Multi-Boot Manager → **Boot Menu Theme & Timeout** + per-ISO **Edit Alias** → verify `ventoy.json` preserves `auto_install` & `persistence`.
   - Multi-Boot Manager → Windows 11 ISO → tap Tweak pill → select 2+ profiles → confirm multi-template selection appears at PC boot.
   - Tap any `.img` → **Explore / Edit Files Inside .img** → browse, import, export, create folder, delete, close cleanly.
3. **Wave 3 (`v4.7.2`):** Storage/Wi-Fi Driver Pack Manager, Opt-In Wi-Fi Auto-Connect Injection, and Dual-LUN Quick-Mount Combos.
4. **Wave 4 (`v4.8.0`):** Compressed Image Engine (`.xz`/`.gz`/`.zst`), Physical USB-C OTG Pen Drive Flasher, and USB HID BIOS Hotkey Helper (`f_hid`).
