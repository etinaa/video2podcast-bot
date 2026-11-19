# T11 brief

## Owner's original words
"Приступайте к T11"

## Task type
Product task: the full team (Analyst, Architect, Developer, Tester, two code reviewers).

## Questions and answers
- Task type? Product (full team).
- The T11 text in `tasks/todo.md` did not mention the `@T10` scenarios and still said `yt-dlp`/`ffmpeg` are a fake `ProcessRunner`. Fix it? Yes: add removal of `not @T10` and the steps for `T10-summary.feature`; real `yt-dlp` and `ffmpeg` in success scenarios (TD4), fake only for failures that cannot be produced for real.

## Rules that apply from earlier decisions
- Scenarios go through the external boundary only (Telegram, HTTP mocks, files, queue); steps never call service code.
- T10 left 16 `@T10` scenarios for T11 (`CucumberIT` filter `and not @T10`); T11 removes the exclusions for @T8, @T9, @TD2 and @T10 and writes their steps.
- Queued status text shows the queue position (`JobQueue.size()` when the job is added): an orchestrator decision from TD4, flagged to the owner.
- Mutation gate: `./mvnw verify -Pmutation` at least 85% (TD3).

## Agreed task
See T11 in `tasks/todo.md`.
