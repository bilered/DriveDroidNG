# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push repackaged `v4.6.5` and confirm `:app:compileDebugKotlin` and `assembleDebug` pass cleanly with zero warnings.
2. **VirtualBox & PC Verification:**
   - Rebuild a Windows 11 USB `.img` in `v4.6.5` (verify Activity Log shows `NTFS 4K, label=<ISO_LABEL>`, `Patched LabConfig hardware bypasses directly into boot.wim Index 2`, and `Panther/unattend.xml only (Rufus Native Mode)`).
   - Boot into Setup (both default Windows 11 Setup and *Previous version of Setup*):
     - Confirm `diskpart` → `select disk 1` → `list partition` shows Partition 2 as **`Primary`** (not `System`).
     - Confirm *"Previous version of Setup"*, Product Key, Edition picker, and *"I accept"* License Terms all appear normally and installation completes cleanly past disk selection without `0x80004005`.
   - Test **"Stock (No Tweaks)"** chip, Pre-Select Edition (`boot.wim/Autounattend.xml` injection), and CA 2023 ↔ `SkuSiPolicy` interlocking in the UI.
