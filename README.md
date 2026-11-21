# PocketBoot (v4.3.2)

Turn your rooted Android phone into a bootable USB drive or virtual CD for any PC.

## What's New in v4.3.2
- **Full Rufus-Style Windows USB Builder (Option 2):** Auto-detects Windows ISOs and offers two options: **Mount ISO Directly** or **Build Bootable Windows USB Image** directly on your phone with **GPT (UEFI)** / **MBR (Legacy BIOS & UEFI-CSM)** support, **>4 GB Windows 11 `install.wim` support** (via exFAT + Rufus `UEFI:NTFS`), bundled ARM64 7-Zip (`7zzs`) extraction, and Rufus bypass toggles (**TPM 2.0 / Secure Boot / 4 GB RAM**, **Skip Microsoft Account**, **Disable Privacy Questions**, **Disable BitLocker**).
- **User-Friendly Root File Browser (Default):** Opens directly when tapping **Browse**, strictly allows only `.iso` and `.img` selection (with an ISO/IMG filter toggle and folder search), includes a **System Picker** button, and drops `/data/local/tmp` from default folders.
- **Create Blank Drive — MB / GB Switch:** Toggle directly between **MB** and **GB** for custom sizes, with simplified instant sparse `.img` creation (format on PC).
- **Download Tab — Top Sync & Custom Repos:** Moved **Sync** to the top of the catalog and added a **Repos** manager to add/remove custom `distros.json` repositories.
- **Cleaner Settings:** Removed the in-app Quick Unmount Shortcut card (the **Unmount USB** Quick Settings tile remains available in Android's Quick Settings editor).
