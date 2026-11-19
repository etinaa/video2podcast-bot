# T11 requirements — Video link to summary message

Status: analyst design ready for team review; aligned with A: Q-analyst-1 and A: Q-architect-check. Date: 2026-11-19.

## Objective and authority

An allowed user sends one video URL in a private Telegram chat and receives progress followed by an English summary with two download buttons. The source and transcript remain on disk for later delivery; this task sends neither file.

Authority: [brief.md](brief.md), T11 in [tasks/todo.md](../todo.md), [SPEC.md](../../SPEC.md), and [tasks/plan.md](../plan.md). Existing T8/T9/T10 contracts remain in force. TD4 and TD5 refine the media acceptance strategy: real yt-dlp/ffmpeg for successful processing, including combined MP4 fallback. SPEC.md's older statement that all functional media uses a fake ProcessRunner is superseded for T11 by the approved brief. No new product assumptions are needed for the core flow.

## Requirements and acceptance criteria

### R1 — Preserve access and private-chat boundaries

- Process URLs only from the owner or an allowed user's private text message. Check the sender's user id, not only chat id. Strip leading/trailing whitespace from the whole text before validation. Any non-command text is treated as a link; invalid text gets one unsupported-link reply, without a status or job. Unknown slash commands stay ignored.
- Unknown users retain the existing rate-limited refusal/access-request behavior. Groups, supergroups and channels remain ignored.
- Rejected access creates no job, makes no media/Whisper/Ollama request and sends no summary. Existing commands, including universal private `/whoami`, keep their behavior.
- URL validation and metadata constraints from T8 apply before download: only the supported safe URL form, maximum 2048 characters, no local/private literal addresses, no credentials, whitespace or control characters. DNS names are not resolved by validation. Exactly 10800 seconds passes at the default limit; anything longer fails. Live/absent-duration inputs are rejected. T8 scenarios and unit tests own the full validation matrix.

### R2 — Acknowledge promptly and report the initial queue position

- With Telegram accepting requests, send an acknowledgement within 5 seconds of receiving an allowed user's URL update, without waiting for metadata, download, Whisper or Ollama to complete.
- The acknowledgement IS the first status message: `Queued: position N`. Send no separate acknowledgement. Its count is running plus waiting jobs including this job (`JobQueue.size()` at addition). The first job in an empty queue shows `Queued: position 1`. This resolves the draft's ambiguous separate-message wording, per A: Q-architect-check.
- The reported position is the initial snapshot, not a requirement to continuously renumber waiting jobs. Completed and failed jobs no longer count. TD2's external queue-position scenarios remain authoritative.
- Slow processing must not block acknowledgement of later submissions or `/whoami` handling.

### R3 — Edit one status message through the stages

- After queued, use Telegram `editMessageText` on the same chat/message id for downloading, transcribing n/N and summarizing, in processing order. Do not create a new status message for each stage.
- Report transcription n/N before the first Whisper request for chunk n, once per chunk; retrying a chunk does not add a progress event. T9's progress matrix remains in force.
- The final summary is a newly sent message, separate from the queued acknowledgement/status; its returned id is the summaryMessageId, not the status id.
- Exact stage copy is `Downloading…`, `Transcribing n/N…`, `Summarizing…`, then `Done.` on success or `Failed.` after the separate failure sentence. Terminal notifications occur after the job leaves the queue. TD2's successful-job completion step must wait for `Done.`, not merely the summary send; see [flows.md](flows.md), F6. Progress edits that Telegram rejects are best effort, as specified in R8.

### R4 — Connect the stages in queue order

- One accepted submission gets its own generated job id and working directory. Preserve its user/chat association throughout all stages.
- Run metadata/duration validation, download, WAV/chunk preparation, ordered transcription, then summary. Keep the existing per-stage failure classifications and timeouts.
- Do not send Ollama requests before the complete transcript exists. Never publish an intermediate map/reduction result as the final summary.
- Only one job processes at a time in the shared first-come queue. A terminal failure must release the worker so the next submission can complete.
- Successful media preparation uses `bestaudio[ext=m4a]/bestaudio/best`, including sources with only combined video/audio. Preserve source bytes. WAV/chunks are temporary and removed after successful transcription; retain source and UTF-8 transcript after successful summary.

### R5 — Send safe, bounded HTML content

- Send one successful summary to the originating private chat using HTML parse mode and disabled link previews.
- Include the video title in bold, a human-readable duration and the final nonempty English bullet summary. Preserve T10's normalized bullet content and independent cap of 1500 UTF-16 units including markers/newlines.
- Escape every user/model-controlled title and summary fragment before inserting it in HTML, including `<`, `>` and `&`; do not allow embedded tags/links to become active markup or double-escape plain content. Quotes must remain safe in their output context.
- The whole message must satisfy Telegram's 4096-character limit after entity parsing, including title/duration/separators. Expanding escape entities does not increase displayed characters. Per [api.md](api.md), collapse title whitespace, strip, and limit the displayed title to 200 UTF-16 units including a final `…` when clipped; never split a surrogate pair (the result may be one unit shorter). Keep the full original title in job.json. Duration uses hours and floored minutes if hours are nonzero (`1h 0m`, `1h 42m`, `3h 0m`), otherwise minutes (`5m`), otherwise seconds (`0s`, `45s`). T10's body cap is unchanged.

