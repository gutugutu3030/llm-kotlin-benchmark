package bench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * `BenchmarkCli` のモデル解決ロジックを検証するテスト。
 */
class BenchmarkCliModelResolutionTest {
    /**
     * `--models` の値を trim・重複除去して解釈できることを検証する。
     */
    @Test
    fun parsesRequestedModelIdsWithTrimAndDedup() {
        val result = parseRequestedModelIds(" github-copilot/gpt-5.3-codex , llama.cpp/Qwen3.6-27B-IQ4_XS.gguf , github-copilot/gpt-5.3-codex ")

        assertEquals(
            listOf("github-copilot/gpt-5.3-codex", "llama.cpp/Qwen3.6-27B-IQ4_XS.gguf"),
            result
        )
    }

    /**
     * `--models` が未指定の場合にエラーになることを検証する。
     */
    @Test
    fun rejectsMissingModelsArgument() {
        val exception = assertThrows<IllegalArgumentException> {
            parseRequestedModelIds(null)
        }

        assertTrue(exception.message!!.contains("--models is required"))
    }

    /**
     * 指定した model ID が `opencode models` に存在する場合に解決できることを検証する。
     */
    @Test
    fun resolvesRequestedModelsFromOpencodeList() {
        val result = resolveSelectedModels(
            selectedRaw = "github-copilot/gpt-5.3-codex,llama.cpp/Qwen3.6-27B-IQ4_XS.gguf",
            opencodeBin = "opencode",
            commandRunner = { command, _, _ ->
                assertEquals(listOf("opencode", "models"), command)
                CommandResult(
                    exitCode = 0,
                    output = "github-copilot/gpt-5.3-codex\nllama.cpp/Qwen3.6-27B-IQ4_XS.gguf\n",
                    timedOut = false
                )
            }
        )

        assertEquals(
            listOf(
                ModelConfig("github-copilot/gpt-5.3-codex", "github-copilot/gpt-5.3-codex"),
                ModelConfig("llama.cpp/Qwen3.6-27B-IQ4_XS.gguf", "llama.cpp/Qwen3.6-27B-IQ4_XS.gguf")
            ),
            result
        )
    }

    /**
     * 指定した model ID が `opencode models` に存在しない場合にエラーになることを検証する。
     */
    @Test
    fun rejectsModelNotFoundInOpencodeList() {
        val exception = assertThrows<IllegalArgumentException> {
            resolveSelectedModels(
                selectedRaw = "github-copilot/gpt-5.3-codex",
                opencodeBin = "opencode",
                commandRunner = { _, _, _ ->
                    CommandResult(
                        exitCode = 0,
                        output = "llama.cpp/Qwen3.6-27B-IQ4_XS.gguf\n",
                        timedOut = false
                    )
                }
            )
        }

        assertTrue(exception.message!!.contains("Unknown model(s): github-copilot/gpt-5.3-codex"))
    }
}
