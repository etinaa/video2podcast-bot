# TD6 brief

## Owner's original words
"Заведи задачу на flaky test."

## Task type
Technical (the Tester investigates and fixes the tests; the Developer joins only if the cause is in production code; both code reviewers review).

## Questions and answers
None: the task text was shown to the owner and approved ("ok").

## Evidence at intake
In the T11 close-out, one of five full `./mvnw clean verify` runs failed: `Transcribe a video link into a plain-text transcript - Broken successful responses are retried rather than called silence - Examples - Example #1.1`, step `"chunk-000.wav" is sent exactly twice` (`T9-transcription.feature:122`), `Expected size: 2 but was: 0` within 30 s (`MediaAssertionSteps.twice`, `PipelineWorld.until`). The failed run's log: `/tmp/t11-orch-final.log` (the rerun, `/tmp/t11-orch-final2.log`, was green). Copy the log into `tasks/TD6/` before `/tmp` is cleaned.

## Agreed task
See TD6 in `tasks/todo.md`.

## Start (2026-11-21)
Owner: "Начни TD6". Status set to `[~]`. Team: Tester (investigates and fixes the tests), Developer only if the cause is in production code, two code reviewers. Known at start: `failed-run.log` (the T9 failure) has no `not_allowed_local`; `failed-run-3.log` has one (T13, T10 scenario); T14 added two platform-policy timeouts and one unexplained T8 failure.
