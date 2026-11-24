# T18 tester — Step 2 complete

## Tests supplied

- `DeferredSteps`: all 27 T18 scenarios/outlines (41 expanded examples, 766 steps), driven through Telegram updates, HTTP/process fixtures, durable queue JSON, application restart and Spring's registered scheduler tasks.
- `DeferredSupport`: controls deferred/retention registrations independently, checks configured fixed/initial delay, and observes asynchronous completion through files/Telegram/HTTP. No feature action calls the retry runner.
- `DeferredJobIT`: four JUnit cases cover durable parking, repeated-outage silence, restart/count preservation, real-media recovery, no replay, actual automatic scheduling without more Telegram updates, and the configured/default 2/12 attempt limits.
- Migrated existing T9/T10/T16 outage assertions, literal closed-port `FailureModesIT`, and the `ScenarioFixtureIT` guard to parking. T14's scheduler fixture recognizes the additional deferred registration. Cleanup/queue assertions correlate the original parked URL, owner and status id even when a later submission succeeds, and wait for asynchronous persistence.

## Final verification

From the repository root, with Java 27:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1
```

**BUILD SUCCESS**, 2026-11-23 20:33:20 +02:00, duration 8m10s.

- Unit phase: **911 tests, 0 failures/errors, 0 skipped**.
- Integration phase: **488 reported cases, 0 failures/errors**. This includes **437/437 Cucumber scenarios passed**, all four DeferredJobIT cases, all seven ScenarioFixtureIT cases and the literal closed-port FailureModesIT cases. Five existing `@manual_media` examples are excluded by the checked-in `cucumber.filter.tags=not @manual_media` configuration; no tests were disabled or deleted.
- JaCoCo line/branch gates, Spotless, SpotBugs/FindSecBugs and PMD: passed.
- Scoped `./mvnw spotless:apply '-DspotlessFiles=.*src/it/.*[.]java'`: passed (`target/tester-format.log`).
- `git diff --check -- src/it`: passed.
- Earlier focused T18 run: **41/41 examples, 766/766 steps, 4/4 DeferredJobIT cases passed** (`target/tester-t18.log`).

The earlier full-run failures were tester fixture expectations/correlations and were corrected; no failures remain due to missing implementation. Earlier full-run evidence is preserved in `target/tester-verify-before-fixture-fixes.log`.

Only tester-owned `src/it` files and T18 comms changed. No feature, production or unit-test edits; no index/branch changes or commits. Deterministic process failures use existing fault fixtures; successful deferred flows use real local yt-dlp/ffmpeg and the WireMock media proxy. No external service or secrets are needed. Mutation testing was not part of this tester run.

STATUS: DONE

## Review round 1 fixes

- `DeferredSteps` now checks no `sendMessage` follows the queued acknowledgement for the chat in both fallback assertions. The rejected parking-edit case also rejects `PARKED` and `GAVE_UP` messages.
- The held-retry double-submission scenario waits for durable queue removal, then requires download, Whisper, Ollama, summary and original status completion counts to remain exactly one for three seconds.
- Focused run: `./mvnw -Pit failsafe:integration-test -Dit.test=CucumberIT,DeferredJobIT -Dcucumber.filter.tags='@T18'`; **41/41 Cucumber scenarios (766/766 steps) and 4/4 DeferredJobIT cases passed**, BUILD SUCCESS, 2026-11-24 09:45:18 +02:00.
- Findings fixed: both Important test findings from the available r1/r2 reviews.

STATUS: DONE

## Step 3 — verification of committed implementation

Verified commit `0e63341` (`T18: deferred jobs on a service outage`) from the repository root with:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1
```

**BUILD SUCCESS**, finished 2026-11-23 20:50:44 +02:00, duration 8m16s.

- Unit phase: **913 tests, 0 failures/errors, 0 skipped**.
- Integration phase: **488 reported cases, 0 failures/errors**. Cucumber: **442 reported, 0 failures/errors, 5 existing manual-media examples skipped** (437 executed and passed). All four DeferredJobIT cases passed.
- Spotless, SpotBugs/FindSecBugs, PMD and configured JaCoCo gates passed.
- No test fixes were needed for the committed implementation.

The full verification log is `target/tester-verify.log`.

STATUS: DONE
