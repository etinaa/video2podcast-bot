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

`ffmpeg` and `yt-dlp` on the host are only needed for local runs. The container image contains its own copies of both.

## Run

```bash
# Ollama listens on 127.0.0.1:11434 by default (brew services start ollama, or open the app)
whisper-server -m ~/models/whisper/ggml-large-v3-turbo.bin --host 127.0.0.1 --port 8081 -l auto -t 8 -mc 0
```

`-l auto` is required for Russian videos: whisper.cpp's own default is English even when `-l` is missing, so the flag has to say `auto` explicitly. The bot also sends `language` with every request (see the contract below), so the flag only matters for manual `curl` calls. The model stays the multilingual `large-v3-turbo`; the bot never asks for translation.

`-mc 0` is required: it stops Whisper from feeding the previous text back in as context. Without it a 2 h 36 min test video got a repetition loop ("And I thought, maybe I could do something else", 14 to 20 times, about 18 seconds of real speech replaced). With `-mc 0` the same 10-minute chunk comes out without the loop and with the missing speech back, at the same speed. The bot sends `temperature=0.0` and no `temperature_inc`, so the temperature fallback does not help here (checked: no change).

Reachability from the container: checked on this machine (Podman 6.2.0, `podman machine` with applehv, 8 CPUs, 6 GiB). With `whisper-server` bound to `127.0.0.1:8081` and Ollama bound to `127.0.0.1:11434` on the host, a container request to `http://host.containers.internal:8081/` and to `http://host.containers.internal:11434/api/tags` both answer HTTP 200. No `0.0.0.0` binding and no `OLLAMA_HOST` change is needed here. If a different Podman setup cannot reach them, bind `whisper-server` with `--host 0.0.0.0` and start Ollama with `OLLAMA_HOST=0.0.0.0`, and restrict the ports with the macOS firewall.

Check that both are up:

```bash
curl -s http://127.0.0.1:8081/ -o /dev/null -w "%{http_code}\n"   # 200
curl -s http://localhost:11434/api/tags                           # lists qwen3:32b
```

## Measured on this machine (T17, 2026-11-23)

MacBook with 8 performance cores, `ggml-large-v3-turbo` on Whisper, `qwen3:32b` (22 GB, 100% GPU, `num_ctx` 8192) in Ollama, the bot run locally (`./run.sh`). Times are wall-clock from the process logs sampled every 5 seconds (`ps`) and the Telegram message times.

| Video | Audio sent as | Result |
|---|---|---|
| 1:54 Russian video, E-AC-3 5.1 inside an `.m4a` (5.5 MB) | converted to AAC stereo 128 kbps | plays (see D15) |
| 3:36 English video | unchanged, AAC `.m4a` | plays, transcript complete (owner checked) |
| 2 h 36 min English talk (9 390 s, 16 chunks of up to 10 min) | compressed to mono AAC 42 kbps, 51 933 907 bytes (44.2 kbps real) | Telegram accepted it, plays to the end |
| 2 h 58 min 20 s Russian audiobook (10 700 s, 18 chunks) | compressed to mono AAC in two `ffmpeg` attempts (37 kbps, then the 29 kbps retry); the final file is 40 222 038 bytes (30.1 kbps real, the size of the first attempt was not recorded) | the owner received it in Telegram without a size error |
| A video longer than 3 hours | not downloaded | the bot answered `TOO_LONG` before any download (log line at 13:24) |

Stages for the 2 h 36 min video (second run, with `-mc 0`):

| Stage | Time | Notes |
|---|---|---|
| Download (`yt-dlp`, 152 MB) | about 16 s | timed on its own with `time` |
| Convert to WAV and split into chunks (`ffmpeg`) | 5 to 10 s | seen in 1 to 2 of the 5-second process samples in both runs |
| Transcribe, 16 chunks | 3 to 4 min | `whisper-server` busy for 3 min (second run) and 4 min (first run) in the process samples, so about 40 to 50 times faster than real time; a single 10-minute chunk took 10 to 13 s when sent with `curl` |
| Summary (Ollama, map-reduce) | 6 to 9 min | from the end of transcription to the start of the next `ffmpeg`/the summary message: about 6 min (first run) and about 9 min (second run) |
| Compress to fit Telegram (on the audio tap) | about 1 min | `ffmpeg` busy for 55 s in the first run and 1 min in the second |
| Upload of the 49.5 MiB audio | about 2 min | from the end of `ffmpeg` to the time of the audio message in Telegram (11:40 to 11:42 in the first run) |

Stages for the 2 h 58 min 20 s video: download, WAV and split: not sampled separately (the first `ffmpeg` sample is at 13:29, the download is estimated at about 1 min from the 16 s of the 152 MB talk); transcribe 18 chunks about 6 min (13:29:38 to 13:35:47, slower than the English talk, the audio was Russian); summary about 9 min (to about 13:44:47); compression about 3 min (two `ffmpeg` attempts, 13:44:47 to 13:47:34, 37 then 29 kbps); upload about 1 min (to the Telegram message time, 13:49). Stages come from the 5-second process samples, except the download estimate and the upload, which come from the Telegram message time. Peak RSS in this run: Java bot 673 MiB (186 MiB in the 2 h 36 min run), `ffmpeg` 68 MiB, `whisper-server` 325 MiB.

