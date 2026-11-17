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
- [ ] All tests pass
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

- [~] **T9: Transcription client and merged transcript** (S)
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

- [ ] **T10: Ollama client and map-reduce summary** (M)
  - Acceptance:
    - The `LlmClient` interface has an Ollama implementation that sets `num_ctx` and the model on every request.
    - `SummaryService` splits the transcript into chunks that fit the context, summarizes each chunk, then summarizes the partial summaries. A short transcript is summarized in a single call.
    - Every request sends `think: false`. An empty `content` is a failure. `prompt_eval_count` is checked against the expected input size, and a silently truncated prompt fails the job (Ollama does not return an error when the prompt exceeds `num_ctx`).
    - The output is English bullet points, capped at 1,500 characters. An unreachable Ollama gives a clear failure.
  - Verify: `./mvnw test -Dtest='OllamaClientTest,SummaryServiceTest'` with WireMock (single call, map-reduce call count, chunk size fits `num_ctx`, length cap, unreachable).
  - Depends on: T3
  - Files: `summary/LlmClient.java`, `summary/OllamaClient.java`, `summary/SummaryService.java`, `summary/Prompts.java`, tests

- [ ] **T11: Link to summary message with buttons (end-to-end)** (M)
  - Acceptance:
    - An allowed user's URL message gets an acknowledgement within 5 seconds, then one status message that is edited through the stages (queued, downloading, transcribing n/N, summarizing).
    - On success the bot sends the HTML message (title, duration, bullets) with **Download audio** and **Download transcript** buttons (`audio:<jobId>`, `transcript:<jobId>`) and saves `job.json` with `expiresAt` = send time + 1 hour. **No audio or transcript file is sent.**
    - Failures produce a clear message and remove the job directory. Title and summary are HTML-escaped.
    - The tag filter `not @T8` in `CucumberIT` is removed, and the step definitions for `src/features/T8-media.feature` are written (the T8 scenarios were deferred to this task). The `@manual_media` scenarios stay manual.
    - Functional test `SummaryFlowIT` (replaces `PipelineIntegrationTest`): link, status edits, final message with two buttons, `job.json`, no file uploads; Whisper and Ollama are WireMock servers built from the T3 fixtures and `yt-dlp`/`ffmpeg` are a fake `ProcessRunner`.
  - Verify: `./mvnw verify -Dit.test=SummaryFlowIT` (functional test on the T4a harness): link, status edits, final message with two buttons, `job.json` content, no file uploads. Failure paths clean up the directory.
  - Depends on: T5, T7, T8, T9, T10
  - Files: `job/PipelineService.java`, `bot/UrlMessageHandler.java`, `bot/SummaryMessageFormatter.java`, `bot/BotUpdateHandler.java`, test

### Checkpoint 3: Summary pipeline
- [ ] All tests pass and `./mvnw verify` meets the coverage target
- [ ] Manual with real services: a short real video produces a summary message with both buttons and nothing else
- [ ] Review with human before proceeding

## Phase 4: On-demand delivery and cleanup

- [ ] **T12: Download transcript button** (S)
  - Acceptance:
    - Tapping **Download transcript** from the job's owner sends `<title>.txt` with `sendDocument`. The file state goes `AVAILABLE` → `SENDING` → `SENT`, and the button changes to "Transcript sent". The audio button stays active.
    - A second tap, or a tap at the same moment, sends the file once. A tap from another user is rejected. A tap on an expired or missing job gives "expired, please resend the link".
    - Functional test `TranscriptButtonIT`: the tap sends the `.txt` once through `sendDocument`; a second tap, another user, and an expired job are handled.
  - Verify: `./mvnw test -Dtest=TranscriptButtonTest` covers the happy path, double tap, concurrent taps, another user, and expired.
  - Depends on: T11
  - Files: `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, `bot/Keyboards.java`, test

- [ ] **T13: Audio preparation and Download audio button** (M)
  - Acceptance:
    - `AudioPreparer` follows the compression rule: a file of 50 MB or less in MP3 or M4A is returned unchanged. A smaller file in another format is converted to M4A at 128 kbps. A file over 50 MB is re-encoded to mono AAC at `48 MiB × 8 / duration`, verified under 50 MB, with one retry at a lower bitrate.
    - Tapping **Download audio** goes through the job queue, shows "Preparing audio…", sends with `sendAudio` (title and performer set), and notes whether and how it was compressed. The same one-send, owner-only, and expired rules as T12 apply.
    - Functional test `AudioButtonIT`: the tap sends audio through `sendAudio`, unchanged and compressed cases, with the same one-send and owner-only rules.
  - Verify: `./mvnw test -Dtest='AudioPreparerTest,AudioButtonTest'` covers each branch of the rule (the unchanged branch must return the same path), the bitrate for a 3-hour input, the retry, and the button rules.
  - Depends on: T11, T12
  - Files: `media/AudioPreparer.java`, `media/FfmpegRunner.java`, `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, tests

