package bench

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `runCommand` のタイムアウト制御と入出力制御を検証するテスト。
 */
class CommandRunnerTest {
    @field:TempDir
    lateinit var tempDir: Path

    /**
     * タイムアウト時に出力読取スレッドが非デーモンとして残存しないことを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun timeoutDoesNotLeaveNonDaemonReaderThread() {
        val script = tempDir.resolve("hang-with-child.sh")
        script.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            (sleep 5) &
            while :; do sleep 1; done
            """.trimIndent() + "\n"
        )
        script.toFile().setExecutable(true)

        val result = runCommand(
            command = listOf(script.toString()),
            timeout = Duration.ofSeconds(1),
            workingDirectory = tempDir
        )

        assertTrue(result.timedOut)

        val hasNonDaemonReaderThread = Thread.getAllStackTraces()
            .keys
            .any { it.name == "process-output-reader" && it.isAlive && !it.isDaemon }
        assertFalse(hasNonDaemonReaderThread)
    }

    /**
     * 子プロセス標準入力を閉じることで EOF 待ちプロセスが終了できることを検証する。
     *
     * @return テストはアサーション成功時に完了する。
     */
    @Test
    fun closesChildStdinSoProcessCanFinish() {
        val outputFile = tempDir.resolve("stdin-close-result.txt")
        val escapedOutputPath = outputFile.toString().replace("'", "'\"'\"'")
        val script = tempDir.resolve("wait-for-stdin-eof.sh")
        script.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            cat >/dev/null
            printf 'done' > '$escapedOutputPath'
            """.trimIndent() + "\n"
        )
        script.toFile().setExecutable(true)

        val result = runCommand(
            command = listOf(script.toString()),
            timeout = Duration.ofSeconds(2),
            workingDirectory = tempDir
        )

        assertFalse(result.timedOut)
        assertTrue(outputFile.toFile().exists())
        assertTrue(outputFile.readText().contains("done"))
    }
}
