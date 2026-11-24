# Task List: video2podcast-bot — V1

Source: `SPEC.md`, plan in `tasks/plan.md`. Package root: `com.video2podcast`.
Sizes: S = 1-2 files, M = 3-5 files.

**Status:** `[ ]` not started, `[~]` in progress with the team, `[x]` done (see `CLAUDE.md`).

**Process:** every task runs through the team of role agents (`.claude/skills/orchestrate/SKILL.md`). Its working files are in `tasks/T<N>/`, and its commits are `T<N>: design`, `T<N>: <title>`, `T<N>: acceptance tests` and `T<N>: done`.

**Tests:** functional tests (`*IT`, Cucumber steps) are written by the Tester in `src/it/java` from the Analyst's scenarios in `src/features`. Unit tests are written by the Developer.

## Phase 1: Foundation and risk spikes

- [x] **T1: Project skeleton on Java 27 / Spring Boot 4.1.2** (M)
  - Acceptance:
    - Maven project with the Maven wrapper, `<java.version>27</java.version>`, Spring Boot 4.1.2, JUnit 5, Mockito, WireMock.
    - Spotless (google-java-format) and JaCoCo are configured and work on Java 27.
    - Typed `@ConfigurationProperties` for all env vars in the spec, with defaults, and a context-loads test.
  - Verify: `./mvnw spotless:apply && ./mvnw verify` passes.
  - Depends on: none
  - Files: `pom.xml`, `Video2PodcastApplication.java`, `config/*Properties.java`, `application.yml`, one context test

- [x] **T2: Telegram long-polling spike (`/whoami`)** (S)
  - Acceptance:
    - The bot connects with long polling on Boot 4.1.2 and replies to `/whoami` with the sender's id, in private chats only.
    - The chosen library approach is recorded in `docs/decisions.md` (starter, or the fallback if the starter fails).
    - The bot's Telegram access sits behind a small `TelegramGateway` interface (send message, edit message, send document/audio, answer callback) used by later tasks.
  - Verify: unit test for the `/whoami` handler with a fake gateway. Manual: send `/whoami` to the real bot and get the id back.
  - Depends on: T1
  - Files: `bot/TelegramGateway.java`, `bot/BotUpdateHandler.java`, `docs/decisions.md`, test

- [x] **T3: Host-service contracts and fixtures** (S)
  - Acceptance:
    - Real `whisper-server` and Ollama (`qwen3:32b`) were called by hand with a small sample. Their exact endpoints, request fields, and response shapes are documented in `docs/host-setup.md`. The Ollama request shows `num_ctx` taking effect.
    - Sample responses are saved as WireMock fixtures in `src/test/resources/fixtures/`.
    - `docs/host-setup.md` has the install and run steps for both on macOS.
  - Verify: the documented `curl` commands run successfully against the host services. Manual check by the owner.
  - Depends on: T1
  - Files: `docs/host-setup.md`, `src/test/resources/fixtures/*.json` (no production code)

### Checkpoint 1: Foundation
- [x] `./mvnw verify` passes on Java 27 with Spotless and JaCoCo
- [x] The bot answers `/whoami` from a real Telegram account
- [x] Whisper and Ollama contracts are documented and match the spec's assumptions (or the spec is updated)
- [ ] Review with human before proceeding

## Phase 2: Access control

- [x] **T4a: WireMock functional-test harness** (M) — added at the owner's request; every later task adds its own functional tests on top of it
  - Acceptance:
    - A reusable test support (JUnit extension or base class) starts WireMock servers for the Telegram Bot API, Whisper and Ollama, boots the full Spring context against them (fake token, no real network), and offers helpers to feed an `Update` to the bot and to assert on the requests WireMock received.
    - The Telegram base URL is configurable (`app.telegram.api-url`, default the real API) so the production client and the long-polling starter can be pointed at WireMock. In functional tests the bot is driven directly and long polling does not run.
    - The `/whoami` flow is covered as the first functional test (`WhoamiIT`): a private message gets one `sendMessage` with the sender's id, and a group message gets none.
    - Functional tests are named `*IT`, run in `./mvnw verify`, need no network and no secrets, and do not count toward the coverage gate.
  - Verify: `./mvnw verify` passes with `WhoamiIT` included.
  - Depends on: T4
  - Files: `config/TelegramConfig.java`, `application.yml`, `src/it/java/com/video2podcast/functional/*` (support and `WhoamiIT`)

- [x] **T4: Allowlist store and access service** (M)
  - Acceptance:
    - `AccessStore` reads and writes `allowed-users.json` atomically (temp file and rename) behind a lock, with an in-memory cache and reload on start.
    - `AccessService` implements: the owner always allowed and not removable, add and remove users, a pending request per user, a 24-hour deny cooldown, and rate limiting of refusal replies (1 per minute per user).
  - Verify: `./mvnw test -Dtest='AccessStoreTest,AccessServiceTest'` covers add and remove, pending lifecycle, cooldown (with a fake clock), rate limit, a corrupted file, and reload after restart.
  - Depends on: T1
  - Files: `access/AccessStore.java`, `access/AccessService.java`, `access/AllowedUser.java`, `access/PendingRequest.java`, tests

- [x] **T5: Authorization gate for messages and callbacks** (S)
  - Acceptance:
    - Every update passes one gate before any handler. Group and channel updates are ignored.
    - An unknown user gets the rate-limited refusal ("No access. Send /start to request it. Your id: …"), and `/start` from an unknown user creates a pending request and replies "Access requested".
    - `/whoami` works for everyone. Allowed users get a help text on `/start`.
    - Functional test `AccessGateIT`: an unknown user gets one refusal per minute and `/start` creates a request; an allowed user gets the help text; `/whoami` works for everyone; a group message gets no reply.
  - Verify: `./mvnw test -Dtest=AuthorizationGateTest` covers owner, allowed, unknown, group chat, callback from unknown, and `/whoami` for everyone.
  - Depends on: T2, T4, T4a
  - Files: `access/AuthorizationGate.java`, `bot/BotUpdateHandler.java`, tests

