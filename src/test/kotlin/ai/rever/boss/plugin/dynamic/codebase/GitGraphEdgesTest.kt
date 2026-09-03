package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitCommitNodeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each graph row draws.
 *
 * The reason this exists: lane assignment alone gave a dot per row and a stub
 * to the neighbour, so a branch running past twenty commits before merging
 * drew nothing across those rows. Continuity is the whole point of a graph,
 * and it is not something to check by squinting at a screenshot.
 */
class GitGraphEdgesTest {

    private fun commit(hash: String, vararg parents: String) =
        GitCommitNodeData(
            hash = hash,
            shortHash = hash,
            subject = hash,
            author = "A",
            authorEmail = "a@b.c",
            date = 0L,
            refs = emptyList(),
            parents = parents.toList(),
        )

    private fun rows(commits: List<GitCommitNodeData>): List<GitGraphEdges.Row> =
        GitGraphEdges.build(commits, GitGraphLayout.assignLanes(commits))

    @Test
    fun `linear history draws one continuous line`() {
        val out = rows(listOf(commit("c", "b"), commit("b", "a"), commit("a")))
        // Every row but the last leaves downward, every row but the first arrives.
        assertTrue(out[0].segments.any { it.fromNode }, "the tip must start a line")
        assertTrue(out[1].segments.any { it.toNode }, "the middle must receive one")
        assertTrue(out[1].segments.any { it.fromNode }, "and continue one")
        assertTrue(out[2].segments.any { it.toNode }, "the root must receive one")
        assertTrue(out.none { it.isMerge })
    }

    @Test
    fun `a branch spanning several commits draws through every row between`() {
        // c -> a skips over b: the row for b must still carry that line, or
        // the branch visually disappears and reappears.
        val out = rows(listOf(commit("c", "a"), commit("b", "a"), commit("a")))
        val passThrough = out[1].segments.filter { !it.fromNode && !it.toNode }
        assertTrue(passThrough.isNotEmpty(), "no line crosses the skipped row: ${out[1].segments}")
    }

    @Test
    fun `a merge commit is marked as one`() {
        val out = rows(listOf(commit("m", "a", "b"), commit("a", "root"), commit("b", "root"), commit("root")))
        assertTrue(out[0].isMerge, "two parents is a merge")
        assertTrue(out[1].segments.isNotEmpty())
        assertTrue(out.drop(1).none { it.isMerge })
    }

    @Test
    fun `a merge emits a line toward each parent`() {
        val out = rows(listOf(commit("m", "a", "b"), commit("a", "root"), commit("b", "root"), commit("root")))
        assertEquals(2, out[0].segments.count { it.fromNode }, "one line per parent")
    }

    @Test
    fun `a parent outside the window does not draw a line into nothing`() {
        // The oldest fetched commit still has parents; they are not in the list.
        val out = rows(listOf(commit("c", "missing")))
        assertTrue(out.single().segments.isEmpty(), "an unknown parent must not produce a segment")
    }

    @Test
    fun `lane count covers every lane a segment reaches`() {
        val out = rows(listOf(commit("m", "a", "b"), commit("a", "root"), commit("b", "root"), commit("root")))
        val maxLane = out.flatMap { r -> r.segments.flatMap { listOf(it.fromLane, it.toLane) } + r.lane }.max()
        assertTrue(GitGraphEdges.laneCount(out) > maxLane, "a lane would be clipped")
    }

    @Test
    fun `an empty history produces no rows`() {
        assertEquals(emptyList(), GitGraphEdges.build(emptyList(), emptyList()))
        assertEquals(1, GitGraphEdges.laneCount(emptyList()))
    }

    @Test
    fun `every segment takes the colour of the lane it settles into`() {
        // The invariant that keeps a branch one colour along its whole length:
        // a segment is coloured by where it ENDS, not where it starts, so the
        // curve leaving a fork already wears the colour of the line it joins.
        val out = rows(
            listOf(
                commit("m", "a", "b"),
                commit("a", "root"),
                commit("b", "root"),
                commit("root"),
            ),
        )
        val wrong = out.flatMap { it.segments }.filter { it.colorLane != it.toLane }
        assertTrue(wrong.isEmpty(), "these would change colour mid-branch: $wrong")
    }
}