### R6 — Publish buttons but upload nothing

- Attach exactly the two inline buttons **Download audio** and **Download transcript** to the summary, with callback data `audio:<jobId>` and `transcript:<jobId>` for the same stored job.
- Callback data contains an identifier, never a URL or filesystem path, and is at most 64 UTF-8 bytes per button.
- No Telegram `sendAudio` or `sendDocument` call occurs anywhere in this link-to-summary flow, on success or failure. Do not prepare/compress a delivery audio file before a button tap. Whisper conversion is still required.
- Receiving/treating these download callbacks, ownership checks on them, independent delivery, expiry responses and duplicate-tap semantics are T12/T13/T14 work, not T11 acceptance.

### R7 — Persist the published job and start retention at publication

- On success, `job.json` is readable in that job's directory and records jobId, sender ownerUserId, originating chatId, summaryMessageId returned by Telegram, metadata title/duration, createdAt and both file entries.
- The AUDIO entry points to the downloaded `source.*` (including source.mp4 for combined media); TRANSCRIPT points to `transcript.txt`. Paths are plain filenames within the job directory. Both entries are AVAILABLE and sentAt is null.
- Set both file expiresAt values to successful summary-send time plus configured JOB_RETENTION (default PT1H). Do not start retention on receipt, enqueue, download or transcript creation. T7's existing schema stores expiresAt per file; no new top-level field is required by the T11 brief's shorthand.
- Delaying summarization past an hour must not consume post-publication retention. A configured nondefault duration must take effect.
- Successful source/transcript/job.json remain available after publishing; do not delete the directory on pipeline success. Sweeping and startup recovery are T14.

### R8 — Report terminal failures safely and clean only that job

- On a terminal processing failure, report a fixed, clear English error in the originating chat and recursively remove the failed job's working directory, including partial downloads, WAV, chunks, transcript and job.json if present. If validation failed before any directory was created, there is nothing to remove.
- Preserve known safe messages: private/sign-in → `This video is private or needs a sign-in.`; unsupported → `This link is not a supported video.`; over default limit → `The video is longer than 3 hours.`; live → `Live streams are not supported.`; media failure → `Could not process this video. Please try again later.`; timeout → `Processing took too long and was stopped.`; no speech → `No speech was found in this video.`; Whisper unreachable → `The transcription server is unreachable.`; other transcription failure → `Transcription failed. Please try again later.`; Ollama unreachable → `The summary server is unreachable.`; other summary failure → `Summary failed. Please try again later.` Configured duration limits use the existing T8 formatting.
- Never send raw stderr, stack traces, filesystem paths, server diagnostic bodies or transcript contents as an error. Send no successful summary or download keyboard after a processing failure.
- Failed-job cleanup must leave other queued jobs, completed jobs and the separate access store untouched. The next queued job must still finish.
- Per [api.md](api.md)/[flows.md](flows.md), a failed initial queued send stops this submission before any media or disk work; no extra send or retry is attempted. The submitted waiting task ends and leaves the queue.
- A rejected progress edit is logged and ignored; processing and subsequent edits continue. Do not retry the failed edit. A failed terminal `Done.`/`Failed.` edit or failed error-message send is logged and swallowed; it cannot strand the queue. No application-level Telegram retry is added.
- A failed job.json create, failed summary send, or failed publication update becomes the generic fixed media failure sentence, with best-effort failed-directory deletion. If the summary send succeeded but publication persistence fails, attempt to edit that summary to the same HTML without buttons, then report the failure. The already-sent text may remain; the no-summary assertion above applies to processing failures before publication, not this recovery case. Withdrawal itself is best effort and must not prevent deletion or queue release.
- A deletion failure is logged and does not replace the original safe failure or strand the queue. Disk deletion cannot be guaranteed when the filesystem refuses it; this does not relax cleanup with a healthy filesystem. Permission-error cleanup/logging branches and queue-shutdown cancellation remain unit tests when no reliable external fixture exists.

### R9 — Execute the inherited external-boundary acceptance

- Tester removes `not @T8`, `not @T9`, `not @TD2` and `not @T10` from CucumberIT and supplies steps for the existing features plus [T11-summary-flow.feature](../../src/features/T11-summary-flow.feature). Keep `@manual_media` cases manual, explicitly excluding them from unattended Cucumber runs.
- Drive the full Spring application with Telegram updates and observe Telegram/Whisper/Ollama HTTP, process-boundary arguments where explicitly required, and disk files. No steps call pipeline/services/classes directly.
- Use real yt-dlp and ffmpeg in success scenarios, with ffmpeg-generated local fixtures served by WireMock through the TD4 media proxy. Only commands/URLs required to route media fixtures may be decorated; transformation/download still execute for real. No internet, live Telegram account/token, real models or other secrets are needed.
- Process fakes remain allowed for deterministic failures and the inherited explicit argument-inspection scenarios; fake success output does not prove media conversion. Keep T8's @TD5 combined-MP4 coverage active.
- Supply SummaryFlowIT on the T4a harness: link → status edits → summary/keyboard → job.json; no uploads, plus failed-directory cleanup. Use T3 response shapes, but provide prompt_eval_count appropriate to each complete Ollama request; a fixed captured count of 71 is not valid for every transcript.

