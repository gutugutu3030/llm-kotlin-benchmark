# Kotlin HumanEval Local Benchmark (opencode)

`llama.cpp` と `github-copilot` を `opencode` 経由で同条件評価するためのローカルベンチです。

## 仕様（今回の合意条件）

- Kotlin HumanEval を取得して使用（デフォルト取得元は HumanEval-X 系 Kotlin JSONL）
- 固定シードで 10 問をサンプリング
- `llama.cpp` と `github-copilot` を同じ 10 問で評価
- 各問題は 1 回生成のみ（再生成なし）
- 指標は `pass@1` と E2E 時間（生成 + コード抽出 + テスト実行）
- 結果は JSON / CSV の両方を出力

## 前提

- JDK 21
- `opencode` が利用可能
- `kotlinc` が利用可能

## 実行

```bash
./gradlew :app:run --args="--count 10 --seed 42"
```

主なオプション:

```bash
--models llama.cpp
--models github-copilot
--models llama.cpp,github-copilot
--llama-model llama.cpp/Qwen3.6-27B-IQ4_XS.gguf
--copilot-model github-copilot/gpt-5.3-codex
--opencode-timeout-sec 300
--output-dir results
--refresh-dataset
```

## モデル選択（`--models`）

`--models` で実行対象を指定できます。未指定時は **両方**（`llama.cpp,github-copilot`）を実行します。

```bash
# llama.cpp のみ
./gradlew :app:run --args="--count 10 --seed 42 --models llama.cpp"

# github-copilot のみ
./gradlew :app:run --args="--count 10 --seed 42 --models github-copilot"

# 両方（明示指定）
./gradlew :app:run --args="--count 10 --seed 42 --models llama.cpp,github-copilot"
```

ヘルプ:

```bash
./gradlew :app:run --args="--help"
```

## モデル疎通チェック（軽量）

`Hello` などの短いプロンプトで、各モデルが応答できるかだけを確認するタスクです。  
HumanEval の取得・コンパイル・実行は行いません。

```bash
# デフォルト（両モデル）
./gradlew :app:pingModels

# モデル指定（片方だけ）
./gradlew :app:pingModels -Pmodels=github-copilot

# プロンプト指定
./gradlew :app:pingModels -PpingMessage="Hello"

# timeout 指定（秒）
./gradlew :app:pingModels -PopencodeTimeoutSec=120

# opencode バイナリ指定
./gradlew :app:pingModels -PopencodeBin=/path/to/opencode
```

`pingModels` のデフォルト timeout は 60 秒です。

CLI から直接実行する場合:

```bash
./gradlew :app:run --args="--ping-models --models github-copilot --ping-message Hello"
```

## 出力

- `results/benchmark-YYYYMMDD-HHMMSS.json`
- `results/benchmark-YYYYMMDD-HHMMSS.csv`

JSON には問題ごとの生出力、pass/fail、モデル時間、コンパイル時間、実行時間、E2E 時間を含みます。
