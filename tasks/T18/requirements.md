# T18 requirements — Deferred jobs after a model-service outage

Status: analyst draft for team review. Date: 2026-11-23.

## Objective and authority

Finish an allowed user's video automatically after Whisper or Ollama recovers, without retaining media while waiting or requiring another Telegram submission. Authority: [brief.md](brief.md), [T18 acceptance](../todo.md), [SPEC.md](../../SPEC.md), and [plan.md](../plan.md). This is a delta to T9–T11/T16 terminal outage handling; all other contracts remain in force. The brief explicitly confirms copy, terminal handling of non-outage retry failures, and silent removal after access revocation.

Accounting confirmed by Architect in A: Q-analyst-1: 12 attempts means 12 scheduled retries after the original submission, initially stored as count 0; at two-hour intervals this is about 24 hours, plus processing/queue delays. The scheduler has a fixed delay and an initial delay equal to the configured interval; restart begins a fresh full-interval wait. An asynchronous pass uses a non-overlap guard. See [A: Q-analyst-1](comms/questions.md) and [api.md](api.md). No wall-clock 24-hour expiry is introduced.

## Requirements and checkable acceptance criteria

### R1 — Park either model-service outage

- Park an accepted private-chat link when normal processing ends with an unavailable Whisper or Ollama. Include both existing kinds: `SERVICE_UNREACHABLE` and `SUMMARY_UNREACHABLE`. Combined failures create one entry, not one per unavailable service.
- Include the T16 preflight health checks and failures after downloading/transcribing; preserve their current classification. A health check that cannot connect or exceeds HEALTH_TIMEOUT already counts as unavailable.
- Do not turn all HTTP errors or inference answer timeouts into outages. Connected Whisper failures (including its exhausted immediate chunk retry), Ollama HTTP/malformed responses and answer timeouts retain their current terminal classification. T9's immediate chunk retry is distinct from a T18 scheduled attempt.
- Invalid/unsafe URLs, unknown users and group/channel messages never create deferred entries. Existing access and validation replies remain unchanged.

### R2 — Persist an ordered, minimal queue

- Store `deferred-jobs.json` beside the configured ACCESS_FILE (`/data/deferred-jobs.json` by default), outside job working directories. Persist the URL, owner user id, originating private chat id, original queued status message id, scheduled-attempt count and original parked time. A stable identity may be stored for correlation; do not retain media, transcript, generated summaries, token, or raw server diagnostics in the queue.
- Append newly deferred submissions in parking order. Repeated failure of an entry updates it without creating a duplicate or moving it behind later entries. Repeated links remain independent submissions; V1 has no URL deduplication.
- Every append, attempt update and removal is persisted atomically under serialization, following the existing allowlist write discipline. A successful update survives an ordinary restart with order, ownership, target, count and parked time intact.
- An absent queue file means an empty queue. Invalid JSON is preserved by rename to `deferred-jobs.json.corrupt-<time>`, then startup continues with an empty queue. Access data and unrelated files remain unchanged.
- Never promise automatic completion before queue persistence succeeds. Persistence failures must not silently discard the last good file or escape as raw diagnostics; detailed fallback/locking contract belongs to Architect api/flows and unit tests.

### R3 — Delete working files immediately and ignore job retention while waiting

- After an outage, remove that attempt's entire working directory before waiting, including any source, WAV, chunks, transcript and job.json. Preflight failures need not create a directory at all.
- A retry revalidates the URL, checks current metadata/duration and starts the normal download → WAV/chunks → transcription → summary flow from scratch. An Ollama outage does not preserve an already-completed transcript.
- JOB_RETENTION and startup work-directory cleanup do not remove deferred entries or reset attempt counts. Cleanup never changes other users' retained files or the access file.
- On retry success the usual files are retained; their retention starts at successful summary publication, not original parking time. Existing maximum video duration (default three hours), English transcription, escaping and file-delivery rules still apply.

### R4 — Notify once using the original status message

- After initial parking, edit the existing queued/progress status message to exactly `Service temporarily unavailable. We will send the result after it recovers.` Do not send a separate outage message or overwrite it with `Failed.`.
- One unavailable submission gets one such edit, including when both services are down. Later unsuccessful scheduled attempts emit no repeated outage, queued, downloading, transcribing or summarizing Telegram messages/edits. They must not flicker the parked message back to progress.
- Telegram rejection of the parking edit does not remove a durably parked entry or block the next job. No automatic Telegram transport retry or fallback duplicate message is introduced; actual receipt cannot be guaranteed when Telegram rejects a call.

