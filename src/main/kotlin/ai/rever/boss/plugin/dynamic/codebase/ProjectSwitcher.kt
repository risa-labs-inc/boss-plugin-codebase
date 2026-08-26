package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.ui.BossPopup
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MenuRowHeight = 34.dp

/**
 * The panel header's project control: the open project's name, and a dropdown
 * that switches to any recent project or opens a new one.
 *
 * Selecting a row goes through ProjectDataProvider.selectProject (the same call
 * the host's own top-bar project menu makes), so the switch is the window's, not
 * this panel's — the top bar, the sidebar panels and every project-scoped tab
 * follow it.
 */
@Composable
internal fun ProjectSwitcher(
    projectName: String,
    entries: List<ProjectSwitcherEntry>,
    onSelect: (ProjectSwitcherEntry) -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // A pick asked for from inside the menu, deliberately NOT run from inside it.
    // On the heavyweight path the menu is its own always-on-top AWT window, and
    // the host's directory picker parents its native FileDialog to whichever
    // window is ACTIVE when it runs. Running the picker from a menu row therefore
    // hands the modal dialog an owner that this same frame disposes, and the
    // selection comes back null - the click looks like it did nothing. The flag
    // moves the call into this panel's own composition, one frame later.
    var pickPending by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(pickPending) {
        if (!pickPending) return@LaunchedEffect
        // One frame is what it takes for the menu window to be disposed; the host's
        // picker then adds its own invokeLater hop before it reads the active window.
        withFrameNanos { }
        pickPending = false
        onOpenProject()
    }

    // Column, not Box: BossPopup measures a zero-size probe wherever it is
    // called to give AnchorBounds something to anchor to, so it has to sit AFTER
    // the trigger row in layout order or the menu opens over the header instead
    // of under it. The probe reports 0x0, so the column is still exactly one row tall.
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (hovered || expanded) BossThemeColors.BorderColor.copy(alpha = 0.45f) else Color.Transparent
                )
                .clickable(interactionSource = interactionSource, indication = null) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = "Project",
                tint = BossThemeColors.AccentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = projectName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false so the chevron sits against the name on a wide
                // panel instead of being pushed to the far edge.
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = "Switch project",
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        if (expanded) {
            // BossPopup, not Popup: under JxBrowser HARDWARE_ACCELERATED a plain
            // Compose popup in a plugin panel is drawn BEHIND the page, and this
            // panel sits beside browser tabs constantly. AnchorBounds because the
            // menu belongs under the header row, not wherever the cursor landed.
            BossPopup(
                onDismissRequest = { expanded = false },
                focusable = true,
                anchoring = BossPopupAnchoring.AnchorBounds
            ) {
                ProjectSwitcherMenu(
                    entries = entries,
                    onSelect = { entry ->
                        expanded = false
                        // Re-selecting the open project would re-run the host's
                        // project-selection flow (workspace prompt included) to
                        // land exactly where we already are.
                        if (!entry.isCurrent) onSelect(entry)
                    },
                    onOpenProject = {
                        expanded = false
                        pickPending = true
                    }
                )
            }
        }
    }
}

@Composable
private fun ProjectSwitcherMenu(
    entries: List<ProjectSwitcherEntry>,
    onSelect: (ProjectSwitcherEntry) -> Unit,
    onOpenProject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = BossThemeColors.SurfaceColor,
        elevation = 8.dp,
        border = BorderStroke(1.dp, BossThemeColors.BorderColor),
        modifier = Modifier.widthIn(min = 240.dp, max = 420.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (entries.isNotEmpty()) {
                // Capped and scrollable: the host's recents list has no bound, and
                // a menu taller than the window cannot be dismissed by its own rows.
                Column(
                    modifier = Modifier
                        .heightIn(max = MenuRowHeight * 9)
                        .verticalScroll(rememberScrollState())
                ) {
                    entries.forEach { entry ->
                        ProjectRow(entry = entry, onClick = { onSelect(entry) })
                    }
                }
                Divider(color = BossThemeColors.BorderColor)
            }
            MenuActionRow(
                icon = Icons.Outlined.FolderOpen,
                label = "Open Project...",
                onClick = onOpenProject
            )
        }
    }
}

@Composable
private fun ProjectRow(entry: ProjectSwitcherEntry, onClick: () -> Unit) {
    val location = remember(entry.path) { ProjectSwitcherEntries.locationLabel(entry.path) }
    MenuRow(onClick = onClick) {
        // The check occupies the icon column on the current row, so names stay
        // aligned whether or not a row is the current one.
        if (entry.isCurrent) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Current project",
                tint = BossThemeColors.AccentColor,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = entry.name,
                fontSize = 13.sp,
                fontWeight = if (entry.isCurrent) FontWeight.Medium else FontWeight.Normal,
                color = BossThemeColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (location.isNotEmpty()) {
                Text(
                    text = location,
                    fontSize = 10.sp,
                    color = BossThemeColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MenuActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    MenuRow(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BossThemeColors.AccentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MenuRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MenuRowHeight)
            .background(if (hovered) BossThemeColors.BorderColor.copy(alpha = 0.45f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