- [x] **T6: Owner commands and the Approve/Deny flow** (M)
  - Acceptance:
    - On a new request the owner gets "Access request from <name> (@username, id N)" with **Approve** and **Deny** buttons (`access:approve:<id>`, `access:deny:<id>`). Names are HTML-escaped.
    - Approve adds the user and notifies them. Deny removes the request, notifies them, and starts the cooldown.
    - `/allow <id>`, `/deny <id>`, and `/users` work for the owner only. The owner can't be removed. Events are logged (who, whom, when).
    - Pending requests and their buttons survive a restart.
    - Functional test `AccessApprovalIT`: an unknown user's `/start` makes the owner receive the request with Approve and Deny buttons; tapping Approve notifies the user and lets them in; tapping Deny refuses them and the cooldown applies; a non-owner tap is rejected.
  - Verify: `./mvnw test -Dtest=AdminCommandsTest` covers owner-only enforcement, approve and deny, cooldown, notifications, a restart with pending requests, and HTML escaping.
  - Depends on: T5
  - Files: `access/AdminCommands.java`, `access/AccessRequestFlow.java`, `bot/Keyboards.java`, tests

### Checkpoint 2: Access control
- [x] All tests pass
- [ ] Manual: a second Telegram account sends `/start`, the owner approves from chat, and the second account is let in. A denied account is refused for 24 hours.
- [ ] Review with human before proceeding

## Phase 3: Summary pipeline

- [x] **T7: Job model, `job.json` store, and single-worker queue** (M)
  - Acceptance:
    - `JobStore` writes and reads `job.json` atomically with `jobId`, `ownerUserId`, `chatId`, `summaryMessageId`, title, duration, `createdAt`, and per-file `{path, state, expiresAt, sentAt}`.
    - File states: `AVAILABLE`, `SENDING`, `SENT`, `EXPIRED`. Transitions are enforced and guarded by a per-job lock.
    - A single-thread `JobQueue` runs tasks one at a time, in order, and one failing task doesn't stop the queue.
  - Verify: `./mvnw test -Dtest='JobStoreTest,JobQueueTest'` covers round-trip, invalid transitions, concurrent transition attempts (one wins), and queue ordering.
  - Depends on: T1
  - Files: `job/JobStore.java`, `job/JobRecord.java`, `job/FileState.java`, `job/JobQueue.java`, tests

- [x] **T8: Media: URL validation, metadata, download, WAV chunks** (M)
  - Acceptance:
    - `ProcessRunner` is an interface, `run(List<String> args, Duration timeout)`, with a real implementation that runs an argument list (no shell strings), captures stdout and stderr, and on timeout kills the process and its children and fails with `JobFailure` (`TIMEOUT`). Timeouts are configurable under `jobs.*`, defaults: metadata 1 min, download 30 min, ffmpeg 30 min.
    - `UrlValidator` accepts only `http`/`https` URLs with a host, up to 2048 characters, no whitespace or control characters, no `user:password@`, and rejects `localhost` and literal loopback, private and link-local IPs. DNS names are not resolved. The URL is always passed to `yt-dlp` after `--`.
    - Metadata (title, duration) comes from `yt-dlp`. A video over 3 hours (exactly 3 hours passes) is rejected before download. A missing duration (live stream) is rejected as `UNSUPPORTED`.
    - Audio download prefers m4a (`bestaudio[ext=m4a]/bestaudio`). `FfmpegRunner` creates a 16 kHz mono WAV and splits it into about 10-minute chunks. Audio preparation for Telegram (`AudioPreparer`, re-encoding, the 50 MB rule) is out of scope (T13).
    - Errors map to a `JobFailure` with a `Kind` (`PRIVATE`, `UNSUPPORTED`, `TOO_LONG`, `FAILED`, `TIMEOUT`) and a user-safe message. `yt-dlp` stderr is matched case-insensitively by substrings from one table (private/sign in/members-only/age → `PRIVATE`, unsupported URL → `UNSUPPORTED`, any other non-zero exit → `FAILED`). Raw stderr goes to the log only, never to the chat.
  - Verify: `./mvnw test -Dtest='UrlValidatorTest,YtDlpClientTest,ChunkSplitterTest'` with a stubbed `ProcessRunner`. Manual: download a short real video in the dev profile.
  - Depends on: T1
  - Files: `media/ProcessRunner.java`, `media/UrlValidator.java`, `media/YtDlpClient.java`, `media/FfmpegRunner.java`, `job/JobFailure.java`, `config/AppProperties.java`, `application.yml`, tests

- [x] **T9: Transcription client and merged transcript** (S)
  - Acceptance:
    - `TranscriptionClient` sends each WAV chunk of a job, in order, to `whisper-server` (`POST /inference`, `response_format=json`, language `en`) and merges the results in order into `transcript.txt` in the job directory (UTF-8, chunks joined by one space, one trailing newline, no header or timestamps).
    - Each chunk's text is trimmed and its whitespace (including inner newlines) is collapsed before merging. A chunk that is empty after that is skipped. If the whole transcript is empty, the job fails with `JobFailure(NO_SPEECH)` ("No speech was found in this video.") and no `transcript.txt` is written.
    - A failed chunk (connection error, non-2xx answer, broken response, timeout) is retried once, immediately. A second failure stops the job: a connection error on the retry gives `JobFailure(SERVICE_UNREACHABLE)` ("The transcription server is unreachable."), any other failure gives `JobFailure(FAILED)` ("Transcription failed. Please try again later."). The remaining chunks are not sent.
    - A progress callback is called with `n` and `N` ("transcribing n/N") before chunk `n` is sent, once per chunk (not again on the retry).
    - The wait for one chunk's answer is `app.whisper.timeout` (env `WHISPER_TIMEOUT`, default `PT10M`); the connection wait is a fixed 5 seconds. A timeout counts as a failed chunk. `SPEC.md` lists the new variable.
    - After `transcript.txt` is written, `audio.wav` and the `chunks/` directory are deleted. The source audio (`source.*`) is kept. On failure nothing is deleted (the pipeline cleans the job directory, T11).
  - Verify: `./mvnw test -Dtest=TranscriptionClientTest` using the T3 fixtures in WireMock (merge order, whitespace collapsing, empty chunk and empty transcript, retry, unreachable, `400`, timeout, progress callbacks, file deletion).
  - Depends on: T3, T8
  - Files: `transcript/TranscriptionClient.java`, `transcript/TranscriptMerger.java`, `job/JobFailure.java` (kinds `NO_SPEECH`, `SERVICE_UNREACHABLE`), `config/AppProperties.java`, `application.yml`, `SPEC.md`, tests

