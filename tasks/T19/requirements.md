# T19 requirements — Russian and English video language

Status: analyst requirements complete; ready for team review. Date: 2026-11-25.

## Objective and authority

An allowed user submitting a Russian video receives a Russian transcript and Russian summary; English videos retain English output. The bot detects the source language and refuses other languages without parking them for retry. Authority: [brief.md](brief.md), T19 in [todo.md](../todo.md), [SPEC.md](../../SPEC.md), [plan.md](../plan.md), and [team answers](comms/questions.md). Requirement ids are local to T19.

This delta supersedes English-only transcription and prompt requirements in T9/T10 and downstream descriptions in T11/T12/T18. All unrelated behavior and quality gates remain in force. The task's `transcribe/WhisperClient.java` path means the existing `transcript/` package; TD12/TD13 put the Ollama boundary in `summary/OllamaLlmClient.java`.

Owner decision A: Q-analyst-2 confirms language selection/rejection on the first chunk with nonempty normalized text, allowing silent intros. This refines the brief's first-chunk wording. A: Q-analyst-3 confirms the malformed-language distinction below. No analyst questions remain open.

## Requirements and acceptance criteria

### R1 — Detect the source language through Whisper

- The first WAV chunk of each new processing attempt is sent to the configured Whisper `POST /inference` with its actual bytes as multipart `file`, `language=auto`, `response_format=verbose_json`, `translate=false`, `detect_language=false`, and `temperature=0.0` (A: Q-analyst-1). This request both detects and transcribes: do not use the detection-only switch as a substitute for transcription.
- Read top-level `text` and `language`; timestamps, segments and probability fields do not become transcript content. Accept `en`/`english` and `ru`/`russian`, trimming surrounding whitespace and comparing case-insensitively. The normalized output language is `en` or `ru`. Normalize other Whisper language names to their codes for R5 (e.g. `german` → `de`). See Architect's table contract for the complete set.
- Detection uses audio, never the video's title, URL, Telegram user's language preference or the script of a guessed transcript. A Russian title with detected English still produces English output, and conversely.
- Documentation removes forced English and uses `-l auto` explicitly; merely removing `-l en` leaves the upstream English default. Keep the multilingual `large-v3-turbo` model and transcription mode, with no translation switch.

### R2 — Use one selected language throughout a job

- After selection, every later chunk uses the same normalized code in its request (`language=en` or `language=ru`), with `response_format=json`, `translate=false`, `detect_language=false`, `temperature=0.0`. Retries preserve that chunk's request language. Later response language fields cannot change the selected language.
- The first chunk's successful text is retained once; detection does not lose or duplicate it. Process later chunks sequentially in playback order, including a short final chunk. Support all 18 ten-minute chunks of an accepted 10,800-second video.
- Language belongs to this job attempt: a subsequent independent English submission must start with `auto` even after a Russian job. A T18 retry starts from scratch and detects again; no language is stored in the deferred queue as a shortcut.
- **Confirmed by owner, A: Q-analyst-2:** Skip language selection/refusal for a successful response whose text normalizes to empty, ignore its language metadata, and send the next chunk with the R1 auto/verbose contract. Repeat until the first nonempty chunk selects the language or triggers refusal. If every chunk is empty, retain the existing NO_SPEECH outcome. Use the same Unicode-whitespace normalization as transcript merging to decide whether text is empty.

### R3 — Preserve the full source-language transcript

- Russian words and punctuation returned by Whisper remain Russian, without transliteration or translation. English words remain English. This is a transcription contract, not a guarantee that recognition is flawless.
- Apply existing T9 normalization: trim and collapse whitespace (including Unicode whitespace), skip empty chunks, join remaining chunks with one ordinary space, and append exactly one LF. Write UTF-8 `transcript.txt` only after all chunks succeed and the result is nonempty. Do not publish a partial transcript.
- After successful persistence remove temporary WAV and chunk files and retain unchanged source bytes. Failed attempts follow existing pipeline cleanup.
- A transcript button tap sends exactly the retained UTF-8 bytes, with a title-derived `.txt` name, no caption, and no new Whisper or Ollama call. No transcript upload occurs before a tap.

### R4 — Summarize in the selected language at every stage

