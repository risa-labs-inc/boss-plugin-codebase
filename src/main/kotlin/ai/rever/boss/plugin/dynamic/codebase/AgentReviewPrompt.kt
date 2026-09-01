package ai.rever.boss.plugin.dynamic.codebase

import ai.rever.boss.plugin.api.GitFileStatusData

/**
 * Prompt builder for the Agent Review feature (P7).
 *
 * Pure function so it is unit-testable: takes the changed-file listing plus
 * whatever diff text was fetched (capped by the caller) and renders the
 * review brief the Atlas agent receives.
 */
object AgentReviewPrompt {

    /** Inline diffs up to this many characters; above it, list the files and
     * point the agent at the git_diff tools instead (keeps the prompt bounded).
     */
    const val INLINE_DIFF_BUDGET = 15_000

    fun build(
        projectPath: String,
        staged: List<GitFileStatusData>,
        unstaged: List<GitFileStatusData>,
        diffText: String?,
        /** Free text from the review panel; empty when the user gave none. */
        instructions: String = "",
        /** Quick keeps it to a short pass; Deep asks for a thorough one. */
        deep: Boolean = false,
        /** The ref the change is being reviewed toward, e.g. "main". Context for
         * the agent only - the attached diff is the uncommitted change, never
         * a diff against this ref.
         */
        baseRef: String = "",
    ): String {
        val sb = StringBuilder()
        if (baseRef.isNotBlank()) {
            sb.appendLine(
                "Review these uncommitted changes (index + working tree) ahead of " +
                    "landing them on `$baseRef`.",
            )
        } else {
            sb.appendLine("Review the uncommitted changes in this project (index + working tree vs HEAD).")
        }
        sb.appendLine()
        sb.appendLine("Focus: correctness, bugs, security, naming, and whether the change does what it claims.")
        if (deep) {
            sb.appendLine(
                "Go deep: trace the affected call paths, consider concurrency, error handling, " +
                    "and edge cases, and check whether tests cover the change.",
            )
        } else {
            sb.appendLine("Keep it quick: surface the issues that matter most, not an exhaustive audit.")
        }
        sb.appendLine("Be specific: cite file:line, say what is wrong, suggest a fix.")
        sb.appendLine("Do not apply any edits; this is a review only.")
        if (instructions.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("The reviewer also asked:")
            sb.appendLine(instructions.trim())
        }
        sb.appendLine()

        if (staged.isEmpty() && unstaged.isEmpty()) {
            sb.appendLine("There are no uncommitted changes in $projectPath.")
            return sb.toString().trimEnd()
        }

        if (staged.isNotEmpty()) {
            sb.appendLine("Staged files:")
            staged.forEach { sb.appendLine("  [${it.indexStatus?.name ?: "?"}] ${it.path}") }
            sb.appendLine()
        }
        if (unstaged.isNotEmpty()) {
            sb.appendLine("Working-tree changes:")
            unstaged.forEach { sb.appendLine("  [${it.workTreeStatus?.name ?: "?"}] ${it.path}") }
            sb.appendLine()
        }

        if (diffText.isNullOrBlank()) {
            sb.appendLine("The diff could not be fetched inline; use the git_diff / git_diff_all tools to read it.")
        } else if (diffText.length >= INLINE_DIFF_BUDGET) {
            sb.appendLine(
                "The full diff exceeds the inline budget (${diffText.length} chars); " +
                    "use the git_diff / git_diff_ref tools to read the individual file diffs.",
            )
        } else {
            sb.appendLine("Full diff:")
            sb.appendLine("```diff")
            sb.appendLine(diffText)
            sb.appendLine("```")
        }
        return sb.toString().trimEnd()
    }
}