- [x] **T10: Ollama client and map-reduce summary** (M)
  - Acceptance:
    - The `LlmClient` interface has an Ollama implementation that sets `num_ctx` and the model on every request.
    - `SummaryService` splits the transcript into chunks that fit the context, summarizes each chunk, then summarizes the partial summaries. A short transcript is summarized in a single call.
    - Every request sends `think: false`. An empty `content` is a failure. `prompt_eval_count` is checked against the expected input size, and a silently truncated prompt fails the job (Ollama does not return an error when the prompt exceeds `num_ctx`).
    - The output is English bullet points, capped at 1,500 characters. An unreachable Ollama gives a clear failure.
  - Verify: `./mvnw test -Dtest='OllamaClientTest,SummaryServiceTest'` with WireMock (single call, map-reduce call count, chunk size fits `num_ctx`, length cap, unreachable).
  - Depends on: T3
  - Files: `summary/LlmClient.java`, `summary/OllamaClient.java`, `summary/SummaryService.java`, `summary/Prompts.java`, `job/JobFailure.java` (kind `SUMMARY_UNREACHABLE`), `config/AppProperties.java`, `application.yml`, `SPEC.md`, tests

- [x] **T11: Link to summary message with buttons (end-to-end)** (M)
  - Acceptance:
    - An allowed user's URL message gets an acknowledgement within 5 seconds, then one status message that is edited through the stages (queued, downloading, transcribing n/N, summarizing). On the queued stage the status text shows the queue position, `JobQueue.size()` counted when the job is added (the first job in an empty queue shows position 1).
    - On success the bot sends the HTML message (title, duration, bullets) with **Download audio** and **Download transcript** buttons (`audio:<jobId>`, `transcript:<jobId>`) and saves `job.json` with `expiresAt` = send time + 1 hour. **No audio or transcript file is sent.**
    - Failures produce a clear message and remove the job directory. Title and summary are HTML-escaped.
    - The tag filters `not @T8`, `not @T9`, `not @TD2` and `not @T10` in `CucumberIT` are removed (`not @TD2` is removed too: the TD2 scenarios go through the queued status text since TD4; `JobQueueTest` still covers the queue itself), and `src/features/T8-media.feature`, `src/features/T9-transcription.feature` and `src/features/T10-summary.feature` go through the external boundary (T8 and T9 were rewritten in TD4), so this task writes the step definitions for them (a link message to the bot, files in the job directory, Whisper and Ollama as WireMock servers). Real `yt-dlp` and `ffmpeg` run in the success scenarios (TD4: a proxy decorator over `ProcessRunner`, fixtures generated by `ffmpeg`); a fake `ProcessRunner` is only for failures that cannot be produced for real. The `@manual_media` scenarios stay manual.
    - Functional test `SummaryFlowIT` (replaces `PipelineIntegrationTest`): link, status edits, final message with two buttons, `job.json`, no file uploads; Whisper and Ollama are WireMock servers built from the T3 fixtures and `yt-dlp` and `ffmpeg` are real in the success path (see TD4).
  - Verify: `./mvnw verify -Dit.test=SummaryFlowIT` (functional test on the T4a harness): link, status edits, final message with two buttons, `job.json` content, no file uploads. Failure paths clean up the directory.
  - Depends on: T5, T7, T8, T9, T10, TD4
  - Files: `job/PipelineService.java`, `bot/UrlMessageHandler.java`, `bot/SummaryMessageFormatter.java`, `bot/BotUpdateHandler.java`, test

### Checkpoint 3: Summary pipeline
- [x] All tests pass and `./mvnw verify` meets the coverage target
- [x] Manual with real services: a short real video produces a summary message with both buttons and nothing else (checked by the owner 2026-11-19 on a 125-second YouTube trailer: 40 seconds end to end, summary with title, duration, 9 bullets and both buttons, no files; the intermediate status stages `Transcribing` and `Summarizing` were not seen live, the owner accepted the checkpoint on the strength of `SummaryFlowIT`)
- [ ] Review with human before proceeding

## Phase 4: On-demand delivery and cleanup

- [x] **T12: Download transcript button** (S)
  - Acceptance:
    - Tapping **Download transcript** from the job's owner sends `<title>.txt` with `sendDocument`. The file state goes `AVAILABLE` → `SENDING` → `SENT`, and the button changes to "Transcript sent". The audio button stays active.
    - A second tap, or a tap at the same moment, sends the file once. A tap from another user is rejected. A tap on an expired or missing job gives "expired, please resend the link".
    - Functional test `TranscriptButtonIT`: the tap sends the `.txt` once through `sendDocument`; a second tap, another user, and an expired job are handled.
  - Verify: `./mvnw test -Dtest=TranscriptButtonTest` covers the happy path, double tap, concurrent taps, another user, and expired.
  - Depends on: T11
  - Files: `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, `bot/Keyboards.java`, test

- [x] **T13: Audio preparation and Download audio button** (M)
  - Acceptance:
    - `AudioPreparer` follows the compression rule: a file of 50 MB or less in MP3 or M4A is returned unchanged. A smaller file in another format is converted to M4A at 128 kbps. A file over 50 MB is re-encoded to mono AAC at `48 MiB × 8 / duration`, verified under 50 MB, with one retry at a lower bitrate.
    - Tapping **Download audio** goes through the job queue, shows "Preparing audio…", sends with `sendAudio` (title and performer set), and notes whether and how it was compressed. The same one-send, owner-only, and expired rules as T12 apply.
    - Functional test `AudioButtonIT`: the tap sends audio through `sendAudio`, unchanged and compressed cases, with the same one-send and owner-only rules.
  - Verify: `./mvnw test -Dtest='AudioPreparerTest,AudioButtonTest'` covers each branch of the rule (the unchanged branch must return the same path), the bitrate for a 3-hour input, the retry, and the button rules.
  - Depends on: T11, T12
  - Files: `media/AudioPreparer.java`, `media/FfmpegRunner.java`, `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, tests

- [x] **T14: Retention sweeper and restart recovery** (M)
  - Acceptance:
    - A `@Scheduled` sweeper (every minute) and a run at startup delete files whose `expiresAt` has passed and mark them `EXPIRED`. A job directory is removed when every file is `SENT` or `EXPIRED`, or when its retention is over. Failed jobs are removed immediately.
    - A job directory that has a file in `SENDING` is never swept (a tap granted before expiry finishes, T12/T13 rule).
    - A job directory is removed by age only when the job is no longer being processed (`DONE` or `FAILED`); a job still processing is left to startup recovery.
    - At startup, `SENDING` files go back to `AVAILABLE`, and directories with a missing or corrupt `job.json` are removed.
    - After expiry the summary message buttons are replaced with "Audio expired" / "Transcript expired" (best effort, labels as in T12). A file already sent keeps its "sent" label.
    - Functional test `ExpiryIT`: after the retention time the files are gone and a tap gets "expired"; a restart between the summary and the tap keeps the buttons working.
  - Verify: `./mvnw test -Dtest=RetentionSweeperTest` with a fake clock and a temp directory: expiry, partial delivery, all delivered, `SENDING` recovery, corrupt `job.json`, a directory with `SENDING` is kept.
  - Depends on: T7, T12, T13
  - Files: `job/RetentionSweeper.java`, `job/JobRecovery.java`, `bot/Keyboards.java`, tests

