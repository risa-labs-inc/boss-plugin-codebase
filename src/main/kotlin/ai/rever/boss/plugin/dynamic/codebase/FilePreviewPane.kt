package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun FilePreviewPane(
    path: String?,
    selectionCount: Int,
    onOpen: (String) -> Unit,
    onOpenDefault: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var refresh by remember { mutableIntStateOf(0) }
    // A new selection immediately discards the previous image and scroll position.
    key(path, refresh) {
        val preview by produceState<FilePreview?>(null, path) {
            if (path != null) value = FilePreviewLoader.load(path)
        }
        Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PREVIEW", color = BossThemeColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }, modifier = Modifier.size(28.dp), enabled = path != null) {
                    Icon(Icons.Outlined.Refresh, "Refresh preview", tint = BossThemeColors.TextSecondary)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Close preview", tint = BossThemeColors.TextSecondary)
                }
            }
            if (path == null) {
                PreviewHint(if (selectionCount > 1) "Select one file to preview." else "Select a file to preview.")
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        File(path).name, color = BossThemeColors.TextPrimary, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val loaded = preview
                    if (loaded == null) {
                        CircularProgressIndicator(Modifier.padding(16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        val details = buildList {
                            add(loaded.kind)
                            loaded.size?.let { add(previewFileSize(it)) }
                            loaded.modifiedMillis?.let {
                                val time = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                                add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time))
                            }
                        }.joinToString(" · ")
                        if (details.isNotEmpty()) PreviewHint(details)
                        when (val body = loaded.body) {
                            is FilePreviewBody.Picture -> {
                                val bitmap = remember(body.image) { body.image.toComposeImageBitmap() }
                                Image(
                                    bitmap, "Preview of ${File(path).name}",
                                    Modifier.fillMaxWidth().height(144.dp), contentScale = ContentScale.Fit
                                )
                                PreviewHint("${body.width} × ${body.height} pixels")
                            }
                            is FilePreviewBody.Text -> {
                                if (body.truncated) PreviewHint("Showing the first 16 KiB. Open the file to see more.")
                                if (body.value.isEmpty()) PreviewHint("Empty file")
                                SelectionContainer {
                                    Text(
                                        body.value, color = BossThemeColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                                    )
                                }
                            }
                            is FilePreviewBody.Message -> PreviewHint(body.value)
                        }
                    }
                }
                Row {
                    TextButton(onClick = { onOpen(path) }, enabled = preview?.canOpenInBoss == true) {
                        Text("Open in BOSS", fontSize = 11.sp)
                    }
                    TextButton(onClick = { onOpenDefault(path) }, enabled = preview?.canOpenDefault == true) {
                        Text("Default app", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewHint(value: String) {
    Text(
        value, color = BossThemeColors.TextSecondary, fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}
