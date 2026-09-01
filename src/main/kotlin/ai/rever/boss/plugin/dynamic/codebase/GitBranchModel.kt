package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitBranchRefData
import ai.rever.boss.plugin.api.GitCommitNodeData

/** What a ref decoration on a commit is. An open set is not needed - git has four. */
internal enum class GitRefKind {
    /** The checked-out branch (`HEAD -> name`). */
    HEAD,

    /** A local branch that is not checked out. */
    LOCAL,

    /** An annotated or lightweight tag. */
    TAG,

    /** A remote-tracking branch (`origin/name`). */
    REMOTE,
}

/** One pill drawn at the end of a commit's subject row. */
internal data class GitRefPill(
    val label: String,
    val kind: GitRefKind,
)

/**
 * One entry of the graph's branch picker.
 *
 * Public only because [CodebaseGitViewModel] is: an internal type on a public
 * flow does not compile. Nothing outside this jar has a reason to construct one.
 */
data class GitBranchOption(
    val name: String,
    val isRemote: Boolean,
    val isCurrent: Boolean,
)

/**
 * The structural half of the graph's branch UI: which pills a commit shows,
 * and what the picker offers.
 *
 * Pure on purpose. Both jobs are string surgery over git's `%D` decorations,
 * where every mistake ("side " with the marker column still attached, a
 * remote pill duplicating its local, `origin/HEAD` rendered as a branch) is
 * invisible until someone squints at a screenshot - and this panel has
 * shipped that class of bug before.
 */
internal object GitBranchModel {

    /**
     * Remotes assumed when the host cannot name them.
     *
     * A ref is remote because its first segment is a REMOTE's name, which is
     * not something `%D` marks - `feature/x` and `origin/x` are the same
     * shape. When the branch list is available the real set is passed in;
     * these two cover the rest.
     */
    val DEFAULT_REMOTES: Set<String> = setOf("origin", "upstream")

    /** Longest ref the pickers will hand back to git. Mirrors the host's own cap. */
    private const val MAX_REF_LENGTH = 255

    /**
     * A ref safe to send through [ai.rever.boss.plugin.api.GitDataProvider].
     *
     * The host validates too - it is the real boundary - but refusing here
     * means the panel can say why instead of silently drawing an empty graph.
     */
    fun isSafeRef(ref: String): Boolean {
        if (ref.isBlank()) return false
        if (ref.startsWith("-")) return false
        if (ref.length > MAX_REF_LENGTH) return false
        return ref.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
    }

    /**
     * The "Diff against…" choices from the repository's real branch list:
     * remote copies folded onto their local name, de-duplicated, sorted.
     *
     * Its own function rather than reusing [options]: the review picker is a
     * list of plain refs to review *toward*, so `origin/main` and `main` are
     * the same answer, whereas the graph picker deliberately keeps them apart
     * because looking at the remote's history is a different thing to do.
     */
    fun reviewBases(
        branches: List<GitBranchRefData>,
        remotes: Set<String> = DEFAULT_REMOTES,
    ): List<String> =
        branches
            .asSequence()
            .map { it.name.trim() }
            .filter { it.isNotEmpty() && it != "HEAD" }
            .map { name ->
                val slash = name.indexOf('/')
                if (slash in 1 until name.length && name.substring(0, slash) in remotes) {
                    name.substring(slash + 1)
                } else {
                    name
                }
            }
            .filter { it.isNotEmpty() && it != "HEAD" }
            .distinct()
            .sorted()
            .toList()

    /** The remote names implied by a branch list, for [refPills]. */
    fun remoteNamesOf(branches: List<GitBranchRefData>): Set<String> =
        branches
            .asSequence()
            .filter { it.isRemote }
            .map { it.name.substringBefore('/') }
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { DEFAULT_REMOTES }

