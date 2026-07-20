package ai.rever.boss.plugin.dynamic.codebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Separator-aware path helpers (issue #7). Each case runs with both Unix and
 * Windows separators via the explicit separator parameter, so Windows
 * behavior is pinned even when the suite runs on macOS/Linux.
 */
class PathUtilsTest {

    @Test
    fun `name returns last segment for both separators`() {
        assertEquals("file.txt", PathUtils.name("/p/a/file.txt", '/'))
        assertEquals("file.txt", PathUtils.name("""C:\p\a\file.txt""", '\\'))
        assertEquals("noseparator", PathUtils.name("noseparator", '/'))
    }

    @Test
    fun `parent returns everything before last segment or empty`() {
        assertEquals("/p/a", PathUtils.parent("/p/a/file.txt", '/'))
        assertEquals("""C:\p\a""", PathUtils.parent("""C:\p\a\file.txt""", '\\'))
        assertEquals("", PathUtils.parent("noseparator", '/'))
    }

    @Test
    fun `isNestedUnder requires a separator boundary`() {
        assertTrue(PathUtils.isNestedUnder("/p/a/b", "/p/a", '/'))
        assertTrue(PathUtils.isNestedUnder("""C:\p\a\b""", """C:\p\a""", '\\'))

        // string-prefix but not path-nested
        assertFalse(PathUtils.isNestedUnder("/p/ab", "/p/a", '/'))
        assertFalse(PathUtils.isNestedUnder("""C:\p\ab""", """C:\p\a""", '\\'))
        // equal paths are not nested
        assertFalse(PathUtils.isNestedUnder("/p/a", "/p/a", '/'))
    }

    @Test
    fun `relativize strips root and leading separator`() {
        assertEquals("src/Main.kt", PathUtils.relativize("/proj/src/Main.kt", "/proj", '/'))
        assertEquals("""src\Main.kt""", PathUtils.relativize("""C:\proj\src\Main.kt""", """C:\proj""", '\\'))
        // the root itself relativizes to ""
        assertEquals("", PathUtils.relativize("/proj", "/proj", '/'))
    }

    @Test
    fun `relativize leaves paths outside the root unchanged`() {
        assertEquals("/other/file", PathUtils.relativize("/other/file", "/proj", '/'))
        // prefix-sibling directory must not be treated as inside the root
        assertEquals("/projX/file", PathUtils.relativize("/projX/file", "/proj", '/'))
        // empty root: unchanged
        assertEquals("/a/b", PathUtils.relativize("/a/b", "", '/'))
    }

    @Test
    fun `filterNestedPaths honors windows separators`() {
        val paths = listOf("""C:\p\a""", """C:\p\a\b""", """C:\p\c""", """C:\p\ab""")
        assertEquals(
            listOf("""C:\p\a""", """C:\p\c""", """C:\p\ab"""),
            FileTreeUtils.filterNestedPaths(paths, separator = '\\')
        )
    }
}
