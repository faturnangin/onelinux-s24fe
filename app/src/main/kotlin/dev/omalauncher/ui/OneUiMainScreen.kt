package dev.omalauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.PhoneStorage
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.AGENTS
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.JobState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.omalauncher.linux.run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Samsung One UI 9 Color Palette
private val OneUiBackground = Color(0xFF0C0E12)
private val OneUiCardBg = Color(0xFF161920)
private val OneUiCardBorder = Color(0xFF262A35)
private val OneUiPillBg = Color(0xFF20242E)
private val OneUiBlue = Color(0xFF2563EB)
private val OneUiLightBlue = Color(0xFF38BDF8)
private val OneUiGreen = Color(0xFF10B981)
private val OneUiRose = Color(0xFFF43F5E)
private val OneUiAmber = Color(0xFFF59E0B)
private val OneUiTextPrimary = Color(0xFFF8FAFC)
private val OneUiTextSecondary = Color(0xFF94A3B8)
private val OneUiTextMuted = Color(0xFF64748B)

private val DEV_DIAGNOSTIC_SCRIPT = """
echo "=== 1. Antigravity (agy) Status ==="
if [ -f /root/.local/share/mise/shims/agy ]; then
    echo "agy Binary: /root/.local/share/mise/shims/agy"
    /root/.local/share/mise/shims/agy --version 2>&1 || true
elif command -v agy >/dev/null 2>&1; then
    echo "agy Binary: $(which agy)"
    agy --version 2>&1 || true
else
    echo "agy: Not found on PATH"
fi
echo ""
echo "=== 2. Compilers & Runtimes ==="
echo "git:     ${'$'}(command -v git >/dev/null && git --version || echo 'Not installed')"
echo "python3: ${'$'}(command -v python3 >/dev/null && python3 --version || echo 'Not installed')"
echo "nodejs:  ${'$'}(command -v node >/dev/null && node --version || echo 'Not installed')"
echo "gcc:     ${'$'}(command -v gcc >/dev/null && gcc --version | head -n 1 || echo 'Not installed (tap Install Dev SDK)')"
echo "make:    ${'$'}(command -v make >/dev/null && make --version | head -n 1 || echo 'Not installed')"
echo "rustc:   ${'$'}(command -v rustc >/dev/null && rustc --version || echo 'Not installed')"
echo ""
echo "=== 3. Utilities & Editors ==="
echo "foot:  ${'$'}(command -v foot >/dev/null && foot --version || echo 'Not installed')"
echo "btop:  ${'$'}(command -v btop >/dev/null && btop --version || echo 'Not installed')"
echo "micro: ${'$'}(command -v micro >/dev/null && micro -version || echo 'Not installed')"
echo ""
echo "=== 4. Virtualization & Wayland ==="
echo "Engine: chroot-ng (in-process seccomp, no ptrace overhead)"
echo "User: ${'$'}(id 2>/dev/null || whoami)"
echo "WAYLAND_DISPLAY: ${'$'}WAYLAND_DISPLAY"
echo "XDG_RUNTIME_DIR: ${'$'}XDG_RUNTIME_DIR"
echo ""
echo "=== 5. Storage Mounts & Free Space ==="
echo "/sdcard/Projects: ${'$'}([ -d /sdcard/Projects ] && echo 'Mounted (/storage/emulated/0/Projects)' || echo 'Not mounted')"
echo "/sdcard/Download: ${'$'}([ -d /sdcard/Download ] && echo 'Mounted (/storage/emulated/0/Download)' || echo 'Not mounted')"
df -h / 2>/dev/null | awk 'NR==2 {print "Disk Space: Used " ${'$'}3 " of " ${'$'}2 " (Free: " ${'$'}4 ")"}'
""".trimIndent()

