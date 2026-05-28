package bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BenchmarkRunnerTest {
    @field:TempDir
    lateinit var tempDir: Path

    private val mapper = jacksonObjectMapper()

    @Test
    fun passesMultilinePromptAsSingleMessageArgument() {
        val capturedPromptPath = tempDir.resolve("captured-prompt.txt")
        val fakeOpencode = tempDir.resolve("fake-opencode.sh")
        val datasetPath = tempDir.resolve("dataset.jsonl")
        val outputDir = tempDir.resolve("results")
        outputDir.createDirectories()

        val task = HumanEvalTask(
            taskId = "HumanEval_kotlin/1",
            prompt = "fun foo(x: Int): Int {\n    // implement\n}\n",
            entryPoint = "foo",
            test = "fun main() { check(foo(2) == 3) }",
            description = "dummy",
            language = "kotlin"
        )
        datasetPath.writeText(mapper.writeValueAsString(task) + "\n")

        val escapedCapturePath = capturedPromptPath.toString().replace("'", "'\"'\"'")
        fakeOpencode.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            [ "$1" = "run" ] || exit 10
            shift
            [ "$1" = "-m" ] || exit 11
            shift 2
            [ "$1" = "--" ] || exit 12
            shift
            [ "$#" -eq 1 ] || exit 13
            printf '%s' "$1" > '$escapedCapturePath'
            echo "forced failure"
            exit 1
            """.trimIndent() + "\n"
        )
        fakeOpencode.toFile().setExecutable(true)

        val config = BenchmarkConfig(
            seed = 42L,
            problemCount = 1,
            datasetUrl = "unused",
            datasetCachePath = datasetPath,
            refreshDataset = false,
            outputDir = outputDir,
            opencodeBin = fakeOpencode.toString(),
            kotlincBin = "kotlinc",
            javaBin = "java",
            opencodeTimeoutSec = 5L,
            compileTimeoutSec = 5L,
            executeTimeoutSec = 5L,
            copilotMaxCalls = 10,
            models = listOf(ModelConfig(name = "fake", modelId = "fake/model"))
        )

        BenchmarkRunner(config).run()

        val capturedPrompt = capturedPromptPath.readText()
        assertTrue(capturedPrompt.contains("Complete the Kotlin function for the following task."))
        assertTrue(capturedPrompt.contains("Task:"))
        assertTrue(capturedPrompt.contains(task.prompt.trim()))
        assertTrue(capturedPrompt.contains('\n'))
    }

    @Test
    fun marksTimedOutModelInvocation() {
        val fakeOpencode = tempDir.resolve("slow-opencode.sh")
        val datasetPath = tempDir.resolve("dataset.jsonl")
        val outputDir = tempDir.resolve("results")
        outputDir.createDirectories()

        val task = HumanEvalTask(
            taskId = "HumanEval_kotlin/1",
            prompt = "fun foo(x: Int): Int {\n",
            entryPoint = "foo",
            test = "fun main() { check(foo(2) == 3) }",
            description = "dummy",
            language = "kotlin"
        )
        datasetPath.writeText(mapper.writeValueAsString(task) + "\n")

        fakeOpencode.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            while :; do :; done
            """.trimIndent() + "\n"
        )
        fakeOpencode.toFile().setExecutable(true)

        val config = BenchmarkConfig(
            seed = 42L,
            problemCount = 1,
            datasetUrl = "unused",
            datasetCachePath = datasetPath,
            refreshDataset = false,
            outputDir = outputDir,
            opencodeBin = fakeOpencode.toString(),
            kotlincBin = "kotlinc",
            javaBin = "java",
            opencodeTimeoutSec = 1L,
            compileTimeoutSec = 5L,
            executeTimeoutSec = 5L,
            copilotMaxCalls = 10,
            models = listOf(ModelConfig(name = "fake", modelId = "fake/model"))
        )

        val artifacts = BenchmarkRunner(config).run()
        val report = mapper.readTree(artifacts.jsonPath.toFile())
        val errorMessage = report["records"][0]["error_message"].asText()

        assertEquals("Model call timed out after 1s", errorMessage)
    }
}
