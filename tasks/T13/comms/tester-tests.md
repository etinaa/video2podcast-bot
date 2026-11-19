# T13 acceptance test handoff

## Scope

Added AudioButtonIT, AudioSetupSteps, AudioAssertionSteps and AudioSupport under src/it/java/com/video2podcast/functional. Reused TranscriptWorld, TranscriptSetupSteps and TranscriptAssertionSteps for retained-job fixtures and shared callback/expiry/ownership behavior. The additional hooks are tagged @T13; transcript scenarios keep their existing behavior.

Tests drive Telegram updates through the real Spring wiring and inspect Telegram HTTP requests, job.json and retained files. They do not invoke preparation/delivery services. All successful media encodes use real ffmpeg; real yt-dlp retrieves a local WireMock media page through a proxy for queue scenarios, without external DNS. Generated media and captured uploads stay under target/; no secrets or binary fixtures are committed.

## Coverage

- Unchanged MP3/M4A bytes, case-insensitive extension, inclusive source thresholds and strict encoded-output thresholds.
- WebM and combined MP4 conversion, actual AAC/M4A stream inspection, duration, preserved channels and removed video.
- Real oversized WAV compression, fallback after conversion overflow, one lower-bitrate retry, rejection of oversized/empty/absent/partial/timed-out outputs.
- Exact Telegram filename/title/performer/caption fields, safe metadata, no unrelated services or uploads.
- Shared-queue waiting observed through a held Whisper HTTP response; duplicate and concurrent taps observed through a held Telegram upload response.
- Owner/access/private-chat enforcement, stored destinations, expiry precedence, missing/corrupt job data, invalid ids and claimed delivery after expiry.
- Transcript delivery during queued audio and after audio success; current transcript labels in final audio keyboard.
- Safe manual retry after preparation/upload failure, no automatic upload retry, best-effort callback answers/keyboards and temporary output cleanup.

Same-Path object identity, exact 3-hour bitrate arithmetic, surrogate boundaries, queue rejection and storage faults remain Developer unit-test obligations, as specified in requirements.md.

## Verification

Execution results will be appended after the final focused run. The orchestrator owns clean/full verify/mutation gates and commits. Tester has not run those gates.

### Focused execution

Command (repository root, Java 27.0.1):

```sh
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=DeliveryScenarios,AudioButtonIT,TranscriptButtonIT > target/t13-delivery-focused.log 2>&1
```

DeliveryScenarios explicitly selects @T12 or @T13; it has no IT suffix so the default build does not run those features twice. The selected set is 113 expanded scenarios (67 T13, 46 T12) plus 3 AudioButtonIT and 3 TranscriptButtonIT methods. Failsafe lists 198 other discovered scenarios as filtered/skipped in this focused command; no feature/scenario was disabled or removed. Default CucumberIT remains unchanged.

Formatting is scoped to the seven changed/new Java files with an absolute-path regex (Spotless matches absolute paths):

```sh
./mvnw spotless:apply spotless:check '-DspotlessFiles=.*/src/it/java/com/video2podcast/functional/(Audio[^/]*|TranscriptWorld|TranscriptAssertionSteps|DeliveryScenarios)\.java'
```

Initial failures were test setup/assertion defects and were corrected without changing production code: shared expiry regex overlap; observing an earlier attempt's terminal event; omitted-versus-null summaryMessageId after persistence; using an audio-upload timestamp to assert a concurrent transcript upload; and a raw media URL with no duration metadata. The queue fixture now supplies real yt-dlp with an HTML/schema media page including duration, as the established pipeline fixture does. No timeout was increased. The shared request-event list is copied before slicing to avoid concurrent modification during asynchronous audio uploads, and uploadSuccess is volatile for cross-thread observation.

One exploratory Cucumber tag-filter invocation ran the broader suite because CucumberIT's annotation takes precedence. Another direct cucumber.features experiment was stopped because it discovered the feature more than once through separate engines. Neither approach is part of the committed test setup or the final command above.

Final result (2026-11-19 19:16:03 +02:00): exit 0 / BUILD SUCCESS in 34.507 seconds. All 113 selected scenarios passed (1,884 steps passed); all six JUnit integration methods passed. Spotless apply/check passed for all seven Java files; git diff --check passed. No remaining missing-code failure or open tester question. Full repository verification, coverage/static-analysis gates and mutation testing remain for the orchestrator.

### Step 3 — access-gate regression after e3d0562

Orchestrator's full verification (/tmp/t13-orch-full.log) established a stale AccessGateIT expectation: an allowed audio callback now reaches the delivery handler and gets expiry, rather than being ignored. Updated the existing assertion to require exactly two responses, matching callback ids and exact refusal/expiry texts; kept the no-sendMessage assertion.

Focused command: `./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=AccessGateIT,AccessApprovalIT,WhoamiIT,SummaryFlowIT,TranscriptButtonIT,AudioButtonIT`. Result: exit 0 / BUILD SUCCESS, 26 tests passed with zero failures/errors/skips in 9.920 seconds (2026-11-19 19:51:36 +02:00). Log: target/t13-access-gate-regression.log. Spotless apply/check scoped to AccessGateIT.java and git diff --check passed. All 306 Cucumber scenarios were already green in the orchestrator's run; no duplicate Cucumber run or clean/full verify was performed here.
