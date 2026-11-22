# T16 acceptance tests — step 2

## Scope

- `FailureModeSteps` / `FailureModeWorld`: executable steps for all 32 concrete T16 feature cases, reusing PipelineWorld's full application/process fixtures and existing common steps. Startup probes, per-job probes, processing HTTP calls and tool invocations have separate journals. Log capture attaches after each Spring logging initialization.
- `FailureModesIT`: three full-context cases against genuine closed loopback ports (Whisper, Ollama, both), covering safe startup logs, working /whoami, exact preflight failure text and absence of media work, inference, uploads and retained job directories. The feature fixtures independently reset GET connections where inference/chat must remain available, especially the waiting-job freshness case; these transport faults are supplemented by the genuine connection-refusal ITs.
- Messages and callbacks use one update executor to match Telegram's single polling consumer. Responsiveness assertions require boundary-event arrival before the configured health deadline, with a held response and no terminal failure/startup-result logs. Timeout cases keep the mock response held through failure and subsequent-job success.
- Pending-access tests close/recreate the real application context over the same durable access file, exercise original/stale callbacks, reject unknown-user callbacks without state changes, and check failed/successful notification retry paths and cooldown persistence.
- Existing T9/T10 closed-port fixtures now stop the relevant server at the first media operation, after successful preflight, preserving mid-job failure coverage. Health GETs do not consume processing response sequences or count as inference calls. TranscriptWorld's no-model assertion permits only GET / and GET /api/version, rejecting every other operation. AudioSupport's held-model fixture likewise returns immediately for GET probes, so only inference signals/enters its hold latch.

No production, unit-test, feature, build configuration or gate thresholds were changed by Tester. No git index/branch/commit operation was performed. All external boundaries use local fixtures and fake credentials; there are no real host-model, Telegram or public-video calls.

## Verification

Final result: `BUILD SUCCESS` from the role's exact command `JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1`, completed 2026-11-22 18:37:50 +02:00 (5:49). All final test/fixture changes are included in this run. See `target/tester-verify.log` and `target/failsafe-reports/`.

- Final focused T16 execution: 32 scenarios / 399 steps passed, plus all 3 FailureModesIT cases, including the single Telegram update lane and synchronized restart/cleanup fixtures (`target/t16-focused.log`, `target/surefire-reports/TEST-com.video2podcast.functional.FailureModesIT.xml`).
- Full Failsafe result: 427 tests, 0 failures, 0 errors, 5 skipped. The full Cucumber result is 384 scenarios / 5591 steps passed; all T16 cases execute. Existing manual-media exclusions were unchanged.
- Spotless, SpotBugs, PMD and JaCoCo checks passed. Unit-only JaCoCo: LINE 2070 covered / 33 missed = 98.43%; BRANCH 847 covered / 53 missed = 94.11%. Both existing 90% thresholds remain unchanged.
- Applied Spotless only to `src/it` with `./mvnw -q spotless:apply '-DspotlessFiles=.*src/it/.*'`. Scoped `git diff --check` passes.

No remaining test failures require Developer/Analyst action. Tests are ready for the assigned reviewers; no acceptance-test commit was made by Tester.

The first interrupted full run identified older retained-delivery assertions that counted startup probes as model operations; those assertions were updated as described above. A subsequent interrupted full run exposed AudioSupport's catch-all held-response transformer intercepting health GETs (startup and per-job timeouts in target/t16-audio-fixture-verify.log); GET handling was separated from inference before restarting the final full gate. Initial focused failures were fixture issues (Logback reset detached capture, recovery directory assertion included a later successful job, asynchronous notification assertion ran too early); the fixtures were corrected, rather than changing production behavior or weakening the intended scenarios.

The task's clean-build/PIT/manual smoke acceptance remains with Developer/orchestrator/owner. Tester uses the role's exact pre-approved full verify command and does not claim a clean build, PIT run or real Telegram smoke test.

## Review round 1 follow-up

Both Important findings and all five suggestions are fixed; per-finding responses and RED/GREEN evidence are in `tester-review-r1.md`. The single final review-fix verification passed at 2026-11-22 19:08:15 +02:00: 836 unit tests, 429 IT entries (zero failures/errors, five existing skips), 384 Cucumber scenarios / 5591 steps passed, and all normal quality gates passed. Evidence: `target/tester-verify.log`. The two new fixture regressions are included in this full run.
