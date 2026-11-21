# PocketBoot v4.4.0

Turn a rooted Android smartphone into a full-featured bootable USB emulator, OS downloader, and on-phone Windows/Linux/Multi-Boot drive builder — zero PC required.

## Highlights in v4.4.0
- **Reliable Hot-Plug, ~1s Eject & Zero-Hang Refresh:** Independent-subshell Unmount→Wake→Remount hot-plug with `sys.usb.state` polling (no `soft_connect` drop), `<15 ms` USB activity monitor, instant watchdog abort on eject, and cancellable Refresh.
- **Full UUPdump Parity (100% On-Phone):** 3 download methods, Additional Updates & Full File List inspectors, native `Winre.wim` injection (with two-stage solid ESD support), unplug-safe ordered `$OEM$` Updates (SSU → MSU → CAB → `.NET`), Inbox Store Apps, `.NET Framework 3.5`, post-reboot Component Cleanup, `ei.cfg`, and Keep Base UUP Packages.
- **Decoupled Downloader & Dedicated Builder Queue:** Frees the Turbo Downloader the instant packages finish; compiles ISO/USB in a separate background Builder with FIFO queueing, dedicated notification channel, and a tap-to-open **Detailed Build Monitor** (4-step timeline, live disk `MB/s`, ETA, rolling binary output).
- **Two-Pane Windows Customization Everywhere:**
  - **Pane 1 — Windows Experience & Bypasses:** Hardware bypasses, MSA bypass, Local Admin account, Data Collection, BitLocker, QoL bundle, Classic Context Menu, CA 2023 Bootloaders, `SkuSiPolicy.p7b`, custom post-install script, and XML/Script Preview.
  - **Pane 2 — System Tweaks (WinUtil):** Dedicated 3-tab sheet (Essential / Preferences / Advanced) with Desktop & Laptop Safe presets and mutual-exclusion guards.
  - Unified Presets and real visual **Folder Picker (`RootFolderPickerSheet`)** across **Windows USB Builder**, **Windows Builds Direct USB**, and **Multi-Boot (Ventoy)**.
- **All Windows Compatibility & Rufus-Style Linux/Tool USB Builder:** Windows 11, 10, 8.1, 7 (x64 UEFI `bootx64.efi` fix), and Server with architecture-aware XML (`amd64`/`x86`/`arm64`), plus 2-mode Linux & Tool USB Builder (`Without Persistence` + `With Persistence`: `writable`/`casper-rw`/`persistence`).
- **Expanded 21-Distro Catalog & Anti-Break Downloader:** Pop!_OS (live API), Debian 13.7, openSUSE Tumbleweed, NixOS, Proxmox VE, Tails (live API), Clonezilla Live, ShredOS, Memtest86+ (`.iso.zip` auto-extract), and fixed GParted Live with automatic CLI `Wget`/`curl` UA negotiation and full Activity Logging.
- **Mint Neon (`#15F5AB`) Default Accent & Decluttered UI.**
