package bench

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 外部コマンド実行結果を表す。
 *
 * @param exitCode プロセス終了コード。タイムアウト時は `null`。
 * @param output 標準出力と標準エラーを統合した出力。
 * @param timedOut タイムアウト発生有無。
 */
data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean
)

/**
 * 外部コマンドを実行し、標準出力と終了状態を取得する。
 *
 * @param command 実行するコマンドと引数の配列。
 * @param timeout コマンド完了を待機する最大時間。
 * @param workingDirectory コマンドを実行する作業ディレクトリ。`null` の場合は現在ディレクトリを使う。
 * @return 実行結果（終了コード、出力、タイムアウト有無）。
 */
fun runCommand(command: List<String>, timeout: Duration, workingDirectory: Path?): CommandResult {
    val processBuilder = ProcessBuilder(command)
    if (workingDirectory != null) {
        processBuilder.directory(workingDirectory.toFile())
    }
    processBuilder.redirectErrorStream(true)
    val process = processBuilder.start()
    // We never send stdin to child processes; close it so commands won't wait for additional input.
    process.outputStream.close()

    val output = StringBuilder()
    val readerThread = thread(start = true, isDaemon = true, name = "process-output-reader") {
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                output.appendLine(line)
            }
        }
    }

    val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        readerThread.join(1_000)
        return CommandResult(exitCode = null, output = output.toString(), timedOut = true)
    }

    readerThread.join(1_000)
    return CommandResult(exitCode = process.exitValue(), output = output.toString(), timedOut = false)
}
