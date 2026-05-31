package bench

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `CodeExtraction` の抽出・整形ロジックを検証するテスト。
 */
class CodeExtractionTest {
    private val task = HumanEvalTask(
        taskId = "HumanEval_kotlin/999",
        prompt = "fun foo(x: Int): Int {\n",
        entryPoint = "foo",
        test = "fun main() { check(foo(2) == 3) }",
        description = "dummy",
        language = "kotlin"
    )

    /**
     * 完全な関数定義を含む出力から対象関数が抽出されることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun extractsFunctionFromFullOutput() {
        val output = """
            ```kotlin
            fun foo(x: Int): Int {
                return x + 1
            }
            ```
        """.trimIndent()

        val source = CodeExtraction.buildCompilationSource(task, output)
        assertTrue(source.contains("fun foo(x: Int): Int {"))
        assertTrue(source.contains("return x + 1"))
        assertTrue(source.contains("fun main()"))
    }

    /**
     * 関数本体のみ返された場合にプロンプトへ追記して補完されることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun appendsContinuationWhenOnlyBodyIsReturned() {
        val output = "return x + 1"
        val source = CodeExtraction.buildCompilationSource(task, output)

        assertTrue(source.contains("fun foo(x: Int): Int {\nreturn x + 1\n}"))
        assertTrue(source.contains("fun main() { check(foo(2) == 3) }"))
    }

    /**
     * ANSI 制御文字やトランスクリプトノイズが除去されることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun removesTranscriptNoiseAndAnsiFromModelOutput() {
        val output = """
            ${'\u001B'}[0m
            > build · Qwen3.6-27B-IQ4_XS.gguf
            ${'\u001B'}[0m
            ← Write foo.kt
            Wrote file successfully.
            ```kotlin
            fun foo(x: Int): Int {
                return x + 1
            }
            ```
        """.trimIndent()

        val source = CodeExtraction.buildCompilationSource(task, output)

        assertTrue(source.contains("fun foo(x: Int): Int {"))
        assertFalse(source.contains("> build ·"))
        assertFalse(source.contains("Wrote file successfully."))
        assertFalse(source.contains("← Write"))
    }

    /**
     * エントリポイント関数の後続にある補助関数が保持されることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun preservesHelperFunctionsAfterEntryPointFunction() {
        val output = """
            ```kotlin
            fun foo(x: Int): Int {
                return helper(x)
            }

            private fun helper(x: Int): Int {
                return x + 1
            }
            ```
        """.trimIndent()

        val source = CodeExtraction.buildCompilationSource(task, output)

        assertTrue(source.contains("fun foo(x: Int): Int {"))
        assertTrue(source.contains("private fun helper(x: Int): Int {"))
        assertTrue(source.contains("return helper(x)"))
    }
}
