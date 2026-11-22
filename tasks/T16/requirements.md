# T16 requirements — Service health checks and failure-mode tests

Status: analyst design, 2026-11-22. Sources: [brief.md](brief.md), T16 in [todo.md](../todo.md), [SPEC.md](../../SPEC.md), [plan.md](../plan.md). Requirement ids are local to T16. Protocol and timing follow [A: Q-analyst-1](comms/questions.md) and [api.md](api.md). Owner answer A: Q-analyst-2 approves checking on the shared worker after queueing; it supersedes the earlier nonblocking-queue sentence in the brief. No open design question remains.

## Objective and boundaries

Detect an unavailable Whisper or Ollama before a submitted video incurs download work, without preventing startup, delaying other users, or changing established failures during processing. Close the missing functional failure/restart coverage while retaining the existing quality gates. This is a delta to the approved V1 spec, not a new pipeline or access model.

Inherited limits remain: private chats only, current user-id allowlist before work and every callback, owner-only access administration, job-owner-only downloads, one processing/audio worker, default maximum duration PT3H (10800 seconds accepted; greater rejected before download), summary at most 1500 characters, Telegram messages at most 4096 characters, cloud uploads at most 50 MiB, retention PT1H after publication, denial cooldown PT24H, and rate-limited unknown-user message refusal once per minute. Existing T8–T14 features remain the acceptance contract for these limits; T16 does not duplicate unrelated boundary scenarios.

## Requirements and acceptance criteria

### R1 — Best-effort startup checks

- Each application start probes both configured services once, even if the first probe fails. A lightweight HTTP operation must not upload audio or request model generation. Use the configured base URLs: Whisper `GET <base>/`, Ollama `GET <base>/api/version`, with no request body and no redirect following. Any received HTTP status (including 404/500/503) counts as reachable; discard response content. This checks HTTP reachability, not service/model readiness.
- Emit one health-result log line per service per startup pass: identify Whisper/Ollama, reachable/unreachable, and a concise safe reason for failure (for example connection refused or no answer in time). Do not emit a health-check stack trace, credentials, raw diagnostic response, or a multiline exception message.
- Run startup checks asynchronously after context readiness; they must not delay startup or polling. Service failure never prevents the bot becoming operational. Even while a startup response is pending, private /whoami and the access flow still work with either or both services unavailable. Startup alone sends no service-failure notice to users and starts no video work.
- No periodic probe is introduced. Startup state is informational; it is not a sticky global ban on later jobs.

### R2 — Fresh checks before each accepted link proceeds to processing

- Authorize the sender and validate the link as today. For each accepted link, queue and acknowledge it first, then freshly check both services when the worker starts that job, before any metadata call, directory creation or audio download; no model inference is used as a probe. Do not reuse a startup result or a prior submission's result.
- If both probes succeed, allow the job to proceed through the existing processing queue and preserve queued acknowledgement, FIFO processing after admission, progress updates, summary and file-delivery behavior. The two probes for one job run in parallel; different jobs retain single-worker FIFO ordering and their own chat association.
- A service restored after an earlier failed check is usable by a newly submitted link without restarting the bot. Failed links are not replayed automatically. A successful probe does not promise that the service will stay available during later work (R5).
- Both probes run in parallel with `app.health.timeout` / `HEALTH_TIMEOUT`, default `PT3S`, required positive. Aggregate bound is timeout + 1 second (4 seconds by default). This budget is independent of inference timeouts. Any HTTP status is reachable, even 503 while loading; do not interpret Ollama reachability as proof that qwen3:32b is installed, or change missing-model inference errors into a new readiness feature.

### R3 — Reject unavailable submissions without download or retained work

