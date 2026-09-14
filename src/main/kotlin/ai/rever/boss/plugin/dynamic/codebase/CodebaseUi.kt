package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.clickable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.awt.Cursor

/**
 * Shared metrics and primitives for the codebase panel's three tabs.
 *
 * Everything here is sized to VS Code / Cursor's sidebar: 22px list rows,
 * 13px primary text, 26px inputs, 14px glyphs. Material 2's own components
 * are deliberately avoided in the row-dense surfaces - `OutlinedTextField`
 * alone is 56dp tall, which is more than two list rows, and `Button` forces
 * 36dp and a 14sp label. Both made the panel look like a settings form
 * instead of a sidebar.
 */
object CodebaseMetrics {
    /** One list row - VS Code's `list.rowHeight`. */
    val RowHeight = 22.dp

    /** Section header ("CHANGES", "STAGED CHANGES"). */
    val SectionHeaderHeight = 22.dp

    /** A text input; VS Code's is 24-26px including its border. */
    val InputHeight = 26.dp

    /** The panel's top tab strip. */
    val TabStripHeight = 28.dp

    /** File names, commit subjects, match lines. */
    val PrimaryText = 13.sp

    /** Group counts, secondary labels. */
    val SecondaryText = 12.sp

    /** Line numbers, dates, dim path suffixes. */
    val MetaText = 11.sp

    /** Row glyphs and toolbar icons. */
    val Glyph = 14.dp

    /** Hit target of a row / toolbar icon button. */
    val IconButton = 20.dp

    /** Horizontal gutter of the panel body. */
    val Gutter = 8.dp

    /** One level of tree indentation. */
    val Indent = 12.dp

    // Corner radii, from BOSS's BossRadii: inputs 3dp, buttons 5dp. Small
    // radii are the design system's "precision instrument" read; Material's
    // 4dp/50% defaults are not it.
    val InputRadius = 3.dp
    val ButtonRadius = 5.dp
    val ChipRadius = 3.dp
}

/**
 * The panel's colour tokens, all derived from the host theme so the three
 * tabs re-skin together. [Hover] and [Selected] mirror VS Code's
 * `list.hoverBackground` / `list.activeSelectionBackground`.
 */
object CodebasePalette {
    // Every token below is one of BOSS's semantic colours, reached through the
    // stable BossColors/BossThemeColors names. In the host those names are
    // getters over BossThemeController.current, so the panel re-skins live when
    // the user switches theme - and the light themes (Daylight) work, which a
    // hardcoded `Color.White.copy(alpha = …)` wash would not.
    //
    //   panel      -> Surface          raised   -> contextMenuBackground
    //   ink        -> Background       line     -> Border
    //   signal     -> Accent           signalWash -> contextMenuHover
    //   lineStrong -> contextMenuBorder
    val Foreground: Color get() = BossThemeColors.TextPrimary
    val Secondary: Color get() = BossThemeColors.TextSecondary
    val Muted: Color get() = BossThemeColors.TextMuted
    val Accent: Color get() = BossThemeColors.AccentColor
    val Data: Color get() = BossThemeColors.SecondaryColor

    /** Panel chrome (this panel's own ground). */
    val Panel: Color get() = BossThemeColors.SurfaceColor

    /** Recessed floor - the fill of an input, so the field reads as inset. */
    val Inset: Color get() = BossThemeColors.BackgroundColor

    /** Raised surface - tooltips, the confirmation sheet. */
    val Raised: Color get() = BossColors.contextMenuBackground

    val Error: Color get() = BossThemeColors.ErrorColor
    val Ok: Color get() = BossThemeColors.SuccessColor
    val Warn: Color get() = BossThemeColors.WarningColor

    /** signalWash - BOSS's own hover/active wash, correct on light themes too. */
    val Hover: Color get() = BossColors.contextMenuHover
    val Selected: Color get() = BossThemeColors.AccentColor.copy(alpha = 0.20f)

    val Border: Color get() = BossThemeColors.BorderColor
    val BorderStrong: Color get() = BossColors.contextMenuBorder
    val Divider: Color get() = BossThemeColors.BorderColor

    /**
     * Git status colours. Cursor's letters, BOSS's status palette - so a
     * modified file reads in the same amber the rest of the app warns in
     * instead of importing VS Code's own hexes.
     */
    val Modified: Color get() = BossThemeColors.WarningColor
    val Added: Color get() = BossThemeColors.SuccessColor
    val Deleted: Color get() = BossThemeColors.ErrorColor
    val Untracked: Color get() = BossThemeColors.SecondaryColor
    val Conflict: Color get() = BossThemeColors.ErrorColor

    /**
     * Graph lane hues, in order. Six theme tokens rather than six fixed
     * colours, so the lanes stay legible against every BOSS ground.
     */
    val laneColors: List<Color>
        get() = listOf(Accent, Ok, Warn, Data, Error, Secondary)