Language (historical, T17, before T19): Whisper was forced to English with `-l en`. On the Russian audiobook the text came out as a garbled English translation with several repetition loops (for example the sentence "I thought it was going to be a new house" seven times in a row, and its phrase more often), even with `-mc 0`. The bot did what it should: the transcript has 30 855 words for 10 700 s, but with looped or mistranslated text a word count cannot show that the transcript is complete. This is the failure that T19 fixes: the bot now detects the language and uses it (see "Languages" below). Use English videos for checks of the transcript from before T19.

Peak memory (RSS) in the 2 h 36 min run: `whisper-server` up to 2.1 GB (the model sits in GPU memory, not counted), Java bot 186 MiB, `ffmpeg` 62 MB, Ollama about 22 GB for the model (`ollama ps`). Nothing needed tuning: chunk size, `num_ctx` 8192 and `keep_alive` stay as they are, and `qwen3:32b` is fast enough that no fall back to `qwen3:14b` is needed.

Telegram size limit: a file of 51 933 907 bytes (above 50 000 000, below 50 MiB = 52 428 800) was accepted by `sendAudio`, so `LIMIT_BYTES = 50 MiB` holds. The encoder output is about 5% larger than the requested bitrate, so for a 3-hour video the first attempt (37 kbps, about 52.6 MB) lands above the limit and the 29 kbps retry is used. The retry also ran on a real 2 h 58 min video (see above): it ended at 40 222 038 bytes and the owner received it. It is covered by tests as well.

## Container: build, start, update, logs

The bot image is built from `Dockerfile` (JDK 27 build, JRE 27 runtime with `ffmpeg` and the latest `yt-dlp`, non-root user, default profile). `compose.yaml` runs one service, `bot`, with two named volumes: `work` (`/work`, job files, cleaned by the bot) and `data` (`/data`, `allowed-users.json`, never touched by job cleanup). No ports are published.

One-time Podman setup (the container needs Podman and the `podman-compose` provider that `podman compose` calls):

```bash
brew install podman podman-compose
podman machine init --cpus 8 --memory 6144   # 8 CPUs and 6 GiB, as on the verified machine
podman machine start
```

Then, for each build and start:

```bash
podman machine start                       # once per boot, if the machine is not running
cp .env.example .env                       # then set TELEGRAM_BOT_TOKEN and ADMIN_USER_ID
podman compose build                       # build the image
podman compose up -d                       # start the bot (or: podman compose up -d --build)
podman compose logs -f bot                 # follow the logs
podman compose down                        # stop and remove the container (volumes stay)
```

Only one bot may poll Telegram per token. Stop `./run.sh` before `podman compose up`, and the other way round.

Settings are read from `.env`; `.env.example` lists every variable with its default. Whisper and Ollama default to `http://host.containers.internal:8081` and `:11434`, so `.env` normally needs only the two required values. Do not copy the `localhost` values used by `./run.sh` into a `.env` that the container reads.

### Update yt-dlp

`yt-dlp` is downloaded at image build time, so a site change that breaks downloads is fixed by rebuilding without the cache for that step:

```bash
podman compose build --no-cache
podman compose up -d
podman compose exec bot yt-dlp --version   # the version now in the container
```

`--no-cache` also rebuilds the Maven stage; to refresh only `yt-dlp`, use `podman compose build --build-arg YT_DLP_REFRESH=$(date +%s)`.

### Logs and data

```bash
podman compose logs --tail 200 bot
podman volume ls                            # work and data volumes (prefixed with the project name)
podman compose exec bot ls /work /data
```

### Service checks

At startup the bot logs one line per service and starts anyway:

```
Health check: whisper reachable
Health check: ollama unreachable (connection refused)
```

Before every job the bot checks both services again (`GET /` on whisper-server, `GET /api/version` on Ollama; any HTTP answer counts as reachable). If one does not answer, the job stops before any download and is parked (see below). Only when the queue file cannot be written does the user get "The transcription server is unreachable." and/or "The summary server is unreachable.". `HEALTH_TIMEOUT` (default `PT3S`) is how long one probe may take; a down service delays the next queued job by at most that plus one second.

### Links parked during an outage

When Whisper or Ollama is down, the bot keeps the link instead of failing it. The status message says "Service temporarily unavailable. We will send the result after it recovers." and the bot retries from scratch (nothing but the link and the owner data is stored; working files are deleted at once). Settings:

- `DEFERRED_RETRY_INTERVAL` (default `PT2H`): the wait between two retry passes, also before the first pass after a start.
- `DEFERRED_MAX_ATTEMPTS` (default `12`, at least 1): retries per link before the bot tells the user to send it again; 12 at two hours is about 24 hours.

