# Task List: video2podcast-bot — V1

Source: `SPEC.md`, plan in `tasks/plan.md`. Package root: `com.video2podcast`.
Sizes: S = 1-2 files, M = 3-5 files.

**Commit rule:** every task is a separate commit (`T<N>: <title>`), made after its verification passes, with the task ticked in this file in the same commit.

## Phase 1: Foundation and risk spikes

- [x] **T1: Project skeleton on Java 27 / Spring Boot 4.1.2** (M)
  - Acceptance:
    - Maven project with the Maven wrapper, `<java.version>27</java.version>`, Spring Boot 4.1.2, JUnit 5, Mockito, WireMock.
    - Spotless (google-java-format) and JaCoCo are configured and work on Java 27.
    - Typed `@ConfigurationProperties` for all env vars in the spec, with defaults, and a context-loads test.
  - Verify: `./mvnw spotless:apply && ./mvnw verify` passes.
  - Depends on: none
  - Files: `pom.xml`, `Video2PodcastApplication.java`, `config/*Properties.java`, `application.yml`, one context test

- [x] **T2: Telegram long-polling spike (`/whoami`)** (S) — manual `/whoami` check with a real bot token is still to be done by the owner
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
- [ ] `./mvnw verify` passes on Java 27 with Spotless and JaCoCo
- [ ] The bot answers `/whoami` from a real Telegram account
- [ ] Whisper and Ollama contracts are documented and match the spec's assumptions (or the spec is updated)
- [ ] Review with human before proceeding

## Phase 2: Access control

- [x] **T4: Allowlist store and access service** (M)
  - Acceptance:
    - `AccessStore` reads and writes `allowed-users.json` atomically (temp file and rename) behind a lock, with an in-memory cache and reload on start.
    - `AccessService` implements: the owner always allowed and not removable, add and remove users, a pending request per user, a 24-hour deny cooldown, and rate limiting of refusal replies (1 per minute per user).
  - Verify: `./mvnw test -Dtest='AccessStoreTest,AccessServiceTest'` covers add and remove, pending lifecycle, cooldown (with a fake clock), rate limit, a corrupted file, and reload after restart.
  - Depends on: T1
  - Files: `access/AccessStore.java`, `access/AccessService.java`, `access/AllowedUser.java`, `access/PendingRequest.java`, tests

- [ ] **T5: Authorization gate for messages and callbacks** (S)
  - Acceptance:
    - Every update passes one gate before any handler. Group and channel updates are ignored.
    - An unknown user gets the rate-limited refusal ("No access. Send /start to request it. Your id: …"), and `/start` from an unknown user creates a pending request and replies "Access requested".
    - `/whoami` works for everyone. Allowed users get a help text on `/start`.
  - Verify: `./mvnw test -Dtest=AuthorizationGateTest` covers owner, allowed, unknown, group chat, callback from unknown, and `/whoami` for everyone.
  - Depends on: T2, T4
  - Files: `access/AuthorizationGate.java`, `bot/BotUpdateHandler.java`, tests

- [ ] **T6: Owner commands and the Approve/Deny flow** (M)
  - Acceptance:
    - On a new request the owner gets "Access request from <name> (@username, id N)" with **Approve** and **Deny** buttons (`access:approve:<id>`, `access:deny:<id>`). Names are HTML-escaped.
    - Approve adds the user and notifies them. Deny removes the request, notifies them, and starts the cooldown.
    - `/allow <id>`, `/deny <id>`, and `/users` work for the owner only. The owner can't be removed. Events are logged (who, whom, when).
    - Pending requests and their buttons survive a restart.
  - Verify: `./mvnw test -Dtest=AdminCommandsTest` covers owner-only enforcement, approve and deny, cooldown, notifications, a restart with pending requests, and HTML escaping.
  - Depends on: T5
  - Files: `access/AdminCommands.java`, `access/AccessRequestFlow.java`, `bot/Keyboards.java`, tests

### Checkpoint 2: Access control
- [ ] All tests pass
- [ ] Manual: a second Telegram account sends `/start`, the owner approves from chat, and the second account is let in. A denied account is refused for 24 hours.
- [ ] Review with human before proceeding

## Phase 3: Summary pipeline

- [ ] **T7: Job model, `job.json` store, and single-worker queue** (M)
  - Acceptance:
    - `JobStore` writes and reads `job.json` atomically with `jobId`, `ownerUserId`, `chatId`, `summaryMessageId`, title, duration, `createdAt`, and per-file `{path, state, expiresAt, sentAt}`.
    - File states: `AVAILABLE`, `SENDING`, `SENT`, `EXPIRED`. Transitions are enforced and guarded by a per-job lock.
    - A single-thread `JobQueue` runs tasks one at a time, in order, and one failing task doesn't stop the queue.
  - Verify: `./mvnw test -Dtest='JobStoreTest,JobQueueTest'` covers round-trip, invalid transitions, concurrent transition attempts (one wins), and queue ordering.
  - Depends on: T1
  - Files: `job/JobStore.java`, `job/JobRecord.java`, `job/FileState.java`, `job/JobQueue.java`, tests