- A short Russian transcript produces a single Ollama request explicitly asking for Russian bullet points. English asks for English bullet points.
- Every map request, intermediate reduction request and final reduction request uses the selected language. Long Russian videos must not fall back to English at the final merge. Supply the full transcript/partial summaries in order under the existing context budget.
- Preserve Ollama request safeguards through Spring AI: configured model, `stream=false`, `think=false`, explicit `num_ctx`, existing output budget, empty-content failure and silent-truncation guard. Language instructions and Unicode inputs count toward the context budget. The Architect's Russian token-estimation choice is a unit contract, not a Gherkin step about an internal algorithm.
- The displayed summary remains bullet points capped at 1,500 characters under the existing character-count rule. A successful real Russian-video check must show understandable Russian summary content. Mocks establish prompt propagation and publication of supplied model content; they cannot prove real model language quality.

### R5 — Refuse unsupported languages immediately and terminally

- When the decisive auto-detection response identifies a language other than `en`/`ru`, stop the job with kind `UNSUPPORTED_LANGUAGE` and exactly `Only English and Russian videos are supported. Detected language: <code>.` For `de` or `german`, `<code>` is `de`. For an unrecognized nonblank value, display its trimmed lower-case form only when it is 2–3 ASCII letters; otherwise display `unknown` (A: Q-analyst-3). Missing/null/non-string/blank metadata on a nonempty detect response follows R6, not this refusal.
- Do not retry a valid unsupported result, send later chunks, call Ollama `POST /api/chat`, send a successful summary/download keyboard, or upload files. Preflight health probes are allowed as before and are not inference calls.
- Delete this attempt's entire working directory, including downloaded source, WAV, chunks, transcript and job.json, without changing other jobs or access data. This refusal is not a service outage: no new deferred entry or recovery promise is allowed.
- If a previously deferred submission encounters unsupported language on recovery, preserve T18 R7/R8 terminal-failure reporting: send the safe unsupported-language error as a separate `sendMessage` to the stored chat and edit the original queued/parked status to `Failed.`. Remove its deferred entry and delete that attempt's files. Do not park it again or notify repeatedly on later scheduler passes.
- The queue remains usable: the next allowed English/Russian submission can complete normally.

### R6 — Preserve retries and distinguish outages from invalid responses

- Retain T9's exactly-one immediate retry of a failed HTTP attempt, including status errors, malformed JSON, invalid `text`, answer timeouts and connection failures. An auto-detection retry remains auto; a later chunk retry remains pinned. Do not resend earlier successful chunks.
- No default-to-English or content-script guess may mask unusable language metadata on a nonempty auto-detection response. Missing/null/non-string/blank `language` is a broken response: retry that chunk once with auto/verbose, then fail transcription if still unusable (A: Q-analyst-3). Malformed JSON and invalid `text` follow the same retry/failure rule. An unfamiliar nonblank language string is a valid unsupported result under R5.
- After a second connected response/answer failure, report `Transcription failed. Please try again later.`, stop later chunks and Ollama calls, clean the job and do not defer. A second connection failure still follows T18 outage parking and its existing English message.
- All-empty successful transcription still gives `No speech was found in this video.` without Ollama calls or deferred entries. Empty chunks remain in auto mode and their language metadata is ignored under R2.
- Ollama empty output, timeout, unreachable service, oversized input and Telegram delivery failures retain existing classification, safe copy and cleanup. T19 adds no new transport retries or model-output language checker.

### R7 — Keep the bot interface and delivery rules in English

- A Russian job still uses `Queued: position 1`, `Downloading…`, `Transcribing n/N…`, `Summarizing…`, `Done.`, `Duration:`, `Download audio` and `Download transcript`, and existing English errors/callback states/captions. Video title, transcript and model-generated summary are source content, not interface translations.
- Title/summary HTML escaping remains mandatory. Cyrillic around `<`, `>`, `&` must display literally, with no active user-supplied markup or double escaping. Keep valid HTML and the whole summary message within 4,096 characters after entity parsing.
- Keep callback data `audio:<jobId>` and `transcript:<jobId>` within 64 UTF-8 bytes. Do not include language, title, URL or paths in callbacks.
- No audio/transcript upload before a tap. Private-chat checks, current allowlist and per-job ownership, one-send button lifecycle, restart delivery, one-hour default retention and deletion after both files are delivered remain unchanged. A Russian job does not bypass these limits.

### R8 — Keep safe title-derived Unicode filenames

- Preserve the existing filename algorithm: replace forbidden filename/control characters with `_`, trim, bound the stem to 100 UTF-16 units without splitting a surrogate pair, then append `.txt` or the delivered audio extension. Empty stems retain the existing `transcript`/`audio` fallback. Do not transliterate Cyrillic or derive names from summary text.
- `Русская лекция` yields `Русская лекция.txt` and, for a retained M4A, `Русская лекция.m4a`. A title containing 101 copies of `Я` yields a stem of exactly 100 copies plus the extension (204 UTF-8 bytes for either `.txt` or `.m4a`). Safe truncation changes the upload name only; preserve the stored title and disk paths.
- Test Cyrillic in actual multipart filenames at the Telegram HTTP boundary, including sanitization and long names, and check both names in the manual Telegram run. Telegram's published Bot API does not give a universal filename-length maximum; do not label the application's 100-unit bound a Telegram rule or invent a numeric platform limit. Record observed acceptance; investigate a demonstrated platform incompatibility with Architect/owner.

