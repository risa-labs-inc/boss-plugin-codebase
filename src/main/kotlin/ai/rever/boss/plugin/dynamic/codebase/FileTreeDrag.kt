package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/** Native file references only; the destination decides how to handle a drop. */
internal object FileTreeDrag {
    /**
     * Selection keys refer to chain tops; file operations refer to compact chain ends.
     * [rows] must be derived from the same tree snapshot as [tree].
     */
    fun paths(
        source: FileNode,
        selectedPaths: Set<String>,
        rows: List<VisibleRow>,
        tree: FileNode? = null
    ): List<String>? {
        if (source.path !in selectedPaths) return listOf(source.getCompactEndNode().path)
        val visible = rows.filterIsInstance<VisibleRow.Node>().map { it.node }
            .filter { it.path in selectedPaths }
        val remaining = selectedPaths - visible.map { it.path }.toSet()
        val nodesByPath = mutableMapOf<String, FileNode>()
        if (remaining.isNotEmpty()) {
            // One traversal at drag start, including loaded descendants of collapsed rows.
            val pending = ArrayDeque<FileNode>()
            tree?.let(pending::addLast)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                nodesByPath[node.path] = node
                node.children.forEach(pending::addLast)
            }
        }
        val hidden = remaining.sorted().map { nodesByPath[it] ?: return null }
        return (visible + hidden).map { it.getCompactEndNode().path }.distinct()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun transferData(
        source: FileNode,
        selectedPaths: Set<String>,
        rows: List<VisibleRow>,
        tree: FileNode? = null,
        onRejected: (String) -> Unit = {}
    ): DragAndDropTransferData? {
        val paths = paths(source, selectedPaths, rows, tree)
        if (paths == null) {
            onRejected("Some selected items are no longer in the file tree. Select the items again before dragging.")
            return null
        }
        val transferable = transferable(paths)
        if (transferable == null) {
            onRejected("These items cannot be dragged because a path is not absolute or contains control characters.")
            return null
        }
        return DragAndDropTransferData(
            transferable = DragAndDropTransferable(transferable),
            supportedActions = listOf(DragAndDropTransferAction.Copy)
        )
    }

    fun transferable(paths: List<String>): Transferable? {
        // Terminal destinations can treat control characters as input. Refuse the whole
        // selection rather than silently attaching only some of the requested files.
        if (paths.isEmpty() || paths.any { path ->
                path.any { it.isISOControl() } || !File(path).isAbsolute
            }) return null
        val files = paths.map(::File)
        return object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                // A new list prevents a destination from mutating the active drag payload.
                return ArrayList(files)
            }
        }
    }
}