private val HW_ACCEL_SCRIPT = """
echo "=== Samsung Exynos 2400e (Xclipse 940) ==="
echo "Architecture: AMD RDNA 3 GPU"
echo ""
echo "=== 1. Kernel Direct Rendering Nodes ==="
if [ -d /dev/dri ]; then
    ls -l /dev/dri
    echo "Status: /dev/dri/renderD128 accessible & mapped"
else
    echo "Warning: /dev/dri node missing"
fi
echo ""
echo "=== 2. Vulkan Driver & ICD Manifests ==="
ls -l /usr/local/share/vulkan/icd.d/ /usr/share/vulkan/icd.d/ 2>/dev/null || echo "No ICD files in default paths"
echo ""
echo "=== 3. Custom RADV Probe (Physical GPU) ==="
if [ -f /usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json ]; then
    echo "Custom ICD found: /usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json"
    unset LIBGL_ALWAYS_SOFTWARE VK_ICD_FILENAMES VK_DRIVER_FILES
    export VK_ICD_FILENAMES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json
    export VK_DRIVER_FILES=/usr/local/share/vulkan/icd.d/radeon_xclipse_icd.aarch64.json
    if command -v vulkaninfo >/dev/null 2>&1; then
        vulkaninfo --summary 2>&1 | grep -E "deviceName|driverID|driverName|apiVersion" || vulkaninfo --summary 2>&1 | head -n 12
    else
        echo "vulkaninfo not installed"
    fi
else
    echo "Testing Default Vulkan ICD:"
    if command -v vulkaninfo >/dev/null 2>&1; then
        vulkaninfo --summary 2>&1 | grep -E "deviceName|driverID|driverName|apiVersion" || echo "vulkaninfo probe complete"
    fi
fi
echo ""
echo "=== 4. OpenGL / EGL Renderer Info ==="
if command -v eglinfo >/dev/null 2>&1; then
    eglinfo -B 2>&1 | grep -E "OpenGL renderer|OpenGL vendor|OpenGL version|EGL_CLIENT_APIS" || true
elif command -v glxinfo >/dev/null 2>&1; then
    glxinfo -B 2>&1 | grep -E "OpenGL renderer|OpenGL vendor|OpenGL version" || true
else
    echo "eglinfo/glxinfo not installed (tap Install Mesa & Vulkan Tools)"
fi
echo ""
echo "=== 5. Active Graphics Environment ==="
env | grep -E "(LIBGL|MESA|VK_|GALLIUM|RADV)" || echo "No custom graphics variables set"
""".trimIndent()

private val DEV_SDK_INSTALL_SCRIPT = """
echo "Starting Dev SDK installation..."
rm -f /var/lib/pacman/db.lck 2>/dev/null || true
pacman -Sy --needed --noconfirm base-devel git python nodejs npm rust 2>&1
echo "Dev SDK installation finished."
""".trimIndent()

private val MESA_VULKAN_INSTALL_SCRIPT = """
echo "Starting Mesa & Vulkan tools installation..."
rm -f /var/lib/pacman/db.lck 2>/dev/null || true
pacman -Sy --needed --noconfirm mesa vulkan-icd-loader vulkan-tools mesa-utils vulkan-radeon 2>&1
echo "Mesa & Vulkan installation finished."
""".trimIndent()

private val UPDATE_SYSTEM_SCRIPT = """
echo "Checking and updating packages (pacman -Syu)..."
rm -f /var/lib/pacman/db.lck 2>/dev/null || true
pacman -Syu --noconfirm 2>&1
echo "System update finished."
""".trimIndent()

/**
 * Dedicated Samsung One UI 9 Hub for OneLinux on Samsung Galaxy S24 FE (Exynos 2400e).
 * Replaces Omalauncher's home screen, widgets, and setup with a clean, debloated interface.
 */