### R10 — Retain project verification gates

- `./mvnw verify -Dit.test=SummaryFlowIT` verifies the dedicated functional flow; `./mvnw verify` runs all nonmanual Cucumber scenarios and existing integration tests.
- `./mvnw clean verify` passes unit tests, independent 90% line/90% branch unit coverage, Spotless, SpotBugs and PMD. `./mvnw verify -Pmutation` meets the 85% mutation gate. Integration tests do not contribute to either coverage gate.
- Analyst artifact validation parses Gherkin and checks requirement tags, wording and links. This is not evidence that the future implementation passes these commands.

## Acceptance mapping and unit-test boundary

| Requirement | Acceptance evidence |
|---|---|
| R1 | T11 access scenarios, T8 URL/duration scenarios, existing access functional tests |
| R2 | T11 blocked-work acknowledgement, TD2 queue-position feature |
| R3 | T11 same-message stages; T9 chunk progress/retry scenarios |
| R4 | T11 FIFO/failure continuation; T8 real media/TD5, T9 merge, T10 summary feature |
| R5 | T11 content/escaping/length; T10 bullet and body-cap scenarios |
| R6 | T11 keyboard and no-upload assertions on success/failure |
| R7 | T11 persisted file entries, delayed publication and retention override |
| R8 | T11 failure/cleanup matrix, isolation, Telegram rejection and filesystem publication failures; inherited failure cases |
| R9 | Runner configuration and executing the full nonmanual suite, reviewed by Tester |
| R10 | Maven reports and gate results supplied by Developer/Tester |

Internal queue future/callback ordering, shutdown behavior, process-child termination, atomic-write mechanics, per-job locks, precise formatter clipping boundaries and low-level transport exception branches remain unit tests. Functional tests may control time/configuration and mock external HTTP, but assertions must observe HTTP/files/queue, not internal methods. In Gherkin, requirement tags are local to the feature's task (e.g. T9 @R1 refers to T9 requirements).

## Out of scope

T12 transcript sending; T13 audio delivery/compression; T14 expiry sweeper/startup recovery; T16 restart/interruption hardening; T17 real-model/manual smoke tests. No RSS, hosting, database, new public API, backend change or dependency addition. No change to existing access approval behavior. No implementation, runner edits, test code, SPEC.md edits or commits by this analyst step.

## Questions and handoff

[Q-analyst-1 and Q-architect-check](comms/questions.md) are answered by the Architect in [api.md](api.md) and [flows.md](flows.md). This revision incorporates the single queued acknowledgement, exact copy, title/duration rules and failure recovery. No owner product choice remains open. Tester should report automation conflicts through the append-only question log; internal mechanics stay unit-tested rather than introducing service calls in steps.


## T9 connection-refusal acceptance clarification — A: Q-tester-1

A refused TCP connection cannot appear in WireMock's HTTP request journal. Do not substitute HTTP 503 or a connection reset for a connection refusal. T9's HTTP retry matrices now cover only requests that reach the HTTP mock. A separate Telegram-driven scenario uses a genuinely closed local Whisper port and asserts the safe unreachable message, no Whisper/Ollama HTTP requests and failed-directory cleanup. It does not claim to count refused attempts through WireMock.

The immediate-retry policy and all mixed-outcome requirements remain unchanged. Deterministic mixed connection-error sequences belong to unit tests, with this explicit mapping:

| Removed HTTP-matrix example | Unit evidence / required addition |
|---|---|
| First attempt refuses, retry succeeds, later chunks continue without resending earlier chunks | **Developer addition required:** connection-error-then-success case in TranscriptionClientTest; existing retriesOnceAndKeepsGoing covers a nonconnection failure only and is not claimed to prove this variant. |
| Connection error then nonconnection failure | TranscriptionClientTest#secondFailureDecidesTheKind |
| Nonconnection failure then connection error | TranscriptionClientTest#connectionFailureOnRetryIsServiceUnreachable |
| Both attempts cannot connect | WhisperClientTest#twoConnectTimeoutsGiveServiceUnreachable; refusedConnectionIsAConnectionError verifies genuine refusal classification; separate closed-port acceptance checks the Telegram outcome. |

Exact retry counts, per-attempt payload preparation and connection-error mapping remain unit contracts; wire-level multipart bytes are asserted only for HTTP requests actually received. This resolves an unobservable assertion without weakening the required behavior or introducing an internal service invocation into Gherkin. The Developer owns the missing unit case; this analyst revision does not claim it already exists or passes.
