package bench

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Comparator
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.random.Random

/**
 * ベンチマーク実行時の設定値を保持する。
 *
 * @param seed 問題サンプリングで使用する乱数シード。
 * @param problemCount 評価対象として抽出する問題数。
 * @param datasetUrl 問題データセットの取得元 URL。
 * @param datasetCachePath データセットのローカルキャッシュ先パス。
 * @param refreshDataset 既存キャッシュがあっても再取得するかどうか。
 * @param outputDir レポート出力先ディレクトリ。
 * @param opencodeBin `opencode` 実行ファイルのパス。
 * @param kotlincBin `kotlinc` 実行ファイルのパス。
 * @param javaBin `java` 実行ファイルのパス。
 * @param opencodeTimeoutSec モデル呼び出しのタイムアウト秒数。
 * @param compileTimeoutSec Kotlin コンパイルのタイムアウト秒数。
 * @param executeTimeoutSec テスト実行のタイムアウト秒数。
 * @param copilotMaxCalls `github-copilot` へ許可する最大呼び出し回数。
 * @param models 実行対象モデルの設定一覧。
 */
data class BenchmarkConfig(
    val seed: Long,
    val problemCount: Int,
    val datasetUrl: String,
    val datasetCachePath: Path,
    val refreshDataset: Boolean,
    val outputDir: Path,
    val opencodeBin: String,
    val kotlincBin: String,
    val javaBin: String,
    val opencodeTimeoutSec: Long,
    val compileTimeoutSec: Long,
    val executeTimeoutSec: Long,
    val copilotMaxCalls: Int,
    val models: List<ModelConfig>
)

/**
 * 単一モデルの識別情報を表す。
 *
 * @param name モデルの表示名。
 * @param modelId `opencode` に渡すモデル ID。
 */
data class ModelConfig(
    val name: String,
    val modelId: String
)

/**
 * HumanEval の 1 問分データを表す。
 *
 * @param taskId 問題 ID。
 * @param prompt 問題本文および関数シグネチャ。
 * @param entryPoint 実装対象となる関数名。
 * @param test 実行するテストコード。
 * @param description 問題説明文。
 * @param language 問題の言語名。
 * @param canonicalSolution 参照用の正解コード。
 */
data class HumanEvalTask(
    @param:JsonProperty("task_id") val taskId: String,
    val prompt: String,
    @param:JsonProperty("entry_point") val entryPoint: String,
    val test: String,
    val description: String,
    val language: String,
    @param:JsonProperty("canonical_solution") val canonicalSolution: String? = null
)

/**
 * モデル単位の集計結果を表す。
 *
 * @param model モデル名。
 * @param passCount 正答数。
 * @param totalCount 総問題数。
 * @param passAt1 pass@1 指標。
 * @param averageE2eMs 平均 E2E 時間（ミリ秒）。
 */
data class ModelSummary(
    val model: String,
    val passCount: Int,
    val totalCount: Int,
    val passAt1: Double,
    val averageE2eMs: Double
)

/**
 * ベンチマーク出力物のパスと集計を保持する。
 *
 * @param jsonPath JSON レポートの出力先パス。
 * @param csvPath CSV レポートの出力先パス。
 * @param summaryByModel モデル別集計結果。
 */
data class BenchmarkArtifacts(
    val jsonPath: Path,
    val csvPath: Path,
    val summaryByModel: List<ModelSummary>
)

/**
 * モデル呼び出しの生結果を保持する内部構造体。
 *
 * @param rawOutput モデルの生出力文字列。
 * @param modelDurationMs モデル呼び出し時間（ミリ秒）。
 * @param timedOut タイムアウトしたかどうか。
 * @param errorMessage エラーメッセージ。
 */
private data class ModelInvocation(
    val rawOutput: String,
    val modelDurationMs: Long,
    val timedOut: Boolean,
    val errorMessage: String?
)

/**
 * コンパイルと実行評価の結果を保持する内部構造体。
 *
 * @param passed テストが成功したかどうか。
 * @param compileDurationMs コンパイル時間（ミリ秒）。
 * @param executeDurationMs 実行時間（ミリ秒）。
 * @param compileOutput コンパイル時の出力。
 * @param executeOutput 実行時の出力。
 * @param errorMessage エラーメッセージ。
 */
private data class EvaluationResult(
    val passed: Boolean,
    val compileDurationMs: Long,
    val executeDurationMs: Long,
    val compileOutput: String,
    val executeOutput: String,
    val errorMessage: String?
)