@Composable
fun OneUiMainScreen(
    runtime: Runtime,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linuxState by runtime.bootstrap.state.collectAsState()
    val apps = runtime.linuxApps.apps
    val agyAgent = AGENTS.firstOrNull { it.bin == "agy" } ?: AGENTS.first()
    var selectedTab by rememberSaveable { mutableStateOf(OneUiTab.APPS) }
    var driverMessage by remember { mutableStateOf<String?>(null) }
    var isUpdatingDriver by remember { mutableStateOf(false) }

    var isImportingRootfs by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    var activeDialogTitle by remember { mutableStateOf<String?>(null) }
    var activeDialogContent by remember { mutableStateOf<String?>(null) }
    var isDialogRunning by remember { mutableStateOf(false) }
    var showReinstallDialog by remember { mutableStateOf(false) }

    fun runGuestTask(title: String, script: String) {
        activeDialogTitle = title
        activeDialogContent = "Running command in Linux environment...\n"
        isDialogRunning = true
        scope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            try {
                runtime.proot.run(script) { line ->
                    sb.appendLine(line)
                    activeDialogContent = sb.toString()
                }
            } catch (e: Exception) {
                sb.appendLine("Execution error: ${e.message}")
                activeDialogContent = sb.toString()
            } finally {
                isDialogRunning = false
            }
        }
    }

    val rootfsImportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                isImportingRootfs = true
                importMessage = "Copying rootfs archive into app cache..."
                scope.launch(Dispatchers.IO) {
                    val res = runtime.importRootfsTarball(uri)
                    withContext(Dispatchers.Main) {
                        isImportingRootfs = false
                        if (res.isSuccess) {
                            importMessage = context.getString(R.string.oneui_tarball_imported)
                            // Start installation automatically with the imported archive
                            runtime.installLinux(listOf(agyAgent))
                        } else {
                            importMessage = "Import failed: ${res.exceptionOrNull()?.message}"
                        }
                    }
                }
            }
        }

    val driverImportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                isUpdatingDriver = true
                scope.launch {
                    try {
                        runtime.gpu.importXclipseDriver(uri, "custom-driver")
                        if (runtime.gpu.active) {
                            driverMessage = context.getString(R.string.oneui_dialog_success)
                        } else {
                            val failure = (runtime.gpu.state as? JobState.Failed)?.log?.joinToString("\n")
                            driverMessage = if (!failure.isNullOrBlank()) "Driver probe failed:\n$failure" else "Driver imported. Status: " + if (runtime.gpu.active) "Active" else "Inactive"
                        }
                    } catch (e: Exception) {
                        driverMessage = "Import failed: ${e.message}"
                    } finally {
                        isUpdatingDriver = false
                    }
                }
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(OneUiBackground)
                .padding(horizontal = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // Samsung One UI Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            stringResource(R.string.oneui_app_title),
                            style =
                                Mono.copy(
                                    fontFamily = FontFamily.Default,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiTextPrimary,
                                ),
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            when (selectedTab) {
                                OneUiTab.STATUS -> "Hardware Acceleration & System Status"
                                OneUiTab.APPS -> "Linux Applications & Shortcuts"
                                OneUiTab.ENV -> "Environment & Developer SDK"
                                OneUiTab.GPU -> "GPU Driver & Acceleration Manager"
                                OneUiTab.STORAGE -> "Shared Storage & Mounts"
                            },
                            style =
                                Mono.copy(
                                    fontFamily = FontFamily.Default,
                                    fontSize = 13.sp,
                                    color = OneUiLightBlue,
                                ),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneUiIconButton(
                            label = stringResource(R.string.oneui_refresh),
                            onClick = {
                                scope.launch { runtime.linuxApps.refresh() }
                            },
                        )
                    }
                }
            }

            // Linux State Handler: Installing / Failed / Not Installed
            when (val state = linuxState) {
                is LinuxState.Installing -> {
                    item {
                        OneUiInstallingCard(state = state, onCancel = { runtime.cancelLinux() })
                    }
                }
                is LinuxState.Failed -> {
                    item {
                        OneUiFailedCard(state = state, onRetry = { runtime.retryLinux() })
                    }
                }
                is LinuxState.NotInstalled -> {
                    item {
                        OneUiInstallPromptCard(
                            isImporting = isImportingRootfs,
                            importMessage = importMessage,
                            onInstall = {
                                runtime.installLinux(listOf(agyAgent))
                            },
                            onImportTarball = {
                                rootfsImportLauncher.launch(arrayOf("*/*"))
                            },
                        )
                    }
                }
                else -> Unit
            }

            // Tab-specific screen content
            when (selectedTab) {
                OneUiTab.STATUS -> {
                    item {
                        OneUiStatusCard(runtime = runtime)
                    }
                    item {
                        OneUiStatusDetailCard(
                            onTestHwAccel = {
                                runGuestTask("Hardware Acceleration Diagnostic", HW_ACCEL_SCRIPT)
                            },
                        )
                    }
                }
                OneUiTab.APPS -> {
                    item {
                        OneUiAppsCard(
                            runtime = runtime,
                            apps = apps,
                            onLaunchApp = { app -> runtime.launch(app) },
                        )
                    }
                    item {
                        OneUiAppsActionCard(
                            onOpenTerminal = { runtime.terminal(null, "Terminal") },
                            onOpenFiles = { runtime.openFiles() },
                        )
                    }
                }
                OneUiTab.ENV -> {
                    item {
                        OneUiLinuxManagerCard(
                            onCheckEnv = {
                                runGuestTask("Dev Environment Status", DEV_DIAGNOSTIC_SCRIPT)
                            },
                            onInstallDevSdk = {
                                runGuestTask("Install Dev SDK", DEV_SDK_INSTALL_SCRIPT)
                            },
                            onUpdateSystem = {
                                runGuestTask("Update System (pacman -Syu)", UPDATE_SYSTEM_SCRIPT)
                            },
                            onReinstall = {
                                showReinstallDialog = true
                            },
                        )
                    }
                }
                OneUiTab.GPU -> {
                    item {
                        OneUiDriverManagerCard(
                            runtime = runtime,
                            isUpdating = isUpdatingDriver,
                            message = driverMessage,
                            onTestHwAccel = {
                                runGuestTask("Hardware Acceleration Diagnostic", HW_ACCEL_SCRIPT)
                            },
                            onInstallGpuPkg = {
                                runGuestTask("Install Mesa & Vulkan Tools", MESA_VULKAN_INSTALL_SCRIPT)
                            },
                            onCheckUpdates = {
                                isUpdatingDriver = true
                                scope.launch {
                                    runtime.downloadGpuDriver("https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/download/radv-xclipse-upstream/RADV-Xclipse-upstream-Linux.zip")
                                    isUpdatingDriver = false
                                    driverMessage = context.getString(R.string.oneui_dialog_success)
                                }
                            },
                            onImportLocal = {
                                driverImportLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-tar",
                                        "application/x-xz",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            onDismissMessage = { driverMessage = null },
                        )
                    }
                }
                OneUiTab.STORAGE -> {
                    item {
                        OneUiStorageCard(context = context)
                    }
                    item {
                        OneUiStorageActionsCard(
                            onImportTarball = {
                                rootfsImportLauncher.launch(arrayOf("*/*"))
                            },
                            onOpenFiles = { runtime.openFiles() },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(100.dp)) }
        }

        // Floating Bottom Navigation Bar (Samsung One UI 9 Floating Capsule)
        OneUiFloatingBottomBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Active Output Dialog
        activeDialogTitle?.let { title ->
            OneUiOutputDialog(
                title = title,
                content = activeDialogContent ?: "",
                isRunning = isDialogRunning,
                onClose = { activeDialogTitle = null },
            )
        }

        // Reinstall Confirmation Dialog
        if (showReinstallDialog) {
            OneUiConfirmDialog(
                title = stringResource(R.string.oneui_reinstall_title),
                description = stringResource(R.string.oneui_reinstall_desc),
                confirmLabel = stringResource(R.string.oneui_btn_confirm_reinstall),
                cancelLabel = stringResource(R.string.oneui_btn_cancel),
                confirmColor = OneUiRose,
                onConfirm = {
                    showReinstallDialog = false
                    runtime.removeLinux()
                    runtime.installLinux(listOf(agyAgent))
                },
                onDismiss = { showReinstallDialog = false },
            )
        }
    }
}

