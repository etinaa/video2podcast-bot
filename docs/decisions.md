# Decisions

## D1: Telegram library (T2)

**Decision:** use `org.telegram:telegrambots-springboot-longpolling-starter` 10.3.0, plus
`telegrambots-client` 10.3.0 for sending. No fallback is needed.

**Evidence (Java 27.0.1, Spring Boot 4.1.2):**
- The starter's auto-configuration (`TelegramBotStarterConfiguration`) loads on Boot 4. It finds our
  `TelegramBot` bean (a `SpringLongPollingBot`) and registers it at startup.
- Started with a fake token, the app reaches Telegram (`deleteWebhook`) and fails fast with
  `401`. A real token proceeds to long polling.
- `telegrambots-client` is not pulled in by the starter, so it is declared explicitly.

**Consequences:**
- The bot token is validated at startup: a wrong token stops the application with a 401 error
  instead of failing quietly later.
- The Telegram integration is disabled in tests with `app.telegram.enabled=false`
  (`src/test/resources/application.properties`), so tests never reach the network. It is on by
  default (`TELEGRAM_ENABLED`). With the bot enabled, a missing token fails with a clear message.
- The rest of the code talks to Telegram only through `TelegramGateway`. `TelegramClientGateway` is
  tested against WireMock using `TelegramUrl` to point the client at a local server.
- The Telegram client lower-cases method names in request paths (`/sendmessage`). Telegram accepts
  this, and the WireMock stubs rely on it.

## D2: Allowlist persistence

**Decision:** `allowed-users.json` is read and written with Jackson 2 (`jackson-databind` and
`jackson-datatype-jsr310`, already on the classpath through the Telegram libraries, now declared
explicitly). Writes go to `<file>.tmp`, are flushed, then renamed over the target (atomic move).

**Why Jackson 2:** Spring Boot 4 defaults to Jackson 3, but the Telegram client needs Jackson 2
anyway. Using it avoids a second JSON stack. Switching later only touches `AccessStore` (and
`JobStore` from T7).

**Fail closed:** a corrupt file is renamed aside and the bot starts with an empty allowlist, so an
unreadable file can never grant access. Only the owner (from `ADMIN_USER_ID`) works until users are
approved again. Denials are persisted too, so the cooldown survives restarts.

## D3: Job state on disk

**Decision:** each job has a directory `<WORK_DIR>/<jobId>/` with a `job.json` that is the only
source of truth about it. `JobStore` changes a job only under that job's own lock and writes the
file atomically (the same `AtomicFiles` helper the allowlist uses). `transition(jobId, kind,
expected, next)` is a compare-and-set, so when two taps race exactly one gets `APPLIED`.

**Job ids** are random UUIDs (36 characters, so `transcript:<jobId>` stays under Telegram's 64-byte
callback limit). An id is validated against `[A-Za-z0-9_-]{1,40}` before it is turned into a path, so
a forged callback can never point outside the work directory. File names inside `job.json` must be
plain names without separators.

**File states:** `AVAILABLE -> SENDING -> SENT`, `SENDING -> AVAILABLE` (failed send or restart
recovery), and `AVAILABLE -> EXPIRED`. `SENT` and `EXPIRED` are final. A `job.json` that is missing,
not valid JSON or incomplete is reported as "no such job" so the buttons answer "expired".

**Queue:** `JobQueue` is one worker thread. A task that throws is logged and its future fails, and
the next task still runs. `close()` waits up to 30 seconds for queued tasks.


## D4: Media wrappers (T8)

**Decision:** `yt-dlp` and `ffmpeg` run only through the `ProcessRunner` interface (argument list,
no shell, timeout, process tree killed on timeout). Failures are `JobFailure(Kind, safeMessage)`;
raw stderr goes to the log only. Metadata comes from `yt-dlp --dump-json`, the URL always follows
`--`, and `UrlValidator` runs inside `YtDlpClient` so no caller can skip it.