- [ ] **T8: Media: URL validation, metadata, download, WAV chunks** (M)
  - Acceptance:
    - `ProcessRunner` runs external commands from an argument list with a timeout and output capture. No shell strings.
    - URL validation accepts only http(s) URLs. Metadata (title, duration) comes from `yt-dlp`, and a video over 3 hours is rejected before download.
    - Audio download prefers m4a. A 16 kHz mono WAV is created and split into about 10-minute chunks.
    - Errors map to a `JobFailure` with a user-safe message (private, unsupported, too long, failed).
  - Verify: `./mvnw test -Dtest='UrlValidatorTest,YtDlpClientTest,ChunkSplitterTest'` with a stubbed `ProcessRunner`. Manual: download a short real video in the dev profile.
  - Depends on: T1, T3
  - Files: `media/ProcessRunner.java`, `media/UrlValidator.java`, `media/YtDlpClient.java`, `media/FfmpegRunner.java`, `job/JobFailure.java`, tests

- [ ] **T9: Transcription client and merged transcript** (S)
  - Acceptance:
    - `TranscriptionClient` sends each WAV chunk to `whisper-server` (language `en`) and merges the results in order into `transcript.txt`.
    - A failed chunk is retried once, and a second failure fails the job with a clear message. An unreachable server gives a clear "Whisper server unreachable" failure.
    - A progress callback reports "transcribing n/N".
    - Each chunk's text is trimmed and its inner newlines are collapsed before merging (the server wraps text per segment and starts with a space).
  - Verify: `./mvnw test -Dtest=TranscriptionClientTest` using the T3 fixtures in WireMock (merge order, retry, unreachable, progress callbacks).
  - Depends on: T3, T8
  - Files: `transcript/TranscriptionClient.java`, `transcript/TranscriptMerger.java`, tests

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
  - Verify: `./mvnw test -Dtest=PipelineIntegrationTest` (Spring Boot Test with WireMock and stubbed process runner): link, status edits, final message with two buttons, `job.json` content, no file uploads. Failure paths clean up the directory.
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
  - Verify: `./mvnw test -Dtest=TranscriptButtonTest` covers the happy path, double tap, concurrent taps, another user, and expired.
  - Depends on: T11
  - Files: `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, `bot/Keyboards.java`, test

- [ ] **T13: Audio preparation and Download audio button** (M)
  - Acceptance:
    - `AudioPreparer` follows the compression rule: a file of 50 MB or less in MP3 or M4A is returned unchanged. A smaller file in another format is converted to M4A at 128 kbps. A file over 50 MB is re-encoded to mono AAC at `48 MiB × 8 / duration`, verified under 50 MB, with one retry at a lower bitrate.
    - Tapping **Download audio** goes through the job queue, shows "Preparing audio…", sends with `sendAudio` (title and performer set), and notes whether and how it was compressed. The same one-send, owner-only, and expired rules as T12 apply.
  - Verify: `./mvnw test -Dtest='AudioPreparerTest,AudioButtonTest'` covers each branch of the rule (the unchanged branch must return the same path), the bitrate for a 3-hour input, the retry, and the button rules.
  - Depends on: T11, T12
  - Files: `media/AudioPreparer.java`, `media/FfmpegRunner.java`, `bot/DeliveryCallbackHandler.java`, `job/DeliveryService.java`, tests

- [ ] **T14: Retention sweeper and restart recovery** (M)
  - Acceptance:
    - A `@Scheduled` sweeper (every minute) and a run at startup delete files whose `expiresAt` has passed and mark them `EXPIRED`. A job directory is removed when every file is `SENT` or `EXPIRED`, or when its retention is over. Failed jobs are removed immediately.
    - At startup, `SENDING` files go back to `AVAILABLE`, and directories with a missing or corrupt `job.json` are removed.
    - After expiry the summary message buttons are replaced with "expired" (best effort).
  - Verify: `./mvnw test -Dtest=RetentionSweeperTest` with a fake clock and a temp directory: expiry, partial delivery, all delivered, `SENDING` recovery, corrupt `job.json`.
  - Depends on: T7, T12, T13
  - Files: `job/RetentionSweeper.java`, `job/JobRecovery.java`, `bot/Keyboards.java`, tests

- [ ] **T18: WireMock functional tests of the whole bot** (M) — added at the owner's request; numbered last to keep T1-T17 stable
  - Acceptance:
    - Functional tests (`*FT`) start the full Spring context with Telegram Bot API, Whisper and Ollama as WireMock servers (using the T3 fixtures) and a fake `ProcessRunner` for `yt-dlp`/`ffmpeg`. Updates are fed to the bot and assertions are made on the requests WireMock received.
    - Scenarios: unknown user requests access and the owner approves; allowed user sends a link and gets exactly one summary message with two buttons and no file uploads; transcript tap sends the `.txt` once; audio tap sends audio (compressed and unchanged cases); tap on an expired job; tap by another user; Whisper or Ollama down gives a clear failure message.
    - The tests run in `./mvnw verify` and need no network and no secrets.
  - Verify: `./mvnw verify` passes with the functional tests included.
  - Depends on: T14
  - Files: `src/test/java/com/video2podcast/functional/*FT.java`, shared WireMock support class, `pom.xml` (failsafe or surefire include)

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
    - Integration tests cover: restart between summary and button tap (buttons still work), restart with pending access requests, private/unsupported/too-long URL, non-allowlisted callbacks, and another user's button.
    - The JaCoCo gate requires at least 90% line coverage and 90% branch coverage across all production code, including `access` and `config`, measured from unit tests only. `./mvnw clean verify` generates `target/site/jacoco/index.html` and enforces both `COVEREDRATIO` minimums of `0.90` through `jacoco:check`; either threshold being missed fails the build. Integration-test coverage does not count toward the gate.
  - Verify: `./mvnw verify` passes with the gate on.
  - Depends on: T14, T15, T18
  - Files: `job/HealthChecks.java`, `RestartRecoveryIT.java`, `FailureModesIT.java`, `pom.xml`

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