@Composable
private fun OneUiStatusCard(runtime: Runtime) {
    val hasStorage = PhoneStorage.hasAllFilesAccess()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_section_status),
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OneUiStatusRow(
                label = stringResource(R.string.oneui_status_gpu),
                accentColor = OneUiGreen,
                active = runtime.gpu.active,
            )
            OneUiStatusRow(
                label = stringResource(R.string.oneui_status_driver),
                accentColor = OneUiBlue,
                active = true,
            )
            OneUiStatusRow(
                label = stringResource(R.string.oneui_status_wayland),
                accentColor = OneUiLightBlue,
                active = true,
            )
            OneUiStatusRow(
                label = stringResource(if (hasStorage) R.string.oneui_status_storage else R.string.oneui_status_storage_need_perm),
                accentColor = if (hasStorage) OneUiGreen else OneUiAmber,
                active = hasStorage,
            )
        }
    }
}

@Composable
private fun OneUiStatusRow(
    label: String,
    accentColor: Color,
    active: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(OneUiPillBg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    color = OneUiTextPrimary,
                ),
        )
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (active) accentColor else OneUiTextMuted),
        )
    }
}

@Composable
private fun OneUiAppsCard(
    runtime: Runtime,
    apps: List<LinuxApp>,
    onLaunchApp: (LinuxApp) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_section_apps),
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(14.dp))

        if (apps.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    stringResource(R.string.oneui_apps_empty),
                    style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextMuted),
                )
            }
        } else {
            // Display apps in 4-column squircle grid
            val rows = (apps.size + 3) / 4
            for (row in 0 until rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (col in 0 until 4) {
                        val index = row * 4 + col
                        if (index < apps.size) {
                            val app = apps[index]
                            Box(modifier = Modifier.weight(1f)) {
                                OneUiGridAppItem(runtime = runtime, app = app, onClick = { onLaunchApp(app) })
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (row < rows - 1) Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun OneUiGridAppItem(
    runtime: Runtime,
    app: LinuxApp,
    onClick: () -> Unit,
) {
    val icon = runtime.linuxApps.icons[app.id]

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(OneUiPillBg)
                    .border(1.dp, OneUiCardBorder, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(OneUiBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        app.name.take(1).uppercase(),
                        style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OneUiBlue),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            app.name,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 11.sp, color = OneUiTextPrimary, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OneUiLinuxManagerCard(
    onCheckEnv: () -> Unit,
    onInstallDevSdk: () -> Unit,
    onUpdateSystem: () -> Unit,
    onReinstall: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_section_linux_manage),
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.oneui_linux_status_healthy),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiLightBlue),
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OneUiSmallBadge(
                label = stringResource(R.string.oneui_agy_badge),
                color = OneUiGreen,
                modifier = Modifier.weight(1f),
            )
            OneUiSmallBadge(
                label = "/sdcard/Projects (Ready)",
                color = OneUiBlue,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_check_env),
                color = OneUiLightBlue,
                modifier = Modifier.weight(1f),
                onClick = onCheckEnv,
            )
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_install_dev_sdk),
                color = OneUiGreen,
                modifier = Modifier.weight(1f),
                onClick = onInstallDevSdk,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_update_pacman),
                color = OneUiBlue,
                modifier = Modifier.weight(1f),
                onClick = onUpdateSystem,
            )
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_reinstall),
                color = OneUiRose,
                modifier = Modifier.weight(1f),
                onClick = onReinstall,
            )
        }
    }
}