**Consequences:** the functional harness (T4a/T11) replaces `ProcessRunner` with a fake. Live
streams (no duration) are `UNSUPPORTED`. DNS names are not resolved, so a public name that points
to a private address is not caught by the validator (accepted; the container has no access to
private services other than the host ones it is meant to reach).

**Approved exception (T8):** the owner approved one SpotBugs exclusion, `COMMAND_INJECTION` in
`DefaultProcessRunner` only (`config/spotbugs-exclude.xml`), because find-sec-bugs flags any
non-constant argument list and a typed command did not help (spike in T8). No PMD suppression is
approved. The exclusion is tracked as technical debt in `tasks/todo.md` (TD1).

**Narrowed (TD1, 2026-11-23):** the owner replaced the class-wide filter file with one
`@SuppressFBWarnings("COMMAND_INJECTION")` on `DefaultProcessRunner.Child.start`, with a
justification in the code. `config/spotbugs-exclude.xml` is gone. The newest find-sec-bugs
(1.14.0) and SpotBugs (4.10.5) still flag the call, so the one suppression stays.

## D5: Queue size is updated before the future completes (TD2, 2026-11-17)

**Decision:** `JobQueue.run` removes a task from `open` before it completes the task's future, on all
paths (success, `RuntimeException`, `Error`). Fixed in production code, not with a bounded wait in the
test, because `size()` is the queue position T11 shows to users.

**Consequences:** a caller that reads `size()` after `get()` or inside a `whenComplete` callback never
sees the finished task. Callbacks run on the worker thread, so they must not block.

## D6: Transcription client (T9, 2026-11-17)

**Decision:** `WhisperClient` makes one `POST /inference` call with the JDK `HttpClient` (no new
dependency) and reports `WhisperException(connection)`. `TranscriptionClient` owns the policy: one
immediate retry per chunk, the retry's failure decides `SERVICE_UNREACHABLE` or `FAILED`, an
all-empty transcript is `NO_SPEECH`. `transcript.txt` is written atomically only after every chunk
succeeded; then `audio.wav` and `chunks/` are deleted best effort (a deletion error is logged and
does not fail the job). `source.*` stays. On failure nothing is deleted; the pipeline cleans the
job directory.

**Consequences:** `JobFailure.Kind` gains `NO_SPEECH` and `SERVICE_UNREACHABLE`; transcription
`FAILED` has its own message through `JobFailure.transcriptionFailed`. `SERVICE_UNREACHABLE` is the
typed hook for the future dead-letter-queue task. There is no per-chunk resume: a rerun sends all
chunks again.

## D7: Mutation testing with PIT in a separate profile (TD3, 2026-11-17)

**Decision:** `pitest-maven` runs only in the Maven profile `mutation`, over `com.video2podcast.*` in `src/main`, with the unit tests of `src/test` only (`com.video2podcast.functional.*`, the ITs and Cucumber steps, is excluded). The build fails when the mutation score is below 85% (`pit.mutation.threshold`). Default `./mvnw verify` and `-Dno-it` do not run PIT.

**Why:** JaCoCo (90%) shows which lines run; PIT shows whether a test notices a wrong result. A separate profile keeps the default build fast. `src/it` is left out because it is owned by the Tester, runs the whole application and would make a PIT run very slow.

**Consequences:** unit tests must assert behaviour, not only execute lines. Exclusions of classes from PIT are listed in `pom.xml` with a reason and need the owner's approval.

## D8: Summary client and map-reduce (T10, 2026-11-17)

**Decision:** `SummaryService` talks to a backend-neutral `LlmClient`; `OllamaClient` (JDK `HttpClient`) sends `num_ctx`, `num_predict`, `think:false` and the model on every request. Chunk size comes from one formula (`numCtx - 1024 output reserve - longest prompt - 32 template overhead`, 3 characters per token, deliberately high). Truncation is detected by comparing `prompt_eval_count` (measured: the cached count is a subset of it, so it is not added) with the same estimate: below half of it fails the job. When the partial summaries do not fit one request they are grouped in order and reduced in rounds (at most 8); every intermediate answer is clipped to a character bound so a group always fits. Only the final answer is normalized to `• ` bullets and capped at 1,500 UTF-16 units. There is no retry in T10.

