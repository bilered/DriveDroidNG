# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.6` and confirm `:app:compileDebugKotlin` and `assembleDebug` pass cleanly.
2. **On-Device & PC/VirtualBox Verification:**
   - Build a Windows 11 26H2 USB `.img` and confirm the Activity Log & PC Explorer / `diskpart list volume` show **`POCKETBOOT- Win 11 -26300.xxxx`**.
   - Verify turning OFF **Skip Wi-Fi Setup** while keeping **Bypass Online Microsoft Account** ON no longer hides the Wi-Fi screen in Preview XML or OOBE.
   - Select an edition chip and tap **Preview XML** — confirm it switches to `boot.wim\Autounattend.xml` with `<ImageInstall>` and `<UserData>`.
