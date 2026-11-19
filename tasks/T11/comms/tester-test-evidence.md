# T11 tester Step 2 evidence

Completed: 2026-11-19T11:40:08+02:00. Result: **PASS**.

## Verification

Executed from the repository root, outside the sandbox, using the exact authorized command:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1
```

| Check | Result |
|---|---|
| Unit tests | 597 passed; no failures/errors/skips |
| CucumberIT | 193 nonmanual scenarios passed; 5 manual cases excluded by `not @manual_media` |
| Cucumber XML | 198 distinct test names; no merged outline-example names |
| SummaryFlowIT | 3 passed: real-media success/publication, partial-download cleanup, sender vs chat access gate |
| Existing functional tests | AccessGateIT 7, AccessApprovalIT 6, WhoamiIT 4 passed |
| Unit line coverage | 98.60% (90% gate) |
| Unit branch coverage | 93.50% (90% gate) |
| Spotless, SpotBugs, PMD | Passed |

Integration tests do not contribute to JaCoCo coverage. This is the default
verification run; no mutation-profile result is claimed by Tester.

Formatting was applied only to tester Java files with
`./mvnw -q spotless:apply '-DspotlessFiles=.*src/it/.*[.]java'`.
`git diff --check -- src/it` passed.

## Delivered coverage and fixtures

- Enabled T8, T9, TD2 and T10 alongside T11; retained the manual-media exclusion.
- Reused the Telegram update steps and common FunctionalTest/WireMocks settings
  and server construction. Scenario contexts allow constructor-time configuration
  overrides and a primary mutable Clock, without invoking processing services.
- Real yt-dlp/ffmpeg success paths use local WireMock media pages and generated
  M4A/combined-MP4 fixtures. The decorator supplies the local proxy and ignores
  host yt-dlp configuration. No fake successful conversion proves a media result.
- Failure/argument fixtures stay at ProcessRunner; Whisper/Ollama/Telegram use
  HTTP mocks. Disk obstacles exercise both initial persistence and post-send
  publication failure, including best-effort button withdrawal.
- Assertions cover queued responsiveness, same-message ordered progress,
  multipart WAV bytes/order, map/reduce HTTP requests and budgets, bounded HTML,
  buttons/no uploads, stored records/files/retention, cleanup isolation and
  continued queue processing after failures.
- Updated two older integration expectations for the agreed unsupported-link
  response to non-command non-link text.
- Q-tester-1 is resolved by the analyst's genuine closed-port scenario and unit
  mapping for mixed connection errors. Fresh design approval is below the reset.

Tools: Java 27.0.1, yt-dlp 2026.10.26, ffmpeg 9.0.2. Fixture provenance is in
`src/it/resources/media/README.md`.

## Evidence retained across later clean builds

- Current log: `target/tester-verify.log`.
- Durable local log copy: `/tmp/t11-tester-verify-final.log`.
- Final Failsafe XML/text archive: `/tmp/t11-tester-failsafe-final.zip`.
- Source/fixture SHA-256 manifest: `tasks/T11/comms/tester-verified-files.json`.

Q-tester-2 is resolved: Developer stopped the overlapping clean build before this
exclusive final run. Evidence was copied before releasing target/ to the team.
No open tester question remains. No production or feature files, dependencies,
Git index or commits were changed by Tester. The orchestrator owns review/commit
handoff and later Step 3 verification.
