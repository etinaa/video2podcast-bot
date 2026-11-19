# T12 research — Transcript callback delivery

Researched and accessed: 2026-11-19. No live Telegram sends or model calls were made. Upstream protocol facts are separated from repository decisions.

## Official documentation

| Source | Fact taken | Consequence for T12 | Accessed |
|---|---|---|---|
| [Telegram sendDocument](https://core.telegram.org/bots/api#senddocument) | Uploads general files using multipart/form-data; a successful call returns a Message. The documented cloud upload limit is 50 MB. | Upload the retained text file with an explicit filename; only a successful response permits SENT. T12 does not introduce a different upload server. | 2026-11-19 |
| [Telegram CallbackQuery](https://core.telegram.org/bots/api#callbackquery) | `from` identifies the sender; callback data need not match buttons currently on the message. | Check sender ownership against durable state; labels alone cannot enforce one-send. | 2026-11-19 |
| [Telegram answerCallbackQuery](https://core.telegram.org/bots/api#answercallbackquery) and [CallbackQuery note](https://core.telegram.org/bots/api#callbackquery) | Answering dismisses the client's progress indicator; optional text displays a notice. | Answer eligible private callbacks, including refusals, using exact agreed texts. | 2026-11-19 |
| [Telegram editMessageReplyMarkup](https://core.telegram.org/bots/api#editmessagereplymarkup) | Updates only reply markup; ordinary messages are targeted by chat_id and message_id. | Preserve summary text while changing progress/final button labels. | 2026-11-19 |
| [Telegram InlineKeyboardButton](https://core.telegram.org/bots/api#inlinekeyboardbutton) | callback_data is limited to 1–64 bytes. | Keep existing job-id callback data unchanged. | 2026-11-19 |

These API facts do not prescribe application retry policy, retention, ownership policy or filename sanitization. Those come from the sources below. No library/version upgrade is required by this analysis.

## Repository evidence

| Source | Fact taken and application | Read |
|---|---|---|
| [SPEC.md](../../SPEC.md), [plan.md](../plan.md), T12 in [todo.md](../todo.md), [brief.md](brief.md) | Owner-only, once-per-file, on-demand transcript delivery; private chats and allowlist; one-hour retention measured from summary publication; audio delivery and sweeping/recovery deferred. Agreed task text takes precedence over older plan wording about task dependencies. | 2026-11-19 |
| [BotUpdateHandler.java](../../src/main/java/com/video2podcast/bot/BotUpdateHandler.java) | Existing callbacks are gated before access-administration dispatch; transcript taps are currently ignored. T12 must add the transcript route after the same checks. | 2026-11-19 |
| [TelegramGateway.java](../../src/main/java/com/video2podcast/bot/TelegramGateway.java), [TelegramClientGateway.java](../../src/main/java/com/video2podcast/bot/TelegramClientGateway.java) | sendDocument already supports a local path and custom upload name. Existing editMessage requires text and markup together; errors share a gateway exception wrapper. | 2026-11-19 |
| [Keyboards.java](../../src/main/java/com/video2podcast/bot/Keyboards.java) | Existing callbacks are audio:<jobId> and transcript:<jobId>, audio first. | 2026-11-19 |
| [JobStore.java](../../src/main/java/com/video2podcast/job/JobStore.java), [JobRecord.java](../../src/main/java/com/video2podcast/job/JobRecord.java), [JobFile.java](../../src/main/java/com/video2podcast/job/JobFile.java), [FileState.java](../../src/main/java/com/video2podcast/job/FileState.java) | Atomic persisted state transitions, per-job lock, validated id and plain file path; SENDING → AVAILABLE is allowed after failure; SENT is terminal and records sentAt. Expiry is per-file. Summary text is not stored in job.json. | 2026-11-19 |
| [whoami.feature](../../src/features/whoami.feature), [T11 feature](../../src/features/T11-summary-flow.feature), [T11 requirements](../T11/requirements.md) | Telegram updates, HTTP mocks and disk observations are the established acceptance boundaries. Existing T11 behavior publishes both AVAILABLE entries without uploads. | 2026-11-19 |
| [api.md](api.md), [flows.md](flows.md), [components.md](components.md), [data-flow.md](data-flow.md), [Q-analyst-1 and corrections](comms/questions.md) | Architect defines exact refusal texts, safe filename transformation, keyboard-only edits, stored destination, check precedence and best-effort failures. Corrected missing-file behavior expires AVAILABLE entries before upload. | 2026-11-19 |
| [common role rules](../../.claude/roles/common.md), [analyst role](../../.claude/roles/analyst.md), [CLAUDE.md](../../CLAUDE.md) | Analyst owns requirements/research/features, not code or tests. Questions are append-only; only orchestrator commits. Unit coverage is 90% lines/branches independently and mutation gate 85%. | 2026-11-19 |

## Findings and resolved questions

1. The summary body is absent from job.json. A reply-markup-only edit preserves it without adding persistent summary text or reconstructing HTML from a callback envelope. The Architect added this boundary in api.md.
2. A per-job state claim prevents duplicate successful uploads from repeated/concurrent taps. It cannot establish Telegram-wide exactly-once delivery after an ambiguous response loss: the gateway cannot distinguish that from rejection, and the architecture permits manual retry after either. Requirements state this limitation explicitly and forbid automatic upload retry. The transport fixture tests application behavior; if the pinned HTTP client transparently retries a dropped connection, Tester must report that observable conflict instead of weakening the one-attempt assertion silently.
3. The initial architecture treated a missing transcript as a retryable send failure, conflicting with SPEC success criterion 6. Analyst flagged the conflict; Architect corrected it: a missing AVAILABLE file yields expired and persists EXPIRED before any upload. Disappearance after a completed claim remains a send failure. The append-only answers preserve the correction history.
4. Existing ownership policy uses callback.from.id; the Architect explicitly declined chat/message binding as an additional condition. The file and edits always target stored chatId/summaryMessageId. Acceptance checks that a forged envelope cannot redirect content.
5. A rejected keyboard update cannot justify rolling back a successfully delivered file. Persisted SENT remains authoritative. Successful upload followed by persistence failure stays SENDING for T14 recovery; low-level fault injection is a unit-test obligation.
6. Retention is checked at callback time without adding the T14 sweeper. SENT/SENDING precedence follows api.md, and an expired timestamp cannot reset those states. Healthy AVAILABLE jobs already on disk can work after startup; SENDING recovery remains deferred.
7. Filename sanitization is a repository architecture contract, not a Telegram-prescribed naming algorithm. Test ordinary/special/long titles at HTTP; control-character and surrogate-pair boundaries additionally belong in unit tests.

## Validation and handoff

Artifacts: [requirements.md](requirements.md), [T12-transcript-button.feature](../../src/features/T12-transcript-button.feature).

Installed Cucumber GherkinParser 42.0.1 parsed and compiled the feature into 46 concrete scenarios; all have local requirement tags. Static checks validate referenced requirement ids, local Markdown link targets, absence of named service/method invocations in steps, and whitespace. Exact parser/validation outcomes are recorded below after verification. Maven clean/verify/mutation and executable acceptance were not run by Analyst; the role rules reserve coordinated build execution for the orchestrator/Tester. Passing syntax does not claim that step definitions or production implementation exist.

Validation result (2026-11-19): 23 scenarios/outlines compile to 46 concrete scenarios with valid requirement tags (R1–R8); R9 is the test-execution/gate obligation. All referenced local Markdown targets exist. Step-boundary and whitespace checks pass, as does git diff --check. The missing-file case was split from unchanged-state refusals to assert its agreed AVAILABLE → EXPIRED persistence. No Maven tests were run.
