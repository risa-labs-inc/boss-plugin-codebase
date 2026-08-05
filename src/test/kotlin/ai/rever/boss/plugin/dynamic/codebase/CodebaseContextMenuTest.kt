package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodebaseContextMenuTest {

    @Test
    fun `project root menu exposes safe directory actions targeting the root`() {
        val projectPath = "/project"
        val invoked = mutableListOf<Pair<String, String>>()
        val items = projectRootContextMenuItems(
            projectPath = projectPath,
            onCreateFile = { invoked += "file" to it },
            onCreateFolder = { invoked += "folder" to it },
            onCopyPath = { invoked += "copy" to it },
            onRevealInFileManager = { invoked += "reveal" to it },
            onOpenInTerminal = { invoked += "terminal" to it }
        )

        assertEquals(5, items.size)
        assertEquals(
            listOf("New File", "New Folder", "Copy Path"),
            items.take(3).map { it.label }
        )
        assertEquals("Open in Terminal", items.last().label)
        assertFalse(items.any { it.label.startsWith("Rename") || it.label.startsWith("Delete") })

        items.forEach { it.onClick() }
        assertEquals(
            listOf("file", "folder", "copy", "reveal", "terminal").map { it to projectPath },
            invoked
        )
    }
}
