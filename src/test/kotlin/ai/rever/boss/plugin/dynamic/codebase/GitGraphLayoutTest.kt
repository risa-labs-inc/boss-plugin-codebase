package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitCommitNodeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the lane-assignment rules the graph renderer relies on: linear
 * history stays in one lane, a fork offsets the older side, and merges
 * continue a child's lane (collapsing lanes at the merge row is the
 * accepted v1 look).
 */
class GitGraphLayoutTest {

    private fun commit(
        hash: String,
        vararg parents: String,
    ): GitCommitNodeData =
        GitCommitNodeData(
            hash = hash,
            shortHash = hash.take(7),
            subject = hash,
            author = "t",
            authorEmail = "t@t",
            date = 0L,
            refs = emptyList(),
            parents = parents.toList(),
        )

    @Test
    fun `empty input yields no lanes`() {
        assertEquals(emptyList(), GitGraphLayout.assignLanes(emptyList()))
    }

    @Test
    fun `linear history sits in a single lane`() {
        // newest first: m3 <- m2 <- m1
        val commits = listOf(commit("m3", "m2"), commit("m2", "m1"), commit("m1"))
        assertEquals(listOf(0, 0, 0), GitGraphLayout.assignLanes(commits))
    }

    @Test
    fun `a fork offsets the older mainline to a second lane`() {
        // main: m1 <- m2 <- m3 ; branch off m2: b1 <- b2 <- b3
        // git log order (newest first): b3 b2 b1 m3 m2 m1
        val commits = listOf(
            commit("b3", "b2"),
            commit("b2", "b1"),
            commit("b1", "m2"),
            commit("m3", "m2"),
            commit("m2", "m1"),
            commit("m1"),
        )
        assertEquals(listOf(0, 0, 0, 1, 1, 1), GitGraphLayout.assignLanes(commits))
    }

    @Test
    fun `a merge continues a child lane without opening new lanes`() {
        // main: m1 <- m2 ; branch: b1(m2) <- b2(b1) ; merge m3(m2, b2)
        // git log order: m3 b2 m2 b1 m1
        val commits = listOf(
            commit("m3", "m2", "b2"),
            commit("b2", "b1"),
            commit("m2", "m1"),
            commit("b1", "m2"),
            commit("m1"),
        )
        val lanes = GitGraphLayout.assignLanes(commits)
        assertEquals(commits.size, lanes.size)
        // Merge sits on a child's lane; nothing opens a third lane.
        assertTrue(lanes.maxOrNull() == 0, "expected the v1 collapse-to-one-lane look, got $lanes")
    }

    @Test
    fun `long linear history stays in the first lane`() {
        // 40 commits, newest first: c40 <- c39 <- ... <- c1
        val commits =
            (40 downTo 1).map { i ->
                if (i == 1) commit("c$i") else commit("c$i", "c${i - 1}")
            }
        val lanes = GitGraphLayout.assignLanes(commits)
        assertEquals(commits.size, lanes.size)
        assertEquals(IntArray(commits.size) { 0 }.toList(), lanes)
    }
}