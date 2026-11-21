package com.pocketboot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AutounattendPreset(
    val name: String,
    val schemeName: String = PartitionScheme.GPT.name,
    val bypassRequirements: Boolean = true,
    val bypassMicrosoftAccount: Boolean = true,
    val disableDataCollection: Boolean = true,
    val disableBitLocker: Boolean = true,
    val disableSponsoredApps: Boolean = false,
    val classicContextMenu: Boolean = false,
    val skipWifiSetup: Boolean = true,
    val createLocalUser: Boolean = false,
    val localUsername: String = "",
    val skipOobe: Boolean = false
)

data class AppSettings(
    val hasCompletedSetup: Boolean = false,
    val defaultDir: String = "/data/media/0/Download",
    val downloadTargetMultiBootPath: String = "",
    val favoriteQuickMountPath: String = "",
    val keepAdbActive: Boolean = false,
    val autoPatchSelinux: Boolean = true,
    val surfaceMode: SurfaceMode = SurfaceMode.DARK,
    val useMonet: Boolean = false,
    val accentHex: Long = 0xFF6366F1L,
    val scanDirs: List<String> = listOf("/data/media/0/Download"),
    val pinnedFiles: List<String> = emptyList(),
    val hiddenFiles: List<String> = emptyList(),
    val verifiedIsoPaths: List<String> = emptyList(),
    val fileModes: Map<String, String> = emptyMap(),
    val useDefaultDistroRepo: Boolean = true,
    val customRepoUrls: List<String> = emptyList(),
    val cachedDistrosJson: String = "",
    val autounattendPresets: List<AutounattendPreset> = emptyList(),
    val downloadConnections: Int = 8,
    val autoVerifySha256: Boolean = true
)

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("pocketboot_prefs", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaultDir = prefs.getString("default_dir", "/data/media/0/Download")
            ?.trim()?.ifBlank { "/data/media/0/Download" } ?: "/data/media/0/Download"

        val rawDirs = prefs.getString("scan_dirs", null)
        val parsedDirs = if (rawDirs.isNullOrBlank()) {
            listOf(defaultDir)
        } else {
            rawDirs.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
        val scanDirs = (listOf(defaultDir) + parsedDirs.filter { it != defaultDir }).distinct()

        val pinned = prefs.getString("pinned_files", "")
            .orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val hidden = prefs.getString("hidden_files", "")
            .orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val verified = prefs.getString("verified_iso_paths", "")
            .orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val customRepos = prefs.getString("custom_repo_urls", "")
            .orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val modeMap = mutableMapOf<String, String>()
        prefs.getString("file_modes", "").orEmpty().split("\n").forEach { line ->
            val sep = line.lastIndexOf('|')
            if (sep > 0) {
                val path = line.substring(0, sep).trim()
                val modeName = line.substring(sep + 1).trim()
                if (path.isNotEmpty() && modeName.isNotEmpty()) {
                    modeMap[path] = modeName
                }
            }
        }

        val presets = mutableListOf<AutounattendPreset>()
        val rawPresets = prefs.getString("autounattend_presets_json", "").orEmpty()
        if (rawPresets.isNotBlank()) {
            runCatching {
                val arr = JSONArray(rawPresets)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    if (name.isNotEmpty()) {
                        val user = o.optString("localUsername", "")
                        presets.add(
                            AutounattendPreset(
                                name = name,
                                schemeName = o.optString("schemeName", PartitionScheme.GPT.name),
                                bypassRequirements = o.optBoolean("bypassRequirements", true),
                                bypassMicrosoftAccount = o.optBoolean("bypassMicrosoftAccount", true),
                                disableDataCollection = o.optBoolean("disableDataCollection", true),
                                disableBitLocker = o.optBoolean("disableBitLocker", true),
                                disableSponsoredApps = o.optBoolean("disableSponsoredApps", false),
                                classicContextMenu = o.optBoolean("classicContextMenu", false),
                                skipWifiSetup = o.optBoolean("skipWifiSetup", true),
                                createLocalUser = o.optBoolean("createLocalUser", user.isNotBlank()),
                                localUsername = user,
                                skipOobe = o.optBoolean("skipOobe", false)
                            )
                        )
                    }
                }
            }
        }

        val modeStr = prefs.getString("surface_mode", SurfaceMode.DARK.name).orEmpty()
        val surfaceMode = runCatching { SurfaceMode.valueOf(modeStr) }.getOrDefault(SurfaceMode.DARK)

        return AppSettings(
            hasCompletedSetup = prefs.getBoolean("setup_done", false),
            defaultDir = defaultDir,
            downloadTargetMultiBootPath = prefs.getString("dl_target_multiboot", "").orEmpty().trim(),
            favoriteQuickMountPath = prefs.getString("fav_quick_mount", "").orEmpty().trim(),
            keepAdbActive = prefs.getBoolean("keep_adb", false),
            autoPatchSelinux = prefs.getBoolean("auto_patch_selinux", true),
            surfaceMode = surfaceMode,
            useMonet = prefs.getBoolean("use_monet", false),
            accentHex = prefs.getLong("accent_hex", 0xFF6366F1L),
            scanDirs = scanDirs,
            pinnedFiles = pinned,
            hiddenFiles = hidden,
            verifiedIsoPaths = verified,
            fileModes = modeMap,
            useDefaultDistroRepo = prefs.getBoolean("use_default_distro_repo", true),
            customRepoUrls = customRepos,
            cachedDistrosJson = prefs.getString("cached_distros_json", "").orEmpty(),
            autounattendPresets = presets,
            downloadConnections = prefs.getInt("dl_connections", 8).coerceIn(1, 16),
            autoVerifySha256 = prefs.getBoolean("auto_verify_sha256", true)
        )
    }

    fun save(settings: AppSettings) {
        val serializedModes = settings.fileModes.entries.joinToString("\n") { "${it.key}|${it.value}" }
        val presetsArr = JSONArray()
        settings.autounattendPresets.forEach { p ->
            presetsArr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("schemeName", p.schemeName)
                    put("bypassRequirements", p.bypassRequirements)
                    put("bypassMicrosoftAccount", p.bypassMicrosoftAccount)
                    put("disableDataCollection", p.disableDataCollection)
                    put("disableBitLocker", p.disableBitLocker)
                    put("disableSponsoredApps", p.disableSponsoredApps)
                    put("classicContextMenu", p.classicContextMenu)
                    put("skipWifiSetup", p.skipWifiSetup)
                    put("createLocalUser", p.createLocalUser)
                    put("localUsername", p.localUsername)
                    put("skipOobe", p.skipOobe)
                }
            )
        }
        prefs.edit()
            .putBoolean("setup_done", settings.hasCompletedSetup)
            .putString("default_dir", settings.defaultDir)
            .putString("dl_target_multiboot", settings.downloadTargetMultiBootPath)
            .putString("fav_quick_mount", settings.favoriteQuickMountPath)
            .putBoolean("keep_adb", settings.keepAdbActive)
            .putBoolean("auto_patch_selinux", settings.autoPatchSelinux)
            .putString("surface_mode", settings.surfaceMode.name)
            .putBoolean("use_monet", settings.useMonet)
            .putLong("accent_hex", settings.accentHex)
            .putString("scan_dirs", settings.scanDirs.distinct().joinToString("\n"))
            .putString("pinned_files", settings.pinnedFiles.distinct().joinToString("\n"))
            .putString("hidden_files", settings.hiddenFiles.distinct().joinToString("\n"))
            .putString("verified_iso_paths", settings.verifiedIsoPaths.distinct().joinToString("\n"))
            .putString("file_modes", serializedModes)
            .putBoolean("use_default_distro_repo", settings.useDefaultDistroRepo)
            .putString("custom_repo_urls", settings.customRepoUrls.distinct().joinToString("\n"))
            .putString("cached_distros_json", settings.cachedDistrosJson)
            .putString("autounattend_presets_json", presetsArr.toString())
            .putInt("dl_connections", settings.downloadConnections.coerceIn(1, 16))
            .putBoolean("auto_verify_sha256", settings.autoVerifySha256)
            .apply()
    }

    companion object {
        fun exportBackupJson(settings: AppSettings): String {
            val presetsArr = JSONArray()
            settings.autounattendPresets.forEach { p ->
                presetsArr.put(
                    JSONObject().apply {
                        put("name", p.name)
                        put("schemeName", p.schemeName)
                        put("bypassRequirements", p.bypassRequirements)
                        put("bypassMicrosoftAccount", p.bypassMicrosoftAccount)
                        put("disableDataCollection", p.disableDataCollection)
                        put("disableBitLocker", p.disableBitLocker)
                        put("disableSponsoredApps", p.disableSponsoredApps)
                        put("classicContextMenu", p.classicContextMenu)
                        put("skipWifiSetup", p.skipWifiSetup)
                        put("createLocalUser", p.createLocalUser)
                        put("localUsername", p.localUsername)
                        put("skipOobe", p.skipOobe)
                    }
                )
            }
            val modesObj = JSONObject()
            settings.fileModes.forEach { (k, v) -> modesObj.put(k, v) }

            return JSONObject().apply {
                put("pocketbootBackupVersion", 1)
                put("defaultDir", settings.defaultDir)
                put("favoriteQuickMountPath", settings.favoriteQuickMountPath)
                put("keepAdbActive", settings.keepAdbActive)
                put("autoPatchSelinux", settings.autoPatchSelinux)
                put("surfaceMode", settings.surfaceMode.name)
                put("useMonet", settings.useMonet)
                put("accentHex", settings.accentHex)
                put("scanDirs", JSONArray(settings.scanDirs))
                put("useDefaultDistroRepo", settings.useDefaultDistroRepo)
                put("customRepoUrls", JSONArray(settings.customRepoUrls))
                put("downloadConnections", settings.downloadConnections)
                put("autoVerifySha256", settings.autoVerifySha256)
                put("fileModes", modesObj)
                put("autounattendPresets", presetsArr)
            }.toString(2)
        }

        fun importBackupJson(rawJson: String, current: AppSettings): AppSettings? {
            return runCatching {
                val obj = JSONObject(rawJson)
                if (!obj.has("pocketbootBackupVersion")) return null

                val defDir = obj.optString("defaultDir", current.defaultDir).ifBlank { current.defaultDir }
                val dirsArr = obj.optJSONArray("scanDirs")
                val parsedDirs = mutableListOf<String>()
                if (dirsArr != null) {
                    for (i in 0 until dirsArr.length()) {
                        val s = dirsArr.optString(i, "").trim()
                        if (s.isNotEmpty()) parsedDirs.add(s)
                    }
                }
                val scanDirs = (listOf(defDir) + parsedDirs.filter { it != defDir }).distinct()

                val reposArr = obj.optJSONArray("customRepoUrls")
                val parsedRepos = mutableListOf<String>()
                if (reposArr != null) {
                    for (i in 0 until reposArr.length()) {
                        val s = reposArr.optString(i, "").trim()
                        if (s.isNotEmpty()) parsedRepos.add(s)
                    }
                }

                val modesMap = mutableMapOf<String, String>()
                obj.optJSONObject("fileModes")?.let { m ->
                    val keys = m.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = m.optString(k, "").trim()
                        if (k.isNotBlank() && v.isNotBlank()) modesMap[k] = v
                    }
                }

                val presets = mutableListOf<AutounattendPreset>()
                obj.optJSONArray("autounattendPresets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name", "").trim()
                        if (name.isNotEmpty()) {
                            val u = o.optString("localUsername", "")
                            presets.add(
                                AutounattendPreset(
                                    name = name,
                                    schemeName = o.optString("schemeName", PartitionScheme.GPT.name),
                                    bypassRequirements = o.optBoolean("bypassRequirements", true),
                                    bypassMicrosoftAccount = o.optBoolean("bypassMicrosoftAccount", true),
                                    disableDataCollection = o.optBoolean("disableDataCollection", true),
                                    disableBitLocker = o.optBoolean("disableBitLocker", true),
                                    disableSponsoredApps = o.optBoolean("disableSponsoredApps", false),
                                    classicContextMenu = o.optBoolean("classicContextMenu", false),
                                    skipWifiSetup = o.optBoolean("skipWifiSetup", true),
                                    createLocalUser = o.optBoolean("createLocalUser", u.isNotBlank()),
                                    localUsername = u,
                                    skipOobe = o.optBoolean("skipOobe", false)
                                )
                            )
                        }
                    }
                }

                val surfMode = runCatching {
                    SurfaceMode.valueOf(obj.optString("surfaceMode", current.surfaceMode.name))
                }.getOrDefault(current.surfaceMode)

                current.copy(
                    hasCompletedSetup = true,
                    defaultDir = defDir,
                    favoriteQuickMountPath = obj.optString("favoriteQuickMountPath", current.favoriteQuickMountPath),
                    keepAdbActive = obj.optBoolean("keepAdbActive", current.keepAdbActive),
                    autoPatchSelinux = obj.optBoolean("autoPatchSelinux", current.autoPatchSelinux),
                    surfaceMode = surfMode,
                    useMonet = obj.optBoolean("useMonet", current.useMonet),
                    accentHex = obj.optLong("accentHex", current.accentHex),
                    scanDirs = scanDirs,
                    useDefaultDistroRepo = obj.optBoolean("useDefaultDistroRepo", current.useDefaultDistroRepo),
                    customRepoUrls = parsedRepos.distinct(),
                    downloadConnections = obj.optInt("downloadConnections", current.downloadConnections).coerceIn(1, 16),
                    autoVerifySha256 = obj.optBoolean("autoVerifySha256", current.autoVerifySha256),
                    fileModes = modesMap,
                    autounattendPresets = presets
                )
            }.getOrNull()
        }
    }
}
