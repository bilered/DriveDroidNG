# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.1` and verify `assembleDebug` + `XOR_SMOKE` pass cleanly.
2. **Phone & VirtualBox Verification:**
   - Slim an official Windows 11 26H2 (`26300.9457`) ISO down to **Windows 11 Pro** with default Clean Stock settings (confirm `ei.cfg` auto-enables, source volume ID is preserved, and MTP copy is byte-exact).
   - Boot the slimmed ISO in VirtualBox and confirm *"Press any key to boot..."*, *"Previous version of Setup"*, genuine Edge, all inbox Store Apps, and OOBE behave identically to the official Microsoft ISO.
   - Test tapping **Cancel** mid-slim and confirm native `7zzs`/`wimlib-imagex`/`xorriso` stops immediately and `.pocketboot_tmp` cleans up.