### R5 — Retry automatically in order through the single worker

- Enable scheduled retries without incoming Telegram updates. DEFERRED_RETRY_INTERVAL defaults to `PT2H`; DEFERRED_MAX_ATTEMPTS defaults to `12`. Both are configurable and documented with environment mappings. Nonpositive values must not create a busy loop or an unbounded retry budget (configuration validation is a unit/startup contract).
- Each scheduled pass retries each eligible waiting entry at most once in persisted FIFO order. It must not loop repeatedly on the first unavailable entry or starve later entries. New entries wait for a later pass rather than being retried recursively in the pass that parks them.
- Deferred processing, fresh link processing and audio preparation share the existing one-at-a-time execution boundary. An entry already submitted/running must not be submitted again by a subsequent tick. Commands and transcript delivery remain responsive as before.
- Before another scheduled attempt, an outage leaves the entry present with the incremented count and unchanged original target/parked time; no working files remain. A retry may fail at a different model service without creating another entry or notification.
- The scheduling and counter contract must be testable with a fake clock/controlled scheduler, not a two-hour sleep. No catch-up burst or exact wall-clock completion guarantee is required.

### R6 — Finish successfully using the normal result and remove the entry

- When services recover, publish the usual HTML summary in the stored chat with Download audio then Download transcript, owner-bound callback data and normal file retention. Remove the deferred entry durably after successful publication.
- Never automatically upload either file. Existing transcript/audio button flows work after retry success, including after an ordinary restart.
- A later scheduled pass or ordinary restart must not replay a successfully removed entry. Normal summary publication safety and cleanup still apply if Telegram or job persistence fails.

### R7 — Exhaust the configured scheduled-attempt budget once

- Initial parking stores count 0. Each scheduled processing attempt consumes one attempt; T9 immediate per-chunk retries and merely waiting in the shared worker do not consume extra scheduled attempts.
- If the twelfth scheduled retry still ends with an outage under defaults, remove the entry and notify the stored chat once with `We could not process this link. Please send it again later.` No thirteenth retry is made.
- A successful twelfth attempt still publishes the normal result. With DEFERRED_MAX_ATTEMPTS=2, the first unsuccessful retry stays and the second unsuccessful retry closes the entry. Restart does not reset the count.
- Terminal exhaustion and other retry failures use the normal separate safe error send and a `Failed.` status edit; success edits the original parked status to `Done.`. Terminal notification is best effort, uses the ordinary safe Telegram failure-reporting convention, and cannot retain the entry indefinitely when rejected. Do not send both the outage notice and the exhaustion text on this attempt.

### R8 — Close immediately on a different retry failure

- Any retry failure classified other than the two outage kinds closes/removes that entry immediately, with its ordinary safe error message to the stored chat. Examples: changed URL metadata is private/live/unsupported/over limit, download or conversion failure, no speech, transcription HTTP failure, bad summary or answer timeout.
- Delete the failed retry's working directory; do not wait for the attempt limit. Later queue entries continue. No raw stderr, paths, server bodies, transcript or secrets enter error messages.
- A non-outage failure on the original submission still uses the existing terminal behavior and never enters this queue.

### R9 — Drop users who no longer have access silently

- Recheck the stored owner user id against current access immediately before retry work, including when the entry has waited behind another running job. If access is gone, remove the entry without health, media, inference/chat requests, summary, uploads or Telegram notification.
- Owner access remains unconditional as in SPEC. Do not authorize by chat id or revive access merely because the user owned a deferred entry. Access revocation does not alter other users' entries.

### R10 — Verification and inherited acceptance