- [x] **T18: Deferred jobs: park on service outage, notify the user, retry every 2 hours** (M)
  - Why: when Whisper (T9) or Ollama (T10) is down, the job fails with `SERVICE_UNREACHABLE` and the user has to resend the link. The owner wants the job kept and finished automatically after the service is back.
  - Acceptance:
    - A job that fails with `SERVICE_UNREACHABLE` is not dropped. It is written to the deferred queue, `deferred-jobs.json` in the data directory next to `allowed-users.json` (atomic write, as the allowlist), with the URL, owner user id, chat id, status message id, attempt count and the time it was parked. Queue order is kept and the queue survives a restart. A file that is not valid JSON is renamed to `deferred-jobs.json.corrupt-<time>` and the bot starts with an empty queue.
    - The working files of such a job are deleted at once. A retry starts from scratch, so `JOB_RETENTION` does not affect the queue.
    - The user gets one message: "Service temporarily unavailable. We will send the result after it recovers." The existing status message is edited, not a second message.
    - Every 2 hours (`@Scheduled`, `DEFERRED_RETRY_INTERVAL`, default `PT2H`) each queued entry is run again through the normal pipeline (T11), one at a time. On success the user gets the usual summary message with both buttons and the entry leaves the queue. If the service is still down the entry stays, its attempt count grows and the user is not messaged again.
    - At most 12 attempts (`DEFERRED_MAX_ATTEMPTS`, default 12, about 24 hours). After that the user gets one message, "We could not process this link. Please send it again later.", and the entry is removed. Any other failure on a retry (not `SERVICE_UNREACHABLE`) closes the entry at once with the normal error message.
    - If the user lost access to the bot while waiting, the entry is dropped silently.
    - Functional test `DeferredJobIT`: Whisper is down, the user gets the "temporarily unavailable" message and the job is queued; after the scheduler runs with Whisper back, the summary message with two buttons is sent and the queue is empty. More scenarios: the attempt limit, a restart, a repeated failure without a new message.
  - Verify: `./mvnw test -Dtest=DeferredJobsTest` with a fake clock and a temp directory (park, scheduled retry, success, still failing, restart, attempt limit), and `./mvnw verify -Dit.test=DeferredJobIT`.
  - Depends on: T9, T10, T11, T14
  - Files: `job/DeferredJobs.java`, `job/DeferredJobRunner.java`, `job/PipelineService.java`, `config/AppProperties.java`, `application.yml`, `.env.example`, `docs/host-setup.md`, `SPEC.md` (criterion 14 and storage), tests

- [ ] **T19: Russian videos: transcript and summary in Russian, language auto-detection** (M)
  - Why: Whisper runs with `-l en`, so a Russian video gives a garbled transcript with loops (seen in T17). The owner wants the transcript and the summary of a Russian video in Russian.
  - Acceptance:
    - The language is detected by Whisper (`-l en` is removed from the Whisper command, the bot asks for detection, `docs/host-setup.md` is updated). English and Russian are supported.
    - Russian video: Russian transcript and Russian summary (the summary prompts carry the language and the Ollama answer is in it). English video works as before. One language is used for all chunks of a long video.
    - Any other language: the job stops right after the language is detected on the first chunk, the working files are deleted and the user gets "Only English and Russian videos are supported. Detected language: <code>." This is not a service failure and does not go to the deferred queue (T18).
    - The bot's own texts (buttons, statuses, errors, captions) stay in English. File names come from the video title as now (Cyrillic and Telegram's file-name limits are checked).
    - The 1500-character summary limit, HTML escaping and all other rules stay.
    - Manual check on a Russian video; the result goes into `docs/host-setup.md`.
    - `SPEC.md` is updated (it says English only and other languages out of scope).
  - Verify: `./mvnw test` (unit tests: detection, the prompt per language, the refusal) and `./mvnw verify -Dit.test=LanguageIT` (the Whisper mock answers `ru`, `en` and `de`; the language of the Ollama request and the refusal without model calls are checked).
  - Depends on: T9, T10, T11, T18
  - Files: `transcribe/WhisperClient.java`, `summary/*` (prompts), `job/PipelineService.java`, `bot` (refusal message), `docs/host-setup.md`, `SPEC.md`, tests

### Checkpoint 4: Full flow
- [x] All tests pass
- [x] Manual: link, summary, tap transcript, tap audio, and the job directory is deleted. A second job left alone is deleted after 1 hour.
- [x] Review with human before proceeding

## Phase 5: Packaging and hardening

- [x] **T15: Dockerfile, Podman compose file, and host setup docs** (M)
  - Acceptance:
    - A multi-stage `Dockerfile` (JDK 27) with `yt-dlp` (latest at build time) and `ffmpeg`, running as a non-root user. The image starts the bot with the default (non-`dev`) profile.
    - `compose.yaml` (runs with `podman compose` / `podman-compose`, no Docker-only extensions) with the `bot` service, a named work volume (`/work`), a separate named data volume for `allowed-users.json` (`/data`), `host.containers.internal` access to Ollama and Whisper, env vars from `.env`, and no published ports.
    - `.env.example` lists every variable the bot reads (names and defaults from `application.yml`; no secrets), and `.gitignore` excludes `.env` (already does; keep it). `docs/host-setup.md` covers the full setup, including how to build, start, update `yt-dlp` and read the logs.
    - Reachability of host Ollama (11434) and Whisper (8081) from the container is checked on this machine. If services bound to `127.0.0.1` are not visible from the Podman VM, `docs/host-setup.md` gives the settings that make them reachable (Whisper `--host 0.0.0.0`, Ollama `OLLAMA_HOST=0.0.0.0`, firewall limits for the ports) and the "not verified" paragraph there is replaced by the result. The commands and results go into `tasks/T15/evidence.md`.
  - Verify: `podman compose build` succeeds, and `podman compose up -d` starts the bot: `podman compose logs bot` shows Spring Boot started and long polling running. The container reaches host Ollama and Whisper (command and output in `evidence.md`). The owner sends one link to the containerized bot and gets the summary. The startup health check that the original text mentioned belongs to T16 (`HealthChecks`), so T15 does not require it.
  - Depends on: T11
  - Files: `Dockerfile`, `compose.yaml`, `.env.example`, `.gitignore`, `.dockerignore`, `docs/host-setup.md`, `tasks/T15/evidence.md`
  - Technical task: Developer and Tester, both code reviewers; no Analyst, no Architect.