- [ ] **T14: Retention sweeper and restart recovery** (M)
  - Acceptance:
    - A `@Scheduled` sweeper (every minute) and a run at startup delete files whose `expiresAt` has passed and mark them `EXPIRED`. A job directory is removed when every file is `SENT` or `EXPIRED`, or when its retention is over. Failed jobs are removed immediately.
    - At startup, `SENDING` files go back to `AVAILABLE`, and directories with a missing or corrupt `job.json` are removed.
    - After expiry the summary message buttons are replaced with "expired" (best effort).
    - Functional test `ExpiryIT`: after the retention time the files are gone and a tap gets "expired"; a restart between the summary and the tap keeps the buttons working.
  - Verify: `./mvnw test -Dtest=RetentionSweeperTest` with a fake clock and a temp directory: expiry, partial delivery, all delivered, `SENDING` recovery, corrupt `job.json`.
  - Depends on: T7, T12, T13
  - Files: `job/RetentionSweeper.java`, `job/JobRecovery.java`, `bot/Keyboards.java`, tests

### Checkpoint 4: Full flow
- [ ] All tests pass
- [ ] Manual: link, summary, tap transcript, tap audio, and the job directory is deleted. A second job left alone is deleted after 1 hour.
- [ ] Review with human before proceeding

## Phase 5: Packaging and hardening

- [ ] **T15: Dockerfile, Podman compose file, and host setup docs** (M)
  - Acceptance:
    - A multi-stage `Dockerfile` (JDK 27) with `yt-dlp` and `ffmpeg`, running as a non-root user.
    - `compose.yaml` (runs with `podman compose` / `podman-compose`, no Docker-only extensions) with the `bot` service, a work volume, a separate data volume for `allowed-users.json`, `host.containers.internal` access, env vars from `.env`, and no published ports.
    - `.env.example` lists every variable (no secrets), and `.gitignore` excludes `.env`. `docs/host-setup.md` covers the full setup.
  - Verify: `podman compose build` succeeds, and `podman compose up -d` starts the bot, which reaches host Ollama and Whisper (`podman compose logs bot` shows the health check passing).
  - Depends on: T11
  - Files: `Dockerfile`, `compose.yaml`, `.env.example`, `.gitignore`, `docs/host-setup.md`

- [ ] **T16: Failure-mode and restart integration tests** (M)
  - Acceptance:
    - Startup health check and per-job checks report "Ollama unreachable" or "Whisper unreachable" to the user, without hanging.
    - Functional tests (`*IT`) cover the failure paths that earlier tasks did not: Whisper or Ollama down, private/unsupported/too-long URL, non-allowlisted callbacks, restart with pending access requests.
    - The JaCoCo gate requires at least 90% line coverage and 90% branch coverage across all production code, including `access` and `config`, measured from unit tests only. `./mvnw clean verify` generates `target/site/jacoco/index.html` and enforces both `COVEREDRATIO` minimums of `0.90` through `jacoco:check`; either threshold being missed fails the build. Integration-test coverage does not count toward the gate.
  - Verify: `./mvnw verify` passes with the gate on.
  - Depends on: T14, T15
  - Files: `job/HealthChecks.java`, `FailureModesIT.java`, `pom.xml`

