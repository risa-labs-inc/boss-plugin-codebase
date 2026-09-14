package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.GitFileStatusData

/**
 * The request behind the commit box's generate button, and the cleanup its
 * reply needs.
 *
 * Both halves are pure so the prompt's shape and the tidy-up can be pinned by
 * tests - a model that wraps its answer in a fence or prefixes it with "Here
 * is a commit message:" would otherwise end up in the box verbatim.
 */
internal object CommitMessagePrompt {

    private const val SYSTEM =
        "You write git commit messages. Reply with the message only: no preamble, no " +
            "markdown fences, no quotes. First line is an imperative subject under 72 " +
            "characters with no trailing period. If the change needs explanation, add a " +
            "blank line and one short paragraph saying WHY, not restating the diff."

    fun request(files: List<GitFileStatusData>, diff: String): AiRequest {
        val listing =
            files.joinToString("\n") { f ->
                val status = if (f.isStaged) f.indexStatus else f.workTreeStatus
                "${statusGlyph(status)}  ${f.path}"
            }
        val body =
            buildString {
                appendLine("Files:")
                appendLine(listing)
                if (diff.isNotBlank()) {
                    appendLine()
                    appendLine("Diff:")
                    appendLine(diff)
                }
            }
        return AiRequest(
            system = SYSTEM,
            messages = listOf(AiMessage.user(body)),
            temperature = 0.2f,
            maxTokens = 400,
            timeoutMs = 45_000,
        )
    }

    /**
     * Strip the wrappers models add around a message: code fences, a leading
     * "commit message:" label, and surrounding quotes.
     */
    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
        }
        text = text.removePrefix("Commit message:").removePrefix("commit message:").trim()
        if (text.length > 1 && text.startsWith('"') && text.endsWith('"')) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text.trimEnd()
    }
}
