# PocketBoot Pending Tasks

1. **GitHub Actions CI Verification (`v4.7.2`, `versionCode = 38`):** Push Wave 3 and verify `gradle testDebugUnitTest --stacktrace` (all 6 JUnit suites) and `gradle assembleDebug --stacktrace` pass green.
2. **On-Device Smoke Tests (Wave 3):**
   - Open **Settings → Driver Pack Library** → import a driver folder or `.zip`/`.7z` containing `.inf` files → confirm `.inf` count, category tag, and size; test multi-pack selection in **Windows USB Builder** and **Multi-Boot Builder / Manager**.
   - On **Home → Dual-LUN Combos**, create a combo pairing LUN 0 + LUN 1 → tap it to mount both drives in a single USB rebind → verify both appear on PC and unmount restores normal USB cleanly.
   - Export & re-import Settings Backup JSON to confirm `dualLunCombos` round-trip cleanly.
3. **Wave 4 (`v4.8.0`):** Compressed Image Engine (`.xz`/`.gz`/`.zst`), Physical USB-C OTG Pen Drive Flasher, and USB HID BIOS Hotkey Helper (`f_hid`).