- [ ] **T17: Real-world smoke tests (short and 3-hour video)** (S)
  - Acceptance:
    - A short video: audio is delivered unchanged. A video of about 3 hours: the audio is compressed, uploaded without a size error, and the transcript is complete.
    - Times and peak memory for each stage are recorded in `docs/host-setup.md`, along with any tuning (chunk size, `num_ctx`, `keep_alive`, or falling back to `qwen3:14b`).
    - All success criteria in `SPEC.md` are checked off.
  - Verify: manual run, with results in the docs. The owner confirms.
  - Depends on: T16
  - Files: `docs/host-setup.md`, `SPEC.md` (only if a criterion changes, and then with the owner's approval)

### Checkpoint: Complete
- [ ] All 15 success criteria in `SPEC.md` are met
- [ ] `./mvnw verify` is green and `podman compose up -d` works from a clean checkout
- [ ] Ready for review

## Backlog

Work that is not scheduled in a phase. Technical debt carries the `[debt]` mark.

- [ ] **TD1 [debt]: Remove the SpotBugs `COMMAND_INJECTION` exclusion for `DefaultProcessRunner`** (S)
  - Why: T8 needed `config/spotbugs-exclude.xml` because find-sec-bugs flags `new ProcessBuilder(args)` in `DefaultProcessRunner` whenever the argument list is not a compile-time constant. A typed command (enum of tools plus argument list) was tried in a T8 spike and is flagged the same way. The owner approved this one narrow exclusion for T8, as a known false positive. Safeguards that stand in for the check: arguments are a list (no shell), the binary is always `yt-dlp` or `ffmpeg`, `UrlValidator` runs first and the URL follows `--`, and a real-process test proves hostile punctuation stays one literal argument.
  - Acceptance:
    - `config/spotbugs-exclude.xml` and its wiring in `pom.xml` are removed, or the exclusion is replaced by a narrower mechanism the owner approves.
    - `./mvnw verify` passes with SpotBugs and find-sec-bugs on and no `COMMAND_INJECTION` suppression, for example because a newer find-sec-bugs or SpotBugs release no longer flags the pattern, or because the process start moves to a form the detector accepts without hiding the call.
    - The real-process argument-preservation test of `DefaultProcessRunner` still passes.
  - Verify: `./mvnw clean verify`, and a search showing no `COMMAND_INJECTION` entry left in `config/` or `pom.xml`.
  - Depends on: T8
  - Files: `config/spotbugs-exclude.xml`, `pom.xml`, `media/DefaultProcessRunner.java`

- [x] **TD2 [debt]: Fix the flaky `JobQueueTest.sizeCountsWaitingAndRunningTasksUntilTheyFinish`** (S)
  - Why: it failed once in four full `./mvnw verify` runs during T8 (`expected: 0 but was: 1` at `JobQueueTest.java:86`). `JobQueue.run` completes the task's future first and removes the task from `open` only in `finally`, so `size()` still counts a task whose future is already done. The test is right; the race is in the production code, and `size()` is the queue position T11 will show to users.
  - Acceptance:
    - `JobQueue.run` removes the task from `open` before it completes the task's future, on all paths (success, `RuntimeException`, `Error`). `size()` is already updated when a caller waiting on the future, or a `whenComplete` callback, runs. Behaviour is otherwise unchanged (order, error logging, `close()`).
    - A new deterministic test reads `queue.size()` inside a `whenComplete` callback of the last task and asserts zero, for a task that succeeds and for one that throws. It fails on the current code.
    - `sizeCountsWaitingAndRunningTasksUntilTheyFinish` is unchanged and passes 200 times in a row (a one-off local loop, not kept in the code).
  - Verify: `./mvnw test -Dtest=JobQueueTest`, then a full `./mvnw verify`.
  - Depends on: T7
  - Files: `job/JobQueue.java`, `JobQueueTest.java`