/**
 * 問題 1 件ごとの評価レコード。
 *
 * @param model モデル名。
 * @param modelId モデル ID。
 * @param taskId 問題 ID。
 * @param passed テスト成功可否。
 * @param e2eMs エンドツーエンド時間（ミリ秒）。
 * @param modelMs モデル呼び出し時間（ミリ秒）。
 * @param compileMs コンパイル時間（ミリ秒）。
 * @param executeMs 実行時間（ミリ秒）。
 * @param errorMessage エラーメッセージ。
 * @param modelOutput モデルの生出力。
 */
private data class TaskRecord(
    val model: String,
    @param:JsonProperty("model_id") val modelId: String,
    @param:JsonProperty("task_id") val taskId: String,
    val passed: Boolean,
    @param:JsonProperty("e2e_ms") val e2eMs: Long,
    @param:JsonProperty("model_ms") val modelMs: Long,
    @param:JsonProperty("compile_ms") val compileMs: Long,
    @param:JsonProperty("execute_ms") val executeMs: Long,
    @param:JsonProperty("error_message") val errorMessage: String?,
    @param:JsonProperty("model_output") val modelOutput: String
)

/**
 * 永続化するベンチマークレポート全体。
 *
 * @param createdAt レポート生成時刻。
 * @param seed 問題サンプリングで使用した乱数シード。
 * @param problemCount 評価した問題数。
 * @param datasetUrl 使用したデータセット URL。
 * @param datasetCachePath 使用したキャッシュパス。
 * @param summaryByModel モデル別集計。
 * @param records 問題ごとの詳細レコード。
 */
private data class BenchmarkReport(
    @param:JsonProperty("created_at") val createdAt: String,
    val seed: Long,
    @param:JsonProperty("problem_count") val problemCount: Int,
    @param:JsonProperty("dataset_url") val datasetUrl: String,
    @param:JsonProperty("dataset_cache_path") val datasetCachePath: String,
    @param:JsonProperty("summary_by_model") val summaryByModel: List<ModelSummary>,
    @param:JsonProperty("records") val records: List<TaskRecord>
)

/**
 * HumanEval ベンチマークの実行を担当する。
 *
 * @param config 実行設定。
 * @param mapper JSON シリアライズ/デシリアライズに使う ObjectMapper。
 */
