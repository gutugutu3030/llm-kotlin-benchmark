package bench

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.system.exitProcess

private const val DEFAULT_DATASET_URL =
    "https://raw.githubusercontent.com/structuredllm/syncode/main/syncode/evaluation/mxeval/data/multilingual_humaneval/HumanEval_kotlin_v1.1.jsonl"

private const val MODEL_LLAMA = "llama.cpp"
private const val MODEL_COPILOT = "github-copilot"
private const val DEFAULT_LLAMA_MODEL = "llama.cpp/Qwen3.6-27B-IQ4_XS.gguf"
private const val DEFAULT_COPILOT_MODEL = "github-copilot/gpt-5.3-codex"
private const val DEFAULT_OPENCODE_TIMEOUT_SEC = 300L

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        printUsage()
        return
    }

    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Argument error: ${e.message}")
        printUsage()
        exitProcess(2)
    }

    val config = try {
        parseConfig(parsed)
    } catch (e: IllegalArgumentException) {
        System.err.println("Argument error: ${e.message}")
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

private fun parseConfig(parsed: ParsedArgs): BenchmarkConfig {
    val problemCount = parsed.value("--count")?.toInt() ?: 10
    val seed = parsed.value("--seed")?.toLong() ?: 42L
    val outputDir = Path(parsed.value("--output-dir") ?: "results")
    val datasetCache = Path(parsed.value("--dataset-cache") ?: ".cache/datasets/HumanEval_kotlin_v1.1.jsonl")
    val refreshDataset = parsed.hasFlag("--refresh-dataset")
    val allModels = listOf(
        ModelConfig(name = MODEL_LLAMA, modelId = parsed.value("--llama-model") ?: DEFAULT_LLAMA_MODEL),
        ModelConfig(name = MODEL_COPILOT, modelId = parsed.value("--copilot-model") ?: DEFAULT_COPILOT_MODEL)
    )
    val selectedModels = selectModels(allModels, parsed.value("--models"))

    return BenchmarkConfig(
        seed = seed,
        problemCount = problemCount,
        datasetUrl = parsed.value("--dataset-url") ?: DEFAULT_DATASET_URL,
        datasetCachePath = datasetCache,
        refreshDataset = refreshDataset,
        outputDir = outputDir,
        opencodeBin = parsed.value("--opencode-bin") ?: "opencode",
        kotlincBin = parsed.value("--kotlinc-bin") ?: "kotlinc",
        javaBin = parsed.value("--java-bin") ?: "java",
        opencodeTimeoutSec = parsed.value("--opencode-timeout-sec")?.toLong() ?: DEFAULT_OPENCODE_TIMEOUT_SEC,
        compileTimeoutSec = parsed.value("--compile-timeout-sec")?.toLong() ?: 45L,
        executeTimeoutSec = parsed.value("--execute-timeout-sec")?.toLong() ?: 20L,
        copilotMaxCalls = 10,
        models = selectedModels
    )
}

private fun selectModels(allModels: List<ModelConfig>, selectedRaw: String?): List<ModelConfig> {
    if (selectedRaw.isNullOrBlank()) {
        return allModels
    }

    val known = mapOf(
        MODEL_LLAMA to MODEL_LLAMA,
        "llama" to MODEL_LLAMA,
        MODEL_COPILOT to MODEL_COPILOT,
        "copilot" to MODEL_COPILOT
    )
    val selectedNames = linkedSetOf<String>()
    for (token in selectedRaw.split(",")) {
        val normalized = token.trim().lowercase()
        if (normalized.isBlank()) {
            continue
        }
        val canonical = known[normalized]
            ?: throw IllegalArgumentException("Unknown model '$token'. Use llama.cpp or github-copilot.")
        selectedNames.add(canonical)
    }
    if (selectedNames.isEmpty()) {
        throw IllegalArgumentException("No model selected. Use --models llama.cpp,github-copilot")
    }

    val modelByName = allModels.associateBy { it.name }
    return selectedNames.map { selectedName ->
        modelByName[selectedName]
            ?: throw IllegalArgumentException("Model '$selectedName' is not configured.")
    }
}

private fun printUsage() {
    println(
        """
        Usage: ./gradlew :app:run --args="--count 10 --seed 42"

        Options:
          --count N                  Number of sampled problems (default: 10)
          --seed N                   Fixed random seed for deterministic sampling (default: 42)
          --dataset-url URL          Kotlin HumanEval JSONL URL
          --dataset-cache PATH       Local dataset cache path (default: .cache/datasets/HumanEval_kotlin_v1.1.jsonl)
          --refresh-dataset          Re-download dataset even if cache exists
          --output-dir PATH          Output directory for JSON/CSV (default: results)
          --opencode-bin PATH        opencode executable (default: opencode)
          --models LIST              Comma-separated models to run: llama.cpp,github-copilot (default: both)
          --llama-model NAME         llama model ID (default: $DEFAULT_LLAMA_MODEL)
          --copilot-model NAME       copilot model ID (default: $DEFAULT_COPILOT_MODEL)
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

private data class ParsedArgs(
    val values: Map<String, String>,
    val flags: Set<String>
) {
    fun value(key: String): String? = values[key]
    fun hasFlag(key: String): Boolean = flags.contains(key)
}

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
