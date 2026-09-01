package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitCommitNodeData

/**
 * What each graph row has to draw.
 *
 * [GitGraphLayout] answers "which lane is this commit in". That alone renders
 * a dot per row and a stub to its neighbour, which is why the graph looked
 * sparse: a branch that runs past twenty commits before merging has a line
 * through every one of those rows, and nothing modelled it.
 *
 * Here every parent link becomes an edge from the child's row and lane to the
 * parent's, and each row collects the edges crossing it. Pure, because the
 * shape of a history is exactly the kind of thing that is painful to verify
 * by looking at it.
 */
internal object GitGraphEdges {

    /** A line segment crossing one row, in lane coordinates. */
    data class Segment(
        /** Lane the segment occupies at the top edge of the row. */
        val fromLane: Int,
        /** Lane it occupies at the bottom edge. */
        val toLane: Int,
        /** True when it starts at this row's commit dot rather than the top edge. */
        val fromNode: Boolean,
        /** True when it ends at this row's commit dot rather than the bottom edge. */
        val toNode: Boolean,
        /** Lane whose colour the segment takes - the branch it belongs to. */
        val colorLane: Int,
    )

    /** Everything one row draws. */
    data class Row(
        val lane: Int,
        val isMerge: Boolean,
        val segments: List<Segment>,
    )

    /** Lanes wide enough to hold every segment in [rows]. */
    fun laneCount(rows: List<Row>): Int =
        (rows.flatMap { row -> row.segments.flatMap { listOf(it.fromLane, it.toLane) } + row.lane }
            .maxOrNull() ?: 0) + 1

    fun build(
        commits: List<GitCommitNodeData>,
        lanes: List<Int>,
    ): List<Row> {
        if (commits.isEmpty()) return emptyList()
        val rowOf = HashMap<String, Int>(commits.size)
        commits.forEachIndexed { index, c -> rowOf.putIfAbsent(c.hash, index) }

        val segments = List(commits.size) { mutableListOf<Segment>() }

        commits.forEachIndexed { childRow, commit ->
            val childLane = lanes.getOrElse(childRow) { 0 }
            for (parentHash in commit.parents) {
                // A parent outside the fetched window draws nothing: the
                // window's last row is where history visibly stops, and a
                // stub leaving the bottom edge would read as a lane that
                // continues into the next row rather than off the page.
                val parentRow = rowOf[parentHash] ?: continue
                if (parentRow <= childRow) continue
                val parentLane = lanes.getOrElse(parentRow) { 0 }

                if (parentRow == childRow + 1) {
                    // Adjacent rows: the line leaves the child's dot at the
                    // bottom half of its row and arrives at the parent's dot in
                    // the top half of the next. BOTH halves are needed - only
                    // emitting the first left every line stopping at the row
                    // boundary instead of reaching the commit below.
                    segments[childRow].add(
                        Segment(childLane, parentLane, fromNode = true, toNode = false, colorLane = parentLane),
                    )
                    segments[parentRow].add(
                        Segment(parentLane, parentLane, fromNode = false, toNode = true, colorLane = parentLane),
                    )
                    continue
                }

                // Leaves the child's dot, settles into the parent's lane.
                segments[childRow].add(
                    Segment(childLane, parentLane, fromNode = true, toNode = false, colorLane = parentLane),
                )
                // Runs straight through everything in between.
                for (row in (childRow + 1) until parentRow) {
                    segments[row].add(
                        Segment(parentLane, parentLane, fromNode = false, toNode = false, colorLane = parentLane),
                    )
                }
                // Arrives at the parent's dot.
                segments[parentRow].add(
                    Segment(parentLane, parentLane, fromNode = false, toNode = true, colorLane = parentLane),
                )
            }
        }

        return commits.mapIndexed { index, commit ->
            Row(
                lane = lanes.getOrElse(index) { 0 },
                isMerge = commit.parents.size > 1,
                // Not distinct(): two parents that resolve to the same lane
                // produce identical segments, and collapsing them would drop a
                // real edge. Drawing one twice costs nothing.
                segments = segments[index].toList(),
            )
        }
    }
}
