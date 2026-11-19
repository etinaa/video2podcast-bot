# T12 brief

## Owner's original words
"Начни задачу T12"

## Task type
Product task: the full team (Analyst, Architect, Developer, Tester, two code reviewers).

## Questions and answers
- Task type? Product (full team).
- The task text in `tasks/todo.md` was agreed earlier and is not changed.

## Rules that apply from earlier decisions
- Scenarios go through the external boundary only (Telegram updates and HTTP mocks, files in the job directory); steps never call service code.
- T11 left the buttons: `Keyboards.delivery(jobId)` with `audio:<jobId>` and `transcript:<jobId>`, constants `AUDIO_PREFIX` and `TRANSCRIPT_PREFIX`; `job.json` holds `ownerUserId`, `chatId`, `summaryMessageId` and both file entries with `expiresAt`. Until T12 a tap on a button is ignored.
- Out of scope: the audio button (T13), the expiry sweeper and the restart recovery of `SENDING` (T14).
- Gates: `./mvnw clean verify` green; `./mvnw verify -Pmutation` at least 85%. In the repository only the orchestrator runs `clean` and `verify` while the Tester works (the Tester runs them when told).

## Agreed task
See T12 in `tasks/todo.md`.
