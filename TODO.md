# PocketBoot Pending Tasks

1. **GitHub Actions CI Verification (`v4.8.0`, `versionCode = 39`):** Push Wave 4 and verify static ARM64 `libzstd.so` build, `gradle testDebugUnitTest --stacktrace` (all 7 JUnit suites), and `gradle assembleDebug --stacktrace` pass green.
2. **On-Device Smoke Tests (Wave 4):**
   - Place a `.img.xz`, `.img.gz`, or `.img.zst` file in Downloads → confirm it appears with a **Compressed** badge and **Extract** pill (cannot be mounted directly or added to Dual-LUN Combos) → extract it in background and verify atomic output + optional source deletion.
   - Plug a spare USB pen drive into your phone via USB-C OTG → open **Home → OTG Flash** → verify ONLY the external pen drive appears (internal UFS never listed) → flash an image with typed `FLASH` confirmation and verify live progress + read-back verification pass cleanly.
