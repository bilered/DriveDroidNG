# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.4` and verify `libchntpw.so` compiles cleanly (`reged-static`, static ARM64) and `assembleDebug` passes.
2. **VirtualBox & PC Boot Verification:**
   - Build a Windows 11 26H2 USB `.img` using default settings (Edition = *"Ask during Setup"*) and check the Activity Log for `Patched LabConfig hardware bypasses directly into boot.wim Index 2` and `Panther/unattend.xml only (Rufus-style)`.
   - Boot the `.img` in VirtualBox / PC and verify:
     1. *"Previous version of Setup"* appears on the Select Setup Option screen.
     2. Product Key, Edition picker, and **Microsoft Software License Terms (*"I accept"*)** appear normally before Select Disk.
     3. TPM/Secure Boot/RAM checks are still bypassed cleanly and OOBE skips Microsoft Account / BitLocker / Telemetry just like official Rufus.