- User-visible order is `Queued: position N`, the failure message, then an edit of the queued status to `Failed.`. If Whisper is unavailable, send `The transcription server is unreachable.` to the originating private chat. If Ollama is unavailable, send `The summary server is unreachable.` If both are unavailable, send one message exactly `The transcription server is unreachable. The summary server is unreachable.` Neither failure may be omitted or duplicated.
- Connection refusal, lack of an HTTP answer within the probe deadline, DNS/transport failure, or a failed check reject preflight. An HTTP error status alone does not reject it. Probe timeouts use the unreachable message, not the existing processing-timeout message.
- Do not let the rejected job proceed to media/model processing, download audio, invoke ffmpeg, call Whisper inference/Ollama chat, publish a summary/keyboard, or upload audio/transcript. Check before creating a working directory and before any yt-dlp invocation, including metadata. No directory is created for a rejected submission. Leave other jobs and the access store intact.
- Failure notification rejection by Telegram must not leave the job queued or retain its directory; another accepted link can still proceed. Deterministic filesystem failure/notification exception details are unit-test obligations, not a new user-facing failure policy.

### R4 — Bound worker delay and keep Telegram responsive

- Confirmed timing: `HEALTH_TIMEOUT=PT3S` per probe, both in parallel, aggregate <=4 seconds by default (timeout + 1 second). Health checking must have its own short deadline measured in seconds; the PT10M inference defaults must not govern probes. Bound connection and response waits, attempt both services despite failure, and do not indefinitely retry health requests.
- Per owner A: Q-analyst-2, run the check on the shared processing/audio worker immediately before execution. A held probe may delay following queued jobs/audio preparation by at most HEALTH_TIMEOUT + 1 second (4 seconds by default); expiry releases the worker without waiting for the held server response. Other users' /whoami messages and valid transcript-button taps receive their normal responses while the check is pending.
- Keep one processing/audio job at a time. This task adds no competing inference worker and does not bypass FIFO for admitted jobs. A waiting job is checked only when its FIFO turn arrives, so it uses current reachability rather than a result taken at link intake.
- Functional tests hold a real mock HTTP response and observe a second Telegram response while the probe is pending, then expiry and subsequent queued-work progress without releasing the held response. Responsiveness assertions must place the Telegram reply/upload before the corresponding health deadline and before health-result/terminal-failure events, while the response is still held; merely replying before mock release could falsely pass after a blocking timeout. Apply the same event-order rule to startup /whoami (no startup health-result lines yet). Shorter test deadlines may be configured through the actual production configuration; a fake clock alone does not prove an HTTP deadline.

### R5 — Preserve T9/T10 mid-job errors and retries

- After healthy preflight, failures at transcription/summary retain their existing typed mapping, safe texts, cleanup, and retry counts. In particular Whisper retries its failed chunk once; Ollama retains the current connection, model-not-found, server-error, empty-answer, truncation and timeout behavior.
- Keep existing T9, T10 and T11 failure scenarios running. Adjust external mock setup so preflight succeeds before the intended later failure; a new preflight rejection is not evidence of a mid-job failure test passing. Distinguish health HTTP calls from inference/chat assertions.
- No deferred job, two-hour retry, or periodic replay (T18).

### R6 — Complete callback failure coverage without duplicating delivery scenarios

- Keep existing non-allowlisted audio/transcript refusal scenarios. Add the missing access:approve and access:deny cases from a non-allowlisted user against a real pending request, checking `answerCallbackQuery` text `No access.` and unchanged persisted allowlist/pending state.
- No user approval/denial notification, request-message edit, file upload, video work or inference may result from these refused callbacks. An allowlisted non-owner remains covered by AccessApprovalIT and must still receive the existing owner-only refusal.

### R7 — Pending access requests survive a real application restart

- Restart means close the application context and start a fresh context against the same on-disk access file; rebuilding an AccessService object alone is not acceptance evidence.
- If an owner notification was delivered and its message id persisted, startup must preserve the pending request and target id without sending a duplicate owner notification. The original Approve or Deny callback still works after restart: approve persists access and notifies the applicant, deny removes the request, notifies the applicant, and enforces the existing PT24H cooldown.
- A callback on a different message id remains stale and changes nothing; after resolution, a repeated original callback cannot resolve the request again.
- If the initial owner notification failed and no message id was stored, startup retries notification and persists the newly delivered message id. A subsequent restart does not repeat a successfully delivered notification. A failed startup retry leaves the request pending for the existing next-/start/startup retry path.
- No health-check outage may erase access requests or stop the approval flow; use local health mocks during restart tests, and include an unavailable-service restart case.

