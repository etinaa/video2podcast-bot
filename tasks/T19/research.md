# T19 research — language detection and source-language output

Research date: **2026-11-25**. Analyst scope: read-only source/API investigation and requirements. No video was submitted to real Whisper/Ollama or Telegram during this research. The manual Russian-video acceptance check remains to be performed and recorded by the implementation team.

## Official sources

### 1. whisper.cpp server, matching the installed release

Source: [whisper.cpp v1.9.6 server.cpp](https://github.com/ggml-org/whisper.cpp/blob/v1.9.6/examples/server/server.cpp), consulted 2026-11-25. Also checked [upstream server usage](https://github.com/ggml-org/whisper.cpp/blob/master/examples/server/README.md); the pinned source is the contract reference.

Facts taken:
- Default language is English. `language=auto` requests detection. Removing the startup English flag alone does not change that default.
- Multipart fields include `language`, `translate`, `detect_language`, `response_format` and `temperature`.
- `detect_language` enables detection-only mode; it is not required for normal auto-language transcription.
- `verbose_json` includes top-level text and a full language name from the completed inference. Plain JSON normally contains text only; detection-only mode can add language but provides no full transcription.
- Optional probability fields are distinct from the transcription language. An English-only model ignores multilingual options.

Implication: use a multilingual model, first-chunk `auto` transcription with verbose output, then requests pinned to the selected code. Explicitly disable translation/detection-only mode. Do not depend on optional probability metadata. Architectural confirmation: A: Q-analyst-1.

### 2. Whisper language identifiers and detection-only semantics

Source: [whisper.cpp v1.9.6 whisper.cpp](https://github.com/ggml-org/whisper.cpp/blob/v1.9.6/src/whisper.cpp), consulted 2026-11-25; language table and auto-language branch of transcription.

Facts taken: the table associates `en` with `english`, `ru` with `russian`, and `de` with `german`; it also contains other supported Whisper languages. Auto detection happens when the language is auto/unset, while the explicit detect-only option returns after that operation.

Implication: normalize full names as well as codes before checking the two product-supported languages. Do not inspect text script as a replacement for the audio result. Reject other language codes even though Whisper can transcribe them. First-nonempty-chunk selection is application policy confirmed by owner in A: Q-analyst-2; upstream documentation does not establish that product policy.

### 3. Ollama chat API

Source: [Ollama POST /api/chat](https://docs.ollama.com/api/chat), consulted 2026-11-25.

Facts taken: requests carry model, role/content messages, runtime options, streaming and thinking controls; responses include message content and prompt-evaluation counts. The API does not expose a dedicated output-language argument.

Implication: carry the selected language in every summary prompt, including intermediate/final reduction. Preserve existing request options and truncation guard through Spring AI. API documentation does not prove that a real model will obey a prompt or that our character-to-token heuristic fits Russian; verify prompt construction/budgets in tests and real Russian quality in the manual run.

### 4. Telegram delivery and limits

Sources: [sendDocument](https://core.telegram.org/bots/api#senddocument), [sendAudio](https://core.telegram.org/bots/api#sendaudio), [sending files](https://core.telegram.org/bots/api#sending-files), [sendMessage](https://core.telegram.org/bots/api#sendmessage) and [InlineKeyboardButton](https://core.telegram.org/bots/api#inlinekeyboardbutton), consulted 2026-11-25.

Facts taken: file uploads use multipart/form-data; audio/document objects preserve a supplied filename. Cloud bot file uploads are limited to 50 MB, message text to 4,096 characters after entity parsing, callback data to 64 bytes. These pages do not specify a universal maximum upload-filename length.

Implication: retain the existing application filename bound and verify Cyrillic multipart names plus real Telegram acceptance. Do not invent a Telegram-specific filename character limit. Summary/HTML and callback rules remain unchanged.

## Local evidence and existing behavior

All paths below are relative to the repository root; inspected 2026-11-25.

| Evidence | Observed fact | Consequence for T19 |
|---|---|---|
| `ls -l /opt/homebrew/bin/whisper-server` | Symlink targets `../Cellar/whisper.cpp/1.9.6/bin/whisper-server`. | Use release 1.9.6 source, rather than assuming the old T3 package version. |
| `whisper-server --help` | Local help reports language default `en`, accepts `auto`, and describes detect-language as exit-after-detection; translate defaults false. | `-l auto` is explicit setup guidance; no inference was run by this command. |
| `src/test/resources/fixtures/whisper-inference-verbose.json` | Existing T3 fixture has top-level `language: english`, `text`, segments and extra probability fields. | Reuse real response shape; an implementation accepting only `en`/`ru` codes would reject real English. |
| `src/main/java/com/video2podcast/transcript/WhisperClient.java` | Current request forces `language=en`, requests plain JSON and returns only `text`. | Detection requires a response carrying both text and language and a contract through the pipeline. |
| `src/main/java/com/video2podcast/transcript/TranscriptionClient.java` and `TranscriptMerger.java` | Sequential chunks, one retry per failed chunk, Unicode whitespace normalization, UTF-8 transcript, final LF and temporary-WAV cleanup already exist. | Preserve these behaviors; pin the language across later chunks and retries. |
| `src/main/java/com/video2podcast/summary/Prompts.java` | SHORT, MAP, REDUCE_ROUND and REDUCE_FINAL all explicitly require English, including non-English input. | All four need the selected language; modifying only the final prompt leaves intermediate summaries in English. |
| `tasks/TD12`, `tasks/TD13`, `summary/OllamaLlmClient.java` | Ollama now uses Spring AI, not the old handwritten client. | Apply language propagation through the current boundary; avoid reviving the removed client. |
| `src/main/java/com/video2podcast/bot/FileNames.java`, T12/T13 requirements | Shared stem sanitization preserves Unicode, bounds at 100 UTF-16 units, avoids splitting a surrogate pair and appends the delivered extension. | Existing Cyrillic naming needs verification, not transliteration. For 100 `Я` characters plus `.txt`/`.m4a`, UTF-8 length is 204 bytes. This arithmetic is an application example, not an API limit. |
| T9/T10/T11/T12/T18 features and requirements | Fixtures/request assertions assume forced English; T18 parks service outages only. | Update fixture metadata and auto/pinned assertions without losing failure coverage. Unsupported language is terminal, including during a scheduled recovery attempt. |
| `docs/host-setup.md` | Setup uses `-l en`; T17 documents garbled English output on Russian audio. | Preserve historical evidence, describe the T19 change and add new real-service evidence rather than relabelling T17 as a passing Russian check. |

## Confirmed design and product policy

A: Q-analyst-1 confirms first auto/verbose, later pinned/plain JSON, normalized language codes, terminal `UNSUPPORTED_LANGUAGE`, and language-specific prompt budgeting. A: Q-analyst-3 confirms that missing/null/non-string/blank language with nonempty text is malformed and follows the existing one-retry rule. An unfamiliar nonblank string is a valid unsupported result; display `unknown` unless it is a recognized name or a 2–3-letter code. These are architectural contracts, not claims that upstream guarantees such malformed answers.

Owner confirmed the silent-first-chunk exception in A: Q-analyst-2: continue automatic detection until the first nonempty chunk; all-empty input keeps NO_SPEECH. No analyst questions remain open. Detection confidence thresholds, switching among languages inside one video and model-output language repair have not been requested. Research provides no basis for silently adding them.

## Handoff verification

- Automated: exact multipart bytes/fields for detection and pinned retries; real-name/code fixtures; malformed metadata vs supported/unsupported results; all prompt stages in each language; safe Russian HTML and filenames; no model calls, deferred entries or remaining job files after refusal. `LanguageIT` is the task's required functional test target.
- Manual: a real Russian video through the bot, summary and transcript quality, English interface, both title-derived delivered filenames. Record versions, commands and observed results in `docs/host-setup.md`; no current result is claimed here.
