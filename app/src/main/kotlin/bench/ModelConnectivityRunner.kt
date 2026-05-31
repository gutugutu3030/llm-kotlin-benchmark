package bench

import java.time.Duration
import java.time.Instant

const val DEFAULT_PING_MESSAGE = "Hello. Reply with exactly: HELLO"

/**
 * モデル疎通チェック 1 件分の結果。
 *
 * @param model モデル名。
 * @param modelId モデル ID。
 * @param ok 疎通チェックが成功したかどうか。
 * @param latencyMs 呼び出しレイテンシ（ミリ秒）。
 * @param output モデルの生出力。
 * @param errorMessage 失敗時のエラーメッセージ。
 */
data class ModelConnectivityResult(
    val model: String,
    val modelId: String,
    val ok: Boolean,
    val latencyMs: Long,
    val output: String,
    val errorMessage: String?
)

/**
 * モデルへの簡易プロンプト送信による疎通確認を実行する。
 *
 * @param config 実行設定。
 * @param message モデルへ送る確認メッセージ。
 */
class ModelConnectivityRunner(
    private val config: BenchmarkConfig,
    private val message: String = DEFAULT_PING_MESSAGE
) {
    /**
     * 設定された全モデルの疎通チェックを実行する。
     *
     * @return モデルごとの疎通結果一覧。
     */
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
