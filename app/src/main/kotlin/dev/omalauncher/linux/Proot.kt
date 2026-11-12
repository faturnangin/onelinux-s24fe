package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.PhoneStorage
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

/** Paths and process launching for the proot'd Arch rootfs. */
class Proot(
    private val context: Context,
) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    val rootfs = File(filesDir, "arch")
    val tmpDir = File(filesDir, "tmp")

    /** Bound to XDG_RUNTIME_DIR inside the guest; holds the Wayland socket and the bridges' FIFOs. */
    val runtimeDir = File(filesDir, "xdg")
    val waylandSocket = File(runtimeDir, "wayland-0")
    val keymap = File(filesDir, "keymap.xkb")

    /** The guest user's home, /root, and the launcher's places in it. */
    val home = File(rootfs, "root")
    val applications = File(home, ".local/share/applications")
    val launcherConfig = File(home, ".config/omalauncher")

    /**
     * A listable stand-in for /sys: apps may only read /sys/devices/system/cpu,
     * and tools like btop abort when they can't walk the directories above it.
     */
    private val sysDir = File(filesDir, "sys")

    /** Stand-ins for /proc files Android hides from apps. */
    val procShim = ProcShim(File(filesDir, "proc"))

    /** How guest apps render OpenGL and Vulkan: in software, or on the GPU once [Gpu] turns it on. */
    @Volatile var graphicsEnv: List<String> = SOFTWARE_ENV

    private val prefs = LauncherData.prefs(context)

    /**
     * proot keeping itself and programs making many syscalls on one fast CPU (patches/proot/0002): Linux runs
     * several times faster. On unless the user turned it off; applies to what starts after a change.
     */
    var cpuPinning by mutableStateOf(prefs.getBoolean(CPU_PINNING, true))
        private set

    fun changeCpuPinning(on: Boolean) {
        cpuPinning = on
        prefs.edit().putBoolean(CPU_PINNING, on).apply()
    }

    /** Launcher data the guest sees in its home, kept outside the rootfs; see [LauncherData]. */
    private val dataBinds =
        listOf(
            "$GUEST_HOME/.local/share/omarchy" to LauncherData.omarchyDir(context),
            "$GUEST_HOME/.config/omalauncher/widgets" to LauncherData.widgetsDir(context),
            "$GUEST_HOME/.config/omalauncher/style" to LauncherData.styleDir(context),
            "$GUEST_HOME/.config/omalauncher/home" to LauncherData.homeDir(context),
            "$GUEST_HOME/.config/omarchy/themes" to LauncherData.userThemesDir(context),
            "$GUEST_HOME/.config/omarchy/backgrounds" to LauncherData.userBackgroundsDir(context),
        )

    /**
     * Android's Chinese, Japanese and Korean font, which the guest has none of (Arch's is ~300 MB).
     * Absent on some devices; the guest then goes without, as before.
     */
    private val cjkFont = File("/system/fonts/NotoSansCJK-Regular.ttc")

    private val fixedBinds = listOf("/tmp" to tmpDir, "/dev/shm" to tmpDir, GUEST_RUNTIME_DIR to runtimeDir) + dataBinds

    /** The phone's shared storage, as far as Android lets the launcher see it ([PhoneStorage]). */
    private val storageBinds =
        if (PhoneStorage.supported()) {
            listOf(
                PhoneStorage.GUEST to PhoneStorage.root,
                PhoneStorage.GUEST_ANDROID to PhoneStorage.root,
                PhoneStorage.GUEST_DOWNLOADS to File(PhoneStorage.root, "Download"),
                PhoneStorage.GUEST_DOCUMENTS to File(PhoneStorage.root, "Documents"),
            )
        } else {
            emptyList()
        }

    /** Host directories bound over guest paths by [command]. */
    private val binds = fixedBinds + storageBinds

    /**
     * Where an absolute guest path lives on the Android side, following the
     * directory binds in [command]: /tmp in the guest isn't rootfs/tmp.
     */
    fun hostFile(guestPath: String): File {
        for ((guest, host) in binds) {
            if (guestPath == guest || guestPath.startsWith("$guest/")) return File(host, guestPath.removePrefix(guest).removePrefix("/"))
        }
        return File(rootfs, guestPath.removePrefix("/"))
    }

    /** Where the guest can see: the rootfs and the binds, as real paths. */
    private val visible by lazy { (listOf(rootfs) + binds.map { it.second }).map { it.canonicalFile } }

    /** Whether [real], a canonical host file, is one the guest can see, rather than the launcher's own. */
    fun guestVisible(real: File) = visible.any { real.startsWith(it) }

    /** The empty directory in the rootfs that the phone's storage is bound over. */
    private val storageMount get() = File(rootfs, PhoneStorage.GUEST.removePrefix("/"))

    /** The guest's files as it sees them; see [GuestFs]. */
    val guestFs by lazy { GuestFs(::hostFile, ::guestVisible) }

    /**
     * Where a guest path that Linux or a widget names is on the Android side, as the guest sees it:
     * `..` stops at /, links are followed inside the guest. Null for a link in a loop or out of the
     * guest, so no path reaches the launcher's own files ([hostFile] is for paths of our own).
     */
    fun guestFile(guestPath: String): File? = runCatching { guestFs.host(guestPath) }.getOrNull()

    /** The guest path of a host file, the reverse of [hostFile]; null if the guest can't see it. */
    fun guestPath(host: File): String? {
        for ((guest, dir) in binds) {
            if (host.startsWith(dir)) return (guest + "/" + host.relativeTo(dir).path).trimEnd('/')
        }
        return if (host.startsWith(rootfs)) "/" + host.relativeTo(rootfs).path else null
    }

    /**
     * Resets what only lives as long as our process (guest processes die with
     * it): /tmp, the runtime dir, a stale pacman lock. Writes the host-side
     * stand-ins every guest command needs. Call once, before any guest runs.
     */
    fun prepare() {
        discardTmp()
        LauncherData.deleteTree(runtimeDir)
        runtimeDir.mkdirs()
        for (dir in listOf(tmpDir, runtimeDir)) {
            runCatching { android.system.Os.chmod(dir.path, 0b111_000_000) }
        }
        // proot skips a bind whose host directory is missing, and the guest would then write into the rootfs.
        // The empty mount points make the bound directories show up in listings of their parents.
        for ((guest, host) in dataBinds) {
            host.mkdirs()
            if (rootfs.isDirectory) File(rootfs, guest.removePrefix("/")).mkdirs()
        }
        // Its mount point, so / lists it; Android 10 has none (an empty one is all that's left there).
        if (rootfs.isDirectory) {
            storageMount.let { if (PhoneStorage.supported()) it.mkdirs() else it.delete() }
            if (PhoneStorage.supported()) {
                for ((guest, host) in storageBinds) {
                    runCatching { host.mkdirs() }
                    File(rootfs, guest.removePrefix("/")).mkdirs()
                }
            }
        }
        File(rootfs, "var/lib/pacman/db.lck").delete()
        // An empty stand-in, so fontconfig lists the bound font when it reads the directory.
        if (rootfs.isDirectory) {
            val stub = File(rootfs, GUEST_CJK_FONT.removePrefix("/"))
            if (cjkFont.isFile) {
                stub.parentFile?.mkdirs()
                stub.createNewFile()
            } else {
                stub.delete()
            }
        }
        for (sub in listOf("devices/system/cpu", "class", "block", "bus", "dev", "fs", "kernel")) File(sysDir, sub).mkdirs()
        val drmClass = File(sysDir, "class/drm").apply { mkdirs() }
        val devChar = File(sysDir, "dev/char").apply { mkdirs() }
        val sgpuDrm = File("/sys/devices/platform/22200000.sgpu/drm")
        if (sgpuDrm.exists()) {
            runCatching {
                File(drmClass, "renderD128").delete()
                android.system.Os.symlink("/sys/devices/platform/22200000.sgpu/drm/renderD128", File(drmClass, "renderD128").path)
                File(drmClass, "card0").delete()
                android.system.Os.symlink("/sys/devices/platform/22200000.sgpu/drm/card0", File(drmClass, "card0").path)
                File(devChar, "226:128").delete()
                android.system.Os.symlink("/sys/devices/platform/22200000.sgpu/drm/renderD128", File(devChar, "226:128").path)
                File(devChar, "226:0").delete()
                android.system.Os.symlink("/sys/devices/platform/22200000.sgpu/drm/card0", File(devChar, "226:0").path)
            }
        }
        updateBatterySysfs()
        // The stand-in was bound over /usr/bin/bwrap, which left proot's empty mount point there; no package owned
        // it, so pacman refused to install bubblewrap and everything needing it (glycin, so GTK apps). A real
        // bwrap is never empty.
        File(rootfs, "usr/bin/bwrap").takeIf { it.isFile && it.length() == 0L }?.delete()
        File(filesDir, "bwrap-shim").delete()
        File(applications, "noctalia-desktop.desktop").delete()
    }

    /** Old /tmp contents waiting to be deleted; see [discardTmp]. */
    private val discardedTmp = File(filesDir, "tmp-discarded")

    /**
     * Starts the guest with an empty /tmp. A day of agent work can leave hundreds of thousands of files there (a
     * cloned repo with node_modules), and deleting them takes long enough on a phone that Linux looked uninstalled
     * meanwhile. So the old one moves aside and is deleted in the background, along with any a killed process left.
     */
    private fun discardTmp() {
        discardedTmp.mkdirs()
        if (tmpDir.exists() && !tmpDir.renameTo(File(discardedTmp, System.nanoTime().toString()))) LauncherData.deleteTree(tmpDir)
        tmpDir.mkdirs()
        thread(name = "tmp-discard", isDaemon = true) {
            runCatching { LauncherData.deleteTree(discardedTmp) }.onFailure { Log.w("proot", "old /tmp", it) }
        }
    }

    /**
     * The bwrap stand-in (assets/bwrap-shim.sh): user namespaces are unavailable under proot, so the real one
     * fails. glycin and Codex look for bwrap on PATH, where /usr/local/bin precedes the package's /usr/bin.
     */
    init {
        try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    updateBatterySysfs()
                }
            }, filter)
        } catch (_: Exception) {}
        updateBatterySysfs()
    }

    fun updateBatterySysfs() {
        try {
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: 100
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 100
            val statusInt = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val statusStr = when (statusInt) {
                android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
                android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Discharging"
            }

            for (name in listOf("battery", "BAT0")) {
                val dir = File(sysDir, "class/power_supply/$name").apply { mkdirs() }
                File(dir, "capacity").writeText("$pct\n")
                File(dir, "status").writeText("$statusStr\n")
                File(dir, "type").writeText("Battery\n")
                File(dir, "present").writeText("1\n")
                File(dir, "technology").writeText("Li-ion\n")
                File(dir, "health").writeText("Good\n")
            }
        } catch (_: Exception) {}
    }

    fun managed(): List<Managed> =
        listOf(
            Managed.asset(context, "bwrap-shim.sh", File(rootfs, "usr/local/bin/bwrap"), Policy.Owned, executable = true),
            Managed.text(File(rootfs, "usr/local/bin/$NOCTALIA_DESKTOP_COMMAND"), Policy.Owned, executable = true) { noctaliaDesktopScript() },
            Managed.text(File(rootfs, "usr/local/bin/$CHROMIUM_XCLIPSE_COMMAND"), Policy.Owned, executable = true) { chromiumXclipseScript() },
            Managed.text(File(home, ".config/niri/config.kdl"), Policy.Owned) { niriDefaultConfig() },
        )

    private fun noctaliaDesktopScript(): String =
        listOf(
            "#!/bin/sh",
            "# Starts Niri (scrolling tiling Wayland compositor) nested inside OneLinux Wayland server.",
            "export XDG_CURRENT_DESKTOP=niri",
            "export XDG_SESSION_TYPE=wayland",
            "export QT_QPA_PLATFORM=wayland",
            "export GDK_BACKEND=wayland",
            "export MOZ_ENABLE_WAYLAND=1",
            "",
            "# Ensure X11 directory exists with correct permissions for xwayland-satellite",
            "mkdir -p /tmp/.X11-unix 2>/dev/null || true",
            "chmod 1777 /tmp/.X11-unix 2>/dev/null || true",
            "",
            "# Clean stale pacman lock if present",
            "rm -f /var/lib/pacman/db.lck 2>/dev/null",
            "",
            "if ! command -v niri >/dev/null 2>&1 || (! command -v noctalia >/dev/null 2>&1 && ! command -v quickshell >/dev/null 2>&1); then",
            "    echo \"=================================================\"",
            "    echo \" Installing Niri & Noctalia Desktop packages...\"",
            "    echo \"=================================================\"",
            "    pacman -Sy --needed --noconfirm niri xwayland-satellite dbus polkit chromium mesa vulkan-icd-loader foot swaybg || true",
            "    pacman -S --needed --noconfirm noctalia || pacman -S --needed --noconfirm quickshell || true",
            "fi",
            "",
            "if ! command -v niri >/dev/null 2>&1; then",
            "    echo \"\"",
            "    echo \"ERROR: Niri is not installed or installation failed.\"",
            "    echo \"Please check your network connection and retry.\"",
            "    echo \"Press Enter to exit...\"",
            "    read -r _",
            "    exit 1",
            "fi",
            "",
            "echo \"=================================================\"",
            "echo \" Starting Niri Nested Compositor...\"",
            "echo \"=================================================\"",
            "",
            "if command -v dbus-run-session >/dev/null 2>&1; then",
            "    dbus-run-session -- niri",
            "else",
            "    niri",
            "fi",
            "status=$?",
            "if [ \$status -ne 0 ]; then",
            "    echo \"\"",
            "    echo \"Niri exited with code \$status.\"",
            "    echo \"Press Enter to close this window...\"",
            "    read -r _",
            "fi",
        ).joinToString("\n", postfix = "\n")

    private fun chromiumXclipseScript(): String =
        listOf(
            "#!/bin/sh",
            "# Launches Chromium with Wayland + Zink + RADV-Xclipse hardware acceleration & WebGL flags.",
            "rm -f /var/lib/pacman/db.lck 2>/dev/null",
            "if ! command -v chromium >/dev/null 2>&1; then",
            "    echo \"Installing Chromium...\"",
            "    pacman -Sy --needed --noconfirm chromium mesa vulkan-icd-loader",
            "fi",
            "if ! command -v chromium >/dev/null 2>&1; then",
            "    echo \"ERROR: Failed to install Chromium.\"",
            "    echo \"Press Enter to exit...\"",
            "    read -r _",
            "    exit 1",
            "fi",
            "export vblank_mode=0",
            "export MESA_VK_WSI_PRESENT_MODE=mailbox",
            "export MESA_NO_ERROR=1",
            "export RADV_PERFTEST=gpl",
            "export RADV_DEBUG=nonggc",
            "export MESA_LOADER_DRIVER_OVERRIDE=zink",
            "export GALLIUM_DRIVER=zink",
            "export LIBGL_DRIVERS_PATH=/usr/local/omalauncher-mesa/lib/dri",
            "export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/local/share/glvnd/egl_vendor.d/50_mesa.json",
            "export VK_ICD_FILENAMES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json",
            "export VK_DRIVER_FILES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json",
            "export MESA_VK_WSI_DEBUG=sw",
            "exec chromium \\",
            "    --no-sandbox \\",
            "    --ozone-platform=wayland \\",
            "    --ignore-gpu-blocklist \\",
            "    --enable-gpu-rasterization \\",
            "    --enable-zero-copy \\",
            "    --enable-webgl \\",
            "    --enable-accelerated-2d-canvas \\",
            "    --num-raster-threads=4 \\",
            "    --disable-gpu-watchdog \\",
            "    --use-gl=angle \\",
            "    --use-angle=vulkan \\",
            "    --enable-features=CanvasOopRasterization,TouchpadOverscrollHistoryNavigation \\",
            "    --disable-dev-shm-usage \\",
            "    \"\$@\"",
        ).joinToString("\n", postfix = "\n")

    private fun niriDefaultConfig(): String =
        listOf(
            "// Default Niri + Noctalia Shell configuration for Galaxy S24 FE (Exynos 2400e / Xclipse 940)",
            "input {",
            "    keyboard {",
            "        xkb {",
            "            layout \"us\"",
            "        }",
            "    }",
            "    touchpad {",
            "        tap",
            "        natural-scroll",
            "    }",
            "    touch {",
            "        map-to-output \"winit\"",
            "    }",
            "    mod-key-nested \"Alt\"",
            "}",
            "",
            "layout {",
            "    gaps 0",
            "    center-focused-column \"never\"",
            "    default-column-width { proportion 1.0; }",
            "    focus-ring {",
            "        off",
            "    }",
            "}",
            "",
            "animations {",
            "    slowdown 0.8",
            "}",
            "",
            "window-rule {",
            "    geometry-corner-radius 0",
            "    clip-to-geometry true",
            "}",
            "",
            "spawn-at-startup \"sh\" \"-c\" \"if command -v noctalia >/dev/null 2>&1; then noctalia; elif command -v qs >/dev/null 2>&1; then qs -c noctalia-shell || qs; elif command -v quickshell >/dev/null 2>&1; then quickshell -c noctalia-shell || quickshell; fi\"",
            "spawn-at-startup \"sh\" \"-c\" \"if command -v swaybg >/dev/null 2>&1; then swaybg -c '#1e1e2e' -m fill; fi\"",
            "",
            "prefer-no-csd",
            "",
            "binds {",
            "    // Primary Alt shortcuts (bypasses Android OS Cmd interception)",
            "    Alt+Space { spawn \"sh\" \"-c\" \"if command -v noctalia >/dev/null 2>&1; then noctalia --toggle-launcher || noctalia; fi\"; }",
            "    Alt+T { spawn \"foot\"; }",
            "    Alt+Return { spawn \"foot\"; }",
            "    Alt+B { spawn \"chromium-xclipse\"; }",
            "    Alt+A { spawn \"foot\" \"-e\" \"agy\"; }",
            "    Alt+Q { close-window; }",
            "    Alt+Left  { focus-column-left; }",
            "    Alt+Right { focus-column-right; }",
            "    Alt+Up    { focus-window-up; }",
            "    Alt+Down  { focus-window-down; }",
            "    Alt+F { maximize-column; }",
            "    Alt+Shift+Slash { show-hotkey-overlay; }",
            "    Alt+Shift+E { quit skip-confirmation=true; }",
            "",
            "    // Fallbacks for Super/Mod keys",
            "    Mod+Space { spawn \"sh\" \"-c\" \"if command -v noctalia >/dev/null 2>&1; then noctalia --toggle-launcher || noctalia; fi\"; }",
            "    Mod+T { spawn \"foot\"; }",
            "    Mod+Return { spawn \"foot\"; }",
            "    Mod+B { spawn \"chromium-xclipse\"; }",
            "    Mod+Q { close-window; }",
            "    Mod+F { maximize-column; }",
            "}",
        ).joinToString("\n", postfix = "\n")

    private fun command(script: String): List<String> {
        val chrootNg = File(nativeDir, "libchroot-ng.so")
        val envVars =
            listOf(
                "HOME=$GUEST_HOME",
                "USER=root",
                "LANG=C.UTF-8",
                // Android's zone name (e.g. Europe/Berlin); the guest otherwise runs on UTC.
                "TZ=${java.util.TimeZone.getDefault().id}",
                "TERM=xterm-256color",
                // mise-installed tools (coding agents, node) live in the last two.
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/root/.local/bin:/root/.local/share/mise/shims",
                "TAR_OPTIONS=--no-same-permissions",
                "MISE_NPM_PACKAGE_MANAGER=npm",
                "DISABLE_AUTOUPDATER=1",
                "OPENCODE_DISABLE_AUTOUPDATE=1",
                "GROK_DISABLE_AUTOUPDATER=1",
                "CLAUDE_CODE_HARBOR_KITE=0",
                "BROWSER=xdg-open",
                "EDITOR=$EDIT_COMMAND",
                "VISUAL=$EDIT_COMMAND",
                "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR",
                "WAYLAND_DISPLAY=${waylandSocket.name}",
                "XDG_SESSION_TYPE=wayland",
                "GDK_BACKEND=wayland",
                "QT_QPA_PLATFORM=wayland",
                "MOZ_ENABLE_WAYLAND=1",
                "SDL_VIDEODRIVER=wayland",
            ) + graphicsEnv

        if (chrootNg.exists()) {
            for (dir in listOf(tmpDir, runtimeDir, sysDir)) {
                if (!dir.exists()) {
                    dir.mkdirs()
                    if (dir == tmpDir || dir == runtimeDir) {
                        runCatching { android.system.Os.chmod(dir.path, 0b111_000_000) }
                    }
                }
            }
            val vmstat = procShim.binds["/proc/vmstat"]
            val sysCpu = File("/sys/devices/system/cpu")
            val sysPlatform = File("/sys/devices/platform")
            val workDir = if (home.isDirectory) GUEST_HOME else "/"
            return listOf(
                chrootNg.path,
                "-u",
                "--setuid-root",
                "--setgid-root",
                "-l",
                "--shared-proc",
                "-w", workDir,
            ) + (if (vmstat?.exists() == true) listOf("-b", "${vmstat.path}:/proc/vmstat") else emptyList()) +
                (if (sysDir.exists()) listOf("-b", "${sysDir.path}:/sys") else emptyList()) +
                (if (sysCpu.exists()) listOf("-b", "${sysCpu.path}:/sys/devices/system/cpu") else emptyList()) +
                (if (sysPlatform.exists()) listOf("-b", "${sysPlatform.path}:/sys/devices/platform") else emptyList()) +
                (if (cjkFont.isFile) listOf("-b", "${cjkFont.path}:$GUEST_CJK_FONT") else emptyList()) +
                binds.flatMap { (guest, host) -> if (host.exists()) listOf("-b", "${host.path}:$guest") else emptyList() } +
                envVars.flatMap { listOf("-E", it) } +
                listOf(rootfs.path, "/bin/sh", "-c", script)
        }

        return listOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r",
            rootfs.path,
        ) + procShim.binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "/dev",
                "-b", "/proc",
                "-b", "${sysDir.path}:/sys",
                "-b", "/sys/devices/system/cpu",
            ) + (if (cjkFont.isFile) listOf("-b", "${cjkFont.path}:$GUEST_CJK_FONT") else emptyList()) +
            binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-w", GUEST_HOME,
                "/usr/bin/env", "-i",
            ) + envVars + listOf("/bin/sh", "-c", script)
    }

    /**
     * Kills every chroot-ng/proot we started and every guest process: before the rootfs
     * is deleted. Our own processes are the only ones whose /proc entries we can read.
     */
    fun killAll() {
        val chrootNg = File(nativeDir, "libchroot-ng.so").path
        val chrootNgCanon = runCatching { File(nativeDir, "libchroot-ng.so").canonicalPath }.getOrNull()
        val proot = File(nativeDir, "libproot.so").path
        val loader = File(nativeDir, "libproot-loader.so").path
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val argv0 = runCatching { File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }.getOrNull()
            val exe = runCatching { android.system.Os.readlink(File(dir, "exe").path) }.getOrNull()
            if (argv0 == chrootNg || exe == chrootNg || (chrootNgCanon != null && exe == chrootNgCanon) || argv0 == proot || exe == loader) {
                runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
            }
        }
    }

    /** A guest `sh -c [script]`, stderr merged into stdout. Needs [prepare] to have run. */
    fun process(script: String): ProcessBuilder =
        ProcessBuilder(command(script)).apply {
            environment().apply {
                clear()
                put("TMPDIR", tmpDir.path)
                put("XDG_RUNTIME_DIR", runtimeDir.path)
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", tmpDir.path)
                put("LD_LIBRARY_PATH", nativeDir.path)
                if (!cpuPinning) put("PROOT_HOME_CPU", "off")
            }
            redirectErrorStream(true)
        }

    companion object {
        private const val CPU_PINNING = "proot_cpu_pinning"

        const val GUEST_HOME = "/root"

        /** XDG_RUNTIME_DIR in the guest, bound to [runtimeDir]. */
        const val GUEST_RUNTIME_DIR = "/run/user/0"

        private const val GUEST_CJK_FONT = "/usr/share/fonts/android/NotoSansCJK-Regular.ttc"
    }
}
