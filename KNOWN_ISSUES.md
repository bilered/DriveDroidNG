# PocketBoot Known Issues & Limitations

- **Microsoft Solitaire Collection (`Error: 1168`):** Fails offline DISM provisioning on base `26100.1`; all other 47 inbox Store apps provision cleanly.
- **Microsoft Edge (`Edge.wim`):** Only integrated offline when Microsoft's UUP manifest for the selected build publishes `Edge.wim` and *Remove Microsoft Edge* is OFF. Otherwise, install online via `winget install --id Microsoft.Edge --exact --silent`.
- **Android 14/15 OEM MediaProvider restrictions:** If a specific ROM blocks `content update` on `MediaStore`, `Gate 2C` logs a notice so the user knows to use `adb pull` or rename the ISO before MTP copy.
