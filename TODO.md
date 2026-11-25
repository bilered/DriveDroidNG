# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.3` and confirm `:app:compileDebugKotlin` compiles cleanly with zero unused-import warnings and `assembleDebug` succeeds.
2. **On-Device Verification:**
   - Rotate the phone mid-build (Windows, Linux, or Ventoy) and verify the build continues uninterrupted without losing progress.
   - Open all bottom sheets (Windows USB Builder, Linux Builder, Ventoy Builder/Manager, Create Blank, Diagnostics, Downloads, Root Browser) on 3-button navigation and while typing — verify buttons clear the navbar and keyboard cleanly.
   - Test Dual-LUN mount (LUN 0 + LUN 1) and delete LUN 1 from either Drives or Root Browser — verify LUN 0 stays mounted and `UsbKeepAliveService` stays active.
   - Test tapping **Cancel** on Linux and Ventoy builds — verify native `7zzs`/`cp` terminates immediately.
   - Verify **Settings → Temp & Build Cache** locks cleanly while a download or build is active.
