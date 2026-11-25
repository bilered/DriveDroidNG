# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.2` and verify `assembleDebug` passes cleanly (leaner APK & faster CI without `xorriso`).
2. **Phone Smoke Test:**
   - Open an ISO's info sheet and confirm **Windows ISO Slimmer** is completely gone while **Windows USB Builder**, **Mount**, and **Ventoy** work normally.
   - Verify **Settings → USB Builder Thermal Profile** and **Pre-Flight Diagnostics → Windows Imaging Tools (`7zzs`, `wimlib`)** display cleanly.
   - Test **Windows USB Builder** (including Cancel mid-extraction) and normal USB Gadget mounting.
