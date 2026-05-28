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
import kotlin.concurrent.thread
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.random.Random

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

data class ModelConfig(
    val name: String,
    val modelId: String
)

data class HumanEvalTask(
    @param:JsonProperty("task_id") val taskId: String,
    val prompt: String,
    @param:JsonProperty("entry_point") val entryPoint: String,
    val test: String,
    val description: String,
    val language: String,
    @param:JsonProperty("canonical_solution") val canonicalSolution: String? = null
)

data class ModelSummary(
    val model: String,
    val passCount: Int,
    val totalCount: Int,
    val passAt1: Double,
    val averageE2eMs: Double
)

data class BenchmarkArtifacts(
    val jsonPath: Path,
    val csvPath: Path,
    val summaryByModel: List<ModelSummary>
)

private data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean
)

private data class ModelInvocation(
    val rawOutput: String,
    val modelDurationMs: Long,
    val timedOut: Boolean,
    val errorMessage: String?
)

private data class EvaluationResult(
    val passed: Boolean,
    val compileDurationMs: Long,
    val executeDurationMs: Long,
    val compileOutput: String,
    val executeOutput: String,
    val errorMessage: String?
)

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

private data class BenchmarkReport(
    @param:JsonProperty("created_at") val createdAt: String,
    val seed: Long,
    @param:JsonProperty("problem_count") val problemCount: Int,
    @param:JsonProperty("dataset_url") val datasetUrl: String,
    @param:JsonProperty("dataset_cache_path") val datasetCachePath: String,
    @param:JsonProperty("summary_by_model") val summaryByModel: List<ModelSummary>,
    @param:JsonProperty("records") val records: List<TaskRecord>
)

class BenchmarkRunner(
    private val config: BenchmarkConfig,
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT)
) {
    fun run(): BenchmarkArtifacts {
        require(config.problemCount > 0) { "--count must be > 0" }
        require(config.models.isNotEmpty()) { "At least one model is required" }
        val hasCopilot = config.models.any { it.name == "github-copilot" }
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

    private fun loadTasks(datasetPath: Path): List<HumanEvalTask> {
        return datasetPath.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() }
                .map { mapper.readValue<HumanEvalTask>(it) }
                .toList()
        }
    }

    private fun invokeModel(model: ModelConfig, task: HumanEvalTask): ModelInvocation {
        val prompt = buildPrompt(task)
        val startedAt = Instant.now()
        val command = listOf(config.opencodeBin,"run", "-m", model.modelId, prompt)
        println(command)
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

    private fun buildPrompt(task: HumanEvalTask): String {
        return """
            Complete the Kotlin function for the following task.
            Output Kotlin code only (no markdown, no prose).
            Keep the function signature and name intact.
            You may return either:
            1) Full function definition
            2) Continuation from inside the function body

            Task:
            ${task.prompt}
        """.trimIndent()
    }

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

    private fun runCommand(command: List<String>, timeout: Duration, workingDirectory: Path?): CommandResult {
        val processBuilder = ProcessBuilder(command)
        if (workingDirectory != null) {
            processBuilder.directory(workingDirectory.toFile())
        }
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()

        val output = StringBuilder()
        val readerThread = thread(start = true, name = "process-output-reader") {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    output.appendLine(line)
                }
            }
        }

        val finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            readerThread.join(1_000)
            return CommandResult(exitCode = null, output = output.toString(), timedOut = true)
        }

        readerThread.join(1_000)
        return CommandResult(exitCode = process.exitValue(), output = output.toString(), timedOut = false)
    }

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

    private fun writeArtifacts(report: BenchmarkReport): Pair<Path, Path> {
        config.outputDir.createDirectories()
        val tag = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now())
        val jsonPath = config.outputDir.resolve("benchmark-$tag.json")
        val csvPath = config.outputDir.resolve("benchmark-$tag.csv")

        mapper.writeValue(jsonPath.toFile(), report)
        csvPath.writeText(buildCsv(report.records))
        return jsonPath to csvPath
    }

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

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun deleteDirectory(path: Path) {
        if (!path.toFile().exists()) {
            return
        }
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
