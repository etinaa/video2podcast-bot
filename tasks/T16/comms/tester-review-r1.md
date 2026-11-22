# Tester response — test review round 1

Base production commit: 5587279. No production, feature, unit-test, build-gate, index or branch changes by Tester.

- reviewer-claude Important (startup deadlines): fixed — startup timeout and pending-reply assertions now measure from first startup probe receipt, excluding Spring context boot; a regression models ten seconds of boot time.
- reviewer-codex Important (T11 Whisper refusal row): fixed — the row calls `refusedWhisper()`, setting `refuseWhisperDuringWork`; a full-context regression requires metadata, download, WAV, split and `Transcribing 1/1…` before the unreachable failure. Existing T9 retry assertions remain unchanged.
- reviewer-claude Suggestion (both startup requests): fixed — the step now awaits both services' held-request latches.
- reviewer-claude Suggestion (queued status identity): fixed — Alice/waiting-job status assertions require the queued message id, stored chat id and later edit timestamp.
- reviewer-claude Suggestion (multipart chat target): fixed — parse the actual `chat_id` part and assert exactly 101; resolve the attached document part and compare its bytes with the retained transcript.
- reviewer-claude Suggestion (unavailable restart services): fixed — the scenario records an independent expectation for the unavailable startup and requires both startup logs to report unreachable before accepting notification retry evidence.
- reviewer-claude Suggestion (connection-reset disclosure): fixed — FailureModesIT's javadoc explicitly distinguishes selective GET resets from the genuine closed-port refusal cases.

## Validation

- RED: `target/t16-r1-red.log` reproduces both Important findings: elapsed startup time incorrectly includes the synthetic boot interval, and the T11 row records no media commands due to premature preflight rejection.
- GREEN: focused ScenarioFixtureIT (7 tests) plus all 32 T16 scenarios / 399 steps pass; see `target/t16-r1-focused.log` and the corresponding Surefire XML.
- Spotless applied only to src/it; scoped whitespace check passes.
- The single final full verification passed: `JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1`, completed 2026-11-22 19:08:15 +02:00 in 5:46. Results: 836 unit tests; 429 IT entries with zero failures/errors and five unchanged skips; 384 Cucumber scenarios / 5591 steps passed. Spotless, JaCoCo, SpotBugs and PMD gates passed. No second full verification was run in this review-fix round.