**Consequences:** new settings `OLLAMA_NUM_CTX` (8192) and `OLLAMA_TIMEOUT` (PT10M). `JobFailure.Kind` gains `SUMMARY_UNREACHABLE`; an answer timeout is `TIMEOUT`, everything else `FAILED` with its own summary message. A `numCtx` below about 3,300 is rejected at start. The estimate is a heuristic: if T17 shows real prompts far below the estimate, only the constants change.

## D9: Summary pipeline and status messages (T11, 2026-11-19)

**Decision:** `PipelineService` (in `job/`) runs the stages in one fixed order and owns the cleanup: any failure deletes the job directory, and `job.json` is written only after the summary exists. It reaches Telegram only through two small interfaces (`JobProgress`, `SummaryPublisher`), so `job/` does not import `bot/`. `UrlMessageHandler` validates the link before queueing (a rejected link costs no queue slot and no status message), sends the first status message (which is the acknowledgement) with `Queued: position N` where N is `JobQueue.size()` read right after `submit`, and edits it through the stages. The worker task waits for that message id before it starts. Progress edits are best effort: a failed edit never aborts a job. The failure message and the final `Done.`/`Failed.` edit are sent after the job has left the queue (from the future's completion on a virtual thread), so a user's next link never counts a job that has already reported its result. The summary is a new message (an edit sends no notification, and a long job needs one). The title in the message is cut to 200 UTF-16 units, which keeps the parsed text far below Telegram's 4,096 without touching T10's 1,500-character summary cap.

**Consequences:** the delivery buttons exist from T11 but do nothing until T12 and T13. A `job.json` is absent while a job runs; T14's startup scan removes directories left that way by a crash. Status texts are fixed strings (`Queued: position N`, `Downloading…`, `Transcribing n/N…`, `Summarizing…`, `Done.`, `Failed.`).

## D10: SpotBugs exclusion for `PipelineService` (T11, 2026-11-19; superseded in T12, 2026-11-19)

**Superseded:** in T12 the owner chose to remove the cause instead of adding a second exclusion. `JobStore.delete` was renamed to `discard` (SpotBugs treats a class as mutable by method-name heuristics such as `delete`), so no class that stores a `JobStore` is flagged, and the `PipelineService` exclusion was deleted from `config/spotbugs-exclude.xml`. The text below is the original decision.

**Decision:** `config/spotbugs-exclude.xml` excludes `EI_EXPOSE_REP2` for `com.video2podcast.job.PipelineService` only. The class keeps a reference to `JobStore`, a shared service; SpotBugs treats it as a mutable value object because it has a `delete` method. The owner approved this exclusion on 2026-11-19 (SPEC.md requires approval for every suppression).

**Consequences:** no other class or pattern is excluded. A new `EI_EXPOSE_REP2` finding elsewhere still fails the build.

## D11: Button taps split into state and Telegram parts (T12, 2026-11-19)

**Decision:** `job/DeliveryService` owns the rule that a file is sent once: `claim` moves `AVAILABLE` → `SENDING` through `JobStore.transition` (one winner per race), `complete` and `release` finish or undo it. `bot/DeliveryCallbackHandler` does the Telegram calls and never touches `job.json`. The buttons on the summary message are changed with a new `TelegramGateway.editButtons` (`editMessageReplyMarkup`), because `job.json` does not keep the summary text and `editMessage` would need it. A tap is answered once ("Sending transcript…" or a refusal); a failed send is reported with a separate message.

**Consequences:** T13 adds the `audio:` branch and calls the same `claim`/`complete`/`release` and `Keyboards.delivery(jobId, audio, transcript)`. If the bot dies between `claim` and `complete` the file stays `SENDING` until T14's startup recovery.

## D12: Audio preparation rule details (T13, 2026-11-19)

**Decision:** The Telegram limit is taken as 50 MiB (52 428 800 bytes), because the SPEC target of 48 MiB already exceeds 50 000 000 bytes. A source is sent unchanged when it is at most the limit and its extension is `mp3` or `m4a`; an encoded result must be strictly under the limit. Bitrate is `floor(48 MiB × 8 / seconds / 1000)` kbps clamped to 8..128; the retry uses 80% of it. A conversion at 128 kbps that comes out too big falls through to the compression path. `AudioPreparer` uses the duration from `job.json`, with no ffprobe. The compression note is the `sendAudio` caption, so the gateway signature gains `fileName` and `caption`. The performer is the constant `Video2Podcast`: the job stores no uploader.

**Consequences:** audio preparation runs on the shared `JobQueue`; a claim made before expiry finishes after it, so T14's sweeper must skip a job with a `SENDING` file. If the owner wants the real uploader as performer, a later task adds `uploader` to `job.json`.

## D13: Retention sweeper and restart recovery (T14, 2026-11-21)

**Decision:** `job/RetentionSweeper` runs `JobStore.recover` once at startup (bean init method, so before long polling starts) and `JobStore.sweep` every `app.jobs.sweep-interval` (default `PT1M`, `fixedDelay`). Both store operations run under the job lock, so a tap and the sweeper never interleave; any `SENDING` file protects the whole directory. There is no persisted job status: a directory without a usable `job.json`, or with `expiresAt == null`, is a job still being processed at runtime (left alone) and an orphan at startup (removed). Expiry uses each file's stored `expiresAt` and never `createdAt`, because `createdAt` is the request time and a long pipeline would otherwise lose its retention. Buttons are refreshed through the `ButtonRefresher` interface (best effort) from the stored file states, so a sent file keeps its "sent" label. A directory whose files are all `SENT`/`EXPIRED` is removed on the next pass, not at the moment of the last send.

**Consequences:** a job directory lives up to one interval longer than strictly needed. A crash orphan lives until the next restart. `AppProperties.Jobs` gains `sweepInterval`. If Telegram is disabled the sweeper still runs with a no-op refresher.

## D14: Health checks at startup and before every job (T16, 2026-11-22)

**Decision:** `job/HealthChecks` probes Whisper (`GET /`) and Ollama (`GET /api/version`) in parallel with one timeout (`app.health.timeout`, default `PT3S`). Any HTTP status counts as reachable; no health route is required and the body is ignored. At startup it only logs one line per service, on a virtual thread after the context is ready, so the bot starts either way. Before every job `PipelineService.execute` calls `requireReachable()` as its first statement, on the job worker: it is after queueing, before any directory or download, and never blocks the single Telegram polling thread. A down service gives the existing `JobFailure` messages; both down gives one message with both sentences and kind `SERVICE_UNREACHABLE`. There is no periodic check.

**Consequences:** the user sees `Queued: position N` first and then the failure within the timeout. A down job holds the worker for at most the timeout. A service that answers HTTP but is broken is still found by the T9/T10 failure paths. Mid-job failures are unchanged.

## D15: Codec check before sending audio unchanged (T17, 2026-11-23)

**Decision:** D12's "send unchanged by extension" is narrowed: a source at most the limit goes out unchanged only as `m4a` with codec `aac` or `mp3` with codec `mp3`. The codec comes from `ffprobe` (first audio stream) through `FfmpegRunner.probeAudioCodec`. Any other small source, or one whose codec cannot be read, is converted to M4A at 128 kbps, and `encodeM4a` now always passes `-ac` (1 for mono, 2 otherwise), so 5.1 sources come out as stereo.

**Consequences:** the smoke test found YouTube serving `.m4a` with an E-AC-3 5.1 track that Telegram's player cannot play. The cost is one short `ffprobe` call for small `mp3`/`m4a` sources. Oversized sources and other extensions are not probed.
