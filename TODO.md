# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.0` and verify `assembleDebug` + `XOR_SMOKE` pass cleanly (faster CI without `cabextract`/`chntpw`).
2. **Phone Smoke Test:**
   - Verify normal ISO/IMG USB Gadget mounting works normally across all thermal profiles.
   - Verify **Downloads → Windows 11** opens straight to Direct Official Retail ISO (26H2 / 25H2 / 24H2) and generates signed Microsoft CDN links.
   - Verify **Settings → Build & Slimmer Thermal Profile** persists Cool / Balanced / Max Speed + Auto Thermal Cooldown.
   - Verify **ISO Slimmer** and **Windows USB Builder** run with the selected thermal profile and produce clean bootable outputs.
   - Verify **Settings → Clean Temp** clears any leftover `.pocketboot_tmp` / old UUP cache folders cleanly.
