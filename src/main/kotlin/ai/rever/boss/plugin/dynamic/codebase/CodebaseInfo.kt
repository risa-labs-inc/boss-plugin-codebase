package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code

/**
 * Codebase panel info (Dynamic Plugin)
 */
object CodebaseInfo : PanelInfo {
    override val id = PanelId("codebase", 2)
    override val displayName = "Codebase"
    override val icon = Icons.Outlined.Code
    override val defaultSlotPosition = left.top.top
}
