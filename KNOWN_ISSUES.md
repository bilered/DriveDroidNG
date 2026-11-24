# PocketBoot Known Issues & Limitations

- **Microsoft Solitaire Collection (`Error: 1168`):** Skipped cleanly in `SetupComplete.cmd` on base `26100.1` so provisioning runs faster with zero errors; all other 47 inbox Store apps provision offline.
- **Servicing duration inside VMs:** Because PocketBoot builds ISOs on an ARM64 phone (without x64 `dism.exe`), the 4.43 GB `KB5132057` cumulative update + `.NET 4.8.1` + `26H2` enablement apply online on first boot (~25–35 minutes in VirtualBox) behind the fullscreen dark-mode update screen before the desktop opens.
- **Android 14/15 OEM MediaProvider restrictions:** If a ROM blocks `content update` on `MediaStore`, `Gate 2C` logs a notice so you can use `adb pull` or rename the ISO before MTP copy.