- [x] **T16: Service health checks and failure-mode tests** (M)
  - Acceptance:
    - **Startup check:** when the bot starts, it probes Whisper and Ollama (short timeout, a few seconds) and logs one line per service: reachable, or unreachable with the reason (no URLs with credentials, no stack trace). The bot starts either way and never hangs on an unreachable service.
    - **Per-job check:** after a link is accepted and before anything is downloaded, the bot probes both services in the same way. If one is unreachable, the user gets the existing message (`The transcription server is unreachable.` or `The summary server is unreachable.`, both if both), the job is not queued further, the working directory is removed, and nothing is downloaded. The check takes seconds at most, and it does not block other users' messages and button taps.
    - Failures that happen later in a job (a service going down mid-job) keep the behaviour from T9 and T10; nothing is changed there.
    - Functional tests (`*IT`) cover the failure paths that earlier tasks did not: Whisper down, Ollama down, both down (at link time, with no download), a private, unsupported and too-long URL (only the paths not yet covered), callbacks from a non-allowlisted user, and a restart with pending access requests. The Analyst lists in `requirements.md` which of these are already covered by T4-T14 scenarios, so the Tester adds only what is missing.
    - The JaCoCo gate (90% line and 90% branch, unit tests only) stays on in `pom.xml` and passes; no test needs network access.
  - Verify: `./mvnw clean verify` and `-Pmutation` (at least 85%) pass. The owner stops Whisper and then Ollama and checks the message in Telegram.
  - Depends on: T14, T15
  - Files: `job/HealthChecks.java` (and a small client method per service if needed), `bot/UrlMessageHandler.java` or the pipeline entry, `config/`, unit tests, `FailureModesIT.java` and its steps, `src/features/T16-failure-modes.feature`, `docs/decisions.md`, `docs/host-setup.md`
  - Product task: Analyst, Architect, Developer, Tester and both code reviewers.

