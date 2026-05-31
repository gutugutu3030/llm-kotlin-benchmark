# Kotlin HumanEval Local Benchmark (opencode)

`opencode` で利用可能な任意の `provider/model` を同条件評価するためのローカルベンチです。

## 仕様（今回の合意条件）

- Kotlin HumanEval を取得して使用（デフォルト取得元は HumanEval-X 系 Kotlin JSONL）
- 固定シードで 10 問をサンプリング
- 指定した `provider/model` を同じ問題セットで評価
- 各問題は 1 回生成のみ（再生成なし）
- 指標は `pass@1` と E2E 時間（生成 + コード抽出 + テスト実行）
- 結果は JSON / CSV の両方を出力

## 前提

- JDK 21
- `opencode` が利用可能
- `kotlinc` が利用可能

## 実行

```bash
./gradlew :app:run --args="--count 10 --seed 42 --models github-copilot/gpt-5.3-codex"
```

主なオプション:

```bash
--models github-copilot/gpt-5.3-codex
--models llama.cpp/Qwen3.6-27B-IQ4_XS.gguf,github-copilot/gpt-5.3-codex
--opencode-timeout-sec 300
--output-dir results
--refresh-dataset
```

## モデル選択（`--models`）

`--models` は **必須** です。`provider/model` をカンマ区切りで指定します。  
起動時に毎回 `opencode models` を実行して一覧を取得し、存在しない model を指定した場合はエラーで終了します。

```bash
# 1モデル
./gradlew :app:run --args="--count 10 --seed 42 --models github-copilot/gpt-5.3-codex"

# 複数モデル
./gradlew :app:run --args="--count 10 --seed 42 --models llama.cpp/Qwen3.6-27B-IQ4_XS.gguf,github-copilot/gpt-5.3-codex"

# 利用可能モデル一覧の確認
opencode models
```

ヘルプ:

```bash
./gradlew :app:run --args="--help"
```

## モデル疎通チェック（軽量）

`Hello` などの短いプロンプトで、各モデルが応答できるかだけを確認するタスクです。  
HumanEval の取得・コンパイル・実行は行いません。

```bash
# モデル指定（必須）
./gradlew :app:pingModels -Pmodels=github-copilot/gpt-5.3-codex

# プロンプト指定
./gradlew :app:pingModels -Pmodels=github-copilot/gpt-5.3-codex -PpingMessage="Hello"

# timeout 指定（秒）
./gradlew :app:pingModels -Pmodels=github-copilot/gpt-5.3-codex -PopencodeTimeoutSec=120

# opencode バイナリ指定
./gradlew :app:pingModels -Pmodels=github-copilot/gpt-5.3-codex -PopencodeBin=/path/to/opencode
```

`pingModels` のデフォルト timeout は 60 秒です。

CLI から直接実行する場合:

```bash
./gradlew :app:run --args="--ping-models --models github-copilot/gpt-5.3-codex --ping-message Hello"
```

## 出力

- `results/benchmark-YYYYMMDD-HHMMSS.json`
- `results/benchmark-YYYYMMDD-HHMMSS.csv`

JSON には問題ごとの生出力、pass/fail、モデル時間、コンパイル時間、実行時間、E2E 時間を含みます。
