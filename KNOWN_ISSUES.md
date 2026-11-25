# PocketBoot Known Issues & Limitations

- **Microsoft Direct ISO Geo/IP Rate Limits:** Microsoft's software-download portal occasionally rate-limits VPN or datacenter IPs; tap **Retry Microsoft CDN** or switch networks if a language list fails to load.
- **Android 14/15 OEM MediaProvider Restrictions:** Some OEM ROMs cache MTP file listings aggressively after creating large `.iso`/`.img` files; re-plugging USB or using `adb pull` refreshes visibility if needed.
