package dev.omalauncher.linux

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.storage.LauncherData
import dev.omalauncher.R
import dev.omalauncher.net.openHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Opt-in GPU acceleration for Linux apps. The device node picks the driver:
 * PanVK-kbase on /dev/mali0 (Mali v10+: Pixel 7 and newer, recent MediaTek),
 * or Turnip on /dev/kgsl-3d0 when that node is an Adreno 6xx or newer.
 * OpenGL is zink on top of that Vulkan driver, through a patched libEGL that
 * keeps zink when the compositor only offers wl_shm. Frames still reach
 * us as wl_shm buffers (MESA_VK_WSI_DEBUG=sw), so the compositor doesn't change.
 *
 * Each driver is a prerelease and talks to a kernel interface that Android
 * updates can change, so it is only used after a check passes, and checked
 * again after every system update; until then apps render in software.
 */
class Gpu(
    private val context: Context,
    private val proot: Proot,
    private val pacman: Pacman,
) {
    private val prefs = LauncherData.prefs(context)

    /** The pinned driver for this phone, or null when we have none. */
    private val spec: GpuDriver? = gpuKind(
        hasMali = File("/dev/mali0").exists(),
        hasKgsl = File("/dev/kgsl-3d0").exists(),
        kgslModel = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText() }.getOrNull(),
        hasDrmRender = File("/dev/dri/renderD128").exists() || File("/sys/class/drm/renderD128").exists(),
        socOrGpuModel = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            Build.HARDWARE,
            Build.BOARD,
            runCatching { File("/sys/kernel/gpu/gpu_model").readText() }.getOrNull(),
        ).joinToString(" "),
    )?.let(GpuDrivers::of)

    /** The phone has a GPU we have a driver for. */
    val supported = spec != null

    /** Whether this phone uses the hot-swappable Xclipse RADV driver (`JimVulkan/radv-xclipse`). */
    val isXclipse = spec?.kind == GpuKind.XCLIPSE

    /** Version or filename of the custom imported `radv-xclipse` driver, or null when using default. */
    var customDriverVersion by mutableStateOf(readCustomDriverVersion())
        private set

    private fun readCustomDriverVersion(): String? =
        runCatching { proot.hostFile(GpuDrivers.XCLIPSE_CUSTOM_META).takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /** "Mali" or "Adreno", for the settings row. Empty when [supported] is false. */
    val driverLabel: String get() = spec?.label ?: ""

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Mali-G715 MC7". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** What changes under the driver: the kernel and Android's own GPU driver come with system updates. */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    /**
     * Activates the GPU if it passed its check on this system build. After
     * a system update, rechecks it first (in the background: apps render in
     * software meanwhile) and stays off if the check fails.
     */
    suspend fun recheck() {
        val driver = spec ?: return
        if (!wanted || active || state is JobState.Working) return
        if (!proot.hostFile(driver.driverPath).isFile) return turnOff()
        // The APK carries a different Mesa than the guest has, or none was installed
        // yet (Mali before it used the patched Mesa). The env names that Mesa.
        val mesaCurrent = runCatching { proot.hostFile(GpuDrivers.MESA_ZINK_STAMP).readText().trim() }
            .getOrNull() == GpuDrivers.MESA_ZINK_TAR_SHA256
        if (mesaCurrent && prefs.getString(CHECKED_BUILD, null) == build) {
            // An older install wrote Mali's manifest on pacman's path. The env now
            // names the /usr/local file, so that file has to exist before we activate.
            if (!proot.hostFile(driver.icdPath).isFile) {
                retireLegacyManifest(driver)
                if (!run(R.string.linux_job_installing, GpuDrivers.manifestScript(driver))) {
                    proot.graphicsEnv = SOFTWARE_ENV
                    conceal(driver)
                    return
                }
            }
            return activate(driver)
        }
        // Install and check in one run: run() records the build as checked on success.
        val ok = if (mesaCurrent) {
            Log.i(TAG, "system changed since the last GPU check, checking again")
            run(R.string.linux_job_checking, GpuDrivers.probeScript(driver))
        } else {
            Log.i(TAG, "the patched Mesa changed, installing it and checking again")
            stageMesaBundle()
            run(R.string.linux_job_installing, GpuDrivers.mesaZinkScript() + " && " + GpuDrivers.probeScript(driver))
        }
        if (ok) {
            activate(driver)
        } else {
            proot.graphicsEnv = SOFTWARE_ENV
            conceal(driver)
        }
    }

    /** Installs the driver and Mesa, then checks them; turns on only if the check passes. */
    suspend fun turnOn() {
        val driver = spec ?: return
        if (state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        stageMesaBundle()
        retireLegacyManifest(driver)
        val script = GpuDrivers.installScript(driver) + " && " + GpuDrivers.probeScript(driver)
        // The install script runs pacman.
        if (run(R.string.linux_job_installing, script, pacman.lock)) {
            activate(driver)
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
            active = false
            proot.graphicsEnv = SOFTWARE_ENV
            conceal(driver)
        }
    }

    /**
     * Back to software rendering for apps started from now on. The library
     * stays, so turning on again is quick. The ICD manifest does not: while
     * it exists the Vulkan loader finds it with no env var set.
     */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = SOFTWARE_ENV
        spec?.let(::conceal)
    }

    private fun activate(driver: GpuDriver) {
        active = true
        proot.graphicsEnv = driver.env
    }

    /** Drops the manifest so a later app cannot load the driver by the default search path. */
    private fun conceal(driver: GpuDriver) {
        proot.hostFile(driver.icdPath).delete()
        retireLegacyManifest(driver)
    }

    /** Before writing the /usr/local manifest, and on the way out. */
    private fun retireLegacyManifest(driver: GpuDriver) = GpuDrivers.retireLegacyManifest(driver, proot.hostFile(GpuDrivers.LEGACY_MALI_ICD))

    /**
     * The patched Mesa tar (scripts/mesa/build-aarch64.sh) is in the APK's assets.
     * A build without that asset leaves the install script to fail closed on the missing file.
     */
    private fun stageMesaBundle() {
        val dest = proot.hostFile(GpuDrivers.MESA_ZINK_BUNDLE)
        val input = try {
            context.assets.open("mesa-zink/mesa-zink.tar.xz")
        } catch (_: FileNotFoundException) {
            return
        }
        dest.parentFile?.mkdirs()
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
    }

    /** Runs [script]; on success remembers the build it passed on and the GPU's name from vulkaninfo. */
    private suspend fun run(
        @StringRes action: Int,
        script: String,
        lock: Mutex? = null,
    ): Boolean {
        val (ok, tail) = proot.trackedJob(action, script, lock) { state = it }
        val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
        Log.i(TAG, "${context.getString(action)}: ${if (ok) "ok, $name" else "failed"}")
        if (ok) {
            device = name
            prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
        }
        return ok
    }

    /**
     * Hot-swaps the Xclipse RADV driver from a local URI (`.zip` from `JimVulkan/radv-xclipse`,
     * `.pkg.tar.xz`, or raw `libvulkan_radeon.so`) without resetting the Linux rootfs.
     */
    suspend fun importXclipseDriver(
        uri: Uri,
        displayName: String,
    ) {
        val driver = spec ?: return
        if (state is JobState.Working) return
        state = JobState.Working(R.string.linux_job_updating, listOf(displayName))
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    stageXclipseStream(input, displayName)
                } ?: error("cannot open $displayName")
            }
        }
        if (extracted.isFailure) {
            state = JobState.Failed(listOf(extracted.exceptionOrNull()?.message ?: "failed to read driver package"))
            return
        }
        customDriverVersion = readCustomDriverVersion()
        applyStagedXclipseDriver(driver)
    }

    /**
     * Hot-swaps the Xclipse RADV driver directly from a URL (e.g. a GitHub release asset
     * or `JimVulkan/radv-xclipse` latest release).
     */
    suspend fun downloadXclipseDriver(url: String) {
        val driver = spec ?: return
        if (state is JobState.Working) return
        val trimmed = url.trim().ifEmpty { GpuDrivers.XCLIPSE_GITHUB_REPO }
        state = JobState.Working(R.string.linux_job_updating, listOf(trimmed))
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val resolvedUrl = resolveGithubReleaseAsset(trimmed)
                val label = resolvedUrl.substringAfterLast('/').ifEmpty { "radv-xclipse" }
                val conn = openHttp(resolvedUrl, connectTimeout = 15_000, readTimeout = 60_000)
                try {
                    if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from $resolvedUrl")
                    conn.inputStream.use { input -> stageXclipseStream(input, label) }
                } finally {
                    conn.disconnect()
                }
            }
        }
        if (fetched.isFailure) {
            state = JobState.Failed(listOf(fetched.exceptionOrNull()?.message ?: "failed to download driver"))
            return
        }
        customDriverVersion = readCustomDriverVersion()
        applyStagedXclipseDriver(driver)
    }

    private suspend fun applyStagedXclipseDriver(driver: GpuDriver) {
        stageMesaBundle()
        val script = "rm -f /var/lib/pacman/db.lck 2>/dev/null || true; " +
            GpuDrivers.hotSwapXclipseScript(driver) + " && " +
            GpuDrivers.mesaZinkScript() + " && " +
            GpuDrivers.probeScript(driver)
        if (run(R.string.linux_job_updating, script)) {
            prefs.edit().putBoolean(WANTED, true).apply()
            wanted = true
            activate(driver)
        } else {
            state = JobState.Failed((state as? JobState.Failed)?.log.orEmpty())
        }
    }

    private fun stageXclipseStream(
        input: InputStream,
        name: String,
    ) {
        val staged = proot.hostFile(GpuDrivers.XCLIPSE_CUSTOM_STAGED)
        val meta = proot.hostFile(GpuDrivers.XCLIPSE_CUSTOM_META)
        staged.parentFile?.mkdirs()
        val tmp = File(staged.parentFile, "libvulkan_radeon.so.tmp")
        val lower = name.lowercase()
        val found = when {
            lower.endsWith(".zip") -> extractSoFromZip(BufferedInputStream(input), tmp)
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> extractSoFromTarXz(BufferedInputStream(input), tmp)
            else -> {
                tmp.outputStream().use { out -> input.copyTo(out) }
                true
            }
        }
        check(found && tmp.isFile && tmp.length() > 1024L) {
            "No libvulkan_radeon.so found in $name"
        }
        // Verify ELF magic (\x7FELF)
        val header = ByteArray(4)
        tmp.inputStream().use { it.read(header) }
        check(header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            "$name is not a valid ELF shared library (note: WCP/Bionic drivers are for Winlator; use a glibc .so/.zip build)"
        }
        tmp.setExecutable(true, false)
        if (staged.exists()) staged.delete()
        check(tmp.renameTo(staged)) { "cannot stage $name" }
        meta.writeText(name.substringAfterLast('/'))
    }

    private fun extractSoFromZip(
        input: InputStream,
        dest: File,
    ): Boolean {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val fn = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && (fn.startsWith("libvulkan_radeon.so") || fn.startsWith("libvulkan_freedreno_radv.so"))) {
                    dest.outputStream().use { out -> zip.copyTo(out) }
                    return true
                }
            }
        }
        return false
    }

    private fun extractSoFromTarXz(
        input: InputStream,
        dest: File,
    ): Boolean {
        TarArchiveInputStream(XZCompressorInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isFile && entry.name.substringAfterLast('/').startsWith("libvulkan_radeon.so")) {
                    dest.outputStream().use { out -> tar.copyTo(out) }
                    return true
                }
            }
        }
        return false
    }

    /**
     * If [url] points to the `JimVulkan/radv-xclipse` repo root or `/releases`, resolves the latest
     * glibc `.zip` or `.so` asset URL from GitHub's releases page without hitting API rate limits.
     */
    private fun resolveGithubReleaseAsset(url: String): String {
        if (url.endsWith(".so") || url.endsWith(".zip") || url.endsWith(".tar.xz")) return url
        val releasesUrl = if (url.contains("github.com/")) {
            url.trimEnd('/').removeSuffix("/releases") + "/releases"
        } else {
            "${GpuDrivers.XCLIPSE_GITHUB_REPO}/releases"
        }
        val html = fetchText(releasesUrl)
        val tagMatch = Regex("""/releases/expanded_assets/([^"'\s>]+)""").find(html)
            ?: Regex("""/releases/tag/([^"'\s>]+)""").find(html)
            ?: error("No releases found at $releasesUrl")
        val tag = tagMatch.groupValues[1]
        val baseRepo = releasesUrl.removeSuffix("/releases")
        val assetsHtml = runCatching { fetchText("$baseRepo/releases/expanded_assets/$tag") }.getOrDefault(html)
        val assetLinks = Regex("""href="([^"]+/releases/download/[^"]+)"""").findAll(assetsHtml)
            .map { it.groupValues[1] }
            .map { if (it.startsWith("http")) it else "https://github.com$it" }
            .toList()
        return assetLinks.firstOrNull { it.contains("glibc", ignoreCase = true) && (it.endsWith(".zip") || it.endsWith(".so")) }
            ?: assetLinks.firstOrNull { !it.endsWith(".wcp") && (it.endsWith(".zip") || it.endsWith(".so") || it.endsWith(".tar.xz")) }
            ?: error("No glibc .zip/.so asset found in release $tag (found only Winlator .wcp; build or import radv-glibc .zip)")
    }

    private fun fetchText(url: String): String {
        val conn = openHttp(url, connectTimeout = 10_000, readTimeout = 15_000)
        return try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} from $url")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")
    }
}