### R9 — Update documentation and record a real Russian-video check

- The role owning SPEC edits must update transcription language/model-mode text, success criteria about English transcripts, English-only/out-of-scope statements and relevant downstream descriptions to say English/Russian auto-detection and terminal refusal for other languages. Translation and other-language support remain out of scope.
- `docs/host-setup.md` must show the updated server command and multipart contract, distinguish the historical T17 English-forcing failure from current behavior, and document language locking, refusal and unchanged English interface.
- Record a real Russian video's title/URL, date, relevant service versions/configuration, detected code, understandable Russian transcript excerpt and summary, observed English bot texts, both delivered filenames, and result in `docs/host-setup.md`. Do not claim this manual check happened based on WireMock or documentation research.
- The analyst supplies requirements/research/features; Developer/orchestrator own implementation, SPEC and host-operation evidence. R9 is verified by document inspection and manual evidence, not an invented automated Gherkin step.

## Verification and handoff

- Developer: `./mvnw test`; unit coverage for normalization/name-to-code mapping, unsupported vs malformed metadata, auto/pinned retry selection, all prompt variants/languages, context estimation and existing safety guards. Nonobservable choices (records, method signatures, exception plumbing, token-estimation arithmetic, filename surrogate boundaries) belong in unit tests.
- Tester: `./mvnw verify -Dit.test=LanguageIT`; exercise Telegram updates, real local media fixtures, Whisper/Ollama WireMock inference calls, persisted files and deferred queue. Cover `ru`, `en`, `de` and real Whisper full-name responses. Scenarios are in `src/features/T19-language.feature`; requirement tags refer only to this file. Finish negative assertions after the terminal Telegram event and queue drain, rather than by arbitrary sleep.
- Full task gate remains `./mvnw clean verify`, JaCoCo at least 90% lines and branches, static analysis/formatting, and the existing mutation gate at least 85%. No skipped scenarios, weakened thresholds or new suppressions are authorized.
- Existing T9 scenario/fixture setup assumes `language=en` and text-only first answers. Analyst updated the T9 first-auto/later-pinned retry wording, explicit successful JSON bodies and T10 first-response fixture; Tester must supply first-response language metadata (prefer T3's existing verbose fixture), preserve deliberately malformed cases, and keep all unrelated assertions active. T10/T11/T16/T18 fixtures similarly need detection metadata; do not inject it into a T19 malformed-response fixture. No code/test implementation belongs to this analyst step.

## Out of scope

- Translating Russian into English or English into Russian; supporting other output languages; translating titles or interface texts.
- Per-user/manual language selection, independent language detection for every chunk, mixed-language switching, confidence thresholds, language re-detection based on summary text, or model-output validation/repair.
- New queues, concurrency, retained language metadata for later reprocessing, persistence migration, changed callback payloads or a language badge in the summary.
- Fixing historic transcripts, improving Whisper recognition/hallucination quality, changing the model/backend, or changing file delivery/compression/retention limits.

## Acceptance coverage

| Requirement | Evidence |
|---|---|
| R1 | Short-language outline, metadata normalization and first-response recovery scenarios |
| R2 | Pinned chunks, 18-chunk boundary, consecutive jobs, pinned retry; three owner-confirmed silent-intro/all-silent cases (A: Q-analyst-2) |
| R3 | Exact UTF-8 transcript/normalization, final chunk and transcript upload scenarios |
| R4 | Short and 8-map/two-intermediate/one-final reduction cases in both languages; Russian cap/HTML case; existing T10 guards retained |
| R5 | Unsupported names/codes/unsafe values, recovery refusal and next-job progress |
| R6 | Malformed metadata recovery/exhaustion, pinned retry and post-detection outage; existing T9/T10/T11/T18 failure scenarios retained |
| R7 | Russian interface/no-upload/HTML/callback assertions plus unchanged T5/T11–T14 access, delivery and retention coverage |
| R8 | Ordinary, sanitized and long Cyrillic multipart upload names; existing filename unit cases retain fallback/surrogate coverage |
| R9 | SPEC/host-setup inspection and real-video evidence, not yet performed by this analyst step |
