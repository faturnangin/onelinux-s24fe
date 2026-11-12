package dev.omalauncher.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

/**
 * Samsung One UI 9 style Folder Hub for Linux Apps on Galaxy S24 FE (Exynos 2400e).
 * Displays an expanded squircle folder card containing quick-action hardware pills,
 * shared storage status, and a 4-column app grid of PRoot Linux applications.
 */
@Composable
fun OneUiFolderHub(
    runtime: Runtime,
    state: LauncherState,
    onSwitchToGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasStorageAccess = PhoneStorage.hasAllFilesAccess()
    val apps = runtime.linuxApps.apps
    val agyAgent = AGENTS.firstOrNull { it.bin == "agy" } ?: AGENTS.first()

    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(Palette.surface.copy(alpha = 0.94f))
                    .border(1.dp, Palette.border.copy(alpha = 0.4f), RoundedCornerShape(32.dp))
                    .padding(20.dp),
        ) {
            // Folder Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        stringResource(R.string.oneui_hub_title),
                        style = Mono.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.foreground),
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        stringResource(R.string.oneui_hub_subtitle),
                        style = Mono.copy(fontSize = TextSize.Small, color = Palette.muted),
                    )
                }

                BracketAction(
                    stringResource(R.string.oneui_switch_grid),
                    color = Palette.accent,
                    onClick = onSwitchToGrid,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Quick Action Pills
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneUiActionPill(
                        label = stringResource(R.string.oneui_terminal_app),
                        color = Palette.accent,
                        modifier = Modifier.weight(1f),
                    ) {
                        val terminalApp = apps.firstOrNull { it.exec.contains("foot") }
                        if (terminalApp != null) {
                            runtime.launch(terminalApp)
                        } else {
                            runtime.terminal("foot", "Terminal")
                        }
                    }

                    OneUiActionPill(
                        label = stringResource(R.string.oneui_action_browser),
                        color = Palette.green,
                        modifier = Modifier.weight(1f),
                    ) {
                        val browserApp = apps.firstOrNull { it.exec.contains("chromium-xclipse") }
                        if (browserApp != null) {
                            runtime.launch(browserApp)
                        } else {
                            runtime.terminal("chromium-xclipse", "Chromium WebGL")
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneUiActionPill(
                        label = stringResource(R.string.oneui_action_agy),
                        color = Palette.accent,
                        modifier = Modifier.weight(1f),
                    ) {
                        runtime.terminal(agyAgent.command, agyAgent.name, agyAgent)
                    }

                    OneUiActionPill(
                        label = stringResource(R.string.oneui_action_gpu),
                        color = if (runtime.gpu.active) Palette.green else Palette.muted,
                        modifier = Modifier.weight(1f),
                    ) {
                        state.show(SettingsPage.Linux)
                    }
                }

                // Shared Storage Status Pill
                OneUiActionPill(
                    label = stringResource(if (hasStorageAccess) R.string.oneui_action_storage else R.string.oneui_action_storage_scoped),
                    color = if (hasStorageAccess) Palette.green else Palette.accent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    runCatching { context.startActivity(PhoneStorage.manageAllFilesIntent(context)) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4-Column Squircle App Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    OneUiAppItem(runtime = runtime, app = app) {
                        runtime.launch(app)
                    }
                }
            }
        }
    }
}

@Composable
private fun OneUiActionPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = Mono.copy(color = color, fontSize = TextSize.Small, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OneUiAppItem(
    runtime: Runtime,
    app: LinuxApp,
    onClick: () -> Unit,
) {
    val icon = runtime.linuxApps.icons[app.id]
    val mask = if (LauncherStyle.monochromeIcons) runtime.linuxApps.masks[app.id] else null

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.background.copy(alpha = 0.7f))
                    .border(1.dp, Palette.border.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val image = mask ?: icon
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Palette.accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        app.name.take(1).uppercase(),
                        style = Mono.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.accent),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        BasicText(
            app.name,
            style = Mono.copy(fontSize = 11.sp, color = Palette.foreground, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