- [x] **T17: Real-world smoke tests (short and 3-hour video)** (S)
  - Acceptance:
    - A short video (link from the owner): audio is delivered unchanged. A video of about 3 hours (link from the owner): the audio is compressed, uploaded without a size error, and the transcript is complete.
    - Times and peak memory for each stage are recorded in `docs/host-setup.md`, along with any tuning (chunk size, `num_ctx`, `keep_alive`, or falling back to `qwen3:14b`).
    - The 50 MiB limit (`LIMIT_BYTES = 52 428 800`) is checked against Telegram's real upload limit (50 000 000 bytes). If a file between the two is refused, the constant is fixed as a separate `dev` change with review.
    - All success criteria in `SPEC.md` are checked off.
  - Verify: manual run, with results in the docs. The owner confirms.
  - Depends on: T16
  - Files: `docs/host-setup.md`, `SPEC.md` (only if a criterion changes, and then with the owner's approval)

### Checkpoint: Complete
- [x] All 15 success criteria in `SPEC.md` are met
- [x] `./mvnw verify` is green and `podman compose up -d` works from a clean checkout
- [ ] Ready for review

## Backlog

Work that is not scheduled in a phase. Technical debt carries the `[debt]` mark.

- [x] **TD1 [debt]: Replace the class-wide `COMMAND_INJECTION` exclusion with one annotation on the call** (S)
  - Why: T8 needed `config/spotbugs-exclude.xml` because find-sec-bugs flags `new ProcessBuilder(args)` in `DefaultProcessRunner` whenever the argument list is not a compile-time constant. A typed command (enum of tools plus argument list) was tried in a T8 spike and is flagged the same way. As of 2026-11-23 `findsecbugs-plugin` 1.14.0, `spotbugs-maven-plugin` 4.10.5.0 and SpotBugs 4.10.5 are the newest releases, so waiting for a newer one does not help. The owner approved (2026-11-23) a narrower mechanism: one `@SuppressFBWarnings` on the method that starts the process. Safeguards that stand in for the check: arguments are a list (no shell), the binary is always `yt-dlp` or `ffmpeg`, `UrlValidator` runs first and the URL follows `--`, and a real-process test proves hostile punctuation stays one literal argument.
  - Acceptance:
    - `config/spotbugs-exclude.xml` and its wiring in `pom.xml` (`excludeFilterFile`) are removed.
    - `pom.xml` gets `com.github.spotbugs:spotbugs-annotations:4.10.5` with scope `provided`. No other dependency is added.
    - `DefaultProcessRunner.Child.start` carries one `@SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "...")` on the method, not on the class. The justification names the safeguards above. No other suppression is added.
    - `./mvnw verify` passes with SpotBugs and find-sec-bugs on. A search shows no `COMMAND_INJECTION` in `config/` and `pom.xml`, and exactly one in `src/main` (that annotation).
    - The real-process argument-preservation test of `DefaultProcessRunner` still passes.
  - Verify: `./mvnw clean verify`, and the search above.
  - Depends on: T8
  - Files: `config/spotbugs-exclude.xml` (removed), `pom.xml`, `media/DefaultProcessRunner.java`

- [x] **TD2 [debt]: Fix the flaky `JobQueueTest.sizeCountsWaitingAndRunningTasksUntilTheyFinish`** (S)
  - Why: it failed once in four full `./mvnw verify` runs during T8 (`expected: 0 but was: 1` at `JobQueueTest.java:86`). `JobQueue.run` completes the task's future first and removes the task from `open` only in `finally`, so `size()` still counts a task whose future is already done. The test is right; the race is in the production code, and `size()` is the queue position T11 will show to users.
  - Acceptance:
    - `JobQueue.run` removes the task from `open` before it completes the task's future, on all paths (success, `RuntimeException`, `Error`). `size()` is already updated when a caller waiting on the future, or a `whenComplete` callback, runs. Behaviour is otherwise unchanged (order, error logging, `close()`).
    - A new deterministic test reads `queue.size()` inside a `whenComplete` callback of the last task and asserts zero, for a task that succeeds and for one that throws. It fails on the current code.
    - `sizeCountsWaitingAndRunningTasksUntilTheyFinish` is unchanged and passes 200 times in a row (a one-off local loop, not kept in the code).
  - Verify: `./mvnw test -Dtest=JobQueueTest`, then a full `./mvnw verify`.
  - Depends on: T7
  - Files: `job/JobQueue.java`, `JobQueueTest.java`

- [x] **TD3: Mutation tests for main code with PIT (min 85%)** (M)
  - Why: the 90% JaCoCo gate shows which lines run, not whether the tests would notice a wrong result. PIT shows that.
  - Acceptance:
    - `pitest-maven` is configured in a Maven profile `mutation`, for `src/main` code only (`src/test` unit tests; `src/it` is not run by PIT). The profile fails the build when the mutation score is below 85%.
    - The default `./mvnw verify` and `./mvnw verify -Dno-it` are unchanged and not slower.
    - Current score is measured. Where it is below 85%, the Developer adds or strengthens unit tests (`src/test/java`) until `./mvnw verify -Pmutation` passes. Production code changes only if a surviving mutant shows a real bug. Excluded classes (config, generated code, main class), if any, are listed in `pom.xml` with a reason for each, and the owner approves the list.
    - `CLAUDE.md` and `docs/` mention the command and the threshold.
  - Verify: `./mvnw verify -Pmutation` is green, and it goes red when the threshold is raised above the current score (one-off check). Technical task: no Tester and no `src/it` tests; the Developer keeps the evidence in `tasks/TD3/`.
  - Depends on: none (all merged tasks T1–T9 are covered)
  - Files: `pom.xml`, `src/test/java/**`, `CLAUDE.md`, `docs/`

- [x] **TD4 [debt]: Rewrite the T8, T9 and TD2 scenarios through the external boundary** (S)
  - Why: the owner's rule is that Cucumber scenarios do not call service code. They go through what can be seen from outside: Telegram, HTTP calls, files, the queue. The scenarios of T8 (`When media preparation validates the URL ...`), T9 (`When transcription runs for the job`) and TD2 name internal components. They are excluded from `CucumberIT` for that reason and run nowhere.
  - Acceptance:
    - `src/features/T8-media.feature`, `T9-transcription.feature` and `TD2-job-queue.feature` have no step that names or calls a class, a component or a method. Steps send a Telegram update to the bot, set up a mock of Whisper, Ollama or the process runner, and check Telegram calls, HTTP requests received by a mock and files in the job directory.
    - Each old scenario is either rewritten, folded into another one as an example (for example the six URLs of "Accept a public HTTP URL unchanged" become examples of one link-message scenario), or removed with a line in `tasks/TD4/requirements.md` saying which unit test covers it. No requirement of T8, T9 and TD2 loses coverage: a table in `requirements.md` maps every old scenario to its new place. For TD2 the Analyst keeps what shows in the "Queued: position N" reply and leaves the rest to the unit tests, with a note in the table.
    - The scenarios use the step wording of `whoami.feature` where they can, and the `@manual_media` scenarios stay tagged as manual.
    - Success scenarios run the real `ffmpeg` and the real `yt-dlp` (decided with the owner): small fixtures are generated by `ffmpeg` and kept in `src/it/resources`, a WireMock server holds them, and the `ProcessRunner` of the functional harness is a thin decorator over the real runner that adds `--proxy http://127.0.0.1:<wiremock port>` to the `yt-dlp` call (checked by hand: a made-up host such as `videos.example` is downloaded by the real `yt-dlp` through such a proxy, as long as the answer carries a `Content-Type`). No external DNS. Process mocks stay only for failure cases (private video, sign in, unsupported URL, timeout) and for checks of the arguments passed to the tool.
    - Two download scenarios: (1) the source has a separate audio track (an m4a file): the download gives one `source.m4a` and the real `ffmpeg` makes the 16 kHz mono WAV and the chunks; (2) the source has only a combined video with sound (an mp4): the download works and the audio is extracted, because the format selector falls back to `best` (TD5). Scenario 2 is tagged `@TD5` and is written for the behaviour after TD5.
    - `CucumberIT` and its filter are not changed (the scenarios stay deferred until T11 writes the steps), so `./mvnw verify` is unchanged.
  - Verify: the Architect and the orchestrator review the features and the mapping table. A text check shows no step with a class or method name. `./mvnw verify` stays green. Technical task: the Analyst writes, the Architect and the orchestrator review; no Developer, no Tester, no code reviewers.
  - Depends on: T8, T9, TD2
  - Files: `src/features/T8-media.feature`, `src/features/T9-transcription.feature`, `src/features/TD2-job-queue.feature`, `tasks/TD4/requirements.md`

- [x] **TD5 [debt]: Fall back to the combined format when a source has no separate audio track** (S)
  - Why: `YtDlpClient` asks for `bestaudio[ext=m4a]/bestaudio`. For a source that has only a combined video with sound (a direct mp4, and many sites other than YouTube) the real yt-dlp answers "Requested format is not available", and the job fails. Checked by hand with yt-dlp 2026.10.26 on a local mp4. `ffmpeg` already turns any container into a 16 kHz mono WAV.
  - Acceptance:
    - The format selector is `bestaudio[ext=m4a]/bestaudio/best`. `SPEC.md` says the same where it names the selector (changed by the orchestrator with the owner's approval, given in this task).
    - For a combined mp4 source the download gives one `source.*` file and the pipeline continues. The behaviour for sources with a separate audio track is unchanged.
    - A unit test in `YtDlpClientTest` asserts the selector in the call arguments. The T8 scenario for the combined mp4 (tagged `@TD5`, written in TD4) is the acceptance check once T11 wires the steps.
  - Verify: `./mvnw verify -Dno-it`, and one by-hand run of the real `yt-dlp` on a local mp4 with the new selector (the command and the result go into `tasks/TD5/evidence.md`). Technical task: the Developer writes, both code reviewers review; no Architect, no Analyst, no Tester.
  - Depends on: T8
  - Files: `media/YtDlpClient.java`, `YtDlpClientTest.java`, `SPEC.md`

- [x] **TD6 [debt]: Flaky T9 scenario "Broken successful responses are retried rather than called silence"** (S)
  - Update from T13 (2026-11-19): two more single-scenario failures (a T8 scenario and a T10 scenario) in `clean verify` runs started from the orchestrator's sandboxed shell; the T10 log shows `Ollama answered 403 ... "reason":"not_allowed_local","source":"baseline_policy"`: the sandbox network policy sometimes denies calls to localhost. Outside the sandbox the same suite was green. The T9 failure below may have the same cause (check its log for `not_allowed_local`). The owner decided that full gates run outside the sandbox with approval. Logs: `tasks/TD6/failed-run*.log`. The Tester already hardened the fixtures in T13 (`tasks/T13/comms/tester-flaky.md`), so TD6 may shrink to checking the T9 log and the remaining weak waits (and the one-off `OllamaClientTest.anAnswerStoppedAtTheLengthLimitIsAccepted` failure, `header parser received no bytes`).
  - Update from T14 (2026-11-21): in the Tester's run, two older scenarios (a T9 Whisper one and one with a held Ollama) timed out with `not_allowed_local / baseline_policy` on localhost even though the command was approved outside the sandbox; one T8 scenario failed once before the URL was submitted (a `/allow` reply with a null `ok`). Evidence: `tasks/T14/comms/questions.md`. The orchestrator's own `clean verify` (outside the sandbox) was green. TD6 should treat the platform network policy as the first suspect.
  - Update from T17 (2026-11-23): the same T9 scenario ("Broken successful responses are retried rather than called silence", Example #1.3) timed out once in the orchestrator's `clean verify` (30 s wait for `transcript.txt`; no `not_allowed_local` and no 401/403 in the log, so not the network policy). The rerun of the same command was green (387/387). The flake is still there; reopen TD6 if it recurs.
  - Update from TD9/TD10 (2026-11-23): another flake family in the orchestrator's `clean verify`. TD9: T12 "Missing or unusable retained content reports expiry" example #1.3 (empty tap event list, seen by the Tester). TD10 (first run): two back-to-back T11 scenarios ("Collapse title whitespace only for display" and "Stop before media work when Telegram rejects the queued acknowledgement") failed in `PipelineWorld.allow` with an empty Telegram mock journal for the allowlist acknowledgement (0.15 s, before any TD10 step). The rerun of the same command was green (398/398). Common thread: the mock's request journal read as empty right after a request; no `not_allowed_local` in the logs.
  - Resolution (2026-11-23, TD11): the real cause of these flakes was found: WireMock ports colliding with other programs that listen on `127.0.0.1` (the Codex app-server daemon, IntelliJ IDEA). Fixed in TD11 (loopback bind plus retry of a taken port, in `src/it` and `src/test`); three `clean verify` runs in a row were green with the daemon running.
  - Why: one of five full `./mvnw clean verify` runs failed at the step `"chunk-000.wav" is sent exactly twice` (`T9-transcription.feature:122`, example 1.1 `{broken`): the Whisper mock journal had 0 requests after 30 seconds. The rerun was green. A flaky test weakens trust in `verify`.
  - Acceptance:
    - The cause is found (in the test or in the code) and written in `tasks/TD6/evidence.md`: the log of the failed run, the code version, the explanation.
    - The cause is removed, not hidden: no timeout is raised by guesswork, and the wait is tied to an observable event. If the cause is in production code (a race in the queue, the retries or the status edits), the Developer fixes it in a separate `fix` commit.
    - The scenario is not disabled and gets no test-level retries.
  - Verify: the failed scenario and the whole T9 group pass 20 times in a row (`-Dcucumber.filter.tags=@T9` in a loop, result in `evidence.md`), then `./mvnw clean verify` is green with the JaCoCo, Spotless, SpotBugs and PMD gates unchanged. Technical task: the Tester investigates and fixes the tests; the Developer joins only if the cause is in the code; both code reviewers review; no Analyst, no Architect (scenarios and contracts do not change).
  - Depends on: T11
  - Files: `src/it/java/com/video2podcast/functional/*` (steps and scenario world), if needed `job/`, `transcript/`, `tasks/TD6/evidence.md`
- [x] **TD7 [debt]: yt-dlp metadata over 8 MiB is cut silently, and failed jobs leave no log** (S)
  - Found at Checkpoint 4 (2026-11-22): a real public YouTube video (156 s) answers `yt-dlp --dump-single-json` with 11.7 MB. `DefaultProcessRunner` keeps only the first `MAX_OUTPUT_BYTES` (8 MiB) and drops the rest, so the JSON is cut, `YtDlpClient.parse` fails with `FAILED`, and the user gets "Could not process this video." No line appears in the bot log: a `JobFailure` is never logged with its cause.
  - Acceptance:
    - `fetchMetadata` asks yt-dlp only for the fields it needs (`title`, `duration`, `is_live`, `_type`) with `--print` and a JSON field selection, so the output stays small whatever the video has. The same checks as now (collections, live, duration, title, TOO_LONG) still hold. The command is verified by hand with the real `yt-dlp` on the video above and on a short video; the commands and results go into `tasks/TD7/evidence.md`.
    - A runner output over the limit is not cut silently: the call fails visibly (a log line with the command name and the limit, and a `FAILED` result), never a parse of a truncated text.
    - Every `JobFailure` that ends a job is logged once at WARN with the job id, the kind and the cause message (no URL, no file paths, no full stderr), by the code that reports the failure to the user.
    - Unit tests: metadata whose full JSON is over 8 MiB (through the new `--print` shape), output over the limit gives a visible failure, the failure log line. The behaviour for every other input is unchanged.
  - Verify: `./mvnw verify -Dno-it` and `./mvnw clean verify`, `-Pmutation` at least 85%. Checkpoint 4 is repeated by the owner with the video above. Technical task: Developer and Tester, both code reviewers; no Analyst, no Architect.
  - Depends on: T8, T11
  - Files: `media/YtDlpClient.java`, `media/DefaultProcessRunner.java`, `bot/UrlMessageHandler.java` (and `job/PipelineService.java` if needed), unit tests, tests under `src/it` if the fixtures need the new output shape, `tasks/TD7/evidence.md`
- [x] **TD8: Tap on a deleted or expired job says the file may already be downloaded** (S)
  - Found at Checkpoint 4 (2026-11-22): after both files are delivered, the sweeper deletes the job directory (SPEC). A repeated tap then answers "Expired, please resend the link.", which is misleading when the file was already downloaded. The bot cannot tell the two cases apart, because `job.json` is deleted with the directory.
  - Acceptance:
    - The refusal text for a missing, expired or unusable job becomes `Files already downloaded or expired, please resend the link.` (constant `DeliveryCallbackHandler.EXPIRED`). It is the same text for the transcript and audio buttons.
    - Nothing else changes: the other refusals (`This button is not for you.`, `Already sent.`, `Already being sent.`, `Something went wrong, please try again.`), the order of checks, the stored data and the deletion rules stay as they are.
    - The existing unit tests and the T12, T13 and T14 scenarios that name the old text are updated to the new text; the SPEC and `docs/` mentions of the old text are updated if there are any.
  - Verify: `./mvnw verify -Dno-it`, `./mvnw clean verify`, `-Pmutation` at least 85%. The owner checks the new text in Telegram during Checkpoint 4. Technical task: Developer and Tester, both code reviewers; no Analyst, no Architect.
  - Depends on: T12, T13, T14
  - Files: `bot/DeliveryCallbackHandler.java`, its unit tests, `src/features/T12-transcript-button.feature`, `T13-audio-button.feature`, `T14-retention.feature` (the Tester updates the matching steps under `src/it`), `SPEC.md` if it quotes the text
- [x] **TD9 [debt]: Send the audio duration to Telegram so the player shows it before download** (S)
  - Found at the T17 smoke run (2026-11-23): the converted M4A plays, but its message shows `00:00` in the Telegram chat until it is opened. `sendAudio` is called without the `duration` parameter, while `job.json` already stores `durationSeconds`.
  - Acceptance:
    - `TelegramGateway.sendAudio` gets a `duration` parameter (seconds) and the multipart request carries the field `duration`. `DeliveryCallbackHandler` takes the value from the stored `job.durationSeconds()`; when it is zero or negative the field is not sent. The tap flow, texts, states, captions and file names do not change.
    - Unit tests and a T13 scenario check the `duration` field of the upload.
  - Verify: `./mvnw verify -Dno-it`, `./mvnw clean verify`, `-Pmutation` at least 85%. The owner checks the audio message in Telegram. Technical task.
  - Depends on: T13
  - Files: `bot/TelegramGateway.java` and its implementation, `bot/DeliveryCallbackHandler.java`, unit tests, a scenario in `src/features/T13-audio-button.feature` and its steps in `src/it`.
- [x] **TD10 [debt]: Show seconds in the summary duration ("1m 54s" instead of "1m")** (S)
  - Found at the T17 smoke run (2026-11-23): a 1:54 video shows `Duration: 1m`. `SummaryMessageFormatter.formatDuration` drops the seconds whenever there is at least one minute. The owner chose the format on 2026-11-23: always show seconds, zero units are left out.
  - Acceptance:
    - `formatDuration` gives hours, minutes and seconds with zero units left out: `114` → `1m 54s`, `120` → `2m`, `3659` → `1h 59s`, `3600` → `1h`, `9390` → `2h 36m 30s`, `45` → `45s`, `0` → `0s`. A negative value still does not throw.
    - Nothing else in the summary changes: title, bullets, the 1,500-character limit, HTML escaping.
    - The unit tests and the T10/T11 scenarios that name the old format are updated, and a new T11 outline checks the duration line for several stored durations. `SPEC.md` keeps its example (`Duration: 1h 42m` is still right); its description of the format is updated if it has one, with the owner's approval.
  - Verify: `./mvnw verify -Dno-it`, `./mvnw clean verify`, `-Pmutation` at least 85%. Technical task.
  - Depends on: T10, T11
  - Files: `bot/SummaryMessageFormatter.java`, its unit tests, the T10/T11 features that show the duration, and their steps in `src/it`.
- [x] **TD11 [debt]: WireMock ports collide with other programs listening on 127.0.0.1** (S)
  - Found (2026-11-23): in `clean verify`, tests sometimes get `401/403` (`not_allowed_local`, `baseline_policy`, `Proxy key is missing…`) or an empty mock journal. Cause, checked by hand: WireMock listens on all interfaces on a random port; if another program already listens on `127.0.0.1` with the same port (the Codex app-server daemon, other Codex processes, IntelliJ IDEA), a request to `localhost:<port>` goes to that program, not to the mock. Reproduced with a wildcard socket on port 54050: the request got the Codex `403 ... not_allowed_local`. With about a dozen foreign listeners, about 16,000 ephemeral ports and about a thousand mocks per run, a collision in a full run is likely. This is the real cause behind TD6 and the later flakes noted in it.
  - Acceptance:
    - Every WireMock server in `src/it` and in `src/test` (`TelegramConfigTest`, `TranscriptionAssembledTest`, `WhisperClientTest`, `OllamaClientTest`, `TelegramClientGatewayTest`; widened on 2026-11-23 after the reviewers found the same exposure there) listens on `127.0.0.1` only and its port is chosen so that nobody else holds that port. A port that is already taken is replaced by another one (a short retry); it never ends in a silent wrong answer.
    - The test clients keep using the URL the mock gives them and never reach a foreign listener on that port.
    - A test shows the fix: with a foreign listener on `127.0.0.1:<port>` the helper does not use that port.
    - No production code changes, no suppressions, no timeout increases.
    - `./mvnw clean verify` runs green at least 3 times in a row while the Codex app-server daemon is running (one run at a time).
  - Verify: `./mvnw clean verify` three times, `-Pmutation` at least 85%.
  - Depends on: TD6, T16
  - Files: `src/it/java/com/video2podcast/functional/WireMocks.java` and the other users of `WireMockServer` in `src/it`, `src/test/java/com/video2podcast/config/TelegramConfigTest.java`
- [~] **TD12 [debt]: Ollama through Spring AI instead of the hand-written client** (M)
  - Raised by the owner (2026-11-24): `OllamaClient` is written by hand on the JDK `HttpClient`, but the project is on Spring. Technical task, ollama client only.
  - Acceptance:
    - First step, a compatibility check, with the result in `tasks/TD12/evidence.md`: a Spring AI version that works with Spring Boot 4.1.x, and proof that `OllamaChatModel` lets us set `num_ctx`, `num_predict`, `think:false` and the model on every request and returns `prompt_eval_count`. If one of them is missing, the Developer stops and the owner decides whether to go on.
    - `LlmClient` is implemented on Spring AI (`spring-ai-starter-model-ollama`) and the `HttpClient` version of `OllamaClient` is removed. The `LlmClient` interface, `SummaryService`, the prompts and the error texts do not change.
    - Behaviour stays: the request parameters, the truncation guard on `prompt_eval_count`, the failure kinds (`SUMMARY_UNREACHABLE` when Ollama is down, `TIMEOUT` on an answer timeout, `FAILED` otherwise), and `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `OLLAMA_NUM_CTX`, `OLLAMA_TIMEOUT` work as before. Spring AI auto-configuration must not change any other bean.
    - The Ollama health check (`GET /api/version`, T16) and the Whisper client do not change.
    - The new dependency and its version are recorded in `docs/decisions.md`. SpotBugs, PMD, JaCoCo 90% and mutation 85% stay green, with no new suppressions.
    - Live check: one real request to Ollama `qwen3:32b` through the bot, with the result in `tasks/TD12/evidence.md`.
  - Verify: `./mvnw verify -Dno-it`, `./mvnw clean verify`, `./mvnw verify -Pmutation -Dno-it`.
  - Depends on: T10, T16
  - Files: `pom.xml`, `summary/OllamaClient.java` (removed), the new `LlmClient` implementation, `config/*`, `application.yml`, `docs/decisions.md`, tests