The queue is the file `deferred-jobs.json` in the directory of `ACCESS_FILE` (the `data` volume). It survives `podman compose down` and is lost if the volume is removed. It contains user links, so do not share it.

## Languages (T19)

- **Supported:** English and Russian, detected from the audio by Whisper. The video title, the URL and the user's Telegram language play no part.
- **Detection:** the first chunk of a job goes to Whisper with `language=auto` and `response_format=verbose_json`. The answer is both the transcript of that chunk and its language. If that chunk has no text (music, a silent intro), its language is ignored and the next chunk is sent with `auto` again; the first chunk with text decides. If no chunk has text: "No speech was found in this video."
- **Locking:** after the decision every later chunk of the job is sent with `language=en` or `language=ru`, so one language is used for the whole video. The next job detects again; nothing is stored, and a job retried after an outage detects again too.
- **Summary:** every Ollama request of the job (short, map, intermediate and final reduction) asks for bullet points in the same language. For Russian the budget uses 2 characters per token instead of 3, so with `OLLAMA_NUM_CTX=8192` a long Russian video needs more map steps than an English one of the same length.
- **Other languages:** the job stops right after detection: no later chunk, no Ollama call, the working files are deleted and the user gets "Only English and Russian videos are supported. Detected language: <code>." It is not a service failure and is not parked for retry. An unrecognized value is shown as `unknown`.
- **Unchanged:** the bot's own texts (buttons, statuses, errors, captions) stay in English. Title-derived file names keep Cyrillic letters (stem cut to 100 characters).

### Manual check on a Russian video (T19)

<!-- T19-MANUAL-CHECK-PLACEHOLDER: NOT DONE YET. The owner runs this check after the code is committed; replace this block with the result. -->

**Status: not done yet.** To record here: video title and URL, date, `whisper-server` and Ollama versions and settings (`-l auto`, `OLLAMA_NUM_CTX`), the language code Whisper returned, an excerpt of the Russian transcript and of the Russian summary, the English bot texts seen, the two delivered file names (`.txt` and audio), the result, and a comparison of Ollama's `prompt_eval_count` with the bot's estimate (2 characters per token for Russian; lower the value if the real count is higher than the estimate).

## whisper-server contract

Captured against the real server with a 7-second WAV (`say` + `ffmpeg -ar 16000 -ac 1 -c:a pcm_s16le`).

**Request:** `POST /inference`, `multipart/form-data`.

| Field | Value | Notes |
|---|---|---|
| `file` | the WAV chunk | required, without it the server answers `400` |
| `response_format` | `verbose_json` (detect mode), `json` (pinned mode) | also accepts `text`, `srt`, `vtt` |
| `language` | `auto` (detect mode), `en` or `ru` (pinned mode) | the server-wide `-l` is only the default for calls without this field; the bot always sends it |
| `translate` | `false` | |
| `detect_language` | `false` | never `true`: the server then stops after detection and returns no text |
| `temperature` | `0.0` | optional |

The bot sends the fields in this order: `file`, `response_format`, `language`, `translate`, `detect_language`, `temperature`.

Detect mode (first chunk with text of a job; it both detects and transcribes):

```bash
curl -s http://127.0.0.1:8081/inference \
  -F file=@sample.wav -F response_format=verbose_json -F language=auto \
  -F translate=false -F detect_language=false -F temperature=0.0
```

Pinned mode (every later chunk, with the language that detect mode selected):

```bash
curl -s http://127.0.0.1:8081/inference \
  -F file=@sample.wav -F response_format=json -F language=ru \
  -F translate=false -F detect_language=false -F temperature=0.0
```

**Response, 200 (pinned mode):** `{"text":" Hello, this is ...\n o'clock, and we will ...\n"}` (fixture `whisper-inference-ok.json`).

**Things the client must handle:**
- The text starts with a space and contains **newlines in the middle of sentences** (it is wrapped per segment). The merger must trim each chunk and collapse whitespace before joining chunks.
- Errors are plain text, not JSON: `400` with the body `Invalid request` (fixture `whisper-inference-bad-request.txt`). A file that is not valid audio gives the same `400`.
- Only WAV is sent, so `--convert` (server-side ffmpeg) is not needed.
- `verbose_json` returns per-segment timestamps and, with words enabled, word probabilities (fixture `whisper-inference-verbose.json`, words stripped). The bot reads only the top-level `text` and, in detect mode, the top-level `language`: a full lower-case name such as `russian` or `german` (not a code). Segments and probabilities are ignored.
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
    { "role": "system", "content": "Summarize the transcript as 2-3 Russian bullet points starting with '- '." },
    { "role": "user", "content": "<transcript chunk>" }
  ]
}
```

The system prompt names the language of the job (`English` or `Russian`, written in English): for example "... Write the final summary as Russian bullet points ...".

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
