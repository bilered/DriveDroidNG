# PocketBoot Pending Tasks

1. **GitHub Actions CI Verification (`v4.7.0`, `versionCode = 36`):** Push Wave 1 and verify `gradle testDebugUnitTest --stacktrace` (all 4 JUnit suites) and `gradle assembleDebug --stacktrace` pass green.
2. **On-Device Smoke Tests (Wave 1):**
   - Start a Windows/Linux/Multi-Boot USB build, press Home to background PocketBoot, verify live progress notification + Cancel action work cleanly and the build finishes reliably without Android killing it.
   - Mount an ISO to a PC and verify the mounted hero card displays live **USB-C Activity** + **Transfer Stats (Session / Peak)** while the PC boots/reads.
   - Open **Settings → About → Updates** and **GitHub** to confirm both point to `https://github.com/bilered/PocketBoot`.
3. **Wave 2 (`v4.7.1`):** Multi-Boot Linux persistence `.dat`, Boot Menu aliases/themes, per-ISO multi-template profiles, and direct `.img` File Explorer.