    /**
     * Readable content colour on top of an [Accent] fill.
     *
     * BOSS names this `onSignal`, but that token has no stable alias on the
     * api surface this plugin compiles against, so it is derived from the
     * accent's own luminance instead - which is correct for every theme,
     * including the amber accents where chalk-on-amber would fail.
     */
    fun onAccent(accent: Color = Accent): Color =
        if (accent.luminance() > 0.55f) Color(0xFF11130F) else Color(0xFFF6F7F4)
}

/**
 * The dim behind a modal sheet. Derived from the theme's own ink rather than
 * black, so it dims BOSS's light themes instead of blacking them out.
 */
val CodebaseScrim: Color
    get() = BossThemeColors.BackgroundColor.copy(alpha = 0.72f)

/** A 1px horizontal rule at the panel's divider colour. */
@Composable
fun CodebaseHRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CodebasePalette.Divider),
    )
}

/**
 * A hoverable list row: the full-bleed hover fill and fixed height every
 * dense row in the panel shares. [content] is laid out in a centred [Row].
 */
@Composable
fun CodebaseListRow(
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    height: androidx.compose.ui.unit.Dp = CodebaseMetrics.RowHeight,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(interaction)
            .background(
                when {
                    selected -> CodebasePalette.Selected
                    hovered -> CodebasePalette.Hover
                    else -> Color.Transparent
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(hovered)
    }
}

/**
 * A collapsible section header: twisty, uppercase title, count badge, and
 * [actions] that - VS Code style - only materialise while the header (or a
 * row under it) is hovered. [expanded] is hoisted so a parent can persist it.
 */
@Composable
fun CodebaseSectionHeader(
    title: String,
    count: Int? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Keep [actions] on screen when the header is not hovered.
     *
     * Hover-reveal is right for a row of icon buttons, and wrong for anything
     * that opens a popup: moving the pointer onto the popup un-hovers the
     * header, the anchor leaves the composition, and the popup closes the
     * instant it is reached. The GRAPH header's branch picker is exactly that.
     */
    actionsAlwaysVisible: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CodebaseMetrics.SectionHeaderHeight)
            .hoverable(interaction)
            .background(if (hovered) CodebasePalette.Hover else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(start = 2.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (expanded) Icons.Rounded.KeyboardArrowDown
                else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = CodebasePalette.Secondary,
        )
        Text(
            text = title,
            fontSize = CodebaseMetrics.MetaText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.0.sp,
            color = CodebasePalette.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(1f), filling: with `fill = false` here and a weighted
            // Spacer after it, the two split the leftover space evenly and
            // parked the badge in the middle of the row instead of at its end.
            modifier = Modifier.weight(1f),
        )
        if (actions != null && (hovered || actionsAlwaysVisible)) actions()
        // The count sits at the right edge, in the same column as the file
        // rows' status letters, so the numbers read down one line.
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            CodebaseCountBadge(count)
        }
    }
}

/**
 * The count on a section or directory row.
 *
 * A circle, not a pill: `Modifier.padding` around the text made the shape grow
 * with the digits, so a one-digit count read as a squashed oval. Sizing the box
 * from the digit count keeps 1-99 perfectly round and lets three digits widen
 * to a capsule rather than clip.
 */
@Composable
fun CodebaseCountBadge(
    count: Int,
    color: Color = CodebasePalette.Accent,
) {
    val text = count.toString()
    val diameter = if (text.length <= 2) BADGE_DIAMETER else BADGE_DIAMETER + 6.dp
    Box(
        modifier = Modifier
            .size(width = diameter, height = BADGE_DIAMETER)
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            // A bare Text centres its LINE BOX, not its glyphs: the font's
            // ascent/descent padding is asymmetric, so the digit sat high in
            // the circle. Trimming the line height to the glyph centres it.
            style = TextStyle(
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = color,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

private val BADGE_DIAMETER = 16.dp

/**
 * A 20dp icon button with a hover tint and a tooltip - the only button shape
 * used inside rows and section headers. [visible] false keeps the slot's
 * width so a row's text does not reflow as actions appear on hover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodebaseIconButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    visible: Boolean = true,
    tint: Color? = null,
) {
    if (!visible) {
        Spacer(Modifier.size(CodebaseMetrics.IconButton))
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    CodebaseTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(CodebaseMetrics.IconButton)
                .clip(RoundedCornerShape(4.dp))
                .hoverable(interaction, enabled = enabled)
                .background(if (hovered && enabled) CodebasePalette.Hover else Color.Transparent)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip,
                modifier = Modifier.size(CodebaseMetrics.Glyph),
                tint = when {
                    !enabled -> CodebasePalette.Muted.copy(alpha = 0.5f)
                    tint != null -> tint
                    hovered -> CodebasePalette.Foreground
                    else -> CodebasePalette.Secondary
                },
            )
        }
    }
}

/** Dark tooltip matching the host's context menus. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodebaseTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        modifier = modifier,
        tooltip = {
            Surface(
                color = CodebasePalette.Raised,
                elevation = 4.dp,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = text,
                    fontSize = CodebaseMetrics.MetaText,
                    color = CodebasePalette.Foreground,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        content = content,
    )
}

/**
 * A compact single-line text input. Material 2's `OutlinedTextField` is 56dp
 * tall with a 16sp default style; this is [CodebaseMetrics.InputHeight] with
 * the panel's 13sp, so three of them still leave room for results.
 *
 * [leading] and [trailing] sit inside the border, as VS Code's search box
 * does with its case, regex and whole-word toggles.
 */
@Composable
fun CodebaseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    minHeight: androidx.compose.ui.unit.Dp = CodebaseMetrics.InputHeight,
    keyHandler: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val focused = remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(CodebaseMetrics.InputRadius))
            .background(CodebasePalette.Inset)
            .border(
                width = 1.dp,
                color = if (focused.value) CodebasePalette.Accent else CodebasePalette.Border,
                shape = RoundedCornerShape(CodebaseMetrics.InputRadius),
            )
            .padding(horizontal = 4.dp),
        // A single-line field centres; a multi-line one starts at the top, so
        // the placeholder sits where the first line of text will, instead of
        // floating in the middle of an empty box.
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(3.dp))
        }
        Box(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                maxLines = maxLines,
                textStyle = TextStyle(
                    color = CodebasePalette.Foreground,
                    fontSize = CodebaseMetrics.PrimaryText,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(CodebasePalette.Foreground),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused.value = it.isFocused }
                    .then(keyHandler),
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = CodebaseMetrics.PrimaryText,
                    color = CodebasePalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(3.dp))
            trailing()
        }
    }
}

