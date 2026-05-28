package bench

object CodeExtraction {
    fun buildCompilationSource(task: HumanEvalTask, rawModelOutput: String): String {
        val cleaned = stripMarkdownFences(rawModelOutput).trim()
        val functionCode = extractFunction(cleaned, task.entryPoint) ?: appendToPrompt(task.prompt, cleaned)
        return buildString {
            append(functionCode.trim())
            append("\n\n")
            append(task.test.trim())
            append('\n')
        }
    }

    private fun appendToPrompt(prompt: String, continuation: String): String {
        return if (continuation.isBlank()) {
            prompt
        } else {
            prompt + continuation
        }
    }

    private fun stripMarkdownFences(text: String): String {
        return text
            .replace(Regex("```[a-zA-Z0-9_-]*"), "")
            .replace("```", "")
            .trim()
    }

    private fun extractFunction(output: String, entryPoint: String): String? {
        if (output.isBlank()) return null
        val signatureRegex = Regex("""fun\s+${Regex.escape(entryPoint)}\s*\(""")
        val match = signatureRegex.find(output) ?: return null
        val candidate = output.substring(match.range.first)
        val openBraceIndex = candidate.indexOf('{')
        if (openBraceIndex < 0) return null

        var depth = 0
        for (i in openBraceIndex until candidate.length) {
            when (candidate[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return candidate.substring(0, i + 1)
                    }
                }
            }
        }
        return candidate
    }
}