class BenchmarkRunner(
    private val config: BenchmarkConfig,
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT)
) {
    /**
     * ベンチマークを実行し、JSON/CSV と集計結果を返す。
     *
     * @return 出力ファイルパスとモデル別集計を含む成果物情報。
     */
    fun run(): BenchmarkArtifacts {
        require(config.problemCount > 0) { "--count must be > 0" }
        require(config.models.isNotEmpty()) { "At least one model is required" }
        require(config.opencodeTimeoutSec > 0) { "--opencode-timeout-sec must be > 0" }
        require(config.compileTimeoutSec > 0) { "--compile-timeout-sec must be > 0" }
        require(config.executeTimeoutSec > 0) { "--execute-timeout-sec must be > 0" }
        val hasCopilot = config.models.any { it.modelId.startsWith("github-copilot/") }
        if (hasCopilot && config.problemCount > config.copilotMaxCalls) {
            throw IllegalArgumentException(
                "Problem count ${config.problemCount} exceeds copilot max calls ${config.copilotMaxCalls}"
            )
        }

        val datasetPath = ensureDataset()
        val allTasks = loadTasks(datasetPath)
        if (config.problemCount > allTasks.size) {
            throw IllegalArgumentException("Requested ${config.problemCount} tasks but dataset has only ${allTasks.size}")
        }

        val selectedTasks = allTasks.shuffled(Random(config.seed)).take(config.problemCount)
        val records = mutableListOf<TaskRecord>()

        for (model in config.models) {
            for (task in selectedTasks) {
                val startedAt = Instant.now()
                val invocation = invokeModel(model, task)

                val record = if (invocation.errorMessage != null || invocation.timedOut) {
                    TaskRecord(
                        model = model.name,
                        modelId = model.modelId,
                        taskId = task.taskId,
                        passed = false,
                        e2eMs = Duration.between(startedAt, Instant.now()).toMillis(),
                        modelMs = invocation.modelDurationMs,
                        compileMs = 0L,
                        executeMs = 0L,
                        errorMessage = invocation.errorMessage ?: "Model call timed out",
                        modelOutput = invocation.rawOutput
                    )
                } else {
                    val evaluation = evaluateTask(model, task, invocation.rawOutput)
                    TaskRecord(
                        model = model.name,
                        modelId = model.modelId,
                        taskId = task.taskId,
                        passed = evaluation.passed,
                        e2eMs = Duration.between(startedAt, Instant.now()).toMillis(),
                        modelMs = invocation.modelDurationMs,
                        compileMs = evaluation.compileDurationMs,
                        executeMs = evaluation.executeDurationMs,
                        errorMessage = evaluation.errorMessage,
                        modelOutput = invocation.rawOutput
                    )
                }
                records.add(record)
            }
        }

        val summaries = summarize(records, config.problemCount)
        val artifacts = writeArtifacts(
            BenchmarkReport(
                createdAt = OffsetDateTime.now().toString(),
                seed = config.seed,
                problemCount = config.problemCount,
                datasetUrl = config.datasetUrl,
                datasetCachePath = config.datasetCachePath.toString(),
                summaryByModel = summaries,
                records = records
            )
        )

        return BenchmarkArtifacts(
            jsonPath = artifacts.first,
            csvPath = artifacts.second,
            summaryByModel = summaries
        )
    }

    /**
     * データセットの存在を保証し、必要に応じてダウンロードする。
     *
     * @return 利用可能なデータセットファイルのパス。
     */
    private fun ensureDataset(): Path {
        val cachePath = config.datasetCachePath
        cachePath.parent?.createDirectories()
        if (cachePath.exists() && !config.refreshDataset) {
            return cachePath
        }

        java.net.URI(config.datasetUrl).toURL().openStream().use { input ->
            cachePath.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cachePath
    }

    /**
     * JSONL データセットを読み込み、問題一覧に変換する。
     *
     * @param datasetPath 読み込むデータセットのパス。
     * @return 読み込まれた問題一覧。
     */
    private fun loadTasks(datasetPath: Path): List<HumanEvalTask> {
        return datasetPath.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() }
                .map { mapper.readValue<HumanEvalTask>(it) }
                .toList()
        }
    }

    /**
     * 指定モデルに対して単一問題の生成を依頼する。
     *
     * @param model 呼び出し対象モデル。
     * @param task 生成対象の問題。
     * @return モデル呼び出し結果。
     */
    private fun invokeModel(model: ModelConfig, task: HumanEvalTask): ModelInvocation {
        val prompt = buildPrompt(task)
        val startedAt = Instant.now()
        val command = listOf(config.opencodeBin, "run", "-m", model.modelId, "--", prompt)
        val result = runCommand(command, Duration.ofSeconds(config.opencodeTimeoutSec), null)
        val modelMs = Duration.between(startedAt, Instant.now()).toMillis()

        if (result.timedOut) {
            return ModelInvocation(
                rawOutput = result.output,
                modelDurationMs = modelMs,
                timedOut = true,
                errorMessage = "Model call timed out after ${config.opencodeTimeoutSec}s"
            )
        }
        if (result.exitCode != 0) {
            return ModelInvocation(
                rawOutput = result.output,
                modelDurationMs = modelMs,
                timedOut = false,
                errorMessage = "Model call failed with exit code ${result.exitCode}"
            )
        }
        return ModelInvocation(
            rawOutput = result.output.trim(),
            modelDurationMs = modelMs,
            timedOut = false,
            errorMessage = null
        )
    }

    /**
     * モデルへ渡すプロンプト文字列を構築する。
     *
     * @param task 対象問題。
     * @return 生成依頼用プロンプト。
     */
    private fun buildPrompt(task: HumanEvalTask): String {
        return """
            Complete the Kotlin function for the following task.
            Output Kotlin code only (no markdown, no prose).
            Do not call tools or read/write files.
            Keep the function signature and name intact.
            Return only the full function definition.

            Task:
            ${task.prompt}
        """.trimIndent()
    }

    /**
     * モデル出力をコンパイル/実行して合否を判定する。
     *
     * @param model 評価対象モデル。
     * @param task 評価対象問題。
     * @param modelOutput モデル出力の生コード。
     * @return 評価結果。
     */
    private fun evaluateTask(model: ModelConfig, task: HumanEvalTask, modelOutput: String): EvaluationResult {
        val workDir = Files.createTempDirectory("humaneval-${sanitize(model.name)}-${sanitize(task.taskId)}-")
        try {
            val source = CodeExtraction.buildCompilationSource(task, modelOutput)
            val sourcePath = workDir.resolve("Main.kt")
            val jarPath = workDir.resolve("program.jar")
            sourcePath.writeText(source)

            val compileStart = Instant.now()
            val compileResult = runCommand(
                listOf(config.kotlincBin, sourcePath.toString(), "-include-runtime", "-d", jarPath.toString()),
                Duration.ofSeconds(config.compileTimeoutSec),
                workDir
            )
            val compileMs = Duration.between(compileStart, Instant.now()).toMillis()
            if (compileResult.timedOut) {
                return EvaluationResult(
                    passed = false,
                    compileDurationMs = compileMs,
                    executeDurationMs = 0L,
                    compileOutput = compileResult.output,
                    executeOutput = "",
                    errorMessage = "Compilation timed out after ${config.compileTimeoutSec}s"
                )
            }
            if (compileResult.exitCode != 0) {
                return EvaluationResult(
                    passed = false,
                    compileDurationMs = compileMs,
                    executeDurationMs = 0L,
                    compileOutput = compileResult.output,
                    executeOutput = "",
                    errorMessage = "Compilation failed (exit ${compileResult.exitCode})"
                )
            }

            val executeStart = Instant.now()
            val executeResult = runCommand(
                listOf(config.javaBin, "-jar", jarPath.toString()),
                Duration.ofSeconds(config.executeTimeoutSec),
                workDir
            )
            val executeMs = Duration.between(executeStart, Instant.now()).toMillis()
            if (executeResult.timedOut) {
                return EvaluationResult(
                    passed = false,
                    compileDurationMs = compileMs,
                    executeDurationMs = executeMs,
                    compileOutput = compileResult.output,
                    executeOutput = executeResult.output,
                    errorMessage = "Execution timed out after ${config.executeTimeoutSec}s"
                )
            }
            return EvaluationResult(
                passed = executeResult.exitCode == 0,
                compileDurationMs = compileMs,
                executeDurationMs = executeMs,
                compileOutput = compileResult.output,
                executeOutput = executeResult.output,
                errorMessage = if (executeResult.exitCode == 0) null else "Tests failed (exit ${executeResult.exitCode})"
            )
        } finally {
            deleteDirectory(workDir)
        }
    }

    /**
     * 問題ごとの結果をモデル単位で集計する。
     *
     * @param records 集計対象レコード一覧。
     * @param taskCount 評価問題数。
     * @return モデル別集計結果。
     */
    private fun summarize(records: List<TaskRecord>, taskCount: Int): List<ModelSummary> {
        return config.models.map { model ->
            val modelRecords = records.filter { it.model == model.name }
            val passCount = modelRecords.count { it.passed }
            val avgE2e = if (modelRecords.isEmpty()) 0.0 else modelRecords.map { it.e2eMs }.average()
            ModelSummary(
                model = model.name,
                passCount = passCount,
                totalCount = taskCount,
                passAt1 = if (taskCount == 0) 0.0 else passCount.toDouble() / taskCount.toDouble(),
                averageE2eMs = avgE2e
            )
        }
    }

    /**
     * レポートを JSON/CSV として出力し、出力先パスを返す。
     *
     * @param report 出力対象レポート。
     * @return JSON パスと CSV パスのペア。
     */
    private fun writeArtifacts(report: BenchmarkReport): Pair<Path, Path> {
        config.outputDir.createDirectories()
        val tag = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now())
        val jsonPath = config.outputDir.resolve("benchmark-$tag.json")
        val csvPath = config.outputDir.resolve("benchmark-$tag.csv")

        mapper.writeValue(jsonPath.toFile(), report)
        csvPath.writeText(buildCsv(report.records))
        return jsonPath to csvPath
    }

    /**
     * レコード一覧を CSV 文字列へ変換する。
     *
     * @param records 変換対象レコード一覧。
     * @return CSV 形式の文字列。
     */
    private fun buildCsv(records: List<TaskRecord>): String {
        val header = listOf(
            "model",
            "model_id",
            "task_id",
            "passed",
            "e2e_ms",
            "model_ms",
            "compile_ms",
            "execute_ms",
            "error_message"
        )
        val body = records.joinToString("\n") { record ->
            listOf(
                record.model,
                record.modelId,
                record.taskId,
                record.passed.toString(),
                record.e2eMs.toString(),
                record.modelMs.toString(),
                record.compileMs.toString(),
                record.executeMs.toString(),
                record.errorMessage.orEmpty()
            ).joinToString(",") { escapeCsv(it) }
        }
        return header.joinToString(",") + "\n" + body + "\n"
    }

    /**
     * CSV セルとして安全に出力できるよう値をエスケープする。
     *
     * @param value エスケープ対象文字列。
     * @return ダブルクォートで囲まれた CSV セル文字列。
     */
    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * 一時ディレクトリ名に使えるよう文字列を正規化する。
     *
     * @param value 正規化対象文字列。
     * @return 英数字と `._-` 以外を `_` に置換した文字列。
     */
    private fun sanitize(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /**
     * ディレクトリ配下を再帰的に削除する。
     *
     * @param path 削除対象ディレクトリ。
     * @return 戻り値は使用しない（`Unit`）。
     */
    private fun deleteDirectory(path: Path) {
        if (!path.toFile().exists()) {
            return
        }
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