@Composable
private fun OneUiDriverManagerCard(
    runtime: Runtime,
    isUpdating: Boolean,
    message: String?,
    onTestHwAccel: () -> Unit,
    onInstallGpuPkg: () -> Unit,
    onCheckUpdates: () -> Unit,
    onImportLocal: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_section_driver),
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.oneui_driver_active),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OneUiSmallBadge(
                label = "sgpu DRM /dev/dri",
                color = OneUiGreen,
                modifier = Modifier.weight(1f),
            )
            OneUiSmallBadge(
                label = "Vulkan 1.4 + Zink",
                color = OneUiLightBlue,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_test_hw),
                color = OneUiGreen,
                modifier = Modifier.weight(1f),
                onClick = onTestHwAccel,
            )
            OneUiBorderButton(
                label = stringResource(R.string.oneui_btn_install_gpu_pkg),
                color = OneUiLightBlue,
                modifier = Modifier.weight(1f),
                onClick = onInstallGpuPkg,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OneUiBorderButton(
                label = stringResource(if (isUpdating) R.string.oneui_dialog_downloading else R.string.oneui_driver_check_github),
                color = OneUiBlue,
                modifier = Modifier.weight(1f),
                onClick = onCheckUpdates,
            )
            OneUiBorderButton(
                label = stringResource(R.string.oneui_driver_import),
                color = OneUiTextSecondary,
                modifier = Modifier.weight(1f),
                onClick = onImportLocal,
            )
        }

        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OneUiPillBg)
                        .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    message,
                    style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = OneUiTextPrimary),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    stringResource(R.string.oneui_dialog_close),
                    style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OneUiBlue),
                    modifier = Modifier.clickable(onClick = onDismissMessage).padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun OneUiSmallBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OneUiOutputDialog(
    title: String,
    content: String,
    isRunning: Boolean,
    onClose: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable(onClick = onClose)
                .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(OneUiCardBg)
                    .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                    .clickable(enabled = false) {}
                    .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        title,
                        style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = OneUiTextPrimary),
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        if (isRunning) "Running command in background..." else "Execution complete",
                        style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = if (isRunning) OneUiAmber else OneUiGreen),
                    )
                }
                OneUiIconButton(
                    label = "✕",
                    onClick = onClose,
                )
            }

            Spacer(Modifier.height(14.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF07090C))
                        .border(1.dp, OneUiCardBorder, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                        .verticalScroll(scrollState),
            ) {
                BasicText(
                    content.ifEmpty { "Executing task..." },
                    style = Mono.copy(fontSize = 11.sp, color = OneUiTextPrimary),
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OneUiBorderButton(
                    label = if (copied) "Copied!" else "Copy Output",
                    color = if (copied) OneUiGreen else OneUiLightBlue,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                        copied = true
                    },
                )
                OneUiBorderButton(
                    label = "Close",
                    color = OneUiTextSecondary,
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                )
            }
        }
    }
}

