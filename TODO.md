# PocketBoot Pending Tasks

1. **GitHub Actions CI Build:** Push `v4.6.7` and confirm `:app:compileDebugKotlin` and `assembleDebug` pass cleanly.
2. **On-Device & PC/VirtualBox Verification:**
   - Build a Windows 11 USB `.img` with **Auto-mount when finished** ON: confirm it mounts immediately as **Writable USB**, displays **`POCKETBOOT-WIN 11 (F:)`** on 1 line with **free/total size text visible**, hides the 1 MB `UEFI:NTFS` partition (no extra `G:` drive), and boots cleanly into Setup without *"Install driver to show hardware"*.
   - Create a Multi-Boot drive: confirm default name & label **`POCKETBOOT Multi ISO`** (editable), instant Multi-Boot badge in Drives list, and clean Writable USB auto-mount.
   - Build a Fedora or Arch Linux USB in **Extracted FAT32** mode: confirm original disc name (trimmed to 11 chars) pre-fills the Volume Label box and boots cleanly without `dracut`/`initramfs` label errors.
   - Check UI screens and Activity Log to confirm `IDM`, `Ventoy`, and `Rufus` appear **only** in **Settings → Open-Source Credits**.
