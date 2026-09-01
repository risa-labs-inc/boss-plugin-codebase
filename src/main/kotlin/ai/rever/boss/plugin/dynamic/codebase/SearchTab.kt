package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.FileMatch
import ai.rever.boss.plugin.api.ProjectSearchProvider
import ai.rever.boss.plugin.api.ReplaceSummary
import ai.rever.boss.plugin.api.SplitViewOperations
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The SEARCH tab of the codebase panel (P7) - global search & replace.
 *
 * The engine is host-side ([ProjectSearchProvider]); a null provider means
 * the host predates it, which renders as an upgrade hint - never a crash.
 *
 * Every input is a StateFlow here rather than a plain `var` shadowed by a
 * `remember` in the composable. With two copies of the query the toggles
 * drifted: flipping Aa / .* / ab changed the view model's flags and left the
 * results from the previous flags on screen, because nothing re-ran the
 * search. Each setter that changes the result set now schedules one.
 */
class CodebaseSearchViewModel(
    private val provider: ProjectSearchProvider?,
    private val splitViewOperations: SplitViewOperations?,
    private val getProjectPath: () -> String? = { null },
) {
    // Blocking content-scan I/O, not CPU-bound work: Default is sized to
    // cores and these calls would block it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val providerOrNull: ProjectSearchProvider? get() = provider

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _pathPattern = MutableStateFlow("")
    val pathPattern: StateFlow<String> = _pathPattern.asStateFlow()

    private val _excludePattern = MutableStateFlow("")
    val excludePattern: StateFlow<String> = _excludePattern.asStateFlow()

    private val _replacement = MutableStateFlow("")
    val replacement: StateFlow<String> = _replacement.asStateFlow()

    private val _isRegex = MutableStateFlow(false)
    val isRegex: StateFlow<Boolean> = _isRegex.asStateFlow()
    private val _caseSensitive = MutableStateFlow(false)
    val caseSensitive: StateFlow<Boolean> = _caseSensitive.asStateFlow()
    private val _wholeWord = MutableStateFlow(false)
    val wholeWord: StateFlow<Boolean> = _wholeWord.asStateFlow()

    private val _results = MutableStateFlow<List<FileMatch>>(emptyList())
    val results: StateFlow<List<FileMatch>> = _results.asStateFlow()

    /** Grouped by file, path-sorted - the shape the results tree renders. */
    private val _grouped = MutableStateFlow<List<FileMatches>>(emptyList())
    val grouped: StateFlow<List<FileMatches>> = _grouped.asStateFlow()

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _dryRun = MutableStateFlow<ReplaceSummary?>(null)
    val dryRun: StateFlow<ReplaceSummary?> = _dryRun.asStateFlow()

    /** True when the result set was truncated at [MAX_RESULTS]. */
    private val _capped = MutableStateFlow(false)
    val capped: StateFlow<Boolean> = _capped.asStateFlow()

    private var searchJob: Job? = null
    private var refreshTimer: Job? = null

    fun setQuery(value: String) {
        _query.value = value
        _dryRun.value = null
        scheduleSearch()
    }

    fun setPathPattern(value: String) {
        _pathPattern.value = value
        scheduleSearch()
    }

    fun setExcludePattern(value: String) {
        _excludePattern.value = value
        scheduleSearch()
    }

    fun setReplacement(value: String) {
        _replacement.value = value
        _dryRun.value = null
    }

    fun setSearchOption(option: SearchToggle, value: Boolean) {
        when (option) {
            SearchToggle.REGEX -> _isRegex.value = value
            SearchToggle.CASE -> _caseSensitive.value = value
            SearchToggle.WORD -> _wholeWord.value = value
        }
        _dryRun.value = null
        // A flag change changes the result set; without this the toggles left
        // the previous flags' results on screen.
        scheduleSearch()
    }

    /** Debounced re-run, so typing does not queue one scan per keystroke. */
    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob =
            scope.launch {
                delay(DEBOUNCE_MS)
                execute()
            }
    }

    /** Run now (the Enter key / the search icon) with no debounce. */
    fun runSearch() {
        searchJob?.cancel()
        searchJob = scope.launch { execute() }
    }

    /**
     * Keep the results honest while the tab is open.
     *
     * A results tree that still lists matches the file no longer contains is
     * worse than no results - after a replace, or any edit elsewhere, it
     * reports work that is already done. The provider caches each file's
     * matches against its mtime (or its buffer version when the file is open),
     * so a re-scan with nothing changed is a directory walk and a stat per
     * file, and an unchanged result set is dropped by StateFlow equality
     * rather than reshuffling the tree.
     */
    fun startAutoRefresh() {
        refreshTimer?.cancel()
        refreshTimer =
            scope.launch {
                while (isActive) {
                    delay(REFRESH_MS)
                    if (_query.value.isNotBlank() && !_busy.value && searchJob?.isActive != true) {
                        // Track the refresh in searchJob like every other scan:
                        // a clear/cancel that lands mid-scan must be able to
                        // reach it. A direct execute() here outran both, and
                        // the scan published the old query's results - plus
                        // _searched - a moment after the user cleared.
                        searchJob = scope.launch { execute() }
                    }
                }
            }
    }

    fun stopAutoRefresh() {
        refreshTimer?.cancel()
        refreshTimer = null
    }

    private suspend fun execute() {
        val q = _query.value
        if (q.isBlank()) {
            _results.value = emptyList()
            _grouped.value = emptyList()
            _searched.value = false
            _message.value = null
            _capped.value = false
            return
        }
        _busy.value = true
        _message.value = null
        try {
            // Exclusion is the engine's, not ours. It used to be a filter over the
            // returned list, with this plugin carrying its own copy of the glob
            // compiler - which meant the provider's cap was reached BEFORE the
            // exclude ran, so excluding a busy directory quietly returned fewer
            // results than existed. Hence the doubled page size that used to be
            // here, which softened the symptom and did not fix it.
            val matches =
                provider?.searchInProject(
                    query = q,
                    pathPattern = _pathPattern.value.ifBlank { null },
                    excludePattern = _excludePattern.value.ifBlank { null },
                    isRegex = _isRegex.value,
                    caseSensitive = _caseSensitive.value,
                    wholeWord = _wholeWord.value,
                    maxResults = MAX_RESULTS,
                ) ?: emptyList()
            // A clear or a newer query that landed mid-scan wins: publishing
            // this result set would resurrect exactly what the clear removed.
            if (_query.value != q) return
            _results.value = matches
            _grouped.value = groupMatchesByFile(matches)
            // Now an honest cap: the engine excluded during the walk, so hitting
            // MAX_RESULTS means there really are more matches the user wants, not
            // that the page was spent on files about to be filtered out.
            _capped.value = matches.size >= MAX_RESULTS
            _searched.value = true
        } catch (e: CancellationException) {
            // Re-throw: a cancelled scan is not a search failure, and
            // swallowing the cancel would also leave _busy stuck true.
            throw e
        } catch (e: Exception) {
            // An in-progress regex ("(", "[a-") throws on every keystroke;
            // report it in the status line instead of clearing the results.
            _message.value = e.message ?: "Search failed"
        } finally {
            _busy.value = false
        }
    }

    /**
     * Open one match at its own line and column.
     *
     * The previous implementation handed [SplitViewOperations.openTab] an
     * anonymous [ai.rever.boss.plugin.api.TabInfo] carrying the path in an
     * extra property. `openTab` has an empty default body and no way to read
     * that property, so clicking a result did nothing at all.
     */
    fun openMatch(match: FileMatch) {
        val ops = splitViewOperations ?: return
        val name = PathUtils.name(match.path).ifEmpty { match.path }
        ops.openFileAtPosition(absolutePathOf(match.path), name, match.line, match.column)
    }

    fun openFile(path: String) {
        val ops = splitViewOperations ?: return
        val name = PathUtils.name(path).ifEmpty { path }
        ops.openFileInEditor(absolutePathOf(path), name)
    }

    /**
     * [FileMatch.path] is project-relative - the host's engine reports
     * `file.relativeTo(projectRoot)`. The editor opens absolute paths, so
     * handing it the relative one produced "file not found" on every result
     * click. [ProjectSearchProvider.replaceInProject] takes either, which is
     * why replace worked while opening did not.
     */
    internal fun absolutePathOf(path: String): String {
        if (path.startsWith("/") || path.matches(WINDOWS_ABSOLUTE)) return path
        val root = getProjectPath()?.trimEnd('/') ?: return path
        return if (root.isEmpty()) path else "$root/$path"
    }

    /** Dry-run first, so the UI can show what will change before writing. */
    fun previewReplacement() {
        if (_query.value.isBlank() || _results.value.isEmpty()) return
        // Set BEFORE launch: inside the coroutine the flag lands only once the
        // dispatcher runs the body, so two clicks in the same frame both saw
        // busy = false and both passed the `enabled = … && !busy` guard.
        if (!_busy.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                _dryRun.value =
                    provider?.replaceInProject(
                        query = _query.value,
                        replacement = _replacement.value,
                        files = matchedFiles(),
                        isRegex = _isRegex.value,
                        caseSensitive = _caseSensitive.value,
                        wholeWord = _wholeWord.value,
                        dryRun = true,
                    )
            } catch (e: Exception) {
                _message.value = e.message ?: "Preview failed"
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyReplacement() {
        val summary = _dryRun.value ?: return
        // See previewReplacement: a duplicate click here is a duplicate WRITE.
        if (!_busy.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val applied =
                    provider?.replaceInProject(
                        query = _query.value,
                        replacement = _replacement.value,
                        files = summary.files.map { it.path },
                        isRegex = _isRegex.value,
                        caseSensitive = _caseSensitive.value,
                        wholeWord = _wholeWord.value,
                        dryRun = false,
                    )
                _message.value = describeReplacement(applied)
                _dryRun.value = null
            } catch (e: Exception) {
                _message.value = e.message ?: "Replacement failed"
            } finally {
                _busy.value = false
            }
            // The files on disk changed; re-scan so the tree matches them.
            execute()
        }
    }

    fun clear() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
        _grouped.value = emptyList()
        _searched.value = false
        _message.value = null
        _dryRun.value = null
        _capped.value = false
    }

    fun dismissPreview() {
        _dryRun.value = null
    }

    private fun matchedFiles(): List<String> = _results.value.map { it.path }.distinct()

    /**
     * What actually happened, per file when it went wrong.
     *
     * [ReplaceSummary.files] carries a reason for every file that was skipped,
     * and reporting only the totals hid it: a replace into an open buffer on a
     * host whose editor plugin predates `applyEdit` comes back as
     * `reason = "unsupported"`, which read as "Replaced 0 in 0 file(s)" - or,
     * worse, as an unexplained version complaint.
     */
    internal fun describeReplacement(applied: ReplaceSummary?): String {
        if (applied == null) return "Replacement failed: the host has no search provider."
        val failures = applied.files.filter { !it.error.isNullOrBlank() }
        val done =
            "Replaced ${applied.totalReplacements} in ${applied.filesReplaced} file(s)"
        if (failures.isEmpty()) return done
        val first = failures.first()
        val name = PathUtils.name(first.path).ifEmpty { first.path }
        val tail = if (failures.size > 1) " (+${failures.size - 1} more)" else ""
        val prefix = if (applied.totalReplacements > 0) "$done. " else ""
        return "${prefix}Failed: $name - ${first.error}$tail"
    }

    fun dispose() {
        searchJob?.cancel()
        refreshTimer?.cancel()
        scope.cancel()
    }

    companion object {
        const val MAX_RESULTS = 500
        const val DEBOUNCE_MS = 250L

        /**
         * How soon an edit made outside the panel shows up in the results.
         *
         * A full project content scan - a directory walk plus a stat per file
         * - so the interval is a real cost on a monorepo, and the case it
         * exists for (someone edits a matched file in the editor) is not one
         * anybody times with a stopwatch. The replace path does not wait for
         * it: applyReplacement re-scans explicitly.
         */
        const val REFRESH_MS = 15_000L

        /** `C:\...` / `C:/...` - an absolute path needs no project root. */
        val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/].*""")
    }
}

enum class SearchToggle { REGEX, CASE, WORD }

/** One file's matches; [path] is project-relative, as the provider returns it. */
data class FileMatches(val path: String, val matches: List<FileMatch>)

/** Grouped and path-sorted, so the tree does not reshuffle between scans. */
internal fun groupMatchesByFile(results: List<FileMatch>): List<FileMatches> =
    results
        .groupBy { it.path }
        .map { (path, matches) -> FileMatches(path, matches.sortedWith(compareBy({ it.line }, { it.column }))) }
        .sortedBy { it.path }

/**
 * SEARCH tab body - Cursor's search view on BOSS's palette.
 *
 * A single 26dp search field carries its own icon and the three match
 * toggles; replace and the include-glob are collapsed behind a twisty and a
 * `…` the way VS Code hides them, so the default state is one input and a
 * full-height result tree. Three stacked `OutlinedTextField`s previously ate
 * 168dp of a sidebar before the first result could appear.
 */
@Composable
fun CodebaseSearchContent(
    viewModel: CodebaseSearchViewModel,
    modifier: Modifier = Modifier,
) {
    if (viewModel.providerOrNull == null) {
        CodebaseEmptyState("Search is unavailable on this host build.", modifier.fillMaxSize())
        return
    }

    val query by viewModel.query.collectAsState()
    val glob by viewModel.pathPattern.collectAsState()
    val exclude by viewModel.excludePattern.collectAsState()
    val replacement by viewModel.replacement.collectAsState()
    val isRegex by viewModel.isRegex.collectAsState()
    val caseSensitive by viewModel.caseSensitive.collectAsState()
    val wholeWord by viewModel.wholeWord.collectAsState()
    val results by viewModel.results.collectAsState()
    val grouped by viewModel.grouped.collectAsState()
    val searched by viewModel.searched.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val dryRun by viewModel.dryRun.collectAsState()
    val capped by viewModel.capped.collectAsState()

    var replaceOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(glob.isNotEmpty() || exclude.isNotEmpty()) }
    // Collapse state by path, hoisted OUT of the lazy items. It used to be a
    // `remember` inside each group's item, which a LazyColumn disposes when
    // the row scrolls off screen - so a group you folded came back expanded
    // the moment you scrolled past it and back. A generation counter forced
    // every group to rebuild on "collapse all"; with the state out here the
    // set is simply rewritten.
    var collapsedFiles by remember { mutableStateOf(emptySet<String>()) }
    val allCollapsed = grouped.isNotEmpty() && grouped.all { it.path in collapsedFiles }

    // Poll only while the tab is on screen.
    DisposableEffect(viewModel) {
        viewModel.startAutoRefresh()
        onDispose { viewModel.stopAutoRefresh() }
    }

    val searchKeys = Modifier.onPreviewKeyEvent { e ->
        when {
            e.type != KeyEventType.KeyDown -> false
            e.key == Key.Enter -> {
                viewModel.runSearch()
                true
            }
            e.key == Key.Escape -> {
                viewModel.clear()
                true
            }
            else -> false
        }
    }

    // Box, not a bare Column: the confirmation sheet is a sibling of the body
    // and has to sit *on top* of it. Emitted after a Column it became a third
    // child of the parent layout instead - measured at zero height, so
    // Replace All ran its dry run and then appeared to do nothing at all.
    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = CodebaseMetrics.Gutter, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The twisty that reveals the replace field, as in VS Code.
            Box(modifier = Modifier.size(CodebaseMetrics.InputHeight)) {
                CodebaseIconButton(
                    icon = if (replaceOpen) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    tooltip = if (replaceOpen) "Hide Replace" else "Toggle Replace",
                    onClick = { replaceOpen = !replaceOpen },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                CodebaseTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search",
                    modifier = Modifier.fillMaxWidth(),
                    keyHandler = searchKeys,
                    leading = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(CodebaseMetrics.Glyph),
                            tint = CodebasePalette.Muted,
                        )
                    },
                    trailing = {
                        CodebaseToggleChip("Aa", "Match case", caseSensitive) {
                            viewModel.setSearchOption(SearchToggle.CASE, !caseSensitive)
                        }
                        CodebaseToggleChip("ab", "Match whole word", wholeWord) {
                            viewModel.setSearchOption(SearchToggle.WORD, !wholeWord)
                        }
                        CodebaseToggleChip(".*", "Use regular expression", isRegex) {
                            viewModel.setSearchOption(SearchToggle.REGEX, !isRegex)
                        }
                    },
                )
                if (replaceOpen) {
                    Spacer(Modifier.height(4.dp))
                    CodebaseTextField(
                        value = replacement,
                        onValueChange = viewModel::setReplacement,
                        placeholder = if (isRegex) "Replace (\$1 for groups)" else "Replace",
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            Icon(
                                Icons.Outlined.FindReplace,
                                contentDescription = null,
                                modifier = Modifier.size(CodebaseMetrics.Glyph),
                                tint = CodebasePalette.Muted,
                            )
                        },
                        trailing = {
                            CodebaseIconButton(
                                icon = Icons.Outlined.FindReplace,
                                tooltip = "Replace all",
                                onClick = { viewModel.previewReplacement() },
                                enabled = results.isNotEmpty() && !busy,
                            )
                        },
                    )
                }
                if (filtersOpen) {
                    Spacer(Modifier.height(4.dp))
                    CodebaseTextField(
                        value = glob,
                        onValueChange = viewModel::setPathPattern,
                        placeholder = "Files to include, e.g. *.kt, src",
                        modifier = Modifier.fillMaxWidth(),
                        monospace = true,
                        leading = {
                            Icon(
                                Icons.Rounded.FilterAlt,
                                contentDescription = null,
                                modifier = Modifier.size(CodebaseMetrics.Glyph),
                                tint = CodebasePalette.Muted,
                            )
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    CodebaseTextField(
                        value = exclude,
                        onValueChange = viewModel::setExcludePattern,
                        placeholder = "Files to exclude, e.g. build, *.min.js",
                        modifier = Modifier.fillMaxWidth(),
                        monospace = true,
                        leading = {
                            Icon(
                                Icons.Rounded.FilterAltOff,
                                contentDescription = null,
                                modifier = Modifier.size(CodebaseMetrics.Glyph),
                                tint = CodebasePalette.Muted,
                            )
                        },
                    )
                }
            }
            Box(modifier = Modifier.size(CodebaseMetrics.InputHeight)) {
                val filtersActive = glob.isNotEmpty() || exclude.isNotEmpty()
                CodebaseIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    tooltip = "Toggle files to include / exclude",
                    onClick = { filtersOpen = !filtersOpen },
                    // Tinted while a filter is narrowing the results, so a
                    // collapsed row never silently hides why a hit is missing.
                    tint = when {
                        filtersActive -> CodebasePalette.Accent
                        filtersOpen -> CodebasePalette.Foreground
                        else -> null
                    },
                )
            }
        }

        SearchSummaryRow(
            matchCount = results.size,
            fileCount = grouped.size,
            searched = searched,
            busy = busy,
            capped = capped,
            message = message,
            allCollapsed = allCollapsed,
            onCollapseAll = {
                collapsedFiles = if (allCollapsed) emptySet() else grouped.mapTo(mutableSetOf()) { it.path }
            },
            onClear = {
                viewModel.clear()
                collapsedFiles = emptySet()
            },
        )
        CodebaseHRule()

        when {
            !searched && !busy ->
                CodebaseEmptyState(
                    "Search across the project.",
                    Modifier.weight(1f),
                    icon = Icons.Rounded.Search,
                )

            searched && results.isEmpty() && !busy ->
                CodebaseEmptyState(
                    "No results found.",
                    Modifier.weight(1f),
                )

            else ->
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    grouped.forEach { entry ->
                        searchFileGroup(
                            entry = entry,
                            expanded = entry.path !in collapsedFiles,
                            onToggle = {
                                collapsedFiles =
                                    if (entry.path in collapsedFiles) collapsedFiles - entry.path
                                    else collapsedFiles + entry.path
                            },
                            onOpenFile = { viewModel.openFile(entry.path) },
                            onOpenMatch = viewModel::openMatch,
                        )
                    }
                }
        }
    }

    dryRun?.let { summary ->
        ConfirmReplaceSheet(
            summary = summary,
            busy = busy,
            capped = capped,
            onDismiss = { viewModel.dismissPreview() },
            onApply = { viewModel.applyReplacement() },
        )
    }
    }
}

/** "12 results in 4 files", collapse-all, clear - VS Code's message row. */
@Composable
private fun SearchSummaryRow(
    matchCount: Int,
    fileCount: Int,
    searched: Boolean,
    busy: Boolean,
    capped: Boolean,
    message: String?,
    allCollapsed: Boolean,
    onCollapseAll: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CodebaseMetrics.RowHeight)
            .padding(start = CodebaseMetrics.Gutter, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = CodebasePalette.Accent,
            )
            Spacer(Modifier.width(6.dp))
        }
        val text =
            when {
                message != null -> message
                !searched -> ""
                matchCount == 0 -> "No results"
                else -> buildString {
                    append(matchCount)
                    append(if (matchCount == 1) " result in " else " results in ")
                    append(fileCount)
                    append(if (fileCount == 1) " file" else " files")
                    if (capped) append(" (capped)")
                }
            }
        Text(
            text = text,
            fontSize = CodebaseMetrics.MetaText,
            color = if (message != null) CodebasePalette.Error else CodebasePalette.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (searched) {
            CodebaseIconButton(
                icon = Icons.Rounded.UnfoldLess,
                tooltip = if (allCollapsed) "Expand all" else "Collapse all",
                onClick = onCollapseAll,
            )
            CodebaseIconButton(Icons.Rounded.Close, "Clear search results", onClear)
        }
    }
}

/**
 * One file's group: the file row as its own lazy item, then one lazy item per
 * match.
 *
 * Not a single `item { }` holding the whole group. That is what it was, and it
 * defeated the point of a LazyColumn: with MAX_RESULTS landing in one or two
 * files, every one of up to 500 match rows composed and measured whether or
 * not it was on screen. Lazy skipping now works per row.
 */
private fun LazyListScope.searchFileGroup(
    entry: FileMatches,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenMatch: (FileMatch) -> Unit,
) {
    item(key = "file-${entry.path}") {
        SearchFileRow(
            entry = entry,
            expanded = expanded,
            onToggle = onToggle,
            onOpenFile = onOpenFile,
        )
    }
    if (!expanded) return
    // Keyed by index, not by line/column: the engine can report two matches on
    // the same line and column (a zero-width regex), and a duplicate key is a
    // hard crash in LazyColumn rather than a rendering glitch.
    itemsIndexed(entry.matches, key = { i, _ -> "match-${entry.path}-$i" }) { _, m ->
        SearchMatchRow(match = m, onOpen = { onOpenMatch(m) })
    }
}

@Composable
private fun SearchFileRow(
    entry: FileMatches,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val (name, dir) = remember(entry.path) { splitPathForDisplay(entry.path) }
    CodebaseListRow(onClick = onToggle) { hovered ->
        Icon(
            imageVector =
                if (expanded) Icons.Rounded.KeyboardArrowDown
                else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = CodebasePalette.Secondary,
        )
        // One weighted slot for name + directory, so the hover action and
        // the count badge keep their place however long the path is.
        CodebaseTooltip(entry.path, modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = CodebaseMetrics.PrimaryText,
                    color = CodebasePalette.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (dir.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = dir,
                        fontSize = CodebaseMetrics.MetaText,
                        color = CodebasePalette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        CodebaseIconButton(
            icon = Icons.Rounded.Search,
            tooltip = "Open file",
            onClick = onOpenFile,
            visible = hovered,
        )
        CodebaseCountBadge(entry.matches.size)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun SearchMatchRow(
    match: FileMatch,
    onOpen: () -> Unit,
) {
    CodebaseListRow(onClick = onOpen) { _ ->
        Spacer(Modifier.width(6.dp))
        Text(
            text = match.line.toString(),
            fontSize = CodebaseMetrics.MetaText,
            fontFamily = FontFamily.Monospace,
            color = CodebasePalette.Muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
        Spacer(Modifier.width(8.dp))
        HighlightedMatchLine(
            line = match.contextLine,
            matchStart = match.column - 1,
            matchLength = match.matchLength,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The context line with the hit marked by a background wash rather than a
 * colour swap - the way an editor's find-highlight reads, and the only option
 * that survives BOSS's light themes, where an accent-coloured glyph on the
 * panel ground drops below the text contrast floor.
 *
 * Long lines are windowed around the match: a 200-column line would otherwise
 * push the hit off the right edge of the panel.
 */
@Composable
private fun HighlightedMatchLine(
    line: String,
    matchStart: Int,
    matchLength: Int,
    modifier: Modifier = Modifier,
) {
    val trimmed = line.trimEnd('\n', '\r')
    val start = matchStart.coerceIn(0, trimmed.length)
    val end = (start + matchLength).coerceIn(start, trimmed.length)

    // Keep a little context to the left of the hit, drop the rest.
    val windowStart = (start - LEAD_CONTEXT).coerceAtLeast(0)
    val leadingTrimmed = trimmed.take(windowStart).isNotEmpty()
    val before = trimmed.substring(windowStart, start).let { if (leadingTrimmed) "…$it" else it }
    val match = trimmed.substring(start, end)
    val after = trimmed.substring(end)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (before.isNotEmpty()) {
            Text(
                text = before.trimStart(),
                fontSize = CodebaseMetrics.SecondaryText,
                fontFamily = FontFamily.Monospace,
                color = CodebasePalette.Secondary,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (match.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(CodebasePalette.Accent.copy(alpha = 0.32f)),
            ) {
                Text(
                    text = match,
                    fontSize = CodebaseMetrics.SecondaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = CodebasePalette.Foreground,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (after.isNotEmpty()) {
            Text(
                text = after,
                fontSize = CodebaseMetrics.SecondaryText,
                fontFamily = FontFamily.Monospace,
                color = CodebasePalette.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private const val LEAD_CONTEXT = 12

/**
 * Replace-all confirmation. In-panel rather than a Material `AlertDialog`:
 * the host's browser surface draws above plugin dialogs.
 */
@Composable
private fun ConfirmReplaceSheet(
    summary: ReplaceSummary,
    busy: Boolean,
    /** True when the search result was truncated at MAX_RESULTS. While capped,
     *  "replace all" would silently cover only the listed files, so the apply
     *  button is disabled and the truncation is stated plainly.
     */
    capped: Boolean,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    // A modal sheet must EAT input: without a consuming pointer modifier the
    // scrim never consumes the click, so the result rows underneath stay live
    // while the confirmation is on screen - and flipping a search toggle back
    // there calls setSearchOption, which nulls _dryRun and yanks this sheet
    // away mid-decision. It also has to hold focus, or the Escape handler on
    // an unfocused node never fires.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CodebaseScrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Every event, not only Press: consuming the down change
                    // is what stops a click reaching the rows underneath, and
                    // consuming scroll stops the list behind the sheet moving
                    // while it is up. Consumed on the MAIN pass, which runs
                    // child-first, so this sheet's own buttons still see it.
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(CodebaseMetrics.ButtonRadius))
                .background(CodebasePalette.Raised)
                .padding(14.dp),
        ) {
            Text(
                text = "Replace all",
                fontSize = CodebaseMetrics.PrimaryText,
                fontWeight = FontWeight.SemiBold,
                color = CodebasePalette.Foreground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Replace ${summary.totalReplacements} occurrence(s) across " +
                    "${summary.filesReplaced} file(s)? Open buffers stay undoable; " +
                    "closed files are written to disk.",
                fontSize = CodebaseMetrics.SecondaryText,
                color = CodebasePalette.Secondary,
            )
            if (capped) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The search was capped at ${CodebaseSearchViewModel.MAX_RESULTS} matches, " +
                        "so this would replace only the listed files - not every match. " +
                        "Narrow the search (glob / exclude) to widen the set.",
                    fontSize = CodebaseMetrics.SecondaryText,
                    color = CodebasePalette.Warn,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CodebasePrimaryButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.width(80.dp),
                )
                Spacer(Modifier.width(8.dp))
                CodebasePrimaryButton(
                    label = "Replace",
                    onClick = onApply,
                    enabled = !busy && !capped && summary.totalReplacements > 0,
                    modifier = Modifier.width(90.dp),
                )
            }
        }
    }
}
