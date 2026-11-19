# T11 research — Pipeline publication and Telegram contracts

Researched: 2026-11-19. Sources distinguish upstream API facts from repository decisions. No live Telegram messages, media downloads or model calls were made by this analysis.

## Official documentation checked

| Source | Fact taken | Application | Accessed |
|---|---|---|---|
| [Telegram sendMessage](https://core.telegram.org/bots/api#sendmessage) | Returns a Message on success; text limit is 4096 characters after entity parsing. HTML mode and preview options are available. | Store the returned summary id; bound the complete displayed text and disable previews. | 2026-11-19 |
| [Telegram editMessageText](https://core.telegram.org/bots/api#editmessagetext) | Private-chat edits identify chat_id and message_id. | Reuse one status message throughout processing. | 2026-11-19 |
| [Telegram HTML formatting](https://core.telegram.org/bots/api#html-style) | Literal `<`, `>` and `&` require escaping outside tags/entities; supported named entities include lt, gt, amp and quot. | Escape dynamic title and summary before adding fixed markup. | 2026-11-19 |
| [Telegram InlineKeyboardButton](https://core.telegram.org/bots/api#inlinekeyboardbutton) | callback_data is limited to 1–64 bytes. | Use short job-id callbacks and check UTF-8 length. | 2026-11-19 |

These are protocol facts, not a choice to upgrade the pinned Telegram library. Telegram documentation cannot decide application retry policy, queue snapshot semantics or retention origin.

## Repository evidence checked

| Source | Fact taken and consequence | Read |
|---|---|---|
| [SPEC.md](../../SPEC.md), [plan.md](../plan.md), T11 in [todo.md](../todo.md), [brief.md](brief.md) | T11 is the join point after T5/T7/T8/T9/T10/TD4: acknowledge within 5 seconds, edit status, send safe HTML/buttons, retain files, clean failures. No automatic uploads. | 2026-11-19 |
| [JobRecord.java](../../src/main/java/com/video2podcast/job/JobRecord.java), [JobFile.java](../../src/main/java/com/video2podcast/job/JobFile.java), [JobStore.java](../../src/main/java/com/video2podcast/job/JobStore.java) | summaryMessageId is null before publication; published(messageId, expiresAt) sets expiry on every file. File paths are plain names within a UUID directory; storage is atomic and supports scoped deletion. Do not invent a top-level expiresAt field. | 2026-11-19 |
| [JobQueue.java](../../src/main/java/com/video2podcast/job/JobQueue.java), [TD2 feature](../../src/features/TD2-job-queue.feature) | size counts waiting and running jobs; finished jobs stop counting before completion callbacks. TD2 externally checks positions 3 and 1. Keep queue mechanics in unit tests. | 2026-11-19 |
| [BotUpdateHandler.java](../../src/main/java/com/video2podcast/bot/BotUpdateHandler.java) | Current handler gates private messages and sender access, handles commands, but has no URL pipeline dispatch yet. T11 must wire the new path after existing checks. | 2026-11-19 |
| [JobFailure.java](../../src/main/java/com/video2podcast/job/JobFailure.java), [T9 requirements](../T9/requirements.md), [T10 requirements](../T10/requirements.md) | Fixed English error messages already distinguish media, transcription, summary, silence and timeout. Preserve these at the Telegram boundary and delete failed jobs. | 2026-11-19 |
| [T8 feature](../../src/features/T8-media.feature), [T9 feature](../../src/features/T9-transcription.feature), [T10 feature](../../src/features/T10-summary.feature), [whoami.feature](../../src/features/whoami.feature) | Existing steps use an allowed Telegram user, a private URL message, WireMock HTTP and disk observations. Reuse that wording, with no internal service invocation. Earlier T9 failure-time retention is unit-only: T11 deletes the failed directory. | 2026-11-19 |
| [TD4 requirements](../TD4/requirements.md), TD5 in [todo.md](../todo.md) | Real media tools for success; local generated recordings and proxy; fake process outputs only for failure/argument contracts; manual_media remains manual. Combined MP4 fallback is approved and implemented in TD5. | 2026-11-19 |
| [CucumberIT.java](../../src/it/java/com/video2podcast/functional/CucumberIT.java) | Currently excludes T8/TD2/T9/T10 and lacks an explicit manual_media exclusion. Tester must enable nonmanual deferred acceptance and keep manual cases out. Analyst does not edit the runner. | 2026-11-19 |
| [T3 host contract](../../docs/host-setup.md), [T10 research](../T10/research.md) | Ollama's captured prompt count corresponds to its captured input, not arbitrary fixtures. T10 checks every request against an estimator and normalizes/caps output. T11 HTTP stubs must return credible per-request counts. | 2026-11-19 |
| [CLAUDE.md](../../CLAUDE.md), [brief.md](brief.md) | Unit coverage gates are 90% lines and branches independently; PIT gate is 85%, excluding functional coverage. Preserve gates, do not weaken tests to enable deferred features. | 2026-11-19 |

## Findings requiring explicit contracts

1. A 1500-character body alone does not bound an arbitrary title. The formatter must keep the complete parsed text within 4096 characters while maintaining valid escaping. Q-analyst-1 asks the Architect to define clipping/formatting rather than having steps duplicate an invented algorithm.
2. Source/transcript must survive successful publication. Temporary Whisper WAV/chunks must not. The durable file expiry clock starts on summary send, even if queue or model latency is long.
3. A Telegram outage may prevent the error message itself, and a filesystem error may prevent deletion. Q-analyst-1 requests concrete architecture recovery choices; no undocumented retry count is imposed here. Processing-failure cleanup remains mandatory when those boundaries are healthy.
4. The initial queue count includes the new job and the running job. Preserve this agreed snapshot without claiming a continuously updated queue rank.
5. T12/T13/T14 behavior must not be pulled into T11 just because buttons are visible. This task publishes the callback identifiers and durable data those tasks need.

## Validation and handoff

Artifacts: [requirements.md](requirements.md) and [T11-summary-flow.feature](../../src/features/T11-summary-flow.feature). Existing T8/T9/T10/TD2 scenarios are retained; T11 adds cross-stage and publication checks rather than replacing their detailed matrices. Questions are append-only in [comms/questions.md](comms/questions.md). Gherkin parsing and static checks are recorded after writing; no claim of executable acceptance until Tester supplies the steps and enables the scenarios.

Validation result (2026-11-19): installed Java 27 and Cucumber GherkinParser 42.0.1 parsed T11-summary-flow.feature successfully. Static checks passed for all 17 scenarios/outlines having valid T11 requirement tags, no named internal service invocations in steps, and existing local Markdown link targets. `git diff --check` passed for tracked changes; the new analyst files were separately checked for whitespace. No Maven build or acceptance test was run for this artifact-only step. Herdr successfully notified orchestrator; direct Architect notification was blocked by that agent's interactive state.


## Architect contract incorporated — 2026-11-19

Source: A: Q-analyst-1 and A: Q-architect-check in [comms/questions.md](comms/questions.md), [api.md](api.md), [flows.md](flows.md). The single `Queued: position N` message both acknowledges and starts status; no additional acknowledgement is expected. Exact stages use U+2026, terminal notifications run after queue removal, and TD2 success completion waits for `Done.`. Titles collapse whitespace and display at most 200 UTF-16 units with ellipsis when clipped, retaining the original stored title. Duration uses the documented hours/minutes/seconds rules. These are architecture choices, not upstream Telegram guarantees.

Progress edit errors do not abort processing. Initial queued-send failure prevents media/disk work. Storage/publication failures clean the job; persistence failure after a successful summary send also attempts removal of its buttons. Final notifications and withdrawal are best effort without application retries. New scenarios observe Telegram rejection responses and filesystem obstacles at job.json, without calling internal services. Permission-denied cleanup/logging and shutdown branches remain unit-test concerns where portable external setup is unavailable. The initial draft's separate-ack wording and open-contract notes are superseded by this revision.

Revision validation (2026-11-19): T11 now has 25 scenarios/outlines; both T11 and TD2 features parsed successfully with installed Cucumber. Requirement tags, local Markdown targets and whitespace checks pass. TD2's success example now explicitly waits for Telegram status `Done.`. No Maven tests were run for this artifact revision.


## Q-tester-1 resolution — 2026-11-19

Repository evidence: [WhisperClientTest](../../src/test/java/com/video2podcast/transcript/WhisperClientTest.java) distinguishes refusedConnectionIsAConnectionError and twoConnectTimeoutsGiveServiceUnreachable. [TranscriptionClientTest](../../src/test/java/com/video2podcast/transcript/TranscriptionClientTest.java) covers mixed terminal outcomes in secondFailureDecidesTheKind and connectionFailureOnRetryIsServiceUnreachable. Its retriesOnceAndKeepsGoing currently uses a nonconnection failure, so a connection-error-then-success unit case is explicitly requested from Developer, not claimed as existing coverage.

Tester correctly identified that a refused connection never enters the HTTP journal and an immediate retry makes a listener stop/start fixture nondeterministic. T9 now keeps mixed connection-error sequences in the unit mapping in [requirements.md](requirements.md) and adds a genuine closed-port Telegram acceptance scenario without a refused-attempt HTTP count. HTTP 503 is not a substitute for connection refusal. No production retry/error policy changes.

Q-tester-1 revision validation: installed Cucumber parser accepts T9-transcription.feature; all 20 scenarios/outlines retain requirement tags, no refused-connection example remains in an HTTP-journal matrix, and changed analyst artifacts have no trailing whitespace. Production/unit tests were not run by Analyst; Q-analyst-2 remains Developer-owned.