- [T18-deferred-jobs.feature](../../src/features/T18-deferred-jobs.feature) drives Telegram updates, external HTTP/proxy fixtures, scheduled time and files. New steps must not invoke internal classes or methods as the feature action.
- Tester supplies DeferredJobIT, including the required Whisper-down → durable queue → scheduler after recovery → summary/empty queue flow, exhaustion, restart and repeated-outage silence. Real yt-dlp/ffmpeg with local WireMock media proxy remain the success-path standard; deterministic process failures may be faked.
- Developer supplies DeferredJobsTest with fake time/temp directories, atomic persistence and concurrency coverage. Keep `./mvnw test -Dtest=DeferredJobsTest`, `./mvnw verify -Dit.test=DeferredJobIT`, `./mvnw clean verify` and `./mvnw verify -Pmutation` green: independent 90% line/branch unit coverage, 85% mutation, Spotless, SpotBugs and PMD unchanged.
- Existing outage scenarios in T9/T10/T11/T16 must agree with R1/R4. Their original requirement tags stay local to their original task; T18 tags refer to this document only in the T18 feature.

## Acceptance mapping and test boundary

| Requirements | External evidence |
|---|---|
| R1–R4 | Initial preflight/mid-job outage matrices; durable metadata, same-message notice, immediate deletion, isolation |
| R2 | Missing/corrupt file startup; ordinary restart retains ordered queue/count/target |
| R3, R5, R6 | Automatic from-scratch success, fresh metadata/transcription, retention begins on publication, no uploads |
| R4, R5 | Repeated-outage silence, changed failing service, FIFO and held-worker overlap scenarios |
| R7 | Default twelfth boundary, configurable two-attempt budget, success on last attempt, rejected notification |
| R8 | Non-outage failures on initial submission and retry; continued processing of next entry |
| R9 | Revocation before retry and while waiting behind a held job; owner access preserved |
| R10 | Tester/Developer reports; Gherkin parsing and text/link review at analyst handoff |

Atomic move failures, lock interleavings, scheduler tick races, shutdown cancellation and interrupted persistence/publication windows remain unit/architecture contracts; a functional scenario must observe HTTP, files or queue content, not a service method call. Ordinary restart durability is required; exactly-once Telegram delivery across a crash between external publication and durable deletion is not promised by this task. Architecture must document that window, not claim a cross-system transaction.

## Out of scope

No database, broker, retry UI/commands, cancellation API, retained media/checkpoints, exponential backoff, health-triggered immediate replay, repeated-link deduplication, multiple bot instances sharing this file, per-user quotas, group support, new model backend or paid service. No changes to audio size/compression, normal access approval, retention of successfully published jobs, or service-level immediate retry classification. Analyst does not implement code/tests, edit SPEC/deployment files, or commit.

## Handoff

Architect specifies JSON field names, fixed-delay scheduling with a full-interval startup delay, durable claim/accounting details and persistence-failure fallback in [api.md](api.md) and [flows.md](flows.md). Failed initial park persistence falls back to the existing safe outage failure message and `Failed.` status; no automatic-completion promise is sent. On later write failures the previous queue remains valid; duplicate processing/notification is a degraded-storage risk to document, not a healthy-storage guarantee. Shutdown interruption leaves the entry/count unchanged. They must satisfy the externally stated contracts above; unresolved product decisions go through the append-only question log to owner. This draft is not implementation verification.

## Existing scenario migration

- T9 `Report an unreachable transcription server without inventing received requests` and T10 `Explain an unreachable Ollama instead of silently hanging` now assert the parked status edit and durable entry rather than the old terminal unreachable send. Their no-request/no-summary assertions stay applicable.
- T11's terminal-failure outline no longer includes the two connection-refusal examples. They are covered by T18 `An outage after preflight also discards all working files` (Whisper and Ollama examples) plus `Park a link when preflight finds an unavailable service`. All non-outage examples remain in T11.
- T16 preflight refusal/stall cases now assert parking; same-message notification replaces the separate error and Failed status. The recovery-via-fresh-submission examples assert only-before-next-retry behavior, so they do not prohibit T18's later automatic replay. Startup health logging, responsiveness, isolation, no-download-before-health and access cases remain in force.
- Tester must update the corresponding step definitions/assertions owned under src/it; these analyst edits intentionally precede implementation and will change acceptance expectations.

## Analyst artifact check — 2026-11-23

All repository feature files parse with the installed Cucumber Gherkin 42.0.1 parser. All 27 T18 scenarios/outlines carry local requirement tags; local requirements/research links resolve. `git diff --check` passes. No production implementation or Maven gate is claimed by these design checks.
