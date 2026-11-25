# PocketBoot Development Progress

## Current Milestone: v4.6.3 (Full Audit Hardening & Android 15 Polish)
- **Lifecycle & Android 15 Compliance:**
  - Added `android:configChanges` to `MainActivity` so screen rotation / foldable resize / dark-mode toggles never cancel active USB/Ventoy builds or orphan native binaries.
  - Implemented `TurboDownloadService.onTimeout(startId, fgsType)` for Android 15 (`targetSdk = 35`) `dataSync` foreground service compliance, pausing gracefully with `.pbmeta` checkpoint preserved.
  - Added `.navigationBarsPadding().imePadding()` to all 12 `ModalBottomSheet` layouts for clean Android 15 edge-to-edge spacing above 3-button navbars and keyboards.
- **Concurrency, Thermal & Data Safety:**
  - Wired `BuildThermalHelper` (CPU affinity, thread limits, Auto Thermal Cooldown, and PID-tree cancel) into `LinuxImageBuilder.kt` and `VentoyImageBuilder.kt` alongside `WindowsImageBuilder.kt`.
  - Blocked concurrent USB/Ventoy builds and locked **Clean Temp** while any build or download is active so live `/data/local/tmp/pb_*` mounts and `turbo_dl` caches cannot be wiped mid-job.
  - Fixed Dual-LUN deletion (`deleteImageFile(path, mountedLun)`) across both `MainActivity.kt` and `BrowseAndBrowserSheets.kt` so deleting LUN 1 only unmounts LUN 1 without disconnecting LUN 0 or stopping `UsbKeepAliveService`.
  - Added `UsbGadgetEngine.sanitizeOutputFileName()`, mounted-target & source-ISO overwrite guards, inline overwrite warning banners across all 4 image builders/sheets, `extraDriversDir` partition + free-space sizing in `WindowsImageBuilder.kt`, and `syncMediaStoreFileSize()` in `createBlankImage()`.
- **Network Safety, UI Copy & Hygiene:**
  - Fixed WebView cross-thread race (`AtomicBoolean`), scoped `WindowsDirectCatalog` cookies to a local `CookieManager`, replaced all 6 stale `'All Channels'` error hints, synced `distros.json` catalog version (`463`), and removed all 38 unused imports.
