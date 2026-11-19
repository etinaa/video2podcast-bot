# T12 tester — Step 2

Implemented `src/it/java/com/video2podcast/functional/`:

- `TranscriptWorld.java`: scenario-owned application with the existing FunctionalTest settings/WireMock helpers, preloaded disk jobs, application clock, real Telegram client, multipart observations, held responses and connection-drop fixture. Media process execution is forbidden by the boundary fixture; Whisper/Ollama remain HTTP mocks.
- `TranscriptSetupSteps.java`, `TranscriptAssertionSteps.java`: all 23 T12 scenarios/outlines, comprising 46 concrete scenarios. Steps drive Telegram updates and inspect HTTP requests/files, without calling delivery services. Assertions cover upload content/name/destination, callback identity, ordered keyboard-only edits, durable SENDING/SENT, retained metadata/files, access/ownership, expiry, startup state, retry and notification failures.
- `TranscriptButtonIT.java`: three full-context checks for successful delivery/duplicate tap, a held concurrent tap, and owner/expiry refusal.
- Existing `PipelineSetupSteps`, `PipelineAssertionSteps`, `WhoamiSteps`: route the three reused clock/no-model/no-output steps to the active T12 fixture.

## Verification

Commands run from the repository root, without `cd` or `clean`:

```sh
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=TranscriptButtonIT
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=CucumberIT -Dcucumber.features=src/features/T12-transcript-button.feature
./mvnw spotless:apply '-DspotlessFiles=.*/src/it/java/com/video2podcast/functional/(Transcript.*|PipelineSetupSteps|PipelineAssertionSteps|WhoamiSteps)\.java'
./mvnw spotless:check '-DspotlessFiles=.*/src/it/java/com/video2podcast/functional/(Transcript.*|PipelineSetupSteps|PipelineAssertionSteps|WhoamiSteps)\.java'
git diff --check -- src/it
```

T12 Cucumber: **46 passed, zero failures/errors/skips**, log `target/t12-tester-selected.log`. Dedicated integration checks: **3 passed, zero failures/errors/skips**, log `target/t12-tester-focused.log`. Scoped formatting and whitespace checks pass. Socket-based runs required execution outside the sandbox after its WireMock bind was rejected.

The initial tag-filtered Cucumber command was overridden by the suite's annotation and ran the broader Cucumber suite: 244 cases, 5 existing manual-media skips, no non-T12 failures. Its five T12 failures were the four fixture null-serialization mismatches and the transport retry defect, all subsequently corrected and verified by the selected feature run. The explicit feature selector is required for focused T12 execution with this runner.

## Defect found

Q-tester-3: a dropped upload response triggered 3 HTTP upload attempts for one tap. Developer disabled automatic connection retries in the real Telegram client. The unchanged one-attempt acceptance assertion now passes, together with rollback to AVAILABLE and the safe manual-retry message. Q-tester-2 was a temporary recording-gateway compile error during parallel implementation and is resolved.

No feature files, production files, unit tests, index or branch were changed by Tester. No full Maven verify, clean or mutation run was performed; full verification and both review gates remain with the orchestrator. No open tester question remains.