    /**
     * The pills for one commit's ref decorations, ordered
     * HEAD → local → tag → remote.
     *
     * Dropped along the way:
     *  - a bare `HEAD` (the detached-HEAD marker names no branch),
     *  - `origin/HEAD` (a symref at the remote's default branch, not a branch),
     *  - a remote pill whose short name already appears as a local pill on the
     *    SAME commit - `main` and `origin/main` side by side says nothing the
     *    first pill did not.
     */
    fun refPills(
        refs: List<String>,
        remotes: Set<String> = DEFAULT_REMOTES,
    ): List<GitRefPill> {
        val parsed =
            refs.mapNotNull { raw ->
                val ref = raw.trim()
                when {
                    ref.isEmpty() -> null
                    ref.startsWith("tag:") ->
                        ref.removePrefix("tag:").trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { GitRefPill(it, GitRefKind.TAG) }

                    ref.startsWith("HEAD ->") ->
                        ref.removePrefix("HEAD ->").trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { GitRefPill(it, GitRefKind.HEAD) }

                    ref == "HEAD" -> null
                    ref.substringBefore('/') in remotes && ref.contains('/') ->
                        if (ref.substringAfter('/') == "HEAD") null
                        else GitRefPill(ref, GitRefKind.REMOTE)

                    else -> GitRefPill(ref, GitRefKind.LOCAL)
                }
            }

        val localNames =
            parsed.filter { it.kind == GitRefKind.HEAD || it.kind == GitRefKind.LOCAL }
                .map { it.label }
                .toSet()

        return parsed
            .filterNot { it.kind == GitRefKind.REMOTE && it.label.substringAfter('/') in localNames }
            .distinctBy { it.kind to it.label }
            .sortedBy { it.kind.ordinal }
    }

    /**
     * Branch names decorating a loaded graph, for [options]' fallback path.
     *
     * Reuses [refPills] rather than re-parsing `%D`, so the two agree by
     * construction, and drops tags - a tag is a place in history, not a branch
     * you can ask the graph to follow.
     *
     * Deliberately NOT [CodebaseGitViewModel.branchesOf], which folds
     * `origin/x` onto `x` for the "Diff Against..." picker. Here the remote is
     * a different thing to look at and keeps its own name.
     */
    fun branchRefsInGraph(
        nodes: List<GitCommitNodeData>,
        remotes: Set<String> = DEFAULT_REMOTES,
    ): List<String> =
        nodes
            .asSequence()
            .flatMap { refPills(it.refs, remotes).asSequence() }
            .filter { it.kind != GitRefKind.TAG }
            .map { it.label }
            .distinct()
            .toList()

    /**
     * The branch picker's entries: the checked-out branch first, then the
     * other locals A-Z, then the remote-tracking branches A-Z.
     *
     * [graphRefs] is the fallback for a host that predates
     * [ai.rever.boss.plugin.api.GitDataProvider.branches] - the branch names
     * decorating the loaded graph. It is a strictly smaller set (a branch
     * whose tip is older than the fetched window is missing from it), which is
     * why it is only consulted when the real list is empty.
     *
     * Remote branches are kept even when a local of the same short name
     * exists: `origin/main` and `main` are different histories the moment
     * either moves, and collapsing them would hide exactly the divergence
     * someone opens a graph to look at.
     */
    fun options(
        branches: List<GitBranchRefData>,
        graphRefs: List<String> = emptyList(),
        current: String = "",
    ): List<GitBranchOption> {
        val remotes = remoteNamesOf(branches)
        val source =
            if (branches.isNotEmpty()) {
                branches.map { GitBranchOption(it.name.trim(), it.isRemote, it.isCurrent) }
            } else {
                graphRefs.map { raw ->
                    val name = raw.trim()
                    GitBranchOption(
                        name = name,
                        isRemote = name.contains('/') && name.substringBefore('/') in remotes,
                        isCurrent = false,
                    )
                }
            }

        val cleaned =
            source
                .asSequence()
                .map { it.copy(name = it.name.trim()) }
                .filter { it.name.isNotEmpty() }
                .filterNot { it.name == "HEAD" }
                .filterNot { it.isRemote && it.name.substringAfter('/') == "HEAD" }
                // isCurrent from the host when it says so, else by name - the
                // fallback path has no current marker of its own.
                .map { if (current.isNotEmpty() && it.name == current) it.copy(isCurrent = true) else it }
                .distinctBy { it.name }
                .toList()

        return cleaned.sortedWith(
            compareBy(
                { !it.isCurrent },
                { it.isRemote },
                { it.name },
            ),
        )
    }
}
