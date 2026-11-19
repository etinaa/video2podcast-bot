# T13 brief

## Owner's original words
"Начни работу с T13"

## Task type
Product task: the full team (Analyst, Architect, Developer, Tester, two code reviewers).

## Questions and answers
- Task type? Product (full team).
- The task text in `tasks/todo.md` was agreed earlier and is not changed.

## Rules that apply from earlier decisions
- Scenarios go through the external boundary only (Telegram updates, HTTP mocks, files); steps never call service code. Real `ffmpeg` is used in the success scenarios (TD4 principle: no mocks where the real tool works); a fake `ProcessRunner` only for failures that cannot be produced for real.
- T12 left: `DeliveryService` (`claim`, `complete`, `release`), `DeliveryCallbackHandler`, `FileNames`, `Keyboards.delivery(jobId, audioState, transcriptState)` with the audio labels (`Preparing audio…`, `Audio sent`, `Audio expired`), `TelegramGateway.editButtons`; the same one-send, owner-only and expired rules apply to the audio tap. `JobStore.delete` was renamed to `discard` (D10 superseded).
- After TD5 the downloaded source can be a combined video (`source.mp4`): it is not MP3 or M4A audio, so the rule converts it. The Architect settles this and the Analyst makes it a scenario.
- Out of scope: the expiry sweeper and restart recovery of `SENDING` (T14); a real 3-hour smoke test (T17).
- Gates: `./mvnw clean verify` green; `./mvnw verify -Pmutation` at least 85%. In the repository only the orchestrator runs `clean`, `verify` and `-Pmutation`, one after another, and checks the exit code before any commit.
- Approval of every new SpotBugs/PMD suppression comes from the owner first; prefer removing the cause.

## Agreed task
See T13 in `tasks/todo.md`.
