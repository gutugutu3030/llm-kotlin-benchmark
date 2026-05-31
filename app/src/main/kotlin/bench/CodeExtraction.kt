package bench

/**
 * モデル出力から Kotlin ソースを抽出し、評価可能な完全ソースを組み立てるユーティリティ。
 */
object CodeExtraction {
    /**
     * 問題情報とモデル出力から、コンパイル用ソースコードを生成する。
     *
     * @param task 対象問題の定義。
     * @param rawModelOutput モデルの生出力。
     * @return テストコードを連結したコンパイル用 Kotlin ソース。
     */
    fun buildCompilationSource(task: HumanEvalTask, rawModelOutput: String): String {
        val cleaned = sanitizeModelOutput(rawModelOutput, task.entryPoint)
        val functionCode = extractSubmission(cleaned, task.entryPoint) ?: appendToPrompt(task.prompt, cleaned)
        val balancedFunctionCode = closeUnbalancedBraces(functionCode)
        return buildString {
            append(balancedFunctionCode.trim())
            append("\n\n")
            append(task.test.trim())
            append('\n')
        }
    }

    /**
     * プロンプト末尾に続きコードを接続する。
     *
     * @param prompt 元の問題プロンプト。
     * @param continuation モデルが返した続きコード。
     * @return 結合後のコード文字列。
     */
    private fun appendToPrompt(prompt: String, continuation: String): String {
        return if (continuation.isBlank()) {
            prompt
        } else {
            prompt + continuation
        }
    }

    /**
     * モデル出力から不要情報を除去し、コード抽出しやすい形へ正規化する。
     *
     * @param rawOutput モデルの生出力。
     * @param entryPoint 対象関数名。
     * @return 正規化後のコード候補文字列。
     */
    private fun sanitizeModelOutput(rawOutput: String, entryPoint: String): String {
        val withoutAnsi = stripAnsi(rawOutput)
        val candidate = extractCodeFence(withoutAnsi, entryPoint) ?: withoutAnsi
        val withoutFences = stripMarkdownFences(candidate)
        return removeTranscriptLines(withoutFences).trim()
    }

    /**
     * ANSI エスケープシーケンスを除去する。
     *
     * @param text 変換対象文字列。
     * @return ANSI 制御文字を除去した文字列。
     */
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("""\u001B\[[0-9;]*[ -/]*[@-~]"""), "")
    }

    /**
     * Markdown コードフェンス内から最適なコードブロックを選択する。
     *
     * @param text コードフェンスを含む可能性がある文字列。
     * @param entryPoint 対象関数名。
     * @return 選択されたコードブロック。存在しない場合は `null`。
     */
    private fun extractCodeFence(text: String, entryPoint: String): String? {
        val blockRegex = Regex("""```(?:[a-zA-Z0-9_-]+)?\s*([\s\S]*?)```""")
        val blocks = blockRegex.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (blocks.isEmpty()) return null

        val signatureRegex = Regex("""fun\s+${Regex.escape(entryPoint)}\s*\(""")
        return blocks.firstOrNull { signatureRegex.containsMatchIn(it) } ?: blocks.last()
    }

    /**
     * CLI トランスクリプト由来のノイズ行を除去する。
     *
     * @param text 変換対象文字列。
     * @return ノイズ行を除去した文字列。
     */
    private fun removeTranscriptLines(text: String): String {
        return text
            .lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("> ") ||
                    trimmed.startsWith("← ") ||
                    trimmed.startsWith("✗ ") ||
                    trimmed.startsWith("✱ ") ||
                    trimmed.startsWith("Error:") ||
                    trimmed == "Wrote file successfully."
            }
            .joinToString("\n")
    }

    /**
     * Markdown のフェンス記法そのものを除去する。
     *
     * @param text 変換対象文字列。
     * @return フェンス除去後の文字列。
     */
    private fun stripMarkdownFences(text: String): String {
        return text
            .replace(Regex("```[a-zA-Z0-9_-]*"), "")
            .replace("```", "")
            .trim()
    }

    /**
     * モデル出力から対象関数以降の提出コードを抽出する。
     *
     * @param output 正規化済みモデル出力。
     * @param entryPoint 対象関数名。
     * @return 抽出された提出コード。見つからない場合は `null`。
     */
    private fun extractSubmission(output: String, entryPoint: String): String? {
        if (output.isBlank()) return null
        val signatureRegex = Regex("""fun\s+${Regex.escape(entryPoint)}\s*\(""")
        val match = signatureRegex.find(output) ?: return null
        val before = output.substring(0, match.range.first)
        val preservedPrefix = before
            .lines()
            .filter { line ->
                val trimmed = line.trimStart()
                line.isBlank() ||
                    trimmed.startsWith("import ") ||
                    trimmed.startsWith("package ") ||
                    trimmed.startsWith("//") ||
                    trimmed.startsWith("/*") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("*/")
            }
            .joinToString("\n")
            .trimEnd()
        val body = output.substring(match.range.first).trim()
        return if (preservedPrefix.isBlank()) body else "$preservedPrefix\n$body"
    }

    /**
     * 開き括弧と閉じ括弧の数を合わせ、不足分を補完する。
     *
     * @param code 補正対象コード。
     * @return 括弧バランスを補正したコード。
     */
    private fun closeUnbalancedBraces(code: String): String {
        val openBraceCount = code.count { it == '{' }
        val closeBraceCount = code.count { it == '}' }
        if (openBraceCount <= closeBraceCount) return code
        return buildString {
            append(code.trimEnd())
            append('\n')
            append("}".repeat(openBraceCount - closeBraceCount))
        }
    }
}
