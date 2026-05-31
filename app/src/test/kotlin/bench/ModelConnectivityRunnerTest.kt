package bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * `ModelConnectivityRunner` の疎通判定ロジックを検証するテスト。
 */
class ModelConnectivityRunnerTest {
    @field:TempDir
    lateinit var tempDir: Path

    /**
     * モデルが非空文字列を返す場合に疎通成功となることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
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

    /**
     * モデルが空出力を返す場合に疎通失敗として扱うことを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
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

    /**
     * 疎通テスト用の共通設定を生成する。
     *
     * @param opencodePath テスト用 `opencode` スクリプトのパス。
     * @return 疎通テスト向けベンチマーク設定。
     */
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
