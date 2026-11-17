# T9 requirements — transcription and merged transcript

Status: analyst draft for team design review. Date: 2026-11-17.

## Objective and authority

Transcribe the ordered WAV chunks prepared by T8 through the local whisper-server and create the complete plain-text transcript for T10/T11 and later T12 delivery. Authority: T9 in `tasks/todo.md`, the owner answers in `brief.md`, `SPEC.md`, and the real-service contract captured by T3 in `docs/host-setup.md`.

T9 depends on T3 and T8. Input is an existing job directory with `source.*`, `audio.wav`, and a nonempty ordered list of WAV chunks in `chunks/`. T8 enforces the default inclusive PT3H maximum and produces approximately ten-minute chunks; T9 must process the entire supplied list, including a short final chunk. It must not impose a smaller duration or chunk-count limit. Architectural argument and existing-transcript contracts are recorded in R9, following the answer to Q-analyst-1; these do not introduce replay/recovery.

This is a component contract, not a Telegram flow. The Gherkin examples describe HTTP requests, caller-visible progress/failure and disk effects. No new functional `*IT` tests are required in T9, as explicitly agreed in the brief. Existing project style, constructor injection and quality gates are inherited from SPEC; no production code is part of this analyst step.

## Requirements and acceptance criteria

### R1 — Explicit Whisper request contract and complete input order

Send each supplied WAV chunk sequentially to the configured `app.whisper.base-url` (`WHISPER_BASE_URL`) at `POST /inference` using multipart/form-data.

Acceptance:
- Each request contains the chunk bytes as `file`, `response_format=json`, and `language=en`, including retries. T3's optional `temperature=0.0` is permitted, not a new product requirement.
- Consume the ordered list supplied by media preparation; filesystem enumeration must not reorder or omit chunks. Do not start the next chunk before the current chunk succeeds (including its retry).
- A single short-video chunk and a list representing the full accepted three-hour video both work; the final shorter chunk is included.
- Read the top-level JSON string `text` from a successful 2xx answer. Additional fields do not become transcript content. Do not derive text from timestamps or verbose segments.
- No real host service is required by automated tests; WireMock verifies the multipart contract against T3 fixtures.

### R2 — Normalize and merge plain text

Trim every chunk's text and collapse whitespace runs, including Unicode whitespace (such as U+00A0), spaces, tabs, CR and LF, to one ordinary space. Merge nonempty normalized chunks in input order.

Acceptance:
- Text `  First\n\tpart.  ` followed by ` Second\r\npart. ` becomes `First part. Second part.\n` on disk. Here and in prose examples, backslash escapes denote actual characters.
- Preserve words, punctuation and Unicode characters. Do not translate, summarize, deduplicate, insert headings, add timestamps or append chunk numbers.
- Separate retained chunk texts by exactly one space. There is exactly one final LF newline, no leading space and no trailing space before that LF.
- Write UTF-8 to `<jobDir>/transcript.txt` only after all chunks have succeeded and the merged text is nonempty. A fresh job has no transcript while later chunks are still pending or after a terminal chunk failure; partial text is not a successful transcript.

### R3 — Empty speech is distinct from a broken response

A successful response with a string `text` that normalizes to empty is a valid empty chunk, skipped without retry.

Acceptance:
- Empty string and whitespace-only chunks contribute no separator or blank line; remaining chunks are still sent and merged.
- Empty chunks still count in progress denominator and chunk numbering.
- If every successful chunk is empty, fail with `JobFailure(NO_SPEECH)` and exactly `No speech was found in this video.`; do not create `transcript.txt`.
- Malformed JSON, missing/null `text`, a non-string `text`, or an empty HTTP body is a broken response under R4, not NO_SPEECH. A 204 with no body is therefore not an empty-speech success.

### R4 — Retry the failed chunk once and stop after a second failure

A connection error, non-2xx status, broken response or answer timeout fails an attempt. Retry that same chunk exactly once, immediately, with no scheduled delay/backoff.

Acceptance:
- Any non-2xx status, including T3's 400 plain-text `Invalid request`, is retryable once; a parseable error body must not be merged as speech.
- A retry that returns valid text or valid empty speech succeeds under R2/R3. Previously successful chunks are not resent; later chunks then continue normally.
- A second failed attempt ends transcription; no third attempt or later chunk request occurs, and no partial transcript is written.
- The outcome of the second attempt determines the terminal kind, even when it differs from the first: connection error → SERVICE_UNREACHABLE; other attempt failure → FAILED.
- Transcription request timeouts map to FAILED after retry, not the media-stage TIMEOUT kind.

### R5 — Bounded connection and answer waits

Use `app.whisper.timeout`, bound from `WHISPER_TIMEOUT`, default `PT10M`, for each chunk answer. Use a fixed five-second connection wait.

Acceptance:
- The default and an override (for example `PT2S`) reach actual request execution. The retry has its own answer wait; there is no one-budget-for-all-chunks deadline.
- A response delayed beyond the configured answer wait triggers R4. A response arriving within that wait can succeed.
- A connection deadline is bounded by five seconds; failure to establish a connection follows the connection-error rule. An established connection whose answer times out follows the answer-timeout FAILED rule.
- `SPEC.md` must list the new variable; typed properties and YAML binding must expose its default. Developer/orchestrator own those changes, not the Analyst.

### R6 — Progress before the first attempt of each chunk

Call the supplied progress callback with one-based `n` and total input chunk count `N` before sending chunk `n`.

