# Tester review round 1

- Important — `safe()` now rejects the configured process stderr, `/tmp/secret`, server diagnostic text, and the scenario work directory in every Telegram send or edit; the failed-download integration test checks its actual injected stderr (`download failed /tmp/secret`). Fixed.
- Important — `position()` now requires exactly one acknowledged queued send after the latest update, checks its text/chat, and waits for its response id before asserting. Fixed.
- Important — Ollama `options()` waits for the submission's terminal status and requires at least one request before checking model/options. Fixed.
- Important — `downloads()` waits for all submissions' terminal status before counting download invocations. Fixed.
- Important — `next()` scopes the second acknowledgement, summary, and `Done.` edit to the latest update and status message id, then checks that summary's published job record and summary message id. Fixed.
- Suggestion — expiry assertions require exactly two file entries and `eachOwner()` requires exactly two job records. Fixed.
- Suggestion — reset `acceptedStatuses` when `completedFixture()` clears prior events. Fixed.
- Suggestion — step inputs for expected Whisper path, WAV/chunk fixture names, rejected-summary body, and transcript endpoints are now asserted. Fixed.
- Suggestion — `job()` uses first-seen directory order to resolve ties in filesystem creation timestamps. Fixed.

Verification: `JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify` — BUILD SUCCESS; 598 unit tests pass, CucumberIT 198 cases / 0 failures / 0 errors / 5 manual skips (193 passed), SummaryFlowIT 3/3, AccessGateIT 7/7, AccessApprovalIT 6/6, WhoamiIT 4/4. Spotless, coverage, SpotBugs and PMD passed.
