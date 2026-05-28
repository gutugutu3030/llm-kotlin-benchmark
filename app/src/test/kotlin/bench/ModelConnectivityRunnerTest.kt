package bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ModelConnectivityRunnerTest {
    @field:TempDir
    lateinit var tempDir: Path

    @Test
    fun succeedsWhenModelReturnsNonEmptyOutput() {
        val fakeOpencode = tempDir.resolve("fake-opencode-success.sh")
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
            printf 'HELLO'
            """.trimIndent() + "\n"
        )
        fakeOpencode.toFile().setExecutable(true)

        val runner = ModelConnectivityRunner(
            config = baseConfig(fakeOpencode),
            message = "Hello"
        )
        val result = runner.run().single()

        assertTrue(result.ok)
        assertEquals(null, result.errorMessage)
        assertTrue(result.output.contains("HELLO"))
    }

    @Test
    fun failsWhenModelReturnsEmptyOutput() {
        val fakeOpencode = tempDir.resolve("fake-opencode-empty.sh")
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
            exit 0
            """.trimIndent() + "\n"
        )
        fakeOpencode.toFile().setExecutable(true)

        val runner = ModelConnectivityRunner(
            config = baseConfig(fakeOpencode),
            message = "Hello"
        )
        val result = runner.run().single()

        assertFalse(result.ok)
        assertEquals("Model returned empty output", result.errorMessage)
    }

    private fun baseConfig(opencodePath: Path): BenchmarkConfig {
        return BenchmarkConfig(
            seed = 42L,
            problemCount = 1,
            datasetUrl = "unused",
            datasetCachePath = tempDir.resolve("unused-dataset.jsonl"),
            refreshDataset = false,
            outputDir = tempDir.resolve("unused-results"),
            opencodeBin = opencodePath.toString(),
            kotlincBin = "kotlinc",
            javaBin = "java",
            opencodeTimeoutSec = 5L,
            compileTimeoutSec = 5L,
            executeTimeoutSec = 5L,
            copilotMaxCalls = 10,
            models = listOf(ModelConfig(name = "fake", modelId = "fake/model"))
        )
    }
}
