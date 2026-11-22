# PocketBoot v4.5.0

Turn a rooted Android smartphone into a full-featured bootable USB emulator, OS downloader, and 100% on-phone Windows/Linux/Multi-Boot drive builder — zero PC required.

## Highlights in v4.5.0
- **Zero-Outage Direct Microsoft UUP Client (`MicrosoftUupClient.kt`):** Talks directly to Microsoft's official Windows Update SOAP servers (`fe3.delivery.mp.microsoft.com`) with anonymous device tickets and cached cookies. If `api.uupdump.net` is down, ISP-blocked, or rate-limited (`HTTP 429`), PocketBoot automatically fails over to Microsoft directly.
- **Bundled + Daily GitHub-Synced UUP Catalog (`uup-builds.json`):** Loads verified Windows 11 (26H2/25H2/24H2/23H2), Windows 10 (22H2 ESU), and Server 2025 builds instantly on open — never stuck on "Finding builds..." — plus a one-tap **Fetch Direct MS** button to discover the newest Retail/Preview/Insider builds on demand.
- **On-Phone Virtual Editions (`VirtualEditionsPlugin.kt`):** Generate Enterprise, Education, Pro Education, Pro for Workstations, IoT Enterprise, Enterprise multi-session, SE, and Home Single Language inside the same `install.wim` / `install.esd` with ~0 MB extra download using safe per-hive `reged -C -I` registry patching.
- **100% Offline Local UUP Folder Builder:** Pick any local folder of `.esd`/`.cab` files via the visual folder picker and compile a bootable ISO or Direct Bootable USB without modifying or deleting your source files.
- **Custom UUP API Mirror & Prefer Direct Microsoft Mode:** Configurable inside Settings → Download Engine.
- **Resilient Downloads & Win 11 Security Protection:** `Mutex`-locked mid-download URL auto-refresh on `HTTP 403`/`410` or near `P1=` expiry, `-EXPRESS.cab` / `baseless_` / `.psf` filtering, standalone Cumulative Update filter, and automatic `SecHealthUI` provisioning in `SetupComplete.cmd` for Windows 11 22H2+.
- **All v4.4.0 Capabilities Included:** Decoupled Downloader + FIFO Builder Queue + Detailed Build Monitor, Two-Pane Windows Experience & WinUtil System Tweaks, Rufus-style 2-mode Linux & Tool USB Builder (`libmkfs_fat.so`), Ventoy Multi-Boot with Windows-only `auto_install` & `/Drivers` injection, 21-distro catalog, and reliable ConfigFS hot-plug + ~1s eject.
