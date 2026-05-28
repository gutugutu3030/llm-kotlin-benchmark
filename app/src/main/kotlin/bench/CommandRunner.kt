package bench

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean
)

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
