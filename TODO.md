# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.9` (`versionCode = 35`) and verify `:app:compileDebugKotlin`, `:app:processDebugResources` (including `network_security_config.xml`), and `assembleDebug` pass green.
2. **On-Device & PC/VM Verification:**
   - **Multi-Boot Builder:** Open on a 9 GB Windows ISO → confirm `8 GB` stays selected (no auto-pick), red inline note appears under **Drive Capacity (Sparse)**, typing `10` in Custom displays **`10 GB`** (not `1 GB`), and building at `16 GB` produces an exact byte-verified ISO.
   - **Multi-Boot Manager:** Verify **`Tweaks` / `Stock`** pills on Win 10/11 ISOs, Add ISO free-space & oversize warnings, Resize Drive live size, and exFAT resize on a stock drive leaves zero stale unattend scripts.
   - **Builders, Blank Drive & Downloads:** Verify blank `.img` creation, Linux DD build, Windows Drivers folder injection, cancelling a running build without UI lag, and renaming/deleting ISOs refreshes library badges cleanly.
