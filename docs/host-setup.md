# Host setup and service contracts

The bot runs in a Podman container (`podman compose`), but `whisper-server` and Ollama run on the macOS host so they can use the GPU (Metal). Inside the container the bot reaches them at `http://host.containers.internal:<port>`. For local runs (`./run.sh`, profile `dev`) it uses `localhost`.

Verified on: MacBook Pro, Apple M3 Max, 64 GB, macOS, Ollama 0.40.16, Homebrew `whisper-cpp`.

## Install

```bash
brew install whisper-cpp ffmpeg yt-dlp
brew install ollama            # or the Ollama desktop app
ollama pull qwen3:32b          # about 20 GB

mkdir -p ~/models/whisper
curl -L --fail -o ~/models/whisper/ggml-large-v3-turbo.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin   # about 1.5 GB
```

`ffmpeg` and `yt-dlp` on the host are only needed for local runs. The container image will contain its own copies (T15).

## Run

```bash
# Ollama listens on 127.0.0.1:11434 by default (brew services start ollama, or open the app)
whisper-server -m ~/models/whisper/ggml-large-v3-turbo.bin --host 127.0.0.1 --port 8081 -l en -t 8
```

Reachability from the container is **not verified yet** (this machine uses Podman 6.2.0 with `podman machine`, 8 CPUs, 6 GiB). `host.containers.internal` is expected to reach the host, but whether services bound to `127.0.0.1` are visible from the Podman VM is not confirmed. If they are not, bind `whisper-server` with `--host 0.0.0.0` and start Ollama with `OLLAMA_HOST=0.0.0.0`, and restrict the ports with the macOS firewall. T15 settles this.

Check that both are up:

```bash
curl -s http://127.0.0.1:8081/ -o /dev/null -w "%{http_code}\n"   # 200
curl -s http://localhost:11434/api/tags                           # lists qwen3:32b
```

## whisper-server contract

Captured against the real server with a 7-second WAV (`say` + `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le`).

**Request:** `POST /inference`, `multipart/form-data`.

| Field | Value | Notes |
|---|---|---|
| `file` | the WAV chunk | required, without it the server answers `400` |
| `response_format` | `json` | also accepts `text`, `verbose_json`, `srt`, `vtt` |
| `language` | `en` | the server-wide `-l en` is the default; sending it per request is explicit |
| `temperature` | `0.0` | optional |

```bash
curl -s http://127.0.0.1:8081/inference \
  -F file=@sample.wav -F response_format=json -F language=en -F temperature=0.0
```

**Response, 200:** `{"text":" Hello, this is ...\n o'clock, and we will ...\n"}` (fixture `whisper-inference-ok.json`).

**Things the client must handle:**
- The text starts with a space and contains **newlines in the middle of sentences** (it is wrapped per segment). The merger must trim each chunk and collapse whitespace before joining chunks.
- Errors are plain text, not JSON: `400` with the body `Invalid request` (fixture `whisper-inference-bad-request.txt`). A file that is not valid audio gives the same `400`.
- Only WAV is sent, so `--convert` (server-side ffmpeg) is not needed.
- `verbose_json` returns per-segment timestamps and, with words enabled, word probabilities (fixture `whisper-inference-verbose.json`, words stripped). V1 uses only `text`.
- Unreachable server means a connection error (nothing is listening on the port).

## Ollama contract

**Request:** `POST /api/chat`, JSON, `"stream": false`.

```json
{
  "model": "qwen3:32b",
  "stream": false,
  "think": false,
  "keep_alive": "10m",
  "options": { "num_ctx": 8192, "temperature": 0.2 },
  "messages": [
    { "role": "system", "content": "Summarize the transcript as 2-3 English bullet points starting with '- '." },
    { "role": "user", "content": "<transcript chunk>" }
  ]
}
```

**Response, 200:** `message.content` holds the text, plus `done`, `done_reason`, `prompt_eval_count`, `eval_count` and durations (fixture `ollama-chat-ok.json`).

**Errors:** a missing model gives `404` with `{"error":"model 'nope:1b' not found"}` (fixture `ollama-model-not-found.json`). Unreachable Ollama means a connection error.

### Findings that change the implementation

1. **`"think": false` is required.** `qwen3` is a thinking model. Without the flag the answer goes to `message.thinking` and `message.content` can be **empty** while the token budget is spent on thinking (fixture `ollama-chat-thinking-default.json`, `done_reason: "length"`). The client sends `think: false` on every request and treats an empty `content` as a failure.
2. **`num_ctx` is applied, and overflow is silent.** The same ~4,800-token prompt was sent twice:

   | `num_ctx` | `prompt_eval_count` | Result |
   |---|---|---|
   | 16384 | 4831 | full prompt used, correct summary |
   | 2048 | 1026 | prompt **truncated without any error**, the model answered about "repeated sentences" |

   `ollama ps` shows the effective context in its CONTEXT column (8192 after the `num_ctx: 8192` request).

   Ollama does not return an error when the prompt does not fit. The client must (a) size chunks so that prompt, instructions and output fit in `num_ctx`, and (b) compare `prompt_eval_count` with the estimated input size and fail the job if the prompt was clearly cut.
3. **Prompt processing speed:** about 146 tokens/s for `qwen3:32b` on this machine (4,825 tokens in 33 s on a cold run). A 3-hour video is about 30,000 words, roughly 40,000 tokens, so the map step takes about 5 minutes of prompt processing plus generation. To be measured properly in T17.
4. **Model load:** first request after idle spends about 7.5 s on `load_duration`. `keep_alive` keeps the model in memory between chunks (`ollama ps` shows `UNTIL`). The runner size for `qwen3:32b` with `num_ctx` 8192 is 22 GB, all on GPU.

## Fixtures

`src/test/resources/fixtures/` holds the captured responses used by the WireMock tests:

| File | Used for |
|---|---|
| `whisper-inference-ok.json` | transcription of one chunk |
| `whisper-inference-verbose.json` | reference for the verbose format |
| `whisper-inference-bad-request.txt` | `400` handling and chunk retry |
| `ollama-chat-ok.json` | summary of one chunk |
| `ollama-chat-thinking-default.json` | empty `content` handling |
| `ollama-model-not-found.json` | `404` handling |
