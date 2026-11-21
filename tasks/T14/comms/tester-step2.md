# T14 Tester — Step 2 acceptance tests

Status: complete. Latest full verification passed at 2026-11-21 12:24:02 +02:00.

## Scope

- Added ExpiryIT for autonomous wall-clock cleanup through Spring's registered schedule and full-context restart followed by explicit delivery/expiry callbacks.
- Added RetentionSupport, RetentionSetupSteps and RetentionAssertionSteps for the 46 T14 Cucumber cases. Shared TranscriptWorld, callback definitions and HTTP/disk assertions remain in use.
- Tests use temporary work/access stores, a controlled application Clock, local WireMock Telegram/Whisper/Ollama servers and ProcessRunner boundary fixtures. No production cleanup/recovery service is called by a test.
- The scheduler fixture runs Spring's registered fixed-delay task on its actual scheduler thread with a short configured interval. A permit controls one pass at a time, and a completion signal makes no-change assertions meaningful. ExpiryIT also permits autonomous successive timer executions. Startup assertions require zero completed scheduled passes.
- Restart closes the prior application context and starts a fresh one against the same work/access stores, preserving startup HTTP observations and original metadata/bytes for comparison.
- Ordinary T12/T13 callback fixtures are installed after startup recovery; their scheduled delay is one day to isolate immediate callback assertions. Explicit startup fixtures still exist before startup and exercise recovery. Analyst aligned the inherited T12 SENDING startup outcome with T14 (Q-tester-3).

## Scenario coverage

R1–R3: idle expiry, equality/pre-boundary time, independent sibling deadlines, old creation times, partial/all delivery, already-terminal entries, live processing, immediate pipeline failure and null publication deadlines.

R4: held audio/document uploads, a claimed audio queued behind held Whisper, whole-directory protection and a rejected upload released after expiry.

R5–R6: AVAILABLE/SENDING/SENT startup combinations, preserved sentAt/deadlines/ownership/destinations/bytes, explicit callbacks without automatic model/media work, expired interrupted sends, unusable records, empty/absent work roots, repeated startup, external/access files and unrelated directory names.

R7–R8: stored keyboard targets and callback data, sent/expired labels, a single combined expiry/recovery edit, absent summary id, failed Telegram edit and independent cleanup of another job.

Low-level lock, I/O, symlink and production polling-order tests remain Developer unit responsibilities under the approved requirements.

## Validation

- Test compilation passed.
- Cucumber registration dry run resolved all 352 automated scenarios / 5,192 steps, including every T14 case. No undefined or ambiguous definitions.
- First full verify passed all 778 unit tests and both ExpiryIT tests; Cucumber registration exposed a duplicate missing-file step. Fixed by sharing the existing MediaAssertionSteps definition.
- Behavioral rerun exposed a null guard needed when that shared assertion is directly constructed by JUnit. Fixed in the test harness. The final verification result below supersedes this intermediate run.

No production code, unit tests, feature files or Git index were changed by Tester. Commits remain with the orchestrator.

### Additional verification evidence

The next full run passed all 46 T14 cases, both ExpiryIT tests, the corrected SummaryFlowIT and genuine T12 startup recovery, plus all 778 unit tests and formatting/coverage/SpotBugs/PMD gates. It had one failure in the inherited T8 “Explain yt-dlp failures safely in chat” empty-stderr example, before URL submission: the allowlist-setup Telegram response had a null Bot API `ok` field. That scenario's preserved `events.log` is empty, so its Telegram response transformer did not observe the request. The exact response source is not established; no application or assertion retry was added to hide it. The earlier behavioral run passed all 352 automated Cucumber cases.

Preserved evidence: target/t14-t8-fixture-failure.log, target/t14-t8-fixture-failure.xml and target/pipeline-it-13386190900447109980/{settings.json,events.log}. The full verification repeat below checked whether that isolated setup failure recurred. This is not a missing T14 implementation failure.


## Final verification result

Ran from the repository root, outside the sandbox, using the role's exact pre-approved command:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1
```

Result: **BUILD SUCCESS**, 2026-11-21 12:24:02 +02:00, elapsed 5m17s.

- 778 unit tests: zero failures/errors/skips.
- 388 integration/acceptance report entries: zero failures/errors, five pre-existing manual-media examples excluded by the repository's existing tag filter.
- All 352 automated Cucumber scenarios passed, including all 46 T14 cases and the aligned T12 startup case.
- Both ExpiryIT tests passed. SummaryFlowIT and the other standalone integration tests passed.
- Spotless, JaCoCo's unit-only 90% line/branch gates, SpotBugs and PMD passed. Scoped git diff --check passed.
- No missing-production-code failure remains. Tester did not run the optional mutation profile or make a commit.

The isolated inherited T8 setup failure did not recur. Its exact cause remains unconfirmed; the preserved evidence above remains available for review. No assertion was weakened and no automatic retry was added.

Step 2 is ready for review. Await the orchestrator's next instruction before Step 3.
