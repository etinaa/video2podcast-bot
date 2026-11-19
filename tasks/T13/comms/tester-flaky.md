# T13 Cucumber failure investigation

Baseline: e3d0562 plus tester changes (including AccessGateIT). Evidence supplied by orchestrator: /tmp/t13-orch-full2.log and /tmp/t13-orch-full3.log. No clean/full verify/mutation runs by tester.

## What the supplied failures actually show

- T10 is not an immediate assertion racing an asynchronous job: e3d0562's SummaryAssertionSteps.ollamaCount calls finishOne before asserting. In full3.log:5168 the real Ollama HTTP client received status 403 with `reason=not_allowed_local`, `source=baseline_policy`, port 63806. The response explicitly says sandbox policy blocks local/private network access. This was not returned by the Ollama WireMock success fixture.
- The matching surviving directory is target/pipeline-it-14095781274055122922. Its settings.json points Ollama to localhost:63806. Its events.log records metadata/download, Whisper, Summarizing, a safe summary-failure message and Failed.; no Ollama request reached WireMock. Therefore finishOne returned after an actual FAILED terminal event; a longer/request-only wait would not fix this failure.
- T8 full2.log:6103 fails during PipelineWorld.allow, before the restricted URL is sent: TelegramBot catches a Telegram transport/protocol exception. Its deepest cause is a Bot API response without the required `ok` field (NullPointerException in ApiResponse.getOk). The following URL refusal also fails to send (19:55:27.762). An empty WireMock Telegram journal means the test's expected endpoint did not observe these calls. This is a broken setup/HTTP boundary, not a missing URL-validation wait. The log does not retain the malformed response body, so attributing that particular response to the same policy interception is an inference, not proven.

## Fixture audit and corrections

Work and focused verification in progress. Neither transport denial is accepted as successful product behavior. No timeouts are raised; no scenario retries, exclusions, or disabled tests are introduced. Local network tests are run with explicit sandbox escalation; network policy is not bypassed in code.

### Changes in the test zone

1. PipelineWorld.allow now requires the actual successful Telegram mock acknowledgement for the requested user before removing setup messages. TelegramBot deliberately catches runtime errors, so consume returning alone was never proof that setup succeeded. A rejected/non-WireMock response now fails setup immediately with the mock URL and user id, instead of producing a misleading 30-second URL-reply timeout.
2. finishOne now waits for the latest submission's terminal HTTP edit matched to its own queued status-message id and stored chat id. Earlier Done./Failed. events, another chat, and another status-message id cannot satisfy it. The 30-second maximum and event polling interval are unchanged. Summary request-count assertions retain exact counts/paths and include that terminal outcome plus Telegram replies in failure diagnostics.
3. Removed static PipelineWorld.building. Each Spring application receives its own Clock and ProcessRunner through its initializer; the last closed pipeline no longer remains globally referenced during retained-file scenarios.
4. Both world types become inactive at the start of teardown. TranscriptWorld previously never reset active. It also drops its closed context reference. Startup asserts that its own world is active. This protects shared-step routing from an already-closed world; it is not evidence that a previous scenario actually supplied the T10 response.
5. Held responses are released before application shutdown, and mock servers are stopped after application shutdown, as before. Pipeline jobs have scenario-owned contexts; Telegram/Whisper/Ollama/proxy servers have scenario-owned ports. The baseline Cucumber Spring context uses separate JVM-lifetime servers. No shared-server reset was found that targets a scenario's server. Existing queue shutdown and hold timeouts are unchanged.

### Regression evidence

New ScenarioFixtureIT reproduces the two lifecycle/setup defects: before fixes, the closed retained world was still active, and rejected /allow setup returned normally. Both tests failed as intended in target/t13-fixture-red.log (2 failures). After corrections, these tests and a third deterministic check of terminal-event correlation pass. The correlation check includes a late terminal for an earlier job and a terminal with the correct message id but wrong chat; neither can complete the current submission.

Final Cucumber verification is running through the existing CucumberIT (all features, normal manual-only exclusion), alongside ScenarioFixtureIT. It is an explicitly escalated local-network run, not an in-sandbox attempt to work around policy. No automatic retries, timeout increases, feature edits, disabled scenarios, production changes, or commits.

### Execution requirement

The confirmed full3 failure is an infrastructure refusal of the intended localhost mock. It cannot be repaired by delaying an assertion or accepting zero requests. The orchestrator's full verification must run with permission for the dynamic localhost mock ports. Tests continue to fail on denied/malformed responses; diagnostics now identify the boundary or actual terminal failure instead of misclassifying it as a scheduling race. Full2's exact response body is unavailable, so its origin remains unproven beyond the transport/protocol failure documented above.

Verification command:

```sh
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=ScenarioFixtureIT,CucumberIT > target/t13-flaky-focused.log 2>&1
```

Formatting command (only files changed for this investigation):

```sh
./mvnw spotless:apply spotless:check '-DspotlessFiles=.*/src/it/java/com/video2podcast/functional/(ScenarioFixtureIT|PipelineWorld|TranscriptWorld|SummaryAssertionSteps)\.java'
```

The three fixture regression methods have passed. Final Cucumber counts/exit code will be appended below.

## Final Cucumber result

2026-11-19 20:25:07 +02:00: focused CucumberIT + ScenarioFixtureIT exited 0 / BUILD SUCCESS in 4m45s. All **306/306 scenarios and 4,425/4,425 steps passed**. All three fixture regression methods passed. Failsafe reports 314 discovered tests, zero failures/errors, five pre-existing @manual_media exclusions. No baseline_policy denial occurred in this explicitly escalated run. Spotless apply/check passed for the four investigation files, and git diff --check passed.

This validates the fixture corrections; it does not turn an execution-policy denial into an allowed network connection. A future denied mock port must remain a failure and requires fixing the verification execution permissions, not weakening scenario assertions.

The six integration-test classes sharing these fixtures were also rerun after the corrections: AccessGateIT, AccessApprovalIT, WhoamiIT, SummaryFlowIT, TranscriptButtonIT and AudioButtonIT. Result 2026-11-19 20:28:31 +02:00: **26 passed, zero failures/errors/skips**, exit 0 / BUILD SUCCESS in 8.358 seconds. Command and log: `./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=AccessGateIT,AccessApprovalIT,WhoamiIT,SummaryFlowIT,TranscriptButtonIT,AudioButtonIT > target/t13-access-gate-regression.log 2>&1`.
