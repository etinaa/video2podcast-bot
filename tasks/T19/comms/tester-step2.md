# T19 tester handoff — Step 2

Completed 2026-11-25 against the working T19 implementation, after design commit 3ce56c0 and scenario correction 2ddb3da. No open tester questions. A: Q-tester-1 is resolved, and the corrected design was reapproved.

## Delivered

- `LanguageIT`: four full-application checks: Russian success, English success, terminal German refusal, and both long Cyrillic uploads with exact retained bytes.
- Cucumber glue for all 42 expanded T19 cases. Coverage includes auto/verbose requests, full-name/code normalization, pinned chunks and retries, silent intros, all-empty input, 18 chunks, consecutive jobs, every map/reduction prompt, UTF-8 transcripts, English interface, HTML/length limits, Cyrillic filenames, unsupported-language cleanup, deferred terminal recovery and no repeat notification.
- Successful English shorthand fixtures now include detection metadata in the existing pipeline, deferred, failure-mode and audio-queue tests. Explicit malformed JSON/metadata fixtures remain unchanged. Updated T9 retry assertions retain WAV-byte checks and verify auto/verbose versus pinned/en/json. T10 long fixtures now remain comfortably within the requested chunk count after the language prompts became shorter.
- Negative assertions wait for the terminal event and drained job queue. Scheduled recovery uses the existing registered-scheduler support; the post-detection outage uses an actual closed loopback listener after successfully returning chunk 0.

Only tester-owned `src/it` and T19 communications were changed by Tester. No production code, unit tests, feature files, dependencies, thresholds, suppressions or scenario exclusions were changed by Tester. No index/branch/commit actions were performed.

## Multipart mock limitation and fix

The long filename case exposed WireMock 3.13.2's fixed 512-byte multipart-part-header limit. Telegram's Java client repeats the supplied filename in the attachment `name` and `filename`; 100 Cyrillic letters plus the extension exceed that parser limit even though each filename is the required 204 UTF-8 bytes. The request was rejected with an HTML 500 before the mock journal/transformer could see it. A minimal real HTTP reproduction is recorded in `target/t19-multipart-probe.log` (short header succeeds; actual long attachment-name/header shape fails with `Header section has more than 512 bytes`). This is a mock limitation, not evidence of a Telegram limit.

`TelegramUploadRecorder` captures original Telegram uploads on loopback before that parser. JSON requests continue through WireMock; uploaded names, headers and binary bodies are not rewritten. Assertions still inspect exact filenames, stored chat, absent transcript caption, English audio caption and byte-identical retained content. The dedicated long-name LanguageIT regression passes through this boundary. Real Telegram acceptance remains manual evidence.

## Verification

- Required full command, from repository root, outside the sandbox:
  `JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1`
  **PASS**, finished 2026-11-25 13:11:11 +02:00, duration 09:56.
- Unit run: **964 tests, 0 failures/errors/skips**.
- Integration run: **534 tests, 0 failures/errors, 5 existing manual-media exclusions**. Cucumber: 484 discovered cases; all **42 T19 cases pass**, with no T19 skip. `LanguageIT`: **4 pass**.
- Full gate includes passing Spotless, SpotBugs, PMD and JaCoCo checks. Unit coverage: **98.66% lines, 93.85% branches**.
- Scoped formatting: `./mvnw spotless:apply spotless:check '-DspotlessFiles=.*/src/it/.*\.java'` passed. Spotless's filter matches absolute paths as a regex; the earlier relative glob was a no-op and was corrected. Final restored-source `spotless:check` and compilation passed (`target/t19-restored-check.log`).
- `git diff --check -- src/it` passed.
- Review guard experiment: temporarily inverted multipart part-name matching in the tester-owned recorder. The long-name LanguageIT failed at the stored-chat/part assertion; the other three checks passed (`target/t19-mutation-red.log`). Restored the helper byte-for-byte, recompiled, and reran focused LanguageIT: **4 pass**, finished 13:12:40 +02:00 (`target/t19-language.log`). This is a test-helper guard experiment, not a project PIT mutation-score claim. The restored helper matches the source used by the successful full gate.

Initial full runs exposed duplicate/overlapping step definitions, an old eight-map input size, formatting differences, and then the multipart mock limit. All were corrected. No test remains failing because implementation is missing, and no application defect remains open from this step.

## Remaining team work

Step 3 verification awaits the orchestrator's instruction after the Developer's code is committed. This step does not claim the R9 live Russian-video check, real model language quality/token-estimate comparison, real Telegram filename acceptance, or documentation/manual acceptance. Those remain separate task evidence. Ready for the reviewers' assessment of the tester changes.

STATUS: DONE
