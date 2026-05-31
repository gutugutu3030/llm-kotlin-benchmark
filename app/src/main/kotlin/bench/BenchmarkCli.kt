package bench

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.system.exitProcess

private const val DEFAULT_DATASET_URL =
    "https://raw.githubusercontent.com/structuredllm/syncode/main/syncode/evaluation/mxeval/data/multilingual_humaneval/HumanEval_kotlin_v1.1.jsonl"

private const val DEFAULT_OPENCODE_TIMEOUT_SEC = 300L
private const val OPENCODE_MODELS_TIMEOUT_SEC = 30L

/**
 * ベンチマーク CLI のエントリーポイント。
 *
 * @param args コマンドライン引数。
 * @return 戻り値は使用しない（`Unit`）。
 */
fun main(args: Array<String>) {
    if (args.contains("--help")) {
        printUsage()
        return
    }

    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        println("Argument error: ${e.message}")
        printUsage()
        exitProcess(2)
    }

    val config = try {
        parseConfig(parsed)
    } catch (e: IllegalArgumentException) {
        println("Argument error: ${e.message}")
        printUsage()
        exitProcess(2)
    }

    if (parsed.hasFlag("--ping-models")) {
        val pingMessage = parsed.value("--ping-message") ?: DEFAULT_PING_MESSAGE
        val results = ModelConnectivityRunner(config, pingMessage).run()

        var hasFailure = false
        for (result in results) {
            if (result.ok) {
                println("OK ${result.model} (${result.modelId}) latency_ms=${result.latencyMs}")
            } else {
                hasFailure = true
                println("NG ${result.model} (${result.modelId}) latency_ms=${result.latencyMs} error=${result.errorMessage}")
            }
        }

        if (hasFailure) {
            exitProcess(1)
        }
        println("Model connectivity check completed.")
        return
    }

    val output = BenchmarkRunner(config).run()
    println("Benchmark completed.")
    println("JSON: ${output.jsonPath}")
    println("CSV: ${output.csvPath}")
    for (summary in output.summaryByModel) {
        println(
            "${summary.model}: ${summary.passCount}/${summary.totalCount} pass " +
                "(pass@1=${"%.3f".format(summary.passAt1)}), avg_e2e_ms=${"%.1f".format(summary.averageE2eMs)}"
        )
    }
}

/**
 * 解析済み引数を `BenchmarkConfig` に変換する。
 *
 * @param parsed 解析済みコマンドライン引数。
 * @return 実行時に利用するベンチマーク設定。
 */
private fun parseConfig(parsed: ParsedArgs): BenchmarkConfig {
    if (parsed.value("--llama-model") != null || parsed.value("--copilot-model") != null) {
        throw IllegalArgumentException("--llama-model/--copilot-model are removed. Specify full provider/model IDs via --models")
    }

    val problemCount = parsed.value("--count")?.toInt() ?: 10
    val seed = parsed.value("--seed")?.toLong() ?: 42L
    val outputDir = Path(parsed.value("--output-dir") ?: "results")
    val datasetCache = Path(parsed.value("--dataset-cache") ?: ".cache/datasets/HumanEval_kotlin_v1.1.jsonl")
    val refreshDataset = parsed.hasFlag("--refresh-dataset")
    val opencodeBin = parsed.value("--opencode-bin") ?: "opencode"
    val selectedModels = resolveSelectedModels(parsed.value("--models"), opencodeBin)

    return BenchmarkConfig(
        seed = seed,
        problemCount = problemCount,
        datasetUrl = parsed.value("--dataset-url") ?: DEFAULT_DATASET_URL,
        datasetCachePath = datasetCache,
        refreshDataset = refreshDataset,
        outputDir = outputDir,
        opencodeBin = opencodeBin,
        kotlincBin = parsed.value("--kotlinc-bin") ?: "kotlinc",
        javaBin = parsed.value("--java-bin") ?: "java",
        opencodeTimeoutSec = parsed.value("--opencode-timeout-sec")?.toLong() ?: DEFAULT_OPENCODE_TIMEOUT_SEC,
        compileTimeoutSec = parsed.value("--compile-timeout-sec")?.toLong() ?: 45L,
        executeTimeoutSec = parsed.value("--execute-timeout-sec")?.toLong() ?: 20L,
        copilotMaxCalls = 10,
        models = selectedModels
    )
}

/**
 * `--models` に指定された model ID 一覧を解決・検証する。
 *
 * @param selectedRaw `--models` の生文字列。
 * @param opencodeBin `opencode` 実行ファイルのパス。
 * @param commandRunner 外部コマンド実行関数。
 * @return 実行対象のモデル設定リスト。
 */
internal fun resolveSelectedModels(
    selectedRaw: String?,
    opencodeBin: String,
    commandRunner: (List<String>, Duration, Path?) -> CommandResult = ::runCommand
): List<ModelConfig> {
    val requestedModelIds = parseRequestedModelIds(selectedRaw)
    val availableModelIds = fetchAvailableModelIds(opencodeBin, commandRunner)

    val unknownModelIds = requestedModelIds.filterNot { availableModelIds.contains(it) }
    if (unknownModelIds.isNotEmpty()) {
        throw IllegalArgumentException(
            "Unknown model(s): ${unknownModelIds.joinToString(", ")}. " +
                "Run `$opencodeBin models` and choose from the returned provider/model IDs."
        )
    }

    return requestedModelIds.map { modelId ->
        ModelConfig(name = modelId, modelId = modelId)
    }
}

