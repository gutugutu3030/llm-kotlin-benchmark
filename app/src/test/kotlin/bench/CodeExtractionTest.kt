package bench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeExtractionTest {
    private val task = HumanEvalTask(
        taskId = "HumanEval_kotlin/999",
        prompt = "fun foo(x: Int): Int {\n",
        entryPoint = "foo",
        test = "fun main() { check(foo(2) == 3) }",
        description = "dummy",
        language = "kotlin"
    )

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

    @Test
    fun appendsContinuationWhenOnlyBodyIsReturned() {
        val output = "return x + 1\n}"
        val source = CodeExtraction.buildCompilationSource(task, output)

        assertTrue(source.contains("fun foo(x: Int): Int {\nreturn x + 1\n}"))
        assertTrue(source.contains("fun main() { check(foo(2) == 3) }"))
    }
}
