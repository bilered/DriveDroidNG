# PocketBoot Pending Tasks

1. **Manual GitHub Actions build:** Push `v26.4` and verify `assembleDebug` + `XOR_SMOKE` pass cleanly.
2. **Manual phone build test:** Rebuild `26300.10075` with `v26.4` APK (reusing cached 8 GB base OS); confirm Store Apps + Microsoft Edge Enterprise MSI top-up cleanly, `Gate 2A/2B/2C` pass, `PocketBoot_LastBuild.log` saves, and `PKGS/` is kept.
3. **Manual USB MTP copy test:** Copy ISO over USB MTP and confirm byte-exact size match on PC.
4. **Manual VirtualBox end-to-end test:**
   - Confirm **"Previous version of Setup"** link appears in Windows 11 Setup.
   - Confirm OOBE completes with zero reboot loops, followed immediately by the fullscreen dark-mode PocketBoot update screen (no `24H2` desktop shown).
   - Confirm final desktop loads directly on **Windows 11 Version 26H2 (`26300.10075`)** with ~47 provisioned Store Apps, Microsoft Edge installed, and `C:\Windows\Setup` cleaned down to `State`.
