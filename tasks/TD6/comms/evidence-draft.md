# TD6 flaky-test investigation

Status: test-harness corrections implemented; final acceptance is blocked by unexpected proxy responses on localhost. The 20-consecutive check and orchestrator full gate remain pending.

## Code and preserved evidence

Investigation baseline: `4f0cad88fe9e21ced9e71094eeb8ef0d3175b71d` (T14 done). Original T11 run occurred before acceptance commit `c29cd30` (2026-11-19 15:20:08 +0200), after production commit `143e3db`; the failure log does not embed a commit id or dirty-tree snapshot. Thus its exact working-tree revision cannot be recovered from the log alone. The fixture and failed assertion are present in `c29cd30` and T11 close-out `9e6ff88`.

`tasks/TD6/failed-run.log` already preserves `/tmp/t11-orch-final.log`, verified byte-for-byte on 2026-11-21. SHA256: `de90c3886e3c681af5eeda61a9c4f451ca2845ec8318bde397df85405bd20219`.

## Diagnosis

Original failure: malformed successful Whisper response example `{broken`, T9-transcription.feature:130; `chunk-000.wav` expected twice, observed zero, after 30 seconds. Immediately preceding this example's timeout, failed-run.log:3191–3194 records two Whisper HTTP **401** responses at 15:18:23.818/819. The next example logs its retry at 15:18:55.367. The fixture configures HTTP 200 and its response transformer has no HTTP 401 branch. Together the unexpected status and absent mock journal show that the intended fixture did not receive those attempts. The retry loop did execute; increasing the request-count wait cannot make requests reach the intended endpoint. The responder's identity/body was not retained (empty logged body), so the exact network intervention remains unproven.

A separate confirmed execution-policy failure exists in failed-run-3.log:5168: Ollama HTTP 403, `reason=not_allowed_local`, `source=baseline_policy`, localhost:63806. failed-run.log and failed-run-2.log contain no `not_allowed_local` marker. T14 also documented policy denials despite escalated commands. These are infrastructure failures; tests must continue to fail when their mock calls are denied. We do not equate the original 401 with the later proven policy response.

T13 already fixed rejected `/allow` acknowledgements, stale retained-world activation, global PipelineWorld.building, and terminal status correlation. Those fixes are in the baseline, documented in tasks/T13/comms/tester-flaky.md. The original zero-request symptom is not proof of a queue or retention defect.

## Test-harness findings

1. `CucumberIT`'s suite-level `@ConfigurationParameter` hardcodes `not @manual_media` with higher precedence than `-Dcucumber.filter.tags=@T9`. The first escalated nominal T9 baseline consequently runs the whole Cucumber suite. Move that normal exclusion to `src/it/resources/junit-platform.properties`, where the command-line property can override it. Normal execution still excludes manual media; focused T9 checks actually select T9.
2. `transcriptionSettled` accepts any previous Ollama request or any prior Failed edit. Correlate the observable outcome to the latest submission, using the existing status-message/chat matching. The current job must reach Summarizing… (transcription persisted and temporary audio removed) or a terminal status; another queued job reaching Ollama cannot satisfy this wait. Regression checks cover stale timestamps and wrong chat/message IDs.
3. After an observable transcription outcome, an exact-count mismatch should report immediately with the endpoint and Telegram outcome, rather than spending another 30 seconds polling an immutable failed job.

No production change, timeout increase, disabled scenario, scenario retry, quality-gate change or full clean/verify is authorized in this tester pane.

## Verification (in progress)

Initial sandboxed targeted run: `target/td6-t9-baseline.log`; failed before scenarios because localhost bind is forbidden (`SocketException: Operation not permitted`). Escalated rerun: `target/td6-t9-baseline-escalated.log`; passed all 352 scenarios, five existing manual exclusions (357 entries), 4m42s. This was the baseline before fixing filter precedence; it was failsafe-only, never a full Maven verify.

Regression RED: `target/td6-fixture-red.log`, two tests, two assertion failures on the extracted original settlement predicate. A further queued-job counterexample fails the timestamp-only approach: `target/td6-correlation-red.log`, two tests, one assertion failure.

Intermediate GREEN: `target/td6-fixture-first-green-t9.log`, five fixture tests and exactly 37 T9 scenarios passed, exit 0. This validates command-line selection; 320 other scenarios are reported filtered/skipped by JUnit, rather than executed. It predates the final current-message correlation refinement and is not counted as final-code stability evidence.

Final-code run: `target/td6-fixture-green-t9.log`, all five fixture tests passed; 36/37 T9 scenarios passed, one error (progress stopped at chunk 1 instead of expected chunk 2). The original malformed-JSON example passed. At 15:36:41.040 and .075, Whisper returned **HTTP 403: Proxy key is missing or incorrect**. Scenario `Stop Telegram progress at the terminally failing chunk` expected a successful first chunk followed by the configured failing second chunk. Its fixture `target/pipeline-it-17959730138249749379/settings.json` points to Whisper `http://localhost:61888`; its events.log contains zero Whisper requests, Transcribing 1/3, the safe transcription failure and Failed. No requests reached the intended mock, so progress correctly stopped at chunk 1. This newly observed failure independently demonstrates the external HTTP boundary problem. Escalation did not prevent it. Its reports and fixture traces are preserved in `target/td6-final-preflight/`.

Formatting: scoped Spotless apply/check passed for all four changed Java files, `target/td6-spotless.log`; `git diff --check` passed. No attempt to alter network restrictions in code.

The 20-run check has **not passed and has not started**: final preflight failed on the proxy response. `Q-tester-1` in comms/questions.md requests a working execution context from the orchestrator. `/tmp/td6-stability.py` is ready to run all 20 sequentially, stopping on the first failure or source change. It preserves each Maven log, Cucumber XML and failsafe summary plus source SHA256. Its checks require 37 executed T9 cases, the original example present once, no failures/errors/flakes, and unchanged sources. No test retry is enabled.

Commands (repository root, JAVA_HOME set to the installed Java):

```sh
./mvnw test-compile failsafe:integration-test failsafe:verify '-Dit.test=ScenarioFixtureIT#transcriptionWait*'
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=ScenarioFixtureIT,CucumberIT -Dcucumber.filter.tags=@T9
# sequential driver uses this command for each trial, after test-compile:
./mvnw failsafe:integration-test failsafe:verify -Dit.test=CucumberIT -Dcucumber.filter.tags=@T9
```

The orchestrator owns full clean/verify, both reviewer decisions, commits, and the final task status. No full clean or Maven lifecycle verify was run by Tester. The acceptance criterion that the external interference be removed remains open; fixture changes improve correctness and diagnostics but cannot remove a proxy's 401/403 response.

## Other intake items

T13 and T14 `/allow`/Ollama failures are recorded in their comms and the retained logs; no production-code race has been established. `OllamaClientTest.anAnswerStoppedAtTheLengthLimitIsAccepted` is a standard WireMock HTTP-200 test with a five-second client deadline. The reported `header parser received no bytes` failure is not present in the three supplied failed-run logs; a specific cause cannot be recovered from that description alone. The unit test belongs to Developer and has not been changed or weakened.
