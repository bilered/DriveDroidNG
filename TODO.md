# PocketBoot Pending Tasks

1. **Manual VirtualBox verification:** Complete clean install of `v26.2`/`v26.3` ISO to verify OOBE completes without reboot loop and upgrades to **Windows 11 Version 26H2 (Build 26300.10075)**.
2. **Manual MTP transfer test:** Overwrite an existing ISO with `v26.3`, copy over USB MTP to PC, and verify exact byte-count parity without `adb pull`.
3. **Manual UUP retention test:** Confirm cached `PKGS/` remains visible under *Unfinished & Cached Windows Builds* after build completion and after running *Clean temporary cache*.
4. **Offline Edge test:** Verify `Edge.wim` downloads and merges into `install.wim` on a fresh UUP build where Microsoft publishes `Edge.wim`.