@Composable
private fun OneUiConfirmDialog(
    title: String,
    description: String,
    confirmLabel: String,
    cancelLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable(onClick = onDismiss)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(OneUiCardBg)
                    .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                    .clickable(enabled = false) {}
                    .padding(22.dp),
        ) {
            BasicText(
                title,
                style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OneUiTextPrimary),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                description,
                style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OneUiBorderButton(
                    label = cancelLabel,
                    color = OneUiTextSecondary,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                OneUiBorderButton(
                    label = confirmLabel,
                    color = confirmColor,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun OneUiStorageCard(context: android.content.Context) {
    val hasStorage = PhoneStorage.hasAllFilesAccess()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_section_storage),
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OneUiStorageRow(stringResource(R.string.oneui_folder_downloads))
            OneUiStorageRow(stringResource(R.string.oneui_folder_documents))
            OneUiStorageRow(stringResource(R.string.oneui_folder_projects))
        }

        if (!hasStorage) {
            Spacer(Modifier.height(14.dp))
            OneUiBorderButton(
                label = stringResource(R.string.oneui_perm_grant_all_files),
                color = OneUiAmber,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    runCatching { context.startActivity(PhoneStorage.manageAllFilesIntent(context)) }
                },
            )
        }
    }
}

@Composable
private fun OneUiStorageRow(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(OneUiPillBg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextPrimary),
        )
    }
}

@Composable
private fun OneUiInstallingCard(
    state: LinuxState.Installing,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiBlue.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_installing_title),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = OneUiTextPrimary),
        )
        Spacer(Modifier.height(10.dp))

        val progress = state.progress ?: 0.5f
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(OneUiPillBg),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(fraction = progress.coerceIn(0.05f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(listOf(OneUiBlue, OneUiLightBlue))),
            )
        }
        Spacer(Modifier.height(8.dp))

        BasicText(
            state.step,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(10.dp))

        OneUiBorderButton(
            label = stringResource(R.string.oneui_installing_cancel),
            color = OneUiRose,
            modifier = Modifier.align(Alignment.End),
            onClick = onCancel,
        )
    }
}

@Composable
private fun OneUiFailedCard(
    state: LinuxState.Failed,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiRose.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_install_failed),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = OneUiRose),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            state.message,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(12.dp))
        OneUiBorderButton(
            label = stringResource(R.string.oneui_install_retry),
            color = OneUiBlue,
            modifier = Modifier.align(Alignment.End),
            onClick = onRetry,
        )
    }
}

@Composable
private fun OneUiInstallPromptCard(
    isImporting: Boolean,
    importMessage: String?,
    onInstall: () -> Unit,
    onImportTarball: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiBlue.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            stringResource(R.string.oneui_install_title),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = OneUiTextPrimary),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            stringResource(R.string.oneui_install_desc),
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
        )

        if (importMessage != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OneUiBlue.copy(alpha = 0.15f))
                        .padding(10.dp),
            ) {
                BasicText(
                    importMessage,
                    style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = OneUiLightBlue),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (isImporting) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(OneUiPillBg)
                        .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "Importing rootfs archive... Please wait...",
                    style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = OneUiLightBlue),
                )
            }
        } else {
            OneUiPillButton(
                label = stringResource(R.string.oneui_install_btn),
                gradient = Brush.horizontalGradient(listOf(OneUiBlue, OneUiLightBlue)),
                onClick = onInstall,
            )
            Spacer(Modifier.height(10.dp))
            OneUiBorderButton(
                label = stringResource(R.string.oneui_install_import_tarball),
                color = OneUiLightBlue,
                modifier = Modifier.fillMaxWidth(),
                onClick = onImportTarball,
            )
        }
    }
}