/**
 * A full-width primary action, 26dp tall with a 12sp label - the shape VS
 * Code's "Commit" button uses. Material's `Button` is 36dp with a 64dp
 * minimum width and its own elevation, which reads as a dialog button.
 */
@Composable
fun CodebasePrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = CodebasePalette.Accent
    val onAccent = CodebasePalette.onAccent(base)
    Row(
        modifier = modifier
            .height(CodebaseMetrics.InputHeight)
            .clip(RoundedCornerShape(CodebaseMetrics.ButtonRadius))
            .hoverable(interaction, enabled = enabled)
            .background(
                when {
                    !enabled -> base.copy(alpha = 0.25f)
                    hovered -> base
                    else -> base.copy(alpha = 0.85f)
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(CodebaseMetrics.Glyph),
                tint = if (enabled) onAccent else onAccent.copy(alpha = 0.45f),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            fontSize = CodebaseMetrics.SecondaryText,
            fontWeight = FontWeight.Medium,
            color = if (enabled) onAccent else onAccent.copy(alpha = 0.45f),
            maxLines = 1,
        )
    }
}

/** A small toggle chip (Aa / .* / ab), sized to sit inside an input. */
@Composable
fun CodebaseToggleChip(
    label: String,
    tooltip: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    CodebaseTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(CodebaseMetrics.ChipRadius))
                .hoverable(interaction)
                .background(
                    when {
                        active -> CodebasePalette.Accent.copy(alpha = 0.30f)
                        hovered -> CodebasePalette.Hover
                        else -> Color.Transparent
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (active) CodebasePalette.Foreground else CodebasePalette.Muted,
            )
        }
    }
}

/**
 * A draggable horizontal splitter. [fraction] is the top pane's share of the
 * available height, clamped to [min]..[max] so neither pane can be dragged
 * away entirely. [onChanged] carries each new fraction to the owner, which
 * owns persistence - the splitter itself is stateless.
 */
@Composable
fun CodebaseSplitter(
    fraction: State<Float>,
    onChanged: (Float) -> Unit,
    totalHeightPx: Float,
    min: Float = 0.15f,
    max: Float = 0.85f,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(totalHeightPx) {
                detectDragGestures { _, dragAmount ->
                    if (totalHeightPx <= 0f) return@detectDragGestures
                    val next = fraction.value + dragAmount.y / totalHeightPx
                    onChanged(next.coerceIn(min, max))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (hovered) CodebasePalette.Accent else CodebasePalette.Divider),
        )
    }
}

/**
 * Centred empty / hint state: optional glyph over dim 12sp text. Used for
 * "no results", "not a repository", and the missing-provider hints.
 */
@Composable
fun CodebaseEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = CodebasePalette.Muted.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = text,
                fontSize = CodebaseMetrics.SecondaryText,
                color = CodebasePalette.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Path split into the part VS Code dims and the part it does not: the file
 * name reads at full contrast, its directory trails behind it muted.
 */
internal fun splitPathForDisplay(path: String): Pair<String, String> {
    // The provider's paths are project-relative POSIX today, but the display
    // split must not assume that forever: take the LAST separator of either
    // family, so a Windows path ever arriving here still renders its file
    // name, not the whole path.
    val idx = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (idx < 0) path to "" else path.substring(idx + 1) to path.substring(0, idx)
}
