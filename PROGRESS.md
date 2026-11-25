# PocketBoot Development Progress

## Current Milestone: v4.6.0 (Production-Focused Streamline)
- **Removed On-Phone UUP ISO Builder:** Stripped ~400 KB of fragile on-phone UUP/MSU/AppX/OOBE servicing code (`WindowsBuildEngine.kt`, `MicrosoftUupClient.kt`, `VirtualEditionsPlugin.kt`, `WindowsBuildMonitorSheet.kt`, `sync-uup-builds.yml`, `uup-builds.json`) and dropped unused `cabextract` & `chntpw` native binaries from CI.
- **Preserved & Expanded Thermal Profile (`BuildThermalHelper.kt`):** Extracted `BuildThermalProfile` (`Cool`, `Balanced`, `Max Speed`) and `Auto Thermal Cooldown` into a standalone helper wired into both **Windows ISO Slimmer** (`7zzs`, `wimlib-imagex`, `xorriso`) and **Windows USB Builder** (`7zzs` extraction), completely isolated from USB Gadget mounting.
- **Streamlined Direct Official Windows Downloads:** Windows 11, Windows 10, and Windows 8.1 cards now open directly into official Microsoft Retail CDN ISO selection (Release → Architecture → Language → Copy Link / Turbo Download).