### R8 — Offline verification and quality gates

- Functional tests (`*IT`, including FailureModesIT and Cucumber steps as needed) exercise Telegram updates and HTTP/filesystem boundaries. Use WireMock and process fixtures; no real Telegram, host Whisper/Ollama, public video host, DNS lookup or model download is required. Local loopback fixture traffic is permitted.
- Keep JaCoCo in pom.xml at >=90% LINE and >=90% BRANCH using unit execution data only. Preserve PIT >=85%, and run `./mvnw clean verify` and `./mvnw verify -Pmutation`. Adding IT coverage cannot substitute for unit coverage or mutation tests.
- Update operational docs through the owning roles: decisions record the health contract; host setup explains startup logs, short timeout, per-job failure messages and manual checks. The owner stops Whisper and then Ollama, submits a link for each and confirms the corresponding Telegram text; automated mock evidence does not replace this manual check.
- Analyst validation is Gherkin parsing, requirement-tag/link checks and scoped whitespace checks. Builds, executable step implementation, unit coverage, mutation results and manual smoke evidence belong to Developer/Tester/orchestrator; none are claimed by these design documents.

## Coverage audit of T4–T14 (read 2026-11-22)

“Covered” below means an existing scenario/test specifies the behavior; it does not claim a test run in this analyst turn. T4–T6 access coverage predates task-tagged feature files and is in Java ITs.

| Requested failure path | Existing specification / executable coverage | T16 delta |
|---|---|---|
| Whisper down at link time, no download | T9-transcription.feature and T11-summary-flow.feature `Remove failed jobs and preserve the safe stage error` cover refusal during inference, after media work | New preflight cases with no download, inference, retained directory |
| Ollama down at link time, no download | T10-summary.feature and the same T11 outline cover summary-stage refusal | New preflight cases |
| Both down at link time | No existing both-service preflight scenario | New case asserting the combined failure message; parallel dual-attempt proof for refused connections belongs to unit tests |
| Private URL | T8-media.feature `Explain yt-dlp failures safely in chat`; T11 failure outline row `private metadata from yt-dlp` checks message/cleanup | Reuse; no duplicate media scenario |
| Unsupported URL | T8 restricted-link/metadata/error scenarios; T11 unsafe-URL scenario and failure outline `unsupported metadata from yt-dlp` | Reuse |
| Too-long URL | T8 duration outlines cover >10800 including fractional excess, configured PT2H and exact-limit acceptance; T11 failure outline `metadata duration of 10801 seconds` checks text/cleanup | Reuse |
| Callback by non-allowlisted user | AccessGateIT `buttonTapFromAnUnknownUserIsRefusedAndAnAllowedUserPasses`; T12 `Revoked access prevents delivery of the user's own job`; T13 current-allowlist refusal | Retain delivery cases; add refused access approve/deny with persisted side-effect checks |
| Pending access after restart | AccessServiceTest `pendingRequestAndItsMessageIdSurviveRestart`; AdminCommandsTest restart/button and missing-notification startup tests are unit-only; AccessApprovalIT covers live approval/denial without context restart | New full-context restart scenarios for preserved buttons, stale binding and notification retry |
| Published job restart / retention | T12/T13 retained delivery and T14-retention.feature cover job files, SENDING recovery and expiry | Reuse; pending access is a separate durable file |

## Out of scope

T18 deferred jobs/two-hour retries; periodic monitoring; service management or restarts; model pulling/warm-up/inference probes; changing the model or inference retry policy; changing SPEC.md limits, queue capacity or access policy; deployment/T17 real-video benchmarks; production code, test code, architecture-owned docs and commits by Analyst.

## Handoff

Scenario source: [T16-failure-modes.feature](../../src/features/T16-failure-modes.feature). Research: [research.md](research.md). A: Q-analyst-1 resolves protocol/timing; owner A: Q-analyst-2 resolves queue placement. All requirements and scenarios incorporate both answers. Internal sanitization branches, deadline configuration validation, cancellation/exception handling and cleanup-I/O interleavings belong in Developer unit tests; external effects have functional scenarios.