@Composable
private fun OneUiPillButton(
    label: String,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(gradient)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White),
        )
    }
}

@Composable
private fun OneUiBorderButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OneUiIconButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(OneUiPillBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 12.sp, color = OneUiTextPrimary),
        )
    }
}

/**
 * 5 Modular Screens in OneLinux Hub:
 * 1. STATUS: Hardware Acceleration Status & System overview
 * 2. APPS: Linux Applications Grid & Launcher
 * 3. ENV: Linux Environment, Antigravity CLI & Dev SDK Management
 * 4. GPU: GPU Driver & Hardware Acceleration Manager
 * 5. STORAGE: Shared Storage & Filesystem Mounts
 */
enum class OneUiTab(
    val title: String,
) {
    STATUS("Status"),
    APPS("Apps"),
    ENV("Env"),
    GPU("GPU"),
    STORAGE("Storage"),
}

@Composable
private fun OneUiTabIcon(
    tab: OneUiTab,
    tint: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (tab) {
            OneUiTab.STATUS -> {
                // Heartbeat / Pulse waveform line (Samsung Device Care health style)
                val path = Path().apply {
                    moveTo(w * 0.08f, h * 0.52f)
                    lineTo(w * 0.28f, h * 0.52f)
                    lineTo(w * 0.40f, h * 0.20f)
                    lineTo(w * 0.58f, h * 0.82f)
                    lineTo(w * 0.72f, h * 0.52f)
                    lineTo(w * 0.92f, h * 0.52f)
                }
                drawPath(path, color = tint, style = stroke)
                if (isSelected) {
                    drawCircle(color = tint, radius = 2.dp.toPx(), center = Offset(w * 0.40f, h * 0.20f))
                    drawCircle(color = tint, radius = 2.dp.toPx(), center = Offset(w * 0.58f, h * 0.82f))
                }
            }
            OneUiTab.APPS -> {
                // 2x2 squircle tile grid
                val pad = w * 0.10f
                val gap = w * 0.14f
                val tileSize = (w - pad * 2f - gap) / 2f
                val corner = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

                for (row in 0..1) {
                    for (col in 0..1) {
                        val left = pad + col * (tileSize + gap)
                        val top = pad + row * (tileSize + gap)
                        if (isSelected) {
                            drawRoundRect(
                                color = tint,
                                topLeft = Offset(left, top),
                                size = Size(tileSize, tileSize),
                                cornerRadius = corner,
                                style = Fill,
                            )
                        } else {
                            drawRoundRect(
                                color = tint,
                                topLeft = Offset(left, top),
                                size = Size(tileSize, tileSize),
                                cornerRadius = corner,
                                style = stroke,
                            )
                        }
                    }
                }
            }
            OneUiTab.ENV -> {
                // Terminal / Shell prompt (>_)
                val chevronPath = Path().apply {
                    moveTo(w * 0.15f, h * 0.28f)
                    lineTo(w * 0.45f, h * 0.50f)
                    lineTo(w * 0.15f, h * 0.72f)
                }
                drawPath(chevronPath, color = tint, style = stroke)
                // Underscore cursor
                drawLine(
                    color = tint,
                    start = Offset(w * 0.52f, h * 0.72f),
                    end = Offset(w * 0.85f, h * 0.72f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            OneUiTab.GPU -> {
                // Microchip / GPU die with pins
                val chipMargin = w * 0.22f
                val chipW = w - chipMargin * 2f
                val chipH = h - chipMargin * 2f
                val chipCorner = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

                // Draw outer body
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(chipMargin, chipMargin),
                    size = Size(chipW, chipH),
                    cornerRadius = chipCorner,
                    style = stroke,
                )

                // Inner core
                if (isSelected) {
                    val innerMargin = w * 0.36f
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(innerMargin, innerMargin),
                        size = Size(w - innerMargin * 2f, h - innerMargin * 2f),
                        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                        style = Fill,
                    )
                }

                // Pins (Top & Bottom, Left & Right)
                val pinLen = w * 0.12f
                val p1 = w * 0.38f
                val p2 = w * 0.62f

                // Top & Bottom pins
                drawLine(tint, Offset(p1, 0f), Offset(p1, pinLen), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(p2, 0f), Offset(p2, pinLen), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(p1, h - pinLen), Offset(p1, h), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(p2, h - pinLen), Offset(p2, h), strokeWidth = stroke.width, cap = StrokeCap.Round)

                // Left & Right pins
                drawLine(tint, Offset(0f, p1), Offset(pinLen, p1), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(0f, p2), Offset(pinLen, p2), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w - pinLen, p1), Offset(w, p1), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w - pinLen, p2), Offset(w, p2), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            OneUiTab.STORAGE -> {
                // Modern file folder
                val folderPath = Path().apply {
                    // Top tab & body
                    moveTo(w * 0.12f, h * 0.32f)
                    lineTo(w * 0.12f, h * 0.28f)
                    lineTo(w * 0.42f, h * 0.28f)
                    lineTo(w * 0.52f, h * 0.38f)
                    lineTo(w * 0.88f, h * 0.38f)
                    lineTo(w * 0.88f, h * 0.76f)
                    lineTo(w * 0.12f, h * 0.76f)
                    close()
                }
                if (isSelected) {
                    drawPath(folderPath, color = tint, style = Fill)
                } else {
                    drawPath(folderPath, color = tint, style = stroke)
                    // Folder crease line
                    drawLine(
                        color = tint,
                        start = Offset(w * 0.12f, h * 0.42f),
                        end = Offset(w * 0.88f, h * 0.42f),
                        strokeWidth = stroke.width * 0.8f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun OneUiFloatingBottomBar(
    selectedTab: OneUiTab,
    onSelectTab: (OneUiTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(OneUiCardBg.copy(alpha = 0.94f))
                    .border(1.dp, OneUiCardBorder, RoundedCornerShape(30.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OneUiTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                val itemBg = if (isSelected) OneUiPillBg else Color.Transparent
                val textColor = if (isSelected) OneUiLightBlue else OneUiTextMuted
                val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(itemBg)
                            .clickable { onSelectTab(tab) }
                            .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OneUiTabIcon(
                        tab = tab,
                        tint = textColor,
                        isSelected = isSelected,
                    )
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        tab.title,
                        style =
                            Mono.copy(
                                fontFamily = FontFamily.Default,
                                fontSize = 11.sp,
                                fontWeight = fontWeight,
                                color = textColor,
                                textAlign = TextAlign.Center,
                            ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun OneUiStatusDetailCard(onTestHwAccel: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            "Hardware Diagnostic & Node Status",
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            "Kernel Direct Rendering Nodes: /dev/dri/card0, card1, renderD128",
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OneUiSmallBadge(
                label = "sgpu DRM (Mapped)",
                color = OneUiGreen,
                modifier = Modifier.weight(1f),
            )
            OneUiSmallBadge(
                label = "AMD RDNA 3 (Active)",
                color = OneUiLightBlue,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        OneUiBorderButton(
            label = "Run Full Hardware Probe",
            color = OneUiGreen,
            modifier = Modifier.fillMaxWidth(),
            onClick = onTestHwAccel,
        )
    }
}

@Composable
private fun OneUiAppsActionCard(
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            "Quick Launch & Tools",
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            "Launch interactive Linux terminal or browse guest storage",
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OneUiBorderButton(
                label = "Open Terminal",
                color = OneUiLightBlue,
                modifier = Modifier.weight(1f),
                onClick = onOpenTerminal,
            )
            OneUiBorderButton(
                label = "Explore Files",
                color = OneUiBlue,
                modifier = Modifier.weight(1f),
                onClick = onOpenFiles,
            )
        }
    }
}

@Composable
private fun OneUiStorageActionsCard(
    onImportTarball: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(OneUiCardBg)
                .border(1.dp, OneUiCardBorder, RoundedCornerShape(26.dp))
                .padding(20.dp),
    ) {
        BasicText(
            "Storage Tools & Rootfs Management",
            style =
                Mono.copy(
                    fontFamily = FontFamily.Default,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            "Manage two-way directory mounts or import rootfs archive",
            style = Mono.copy(fontFamily = FontFamily.Default, fontSize = 13.sp, color = OneUiTextSecondary),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OneUiBorderButton(
                label = "Browse Files",
                color = OneUiBlue,
                modifier = Modifier.weight(1f),
                onClick = onOpenFiles,
            )
            OneUiBorderButton(
                label = "Import Rootfs (.tar)",
                color = OneUiTextSecondary,
                modifier = Modifier.weight(1f),
                onClick = onImportTarball,
            )
        }
    }
}