/**
 * `--models` 引数を provider/model 形式の model ID 一覧へ変換する。
 *
 * @param selectedRaw `--models` の生文字列。
 * @return 入力順を維持した model ID 一覧（重複除去済み）。
 */
internal fun parseRequestedModelIds(selectedRaw: String?): List<String> {
    if (selectedRaw.isNullOrBlank()) {
        throw IllegalArgumentException("--models is required. Specify provider/model IDs (comma-separated).")
    }

    val selectedModelIds = linkedSetOf<String>()
    for (token in selectedRaw.split(",")) {
        val modelId = token.trim()
        if (modelId.isBlank()) {
            continue
        }
        if (!modelId.contains("/")) {
            throw IllegalArgumentException("Invalid model '$modelId'. Use provider/model format.")
        }
        selectedModelIds.add(modelId)
    }

    if (selectedModelIds.isEmpty()) {
        throw IllegalArgumentException("--models is required. Specify at least one provider/model ID.")
    }
    return selectedModelIds.toList()
}

/**
 * `opencode models` を実行して利用可能な model ID 一覧を取得する。
 *
 * @param opencodeBin `opencode` 実行ファイルのパス。
 * @param commandRunner 外部コマンド実行関数。
 * @return 利用可能な model ID 集合。
 */
internal fun fetchAvailableModelIds(
    opencodeBin: String,
    commandRunner: (List<String>, Duration, Path?) -> CommandResult = ::runCommand
): Set<String> {
    val result = try {
        commandRunner(
            listOf(opencodeBin, "models"),
            Duration.ofSeconds(OPENCODE_MODELS_TIMEOUT_SEC),
            null
        )
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to run `$opencodeBin models`: ${e.message}")
    }

    if (result.timedOut) {
        throw IllegalArgumentException("`$opencodeBin models` timed out after ${OPENCODE_MODELS_TIMEOUT_SEC}s")
    }
    if (result.exitCode != 0) {
        throw IllegalArgumentException("`$opencodeBin models` failed with exit code ${result.exitCode}")
    }

    val models = result.output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toCollection(linkedSetOf())

    if (models.isEmpty()) {
        throw IllegalArgumentException("`$opencodeBin models` returned no models")
    }
    return models
}

/**
 * CLI の使用方法とオプションを標準出力に表示する。
 *
 * @return 戻り値は使用しない（`Unit`）。
 */
private fun printUsage() {
    println(
        """
        Usage: ./gradlew :app:run --args="--count 10 --seed 42 --models provider/model"

        Options:
          --count N                  Number of sampled problems (default: 10)
          --seed N                   Fixed random seed for deterministic sampling (default: 42)
          --dataset-url URL          Kotlin HumanEval JSONL URL
          --dataset-cache PATH       Local dataset cache path (default: .cache/datasets/HumanEval_kotlin_v1.1.jsonl)
          --refresh-dataset          Re-download dataset even if cache exists
          --output-dir PATH          Output directory for JSON/CSV (default: results)
          --opencode-bin PATH        opencode executable (default: opencode)
          --models LIST              Comma-separated provider/model IDs to run (required; validated by `opencode models`)
          --kotlinc-bin PATH         kotlinc executable (default: kotlinc)
          --java-bin PATH            java executable (default: java)
          --opencode-timeout-sec N   Timeout for each model call (default: $DEFAULT_OPENCODE_TIMEOUT_SEC)
          --ping-models              Check model connectivity only (no dataset, no compile/test)
          --ping-message TEXT        Message used by --ping-models (default: "$DEFAULT_PING_MESSAGE")
          --compile-timeout-sec N    Timeout for each compile (default: 45)
          --execute-timeout-sec N    Timeout for each test execution (default: 20)
          --help                     Show this help
        """.trimIndent()
    )
}

/**
 * コマンドライン引数の解析結果を保持する。
 *
 * @property values 値を伴うオプションのマップ。
 * @property flags 真偽フラグとして扱うオプションの集合。
 */
private data class ParsedArgs(
    val values: Map<String, String>,
    val flags: Set<String>
) {
    /**
     * 指定キーのオプション値を取得する。
     *
     * @param key 取得対象のオプションキー。
     * @return キーに対応する値。存在しない場合は `null`。
     */
    fun value(key: String): String? = values[key]

    /**
     * 指定キーのフラグ有無を判定する。
     *
     * @param key 判定対象のフラグキー。
     * @return フラグが存在する場合は `true`。
     */
    fun hasFlag(key: String): Boolean = flags.contains(key)
}

/**
 * コマンドライン引数配列を値付きオプションとフラグに分解する。
 *
 * @param args コマンドライン引数配列。
 * @return 解析済み引数オブジェクト。
 */
private fun parseArgs(args: Array<String>): ParsedArgs {
    val values = linkedMapOf<String, String>()
    val flags = linkedSetOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) {
            throw IllegalArgumentException("Unexpected positional argument: $arg")
        }

        if (arg == "--refresh-dataset" || arg == "--ping-models") {
            flags.add(arg)
            i += 1
            continue
        }

        if (i + 1 >= args.size || args[i + 1].startsWith("--")) {
            throw IllegalArgumentException("Missing value for $arg")
        }
        values[arg] = args[i + 1]
        i += 2
    }
    return ParsedArgs(values, flags)
}
