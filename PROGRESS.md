# PocketBoot Development Progress

## Current Milestone: v4.5.0 (v26.4.1)
- **Windows 11 26H2 (Build 26300.10075) UUP ISO Builder:**
  - Confirmed clean upgrade from `24H2` (`26100.1`) to **Windows 11 26H2 (`26300.10075`)** with zero OOBE reboot loops.
  - Bulletproof Store Apps: paced UUPdump queries + zero-rate-limit Direct Microsoft (`fe3.delivery.mp.microsoft.com`) fallback + hard guard preventing silent 0-app builds; simplified UI to default-ON **All Inbox Store Apps**.
  - Guaranteed offline Microsoft Edge via `Edge.wim` or official Microsoft Edge Enterprise Stable MSI fallback.
  - Restored **"Previous version of Setup"** in Windows 11 24H2/26H2 WinPE by patching `LabConfig` directly into `boot.wim` Index 2 `SYSTEM` hive and staging `unattend.xml` exclusively in `$OEM$\$$\Panther\unattend.xml`.
  - Full pre-desktop servicing: locks `Winlogon\Shell` immediately after OOBE ends (`OOBEInProgress=0x0`), suppresses early `explorer.exe`, and shows a fullscreen dark-mode WinForms progress screen (`PocketBoot_WaitScreen.ps1`) through both phases so the actual desktop only loads once `26H2` is completely finished.
  - Hardened CBS/TiWorker settle checks, scheduled post-reboot `/ResetBase` cleanup, faster `SetupComplete.cmd` app provisioning, and auto-saved `PocketBoot_LastBuild.log`.
