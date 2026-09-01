package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitCommitNodeData

/**
 * Lane assignment for the git graph view (P7).
 *
 * Input is in `git log` order (newest first, topological - a commit's
 * children always come before it). Output is one lane index per commit,
 * 0 being the leftmost lane.
 *
 * Rules (deliberately simple, pinned by [GitGraphLayoutTest]):
 * 1. A commit with already-placed children continues the rightmost child's
 *    lane - branch points and merges sit on a child's line.
 * 2. A commit without placed children takes the leftmost lane whose current
 *    holder is an ancestor of it (the line keeps running), else the first
 *    empty lane, else a new lane opens.
 *
 * This renders linear history and fork/branch shapes correctly. A merge
 * whose both parents are on separate lanes collapses them into one lane at
 * the merge row - an accepted v1 look, not a bug to "fix" in the renderer.
 */
object GitGraphLayout {

    fun assignLanes(commits: List<GitCommitNodeData>): List<Int> {
        if (commits.isEmpty()) return emptyList()

        val childrenOf = HashMap<String, MutableList<String>>()
        commits.forEach { c ->
            c.parents.forEach { p ->
                childrenOf.getOrPut(p) { mutableListOf() }.add(c.hash)
            }
        }

        // Ancestor sets, bounded by the fetched window - fine for a few hundred commits.
        val parentsByHash = commits.associateBy({ it.hash }, { it.parents })
        val ancestorCache = HashMap<String, HashSet<String>>()
        fun ancestorsOfCommit(c: GitCommitNodeData): HashSet<String> =
            ancestorCache.getOrPut(c.hash) {
                val set = HashSet<String>()
                val queue = ArrayDeque(c.parents)
                while (queue.isNotEmpty()) {
                    val p = queue.removeFirst()
                    if (set.add(p)) parentsByHash[p]?.let { queue.addAll(it) }
                }
                set
            }

        val laneOf = HashMap<String, Int>()
        val laneHolder = HashMap<Int, String>()
        val result = IntArray(commits.size)

        commits.forEachIndexed { i, c ->
            val lane =
                laneOf[c.hash]
                    ?: run {
                        val childLanes = childrenOf[c.hash].orEmpty().mapNotNull { laneOf[it] }
                        when {
                            childLanes.isNotEmpty() -> childLanes.max()
                            else -> {
                                val anc = ancestorsOfCommit(c)
                                (0 until maxOf(laneHolder.size, 1)).firstOrNull { l ->
                                    val holder = laneHolder[l]
                                    holder != null && holder in anc
                                } ?: (0 until maxOf(laneHolder.size, 1)).firstOrNull { laneHolder[it] == null }
                                    ?: laneHolder.size
                            }
                        }
                    }
            laneOf[c.hash] = lane
            laneHolder[lane] = c.hash
            result[i] = lane
        }

        return result.toList()
    }
}
