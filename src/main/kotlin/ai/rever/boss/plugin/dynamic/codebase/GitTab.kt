package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitBranchRefData
import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The GIT tab of the codebase panel (P7): the changes accordion plus the
 * true lane graph, both driven by the host's [GitDataProvider].
 *
 * [git] null means the host predates the provider - the UI renders a hint.
 * [onAgentReview] composes and publishes the review event; the plugin wires
 * it to the application event bus + panel focus, so the view model stays
 * free of plugin-context plumbing. The review always asks the agent to RUN
 * the brief - pressing the button is the request, so leaving the prompt
 * sitting unsent in the agent's composer is never the answer.
 *
 * Status is *collected*, never snapshotted. The provider refreshes
 * asynchronously (and over IPC when this plugin runs out-of-process), so
 * reading `fileStatus.value` right after an operation returns the state from
 * before it - which is why staging a file used to leave the row where it was
 * until the next poll tick.
 */
class CodebaseGitViewModel(
    private val git: GitDataProvider?,
    private val onAgentReview: (prompt: String) -> Unit,
    private val getProjectPath: () -> String?,
    private val getWindowId: () -> String? = { null },
    /** Resolves the AI gateway lazily; null on a host without one. */
    private val aiGateway: () -> AiGatewayAPI? = { null },
    /** Why AI is unavailable, or null when it is ready. */
    private val aiUnavailable: () -> String? = { null },
    /**
     * How long [awaitRepositoryFlag] waits for the host's repository flag to
     * move after a refresh. Injectable so tests do not pay the real settle
     * time on every construction.
     */
    private val repoFlagSettleMs: Long = REPO_FLAG_SETTLE_MS,
) {
    // Blocking git/IPC and storage I/O, not CPU-bound work: Default is
    // sized to cores and these calls would block it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val providerOrNull: GitDataProvider? get() = git

    // ---- changes accordion ----

    private val _fileStatus = MutableStateFlow<List<GitFileStatusData>>(emptyList())
    val fileStatus: StateFlow<List<GitFileStatusData>> = _fileStatus.asStateFlow()

    // ---- graph ----

    private val _graph = MutableStateFlow<List<GitCommitNodeData>>(emptyList())
    val graph: StateFlow<List<GitCommitNodeData>> = _graph.asStateFlow()

    private val _graphBusy = MutableStateFlow(false)
    val graphBusy: StateFlow<Boolean> = _graphBusy.asStateFlow()

    /** Latched once a load returns fewer commits than it requested. See [hasMoreGraph]. */
    private val _graphExhausted = MutableStateFlow(false)

    /**
     * Whether "Load more" has anything left to load.
     *
     * A FLOW, not a function. As a function it read [_graphExhausted] and the
     * graph size as plain values, and only re-evaluated because a changing
     * graph happened to drive recomposition - so the one case it needed to
     * cover was the one it missed: a "Load more" that returns the same list is
     * conflated away by StateFlow equality, the latch flips, and nothing
     * recomposes, leaving the action on screen.
     *
     * Not `size < GRAPH_MAX` either: a 12-commit repository is under the
     * ceiling forever, so the action showed on every repository smaller than
     * 200 commits and each click refetched the same rows. The latch is set
     * when a load comes back with fewer commits than it asked for, which is
     * the only signal the provider gives that history ran out.
     */
    val hasMoreGraph: StateFlow<Boolean> =
        combine(_graph, _graphExhausted) { graph, exhausted ->
            !exhausted && graph.size < GRAPH_MAX
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The ref the graph is showing, or null for the checked-out branch.
     *
     * Null rather than the branch's own name on purpose: HEAD moves. Pinning
     * the name at load time would leave the graph quietly showing the branch
     * the user *was* on after an external `git switch`, while the toolbar
     * above it named the new one.
     */
    private val _graphRef = MutableStateFlow<String?>(null)
    val graphRef: StateFlow<String?> = _graphRef.asStateFlow()

    /** Every branch of the repository, for the graph's picker. */
    private val _branchRefs = MutableStateFlow<List<GitBranchRefData>>(emptyList())

    private val _branchOptions = MutableStateFlow<List<GitBranchOption>>(emptyList())
    val branchOptions: StateFlow<List<GitBranchOption>> = _branchOptions.asStateFlow()

    /**
     * Remote names, so a commit's `origin/x` decoration is drawn as a remote
     * pill and a local `feature/x` is not. `%D` does not distinguish them -
     * they are the same shape - so the branch list is the only authority.
     */
    private val _remoteNames = MutableStateFlow(GitBranchModel.DEFAULT_REMOTES)
    val remoteNames: StateFlow<Set<String>> = _remoteNames.asStateFlow()

    // ---- shared ----

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * True only while Agent Review is collecting.
     *
     * Its own flag for the same reason [_generating] is: [_busy] is set by
     * every stage, unstage, discard, commit, fetch, pull and push, so driving
     * the review pill from it made staging a file read "Reviewing…", and made
     * the review button dead for the duration of any unrelated git operation.
     */
    private val _reviewing = MutableStateFlow(false)
    val reviewing: StateFlow<Boolean> = _reviewing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * List or tree for the change groups.
     *
     * Held here rather than in a `remember` inside the tab: the composable is
     * torn down on every tab switch, so the choice did not survive a hop to
     * FILES and back, let alone a restart. [GitChangeLayout.fromStorage] gives
     * the default; the panel component seeds and persists it.
     */
    private val _changeLayout = MutableStateFlow(GitChangeLayout.fromStorage(null))
    val changeLayout: StateFlow<GitChangeLayout> = _changeLayout.asStateFlow()

    fun setChangeLayout(value: GitChangeLayout) {
        _changeLayout.value = value
    }

    fun toggleChangeLayout() {
        _changeLayout.value = _changeLayout.value.toggled()
    }

    /**
     * Splitter position: the top pane's share of the graph/changes split.
     *
     * Same reason as [changeLayout]: a `remember` inside the tab resets on
     * every hop to FILES and back, and this is the user's layout, not an
     * accident of composition. The splitter clamps to 0.15..0.85; the setter
     * re-clamps so a future caller cannot pin a pane at zero.
     */
    private val _splitFraction = MutableStateFlow(0.55f)
    val splitFraction: StateFlow<Float> = _splitFraction.asStateFlow()

    fun setSplitFraction(value: Float) {
        _splitFraction.value = value.coerceIn(0.15f, 0.85f)
    }

    // ---- agent review options ----

    private val _reviewInstructions = MutableStateFlow("")
    val reviewInstructions: StateFlow<String> = _reviewInstructions.asStateFlow()

    private val _reviewDeep = MutableStateFlow(false)
    val reviewDeep: StateFlow<Boolean> = _reviewDeep.asStateFlow()

    private val _reviewBase = MutableStateFlow("")
    val reviewBase: StateFlow<String> = _reviewBase.asStateFlow()

    /**
     * Branches offered in "Diff against…".
     *
     * The repository's real branch list when the host has
     * [GitDataProvider.branches], and the loaded graph's `%D` decorations
     * otherwise. The decorations only name branches whose tip happens to sit
     * inside the fetched window of history, so on their own they are strictly
     * smaller than the set worth reviewing against - a fallback, not the
     * source.
     */
    private val _branches = MutableStateFlow<List<String>>(emptyList())
    val branches: StateFlow<List<String>> = _branches.asStateFlow()

    fun setReviewInstructions(value: String) {
        _reviewInstructions.value = value
    }

    fun setReviewDeep(value: Boolean) {
        _reviewDeep.value = value
    }

    fun setReviewBase(value: String) {
        _reviewBase.value = value
    }

    // ---- commit box ----

    private val _commitMessage = MutableStateFlow("")
    val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

    /** True while a commit message is being generated. */
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** Whether the host reports the current project as a git repository. */
    val isGitRepository: StateFlow<Boolean> = git?.isGitRepository ?: MutableStateFlow(false)

    /**
     * The checked-out branch, read off the tip commit's ref decorations
     * (`HEAD -> name`). Empty until the graph loads; the API exposes no
     * branch member of its own.
     */
    private val _currentBranch = MutableStateFlow("")
    val currentBranch: StateFlow<String> = _currentBranch.asStateFlow()

    /**
     * Why there is no repository, when there is none. Selecting a folder that
     * merely *holds* repositories (a `~/Projects` with a dozen checkouts under
     * it) is the common case, and "No Git repository" alone reads as a bug
     * rather than as the accurate answer it is.
     */
    private val _noRepoHint = MutableStateFlow("")
    val noRepoHint: StateFlow<String> = _noRepoHint.asStateFlow()

    /**
     * False until the first status read has come back.
     *
     * Without it the tab paints its "no repository" / "no changes" states
     * while the very first read is still in flight, which reads as "the panel
     * is broken, hit refresh" - and refreshing only appeared to fix it
     * because by then the load had finished.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Serialises graph loads. See [loadGraph].
     *
     * Declared ABOVE `init`, which calls loadGraph() during construction:
     * property initializers run in declaration order, so leaving these below it
     * left them null at that first call.
     */
    private val graphMutex = Mutex()

    /**
     * Which graph load is newest.
     *
     * Atomic because loadGraph is called from the UI thread (Refresh, Load
     * more, the branch picker) AND from inside coroutines on [scope] (after a
     * commit, a pull, a project switch). A plain `var` holding the in-flight
     * Job would be read and written from both with no happens-before.
     */
    private val graphGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        // Mirror the provider's status flow for the lifetime of the tab. This
        // is the single source of truth for the three groups; operations only
        // ask the provider to refresh and let the mirror deliver the result.
        if (git != null) {
            scope.launch {
                git.fileStatus.collect { _fileStatus.value = it }
            }
            // Start loading with the panel, not with the tab: by the time the
            // user switches to GIT the data is already there.
            refreshStatus()
            loadGraph(reset = true)
        } else {
            _loaded.value = true
        }
    }

    fun setCommitMessage(value: String) {
        _commitMessage.value = value
    }

    /** Volatile: started/stopped from the UI thread, cancelled from dispose(). */
    @Volatile
    private var statusTimer: Job? = null

    /** Ask the provider to re-read the work tree; the collector delivers it. */
    fun refreshStatus() {
        scope.launch {
            try {
                val isRepo = awaitRepositoryFlag()
                // Only when there is no repository. describeMissingRepo lists the
                // project root and stats up to CHILD_SCAN_LIMIT children looking
                // for nested .git dirs - blocking work for a hint the normal case
                // never renders.
                _noRepoHint.value = if (isRepo) "" else describeMissingRepo(getProjectPath())
            } catch (e: Exception) {
                // The one provider call here used to have no catch, so an IPC
                // drop escaped the launch to the thread's uncaught handler and
                // left the hint stale. Everything else in this class reports.
                _message.value = "Failed to read git status: ${e.message ?: e::class.simpleName}"
            } finally {
                _loaded.value = true
            }
        }
    }

    /**
     * Refresh, then read [GitDataProvider.isGitRepository] once it has actually
     * moved - the same wait the MCP tools' `awaitFresh` does, for the same
     * reason: the provider refreshes asynchronously, and over IPC when this
     * plugin runs out-of-process, so the value sampled immediately after
     * `refreshStatus()` is the one from BEFORE it.
     *
     * Getting this wrong is visible twice over: the panel flashes "X is not a
     * Git repository" on a perfectly good repo (because [_loaded] flips in the
     * same breath), and it pays for a directory listing plus up to
     * [CHILD_SCAN_LIMIT] stats that a repository never needs.
     *
     * A no-op refresh emits nothing and the wait simply times out onto the
     * current value, which is already correct in that case.
     */
    private suspend fun awaitRepositoryFlag(): Boolean {
        val provider = git ?: return false
        val before = provider.isGitRepository.value
        provider.refreshStatus()
        return withTimeoutOrNull(repoFlagSettleMs) {
            provider.isGitRepository.first { it != before }
        } ?: provider.isGitRepository.value
    }

    /**
     * Poll the work tree while the tab is open. The provider does not watch
     * the filesystem for us, so a bare re-read of its cached value would
     * never see an edit made outside the panel - the tick has to drive
     * [GitDataProvider.refreshStatus].
     */
    fun startStatusTimer() {
        if (git == null) return
        statusTimer?.cancel()
        statusTimer =
            scope.launch {
                while (isActive) {
                    delay(REFRESH_MS)
                    // A throwing provider (IPC drop, repo mid-rebase) must not
                    // end the polling loop: catch and let the next tick retry.
                    // The UI symptom - status stops moving - stays visible, and
                    // any explicit operation surfaces the error via op().
                    try {
                        git.refreshStatus()
                    } catch (_: Exception) {
                        // Deliberately silent here: surfacing on every 5 s tick
                        // would overwrite real operation results.
                    }
                }
            }
    }

    /**
     * Stop polling. The view model outlives the GIT tab (it is remembered for
     * the whole panel so its scope is disposed once), so without this the
     * timer kept shelling out `git status` every few seconds while the user
     * sat on FILES or SEARCH.
     */
    fun stopStatusTimer() {
        statusTimer?.cancel()
        statusTimer = null
    }

    fun onProjectChanged() {
        if (git == null) return
        // Everything loaded in init describes the repository that was active
        // THEN. Reset the stale parts and re-read the new project now, rather
        // than leaving the graph, branch chip and change groups of the old
        // project on screen until someone hits refresh.
        _currentBranch.value = ""
        _branchOptions.value = emptyList()
        _graph.value = emptyList()
        _graphRef.value = null
        _reviewBase.value = ""
        // A commit message and review instructions are written ABOUT a
        // project; carrying them into the next one puts someone else's draft
        // in your commit box.
        _commitMessage.value = ""
        _reviewInstructions.value = ""
        _graphExhausted.value = false
        refreshStatus()
        loadGraph(reset = true)
    }

    /**
     * Load (or page) the commit graph.
     *
     * Superseding, not de-duplicating. Setting [_graphBusy] inside the
     * coroutine let two "Load more" clicks in the same frame both pass the
     * `enabled` guard, compute the same limit from the same `_graph.value.size`
     * and issue two identical fetches - whichever returned first cleared the
     * spinner while the other was still running, and the two then interleaved
     * their writes to `_graph` and `_graphExhausted`.
     *
     * A compare-and-set would fix that by DROPPING the second call, which is
     * right for a repeated "Load more" and wrong for everything else:
     * `selectGraphBranch` and Refresh are new intent, and dropping them leaves
     * the graph on the previous ref.
     *
     * So loads are serialised on [graphMutex] instead - only one fetch touches
     * the graph state at a time - and a request that a newer one has already
     * superseded while it queued is skipped rather than re-fetching what the
     * newer one is about to fetch anyway.
     */
    fun loadGraph(reset: Boolean = false) {
        // Set here, not in the body: the spinner must be up from the click,
        // not from whenever the dispatcher gets to it.
        _graphBusy.value = true
        val generation = graphGeneration.incrementAndGet()
        scope.launch {
            graphMutex.withLock {
                // Superseded while queued: the newer request will do this work
                // with a fresher view of the state, and it owns the spinner.
                if (generation != graphGeneration.get()) return@withLock
                try {
                    val limit =
                        if (reset) GRAPH_PAGE
                        else (_graph.value.size + GRAPH_PAGE).coerceAtMost(GRAPH_MAX)
                    val ref = _graphRef.value
                    // Always logGraphFor, never logGraph: its default body
                    // delegates to logGraph for a null ref, so on a host that
                    // predates 1.0.90 the checked-out history still draws and only
                    // the picker degrades.
                    var failed = false
                    val nodes =
                        runCatching { git?.logGraphFor(ref, limit) }.getOrElse { e ->
                            // A throwing provider (IPC drop, repo mid-rebase) leaves a
                            // message instead of an unhandled exception and a spinner
                            // that just stops.
                            _message.value = "Failed to load git history: ${e.message}"
                            failed = true
                            null
                        } ?: emptyList()
                    // The HEAD decoration only names the checked-out branch while
                    // the graph IS HEAD's; another branch's tip carries no
                    // `HEAD ->`, and reading it there blanked the toolbar.
                    // Publish it BEFORE the graph: the name is part of reading
                    // this graph, and a consumer that observes a non-empty graph
                    // (the test does, on its own thread) must never see it
                    // without the branch name - the reverse order raced on CI.
                    if (ref == null) {
                        branchOf(nodes).takeIf { it.isNotEmpty() }?.let { _currentBranch.value = it }
                    }
                    // Fewer rows than requested means the provider reached the root
                    // commit; anything else would page forever over the same list.
                    // Never latched off a FAILED load: that path yields an empty
                    // list too, and hiding "Load more" until the next reset is the
                    // wrong answer to a dropped IPC call.
                    if (!failed) _graphExhausted.value = nodes.size < limit
                    _graph.value = nodes
                    refreshReviewBases(nodes)
                    // Only on a reset: paging deeper into the same history cannot
                    // change which branches exist, and this is two extra `git
                    // branch` invocations behind a lock every "Load more" would
                    // otherwise pay for.
                    if (reset) refreshBranchOptions()
                } finally {
                    // Only the newest load owns the spinner. A superseded one
                    // clearing it would hide a load that is still running; the
                    // newest is never superseded, so it always clears.
                    if (generation == graphGeneration.get()) _graphBusy.value = false
                }
            }
        }
    }

    /**
     * Re-read the branch list and rebuild the picker.
     *
     * Not derived from the graph's ref decorations: those only name branches
     * whose tip happens to be inside the fetched window of history, so a
     * picker built from them cannot offer the branch you actually want to look
     * at. The decorations stay as the fallback for a host without
     * [GitDataProvider.branches].
     */
    private suspend fun refreshBranchOptions() {
        val refs = try { git?.branches().orEmpty() } catch (_: Exception) { emptyList() }
        _branchRefs.value = refs
        _remoteNames.value = GitBranchModel.remoteNamesOf(refs)
        refs.firstOrNull { it.isCurrent }?.let { _currentBranch.value = it.name }
        _branchOptions.value =
            GitBranchModel.options(
                branches = refs,
                // The graph's own decorations, not _branches: that list folds
                // `origin/x` onto `x` for the "Diff Against..." picker, and
                // here the remote is a separate thing to look at.
                graphRefs = GitBranchModel.branchRefsInGraph(_graph.value, _remoteNames.value),
                current = _currentBranch.value,
            )
        // The authoritative list just arrived; rebuild the review picker off
        // it rather than leaving the graph-derived fallback in place.
        refreshReviewBases(_graph.value)
    }

    /**
     * Rebuild the "Diff against…" options, preferring the repository's real
     * branch list over the graph's ref decorations, and seed the base with
     * main/master the first time it lands.
     */
    private fun refreshReviewBases(nodes: List<GitCommitNodeData>) {
        val found =
            GitBranchModel.reviewBases(_branchRefs.value, _remoteNames.value)
                .ifEmpty { branchesOf(nodes, _remoteNames.value) }
        _branches.value = found
        if (_reviewBase.value.isBlank()) {
            _reviewBase.value = defaultBase(found, _currentBranch.value)
        }
    }

    /**
     * Point the graph at [name], or at the checked-out branch when it is null.
     *
     * The name comes from a list git produced, but it crosses the plugin API
     * on the way back, so it is checked here as well as host-side - a refused
     * ref says so instead of drawing an empty graph.
     */
    fun selectGraphBranch(name: String?) {
        val target = name?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null && !GitBranchModel.isSafeRef(target)) {
            _message.value = "Failed: \"$target\" is not a usable branch name."
            return
        }
        if (target == _graphRef.value) return
        _graphRef.value = target
        loadGraph(reset = true)
    }

    /** Return the graph to the checked-out branch. */
    fun showCurrentBranch() = selectGraphBranch(null)

    /** The branch the graph is showing, for the section header's chip. */
    fun displayedRef(): String = _graphRef.value ?: _currentBranch.value

    // ---- remote operations ----

    /** `git fetch --all` - updates remote-tracking refs, touches no work tree. */
    fun fetch() = op(refreshGraph = true) { git?.fetch(prune = false) }

    /** `git pull` into the checked-out branch. */
    fun pull() = op(refreshGraph = true) { git?.pull() }

    /**
     * `git push -u origin HEAD`. Fired only from a confirmed action in the UI
     * - a push is the one operation here whose effect leaves this machine.
     */
    fun push() = op(refreshGraph = true) { git?.push() }

    // ---- row / commit operations ----

    fun stage(path: String) = op { git?.stage(path) }

    fun unstage(path: String) = op { git?.unstage(path) }

    fun discard(path: String) = op { git?.discardChanges(path) }

    fun stageAll() = op { git?.stageAll() }

    /**
     * Stage or discard a set of paths as ONE operation.
     *
     * Issuing the per-file call in a loop from the UI started a coroutine per
     * file, and git refuses a second concurrent index write - so "stage all"
     * staged the first file and dropped the rest. Sequential, inside a single
     * op, also means one busy state and one refresh rather than N.
     */
    fun stagePaths(paths: List<String>) = batch(paths) { git?.stage(it) }

    fun discardPaths(paths: List<String>) = batch(paths) { git?.discardChanges(it) }

    fun unstagePaths(paths: List<String>) = batch(paths) { git?.unstage(it) }

    private fun batch(
        paths: List<String>,
        action: suspend (String) -> GitOperationResultData?,
    ) {
        if (paths.isEmpty()) return
        op {
            var failure: GitOperationResultData.Error? = null
            for (path in paths) {
                val result = action(path)
                if (result is GitOperationResultData.Error && failure == null) failure = result
            }
            // Report the FIRST failure, if any: a later success would
            // otherwise overwrite it and the operation would look clean.
            failure ?: GitOperationResultData.Success("${paths.size} file(s) updated")
        }
    }

    fun unstageAll() = op { git?.unstageAll() }

    fun revert(hash: String) = op(refreshGraph = true) { git?.revert(hash) }

    fun cherryPick(hash: String) = op(refreshGraph = true) { git?.cherryPick(hash) }

    fun checkout(ref: String) = op(refreshGraph = true) { git?.checkout(ref) }

    /**
     * Commit. With nothing staged but tracked changes present, [stageFirst]
     * carries exactly the paths to stage - matching VS Code's "Commit" on an
     * empty index; the button label says so, so the extra `git add` is never
     * a surprise.
     *
     * A path LIST, not a `stageAll` flag. The provider's stageAll is
     * `git add -A`, which also stages untracked files: under a button labelled
     * "Commit All", sitting above a separate UNTRACKED group the user has not
     * touched, that quietly commits files they never staged. The CHANGES
     * header button already refuses stageAll for the same reason, and here the
     * stakes are higher because this one lands a commit.
     */
    fun commit(stageFirst: List<String> = emptyList()) {
        val message = _commitMessage.value.trim()
        if (message.isEmpty()) {
            _message.value = "Enter a commit message first."
            return
        }
        if (git == null) {
            _message.value = "Git is unavailable on this host."
            return
        }
        if (!_busy.compareAndSet(expect = false, update = true)) return
        _message.value = null
        scope.launch {
            try {
                for (path in stageFirst) {
                    val staged = git.stage(path)
                    if (staged is GitOperationResultData.Error) {
                        // Stop before committing: a partial index plus a commit
                        // is worse than no commit at all.
                        _message.value = "Failed to stage $path: ${staged.message}"
                        return@launch
                    }
                }
                report(git.commit(message)) {
                    _commitMessage.value = ""
                    loadGraph(reset = true)
                }
                git.refreshStatus()
            } catch (e: Exception) {
                // The one operation here that used to have no catch - and the
                // most consequential to lose, because after staging, a failure
                // between the stage and the commit leaves the index changed
                // with nothing on screen saying so.
                _message.value = "Failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun openFile(path: String) {
        git?.openFile(path, getWindowId().orEmpty())
    }

    fun openFileDiff(path: String, staged: Boolean) {
        git?.openDiff(path, staged = staged, windowId = getWindowId().orEmpty())
    }

    fun openCommitDiff(hash: String) {
        // fromRef only, so the host runs `git show <hash>` rather than
        // `git diff <hash>~1 <hash>`. A root commit has no `~1`: that form exited
        // non-zero and rendered "No changes to show" for the commit that created
        // every file in the repository. `git show` also renders merge commits.
        git?.openDiff(
            filePath = "",
            staged = false,
            fromRef = hash,
            toRef = null,
            windowId = getWindowId().orEmpty(),
        )
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Draft a commit message from what is actually staged (or, with an empty
     * index, from the working tree) and put it in the box for editing.
     *
     * Deliberately a draft and not a commit: the message is the one part of a
     * commit a reviewer reads most and an author should always get the last
     * word on.
     */
    fun generateCommitMessage() {
        if (_generating.value) return
        aiUnavailable()?.let {
            _message.value = it
            return
        }
        val gateway =
            aiGateway() ?: run {
                // aiUnavailable() said ready but the gateway is gone now (unloaded
                // in between): a silent return would read as a dead button.
                _message.value = "AI is unavailable."
                return
            }
        if (!_generating.compareAndSet(expect = false, update = true)) return
        _message.value = null
        scope.launch {
            try {
                val status = _fileStatus.value
                val subject = status.filter { it.isStaged }.ifEmpty { status.filter { it.isUnstaged } }
                if (subject.isEmpty()) {
                    _message.value = "Nothing to describe - there are no changes."
                    return@launch
                }
                val diff = collectDiff(subject, COMMIT_DIFF_BUDGET).text
                val reply =
                    gateway.complete(CommitMessagePrompt.request(subject, diff)).getOrNull()
                val text = CommitMessagePrompt.clean(reply?.text.orEmpty())
                if (text.isBlank()) {
                    _message.value = "The model returned no message."
                } else {
                    _commitMessage.value = text
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not generate a commit message."
            } finally {
                _generating.value = false
            }
        }
    }

    /**
     * A compact unified-ish diff for a PROMPT: changed lines plus [context] lines
     * around each change, with runs of unchanged text collapsed to an ellipsis.
     *
     * Built from the parsed hunk lines rather than [GitDiffData.rawUnified], which is
     * the whole file (the host diffs with -U100000 for the viewer's side-by-side
     * alignment). Pure, so [GitTabCompactDiffTest] can pin it.
     */
    internal fun compactDiff(
        diff: GitDiffData,
        context: Int = 3,
    ): String {
        val lines = diff.hunks.flatMap { it.lines }
        if (lines.none { it.kind != DiffLineKind.CONTEXT }) return ""
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (l.kind != DiffLineKind.CONTEXT) {
                for (j in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(lines.size - 1)) keep[j] = true
            }
        }
        val sb = StringBuilder()
        var printedAny = false
        var gap = false
        lines.forEachIndexed { i, l ->
            if (!keep[i]) {
                gap = true
                return@forEachIndexed
            }
            if (gap && printedAny) sb.append("  …\n")
            gap = false
            val prefix =
                when (l.kind) {
                    DiffLineKind.ADDED -> "+"
                    DiffLineKind.REMOVED -> "-"
                    else -> " "
                }
            sb.append(prefix).append(l.text).append('\n')
            printedAny = true
        }
        return sb.toString()
    }

    /** The collected diff text, and whether anything was left out of it. */
    internal data class CollectedDiff(val text: String, val truncated: Boolean)

    /**
     * Per-file compact diffs for [files], concatenated with a `--- path` header
     * each, bounded to [budget] characters total. Used to build the prompt
     * payload for Agent Review and commit-message generation.
     *
     * [CollectedDiff.truncated] reports whether ANY file was cut short, dropped
     * for want of budget, dropped past [MAX_INLINE_FILES], or failed to fetch.
     * The caller passes that to the prompt builder as a fact, rather than the
     * builder sniffing the marker out of the text - and it used to be missed
     * entirely for the common shape: with a dozen medium files the loop broke
     * at file 7, appended no marker, and the prompt said "Full diff:" over
     * seven of them with nothing saying the rest existed.
     */
    private suspend fun collectDiff(files: List<GitFileStatusData>, budget: Int): CollectedDiff {
        val provider = git ?: return CollectedDiff("", false)
        val distinct = files.distinctBy { it.path }
        // Files past the cap never get a per-file marker of their own, so the
        // overflow itself is the signal.
        var truncated = distinct.size > MAX_INLINE_FILES
        val sb = StringBuilder()
        for (f in distinct.take(MAX_INLINE_FILES)) {
            // A throwing provider (IPC drop, repo mid-rebase) on ONE file must not
            // kill the whole collection: skip that file, keep the rest. The file
            // stays in the listing above, and the agent can fetch it via git_diff.
            val diff =
                runCatching { provider.diffFile(f.path, staged = f.isStaged && !f.isUnstaged) }
                    .getOrNull()?.firstOrNull()
                    ?: runCatching { provider.diffFile(f.path, staged = false) }
                        .getOrNull()?.firstOrNull()
            if (diff == null) {
                truncated = true
                continue
            }
            // compactDiff, not rawUnified: the host generates diffs with whole-file
            // context (-U100000) for the viewer, so rawUnified is the entire file. Fed
            // to a char-budgeted prompt that broke on the first oversized block, a
            // one-line change in a 500-line file produced NO diff at all. The compact
            // form is just the changed lines plus a little context, so the budget now
            // bounds real content instead of context padding.
            val block = compactDiff(diff).ifBlank { continue }
            // Reserve the per-file header and the truncation marker, so the
            // framing never pushes the result past [budget].
            val frame = "--- ${f.path}\n".length + AgentReviewPrompt.TRUNCATION_MARKER.length + 2
            val remaining = budget - sb.length - frame
            // Every file gets sliced to fit, not just the first: breaking on the
            // first block that does not fit whole dropped the rest silently. Stop
            // once what is left would be too small to read.
            if (remaining < MIN_DIFF_SLICE) {
                truncated = true
                break
            }
            val slice =
                if (block.length > remaining) {
                    truncated = true
                    block.take(remaining) + "\n" + AgentReviewPrompt.TRUNCATION_MARKER + "\n"
                } else {
                    block
                }
            sb.append("--- ").append(f.path).append('\n').append(slice).append('\n')
        }
        return CollectedDiff(sb.toString(), truncated)
    }

    /**
     * Agent Review: gather the changed files and as much diff text as fits
     * the inline budget, then hand the composed prompt to [onAgentReview].
     */
    fun startAgentReview() {
        if (!_reviewing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val status = _fileStatus.value
                val staged = status.filter { it.isStaged }
                val unstaged = status.filter { it.isUnstaged }

                // Reuse collectDiff: it uses compactDiff (not rawUnified, which with
                // the host's -U100000 is the entire file) and slices an oversized
                // block instead of breaking, so a large file does not empty the
                // whole prompt.
                val collected =
                    if (git != null) collectDiff(staged + unstaged, AgentReviewPrompt.INLINE_DIFF_BUDGET)
                    else CollectedDiff("", false)

                onAgentReview(
                    AgentReviewPrompt.build(
                        projectPath = getProjectPath().orEmpty(),
                        staged = staged,
                        unstaged = unstaged,
                        diffText = collected.text.takeIf { it.isNotBlank() },
                        diffTruncated = collected.truncated,
                        instructions = _reviewInstructions.value,
                        deep = _reviewDeep.value,
                        baseRef = _reviewBase.value,
                    ),
                )
            } catch (e: Exception) {
                // A throwing provider (IPC drop, repo mid-rebase) must not vanish as
                // a dead button: surface it like any other git failure.
                _message.value = "Agent review failed: ${e.message ?: e::class.simpleName}"
            } finally {
                _reviewing.value = false
            }
        }
    }

    fun dispose() {
        statusTimer?.cancel()
        scope.cancel()
    }

    /**
     * Run one provider operation, surface its result, then ask the provider
     * to re-read status - the collector in [init] publishes the new groups.
     */
    private fun op(
        refreshGraph: Boolean = false,
        block: suspend () -> GitOperationResultData?,
    ) {
        if (git == null) {
            _message.value = "Git is unavailable on this host."
            return
        }
        // Set BEFORE launch. Inside the coroutine body it is set only once the
        // dispatcher runs it, so two clicks landing in the same frame both saw
        // busy = false and both passed the `enabled = !busy` guard - two
        // concurrent index writes, which is what batch() exists to avoid.
        if (!_busy.compareAndSet(expect = false, update = true)) return
        _message.value = null
        scope.launch {
            try {
                try {
                    report(block()) { if (refreshGraph) loadGraph(reset = true) }
                    git.refreshStatus()
                } catch (e: Exception) {
                    // A throwing provider (IPC drop, repo mid-rebase) must not vanish:
                    // report() already exists to surface failures, and a raw throw
                    // here would leave _message null with only the spinner stopping.
                    _message.value = "Failed: ${e.message ?: e::class.simpleName}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun report(result: GitOperationResultData?, onSucceed: () -> Unit) {
        _message.value =
            when (result) {
                is GitOperationResultData.Success -> result.message
                is GitOperationResultData.Error -> "Failed: ${result.message}"
                null -> "No git project open."
            }
        if (result is GitOperationResultData.Success) onSucceed()
    }

    companion object {
        const val GRAPH_PAGE = 50
        const val GRAPH_MAX = 200
        private const val REFRESH_MS = 5_000L
        private const val MAX_INLINE_FILES = 15

        /** Diff characters sent when drafting a commit message. */
        private const val COMMIT_DIFF_BUDGET = 12_000

        /**
         * Smallest slice of a file's diff worth attaching. Below this the
         * remaining budget buys a few unreadable characters, so the collection
         * stops and reports itself truncated instead.
         */
        private const val MIN_DIFF_SLICE = 200

        /** Immediate children inspected for the no-repository hint. */
        private const val CHILD_SCAN_LIMIT = 200

        /** How long [awaitRepositoryFlag] waits for the provider's flag to move. */
        private const val REPO_FLAG_SETTLE_MS = 2_000L

        /**
         * A sentence for the empty state, from the selected folder alone:
         * names the folder, and counts the repositories directly inside it so
         * the fix ("open one of them") is obvious. Immediate children only -
         * a recursive scan of an arbitrary folder is not worth a panel hint.
         */
        internal fun describeMissingRepo(projectPath: String?): String {
            if (projectPath.isNullOrBlank()) return "No project open."
            val root = java.io.File(projectPath)
            if (!root.isDirectory) return "No Git repository in this project."
            val name = root.name.ifEmpty { projectPath }
            val nested =
                try {
                    root.listFiles()
                        ?.asSequence()
                        ?.filter { it.isDirectory }
                        ?.take(CHILD_SCAN_LIMIT)
                        ?.count { java.io.File(it, ".git").exists() }
                        ?: 0
                } catch (_: Exception) {
                    0
                }
            return when {
                nested == 1 -> "$name is not a Git repository, but one folder inside it is. Open that folder as the project."
                nested > 1 -> "$name is not a Git repository. It holds $nested of them - open one as the project."
                else -> "$name is not a Git repository."
            }
        }

        /**
         * Every branch named in a graph's ref decorations, de-duplicated and
         * with remote copies folded onto their local name. [remoteNames] is
         * the repository's real remote set (not a hardcoded "origin"): a
         * clone from `upstream/feat/x` folds too, while a local branch that
         * merely looks like `someone/something` does not.
         */
        internal fun branchesOf(nodes: List<GitCommitNodeData>, remoteNames: Set<String>): List<String> =
            nodes
                .asSequence()
                .flatMap { it.refs.asSequence() }
                .map { it.removePrefix("HEAD ->").trim() }
                .filterNot { it.isBlank() || it == "HEAD" || it.startsWith("tag:") }
                .map { ref ->
                    val slash = ref.indexOf('/')
                    if (slash in 1 until ref.length && ref.substring(0, slash) in remoteNames) {
                        ref.substring(slash + 1)
                    } else {
                        ref
                    }
                }
                .distinct()
                .sorted()
                .toList()

        /** "main"/"master" when the history has one, else the current branch. */
        internal fun defaultBase(branches: List<String>, current: String): String =
            branches.firstOrNull { it == "main" }
                ?: branches.firstOrNull { it == "master" }
                ?: current

        /**
         * The branch name out of a graph's ref decorations. `%D` yields
         * entries like `HEAD -> feat/x`, `origin/feat/x`, `tag: v1`; only the
         * HEAD arrow names the checked-out branch, and a detached HEAD has
         * none - which reads as "detached", not as some remote's name.
         */
        internal fun branchOf(nodes: List<GitCommitNodeData>): String {
            val refs = nodes.firstOrNull()?.refs ?: return ""
            refs.firstOrNull { it.startsWith("HEAD ->") }
                ?.let { return it.removePrefix("HEAD ->").trim() }
            return if (refs.any { it.trim() == "HEAD" }) "detached" else ""
        }
    }
}

/** Untracked files carry only a work-tree status; keep them out of the staged list. */
internal val GitFileStatusData.isUntracked: Boolean
    get() = !isStaged && workTreeStatus == GitFileStatusTypeData.UNTRACKED
