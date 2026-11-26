# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.8` and confirm `:app:compileDebugKotlin` and `assembleDebug` pass with zero errors.
2. **On-Device & PC/VM Verification:**
   - Open **Windows USB Builder** → tap **System Tweaks** → verify dedicated full-screen screen opens cleanly with search bar, history chips, collapsible sections, `ⓘ` info dialogs, preset match badge, and `+ Save Preset`.
   - Confirm **Classic Right-Click Menu** is gone from Pane 1 and present under **System Tweaks → Advanced**.
   - Test **Add Undo Script** OFF vs. ON: verify `Preview .cmd` shows `Undo.cmd` tab only when ON and at least 1 tweak is active.
   - Verify **Disable Windows Update (Store-Safe)** script writes dynamic install-UTC StartTime + `2099-12-31T23:59:59Z` EndTime and omits `DoNotConnectToWindowsUpdateInternetLocations` & `ExcludeWUDriversInQualityUpdate`.
   - Confirm **Settings → Open-Source Credits** lists **Chris Titus Tech WinUtil (MIT)** while no third-party brand names appear in System Tweaks UI or Activity Logs.
