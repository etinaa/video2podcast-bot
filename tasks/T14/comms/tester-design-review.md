# T14 Tester — Step 1 design review

Reviewed tester/common role rules, SPEC.md, tasks/plan.md, T14 task/brief, requirements/research, all four architecture artifacts, the feature and existing comms. Checked the existing functional harness, persisted-record validation and pinned Telegram starter lifecycle.

## Testability

All 29 scenario definitions (46 concrete cases including outline examples) have observable contracts:

- R1–R3: real scheduled execution with a controlled application Clock; disk bytes, job.json states/deadlines and directory removal. Configure the documented sweep interval; advancing Clock alone is insufficient evidence of execution. An execution barrier at the scheduler boundary can establish completion for passes that intentionally change nothing.
- R4: hold WireMock upload responses or the earlier pipeline's Whisper response, assert persisted SENDING and sibling/file preservation across a scheduled pass, then release the response. Lock interleavings remain Developer unit coverage.
- R5–R6: prepare disk fixtures before context startup; restart a fresh real application context using the same work/access stores. Observe preserved deadlines/bytes, recovered labels, removal of unusable records and fresh explicit callbacks. No direct cleanup/recovery service calls.
- R7–R8: inspect Telegram HTTP targets, labels, callback data, edit counts and absent uploads/body edits/deletions. Reject edits externally and observe independent filesystem cleanup and other jobs.

The existing harness already supports construction-time settings, a mutable Clock, boundary ProcessRunner fixtures and real Telegram HTTP calls. Step 2 will need isolated startup/scheduler fixtures, scheduled-pass completion observations, and controlled ticks for existing T12/T13 immediate-after-send assertions. No production contract extension is needed for those fixtures.

## Open finding

Q-tester-1 in questions.md: explicit startup recovery ordering before polling registration. The existing harness excludes TelegramBotStarterConfiguration, so its restart tests do not prove this production ordering. The pinned TelegramBotInitializer implements InitializingBean; unrelated singleton init order must not be assumed. Await Architect clarification/design update and Developer wiring/order coverage before approval.

No tests or production code were written or run. Design approval is pending Q-tester-1.

## Resolution and approval

Q-tester-1 is resolved by the explicit @DependsOn("retentionSweeper") dependency on TelegramConfig.telegramBot and the specified Developer context/order tests. Verified the pinned starter's bytecode: its configuration resolves the bot list before constructing TelegramBotInitializer, whose afterPropertiesSet then registers those bots. Recovery therefore completes before polling registration. Scheduled execution follows context refresh and the configured initial delay.

No open Tester design questions remain. Step 1 is complete; design approved. Test implementation awaits the orchestrator's Step 2 instruction. No tests were written or run during this review.
