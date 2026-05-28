package bench

import java.time.Duration
import java.time.Instant

const val DEFAULT_PING_MESSAGE = "Hello. Reply with exactly: HELLO"

data class ModelConnectivityResult(
    val model: String,
    val modelId: String,
    val ok: Boolean,
    val latencyMs: Long,
    val output: String,
    val errorMessage: String?
)

class ModelConnectivityRunner(
    private val config: BenchmarkConfig,
    private val message: String = DEFAULT_PING_MESSAGE
) {
    fun run(): List<ModelConnectivityResult> {
        require(config.models.isNotEmpty()) { "At least one model is required" }
        require(config.opencodeTimeoutSec > 0) { "--opencode-timeout-sec must be > 0" }
        require(message.isNotBlank()) { "--ping-message must not be blank" }

        return config.models.map { model ->
            val startedAt = Instant.now()
            val result = runCommand(
                listOf(config.opencodeBin, "run", "-m", model.modelId, "--", message),
                Duration.ofSeconds(config.opencodeTimeoutSec),
                null
            )
            val latencyMs = Duration.between(startedAt, Instant.now()).toMillis()
            val trimmedOutput = result.output.trim()

            when {
                result.timedOut -> ModelConnectivityResult(
                    model = model.name,
                    modelId = model.modelId,
                    ok = false,
                    latencyMs = latencyMs,
                    output = result.output,
                    errorMessage = "Timed out after ${config.opencodeTimeoutSec}s"
                )

                result.exitCode != 0 -> ModelConnectivityResult(
                    model = model.name,
                    modelId = model.modelId,
                    ok = false,
                    latencyMs = latencyMs,
                    output = result.output,
                    errorMessage = "Model call failed with exit code ${result.exitCode}"
                )

                trimmedOutput.isBlank() -> ModelConnectivityResult(
                    model = model.name,
                    modelId = model.modelId,
                    ok = false,
                    latencyMs = latencyMs,
                    output = result.output,
                    errorMessage = "Model returned empty output"
                )

                else -> ModelConnectivityResult(
                    model = model.name,
                    modelId = model.modelId,
                    ok = true,
                    latencyMs = latencyMs,
                    output = result.output,
                    errorMessage = null
                )
            }
        }
    }
}
