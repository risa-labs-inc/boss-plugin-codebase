package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTreeDragTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "drag project")
    private fun node(name: String, directory: Boolean = false) = FileNode(
        name = name,
        path = File(root, name).absolutePath,
        isDirectory = directory,
        loadingState = NodeLoadingState.LOADED
    )

    @Test
    fun `selected rows follow display order then collapsed selection and omit status placeholders`() {
        val first = node("first.txt")
        val second = node("second.txt")
        val hidden = node("hidden.txt")
        val rows = listOf(
            VisibleRow.Node(first, 0),
            VisibleRow.Loading(first.path, 1),
            VisibleRow.Node(second, 0),
            VisibleRow.Empty(second.path, 1)
        )
        val selection = linkedSetOf(second.path, hidden.path, first.path)
        val tree = node("root", true).copy(children = listOf(hidden, second, first))
        assertEquals(listOf(first.path, second.path, hidden.path), FileTreeDrag.paths(second, selection, rows, tree))
        assertNull(FileTreeDrag.paths(second, selection, rows, null))
        assertEquals(linkedSetOf(second.path, hidden.path, first.path), selection)
    }

    @Test
    fun `dragging an unselected row carries only that row`() {
        val selected = node("selected.txt")
        val source = node("source.txt")
        val rows = listOf(VisibleRow.Node(selected, 0), VisibleRow.Node(source, 0))
        assertEquals(listOf(source.path), FileTreeDrag.paths(source, setOf(selected.path), rows, null))
    }

    @Test
    fun `compacted directory drags its displayed end without expanding its children`() {
        val file = node("src/main/App.kt")
        val end = node("src/main", true).copy(children = listOf(file))
        val top = node("src", true).copy(children = listOf(end))
        val rows = listOf(VisibleRow.Node(top, 0), VisibleRow.Node(file, 1))
        assertEquals(listOf(end.path), FileTreeDrag.paths(top, setOf(top.path), rows, top))
        assertEquals(listOf(end.path, file.path), FileTreeDrag.paths(top, setOf(file.path, top.path), rows, top))
        assertEquals(listOf(end.path), FileTreeDrag.paths(top, emptySet(), rows, top))
    }

    @Test
    fun `selected compact chain top and end produce one directory reference`() {
        val end = node("src/main", true)
        val top = node("src", true).copy(children = listOf(end))
        val rows = listOf(VisibleRow.Node(top, 0))
        assertEquals(listOf(end.path), FileTreeDrag.paths(top, setOf(top.path, end.path), rows, top))
    }

    @Test
    fun `native transfer exposes actual Files preserving special characters and order`() {
        val paths = listOf("photo space.png", "quote's \"image\".png", "café 日本.png", "$(echo nope);&.txt")
            .map { File(root, it).absolutePath }
        val transfer = assertNotNull(FileTreeDrag.transferable(paths))
        assertEquals(listOf(DataFlavor.javaFileListFlavor), transfer.transferDataFlavors.toList())
        assertTrue(transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor))
        val files = assertIs<List<*>>(transfer.getTransferData(DataFlavor.javaFileListFlavor))
        assertEquals(paths, files.map { assertIs<File>(it).absolutePath })
        assertFailsWith<UnsupportedFlavorException> { transfer.getTransferData(DataFlavor.stringFlavor) }
        @Suppress("UNCHECKED_CAST")
        (files as MutableList<File>).clear()
        assertEquals(paths.map(::File), transfer.getTransferData(DataFlavor.javaFileListFlavor))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `Compose transfer offers copy only and no completion side effect`() {
        val source = node("photo.png")
        val data = assertNotNull(FileTreeDrag.transferData(source, emptySet(), emptyList(), source))
        assertEquals(listOf(DragAndDropTransferAction.Copy), data.supportedActions.toList())
        assertNull(data.onTransferCompleted)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `rejected drag reports a reason and never produces a partial payload`() {
        val source = node("safe.png")
        val unsafe = node("unsafe\nname.png")
        val rows = listOf(VisibleRow.Node(source, 0), VisibleRow.Node(unsafe, 0))
        val messages = mutableListOf<String>()
        assertNull(FileTreeDrag.transferData(source, setOf(source.path, unsafe.path), rows, tree = null, onRejected = messages::add))
        assertTrue(messages.single().contains("control characters"))
        messages.clear()
        assertNull(FileTreeDrag.transferData(source, setOf(source.path, "missing"), rows, tree = null, onRejected = messages::add))
        assertTrue(messages.single().contains("no longer in the file tree"))
    }

    @Test
    fun `unsafe terminal control characters cancel the whole transfer`() {
        val safe = node("safe.png").path
        for (control in listOf('\u0000', '\n', '\r', '\t', '\u001b', '\u007f', '\u0085', '\u009b')) {
            assertNull(FileTreeDrag.transferable(listOf(safe, node("bad${control}name.png").path)))
        }
        assertNull(FileTreeDrag.transferable(emptyList()))
        assertNull(FileTreeDrag.transferable(listOf("relative.png")))
    }
}