Acceptance:
- For three chunks the callback sequence is `(1,3)`, `(2,3)`, `(3,3)`, each before that chunk's first HTTP attempt.
- Retry does not emit a duplicate callback. Empty speech does not suppress a callback or renumber later chunks.
- Terminal failure at chunk 2 of 3 leaves exactly `(1,3)`, `(2,3)` and no callback for chunk 3.
- These values allow T11 to display `transcribing n/N`; T9 itself need not send or edit a Telegram message.

### R7 — Safe, typed failure contract

Expose terminal failures as JobFailure with fixed English text suitable for future chat delivery.

| Condition | Kind | Exact message |
|---|---|---|
| All successful chunk texts empty | NO_SPEECH | No speech was found in this video. |
| Retry fails to connect | SERVICE_UNREACHABLE | The transcription server is unreachable. |
| Retry fails for another request/response reason | FAILED | Transcription failed. Please try again later. |

Acceptance:
- Add NO_SPEECH and SERVICE_UNREACHABLE without changing existing media failure meanings. T9's FAILED copy is transcription-specific; the current generic JobFailure FAILED factory message is different.
- Failure messages must not contain server response bodies, stack traces, local paths, input URLs or arbitrary server text. Diagnostic causes are not safe-message content.
- SERVICE_UNREACHABLE is only a typed failure in T9; it does not enqueue a deferred retry or promise eventual delivery.

### R8 — Release temporary audio only after transcript persistence

After successfully writing the complete nonempty `transcript.txt`, delete `audio.wav` and the `chunks/` directory with its contents. Keep the original `source.*` unchanged for later audio delivery.

Acceptance:
- Successful transcription leaves a readable transcript and unchanged source bytes, with neither `audio.wav` nor `chunks/` remaining. Other job files, such as `job.json`, are kept.
- No WAV/chunk deletion starts before the transcript write succeeds.
- On terminal HTTP/response/timeout failure, NO_SPEECH, or transcript-write failure, preserve source, WAV, chunks and unrelated job files; T11 owns eventual failure-directory cleanup.
- Persist atomically: the final transcript is the complete new text or remains untouched. An IOException while writing gives FAILED with `Transcription failed. Please try again later.` and preserves inputs; no partial final transcript is exposed.
- Per the Architect’s answer to Q-analyst-1, a deletion error after persistence is logged at WARN with the file name only; the other deletions are still attempted, the result stays success, and completed deletions are not rolled back. T11/T14 handle leftovers. The clean-disk success criterion above applies when deletion operations succeed.

### R9 — Architectural caller and existing-artifact contracts

These are component argument contracts from `api.md`, `flows.md` and A: Q-analyst-1, not additional Telegram product behavior.

Acceptance:
- A null argument is a programming error and gives NullPointerException. An empty chunk list gives FAILED without progress or requests; a job directory that is not an existing directory gives FAILED without requests.
- A missing or unreadable chunk is an ordinary nonconnection attempt failure: retry once, then FAILED with the transcription-specific message; preserve inputs and stop later chunks.
- Supplied list order wins even when it differs from filename order. A supplied chunk outside the job's chunks directory is accepted; T8 is responsible for supplying the normal paths.
- An existing transcript is never read for resume. Success atomically replaces it; chunk failure, NO_SPEECH or write failure leaves its bytes untouched.
- A throwing progress callback propagates its original exception unchanged before that chunk is sent. No transcript is written and no cleanup begins.

## Verification and handoff

- Developer: `./mvnw test -Dtest=TranscriptionClientTest`, with WireMock and temporary directories, using `whisper-inference-ok.json` and `whisper-inference-bad-request.txt` from T3. Add focused merger/configuration tests as needed. Verify request bytes/fields, order, malformed responses, normalization, UTF-8, retry success/exhaustion and mixed failure kinds, progress ordering, deadlines, no partial transcript on chunk failure and disk cleanup/preservation.
- Use short configured answer timeouts in tests; do not wait ten minutes. A localhost refused-connection test proves error classification, not a five-second connection deadline. Verify connection-deadline wiring separately with controlled transport behavior; an ordinary delayed WireMock response only tests the answer timeout.
- Tester: `src/features/T9-transcription.feature` supplies acceptance examples for later integration. The current Cucumber runner excludes only T8 and TD2. Exclude `@T9` during T9 (or an equivalent scoped mechanism) so these unwired steps do not break `verify`; keep existing scenarios enabled. A: Q-analyst-2 confirms the Tester will apply `not @T8 and not @TD2 and not @T9` in Step 2; Step 1 design is approved, and no test code changes are needed in this analyst step. T11 will integrate the Telegram behavior and corresponding steps.
- Project gate before the orchestrator commits: `./mvnw clean verify`, including at least 90% line and branch coverage, Spotless, SpotBugs and PMD. This document does not claim that implementation tests have run or passed.
- Review the draft with Developer, Tester and orchestrator under the existing team workflow. Open design clarifications live in append-only `comms/questions.md`.

## Scope and SPEC consistency

No Telegram handlers/messages/buttons, authorization changes, job queue/store/state integration, summaries/Ollama, file uploads, audio delivery encoding/50 MB logic, new chunk splitting/duration validation, retention/restart recovery, or pipeline failure-directory deletion. Non-English transcription and translation remain outside V1. No new database, host-service deployment or real-service functional `*IT` tests.

The owner's DLQ/equivalent failure query, two-hour recovery polling and temporary-unavailability notification are explicitly deferred by the brief to a separate intake after T11, with T14/retention constraints to resolve there.

SPEC requires transcript creation when transcription finishes but its Never section also literally forbids creating it before a button tap. The explicit T9 task/brief requires pre-summary disk creation; delivery waits for T12. Q-analyst-3 asks the orchestrator to align the stale sentence and add WHISPER_TIMEOUT. No new product decision is assumed by documenting the already agreed T9 behavior.
