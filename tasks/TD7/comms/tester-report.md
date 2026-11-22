# TD7 tester report

2026-11-22. Tester work complete; integration compatibility is green. A logging requirement discrepancy is tracked separately as Q-tester-1 in questions.md and was sent to Developer. This report does not approve all TD7 acceptance criteria.

## Scope and boundary

Read .claude/roles/{common,tester}.md, SPEC.md, tasks/plan.md, TD7 in tasks/todo.md and tasks/TD7/brief.md. Technical task: no Analyst/Architect design artifacts or new Gherkin are required. Wrote only src/it and this task's comms; did not change production, unit tests, features, Maven configuration or the Git index.

Existing PipelineWorld fixtures remain compatible with the changed command: real yt-dlp receives the application arguments plus --proxy and --ignore-config; stage detection uses --skip-download, which the new command retains. Fake metadata already uses the selected JSON object shape, so no fixture migration is needed. AudioSupport also passes the command through to real yt-dlp without depending on --dump-single-json.

Added src/it/java/com/video2podcast/functional/MetadataProjectionIT.java. It serves a 59-second M4A through the existing local WireMock proxy and a JSON-LD page with a 9 MiB description. A separate real yt-dlp --dump-single-json fixture probe redirects to disk, proving that extracted metadata (not merely HTML) exceeds 8 MiB. The test then sends the URL through TelegramBot's update boundary, with real yt-dlp and ffmpeg. It asserts Done, the title and duration in the summary, both download buttons, Whisper and Ollama requests, and no unsolicited uploads. It does not call processing services.

## Results

Tools: yt-dlp 2026.10.26; ffmpeg 9.0.2; Java 27 via SDKMAN current.

- New large-metadata acceptance test: passed. Full JSON was 9,440,086 bytes; the application's selected metadata was {"title": "Example talk", "duration": 59}. Artifacts: target/pipeline-it-403950898052575307/{full-metadata.json,full-metadata.stderr,process.log,events.log,settings.json}.
- Existing T8 group: 76 selected scenarios passed, zero failures/errors. Covers real separate M4A, combined MP4, exact duration limits, ordered chunks, and the existing fake-only matrices for rejected metadata, collection/live handling, TOO_LONG, argument safety, deadlines and failures. Other discovered scenarios were excluded by the selection; no tests were disabled or given retries. Log: target/td7-tester-t8.log.
- Existing T11 title checks: all 3 selected scenarios passed (markup escaping, complete Telegram message limit with a long title, and decoded whitespace). Final invocation also reran the formatted new regression: 4 executed tests total, zero failures/errors. Log: target/td7-tester-final.log.
- Scoped Spotless apply succeeded for the new file; git diff --check -- src/it passed. Log: target/td7-tester-format.log.

Focused commands, from repository root with JAVA_HOME="$HOME/.sdkman/candidates/java/current":

```sh
./mvnw test-compile failsafe:integration-test -Dit.test=CucumberIT '-Dcucumber.filter.tags=@T8 and not @manual_media' > target/td7-tester-t8.log 2>&1
./mvnw test-compile failsafe:integration-test -Dit.test=MetadataProjectionIT,CucumberIT '-Dcucumber.filter.tags=@T11' '-Dcucumber.filter.name=Escape markup|Keep the complete message|Collapse title whitespace' > target/td7-tester-final.log 2>&1
./mvnw spotless:apply '-DspotlessFiles=.*MetadataProjectionIT\.java' > target/td7-tester-format.log 2>&1
```

The first sandboxed attempt could not bind localhost (SocketException: Operation not permitted); focused runtime commands were rerun with approved escalation. The initial new-test log is named target/td7-tester-red.log, but it is NOT evidence of a baseline behavioral failure: the Developer's --print change arrived before that successful runtime run. The fixture's full dump size is independently checked on every test run.

No clean or verify phase was run. Failsafe integration-test alone can report BUILD SUCCESS despite test failures; the test result counts and failsafe-summary.xml were checked explicitly (zero failures/errors), not just Maven's exit status.

## Remaining TD7 checks / finding

- Q-tester-1: current terminal failure WARN logs the cause class (e.g. JsonParseException), while TD7 requires the cause message without URLs, file paths or full stderr. Developer notified; acceptance reconciliation and unit coverage remain with Developer/Orchestrator.
- Oversize runner failure and exact warning contents/once-only logging have explicit unit-test requirements in TD7 and remain Developer-owned. The new acceptance regression exercises avoiding oversized unused metadata via the real external boundary.
- Full clean/verify, unit coverage gates, mutation testing and public YouTube/manual checkpoint evidence remain with Orchestrator/Developer/Owner as assigned. These local tests use no public network or secrets.

STATUS: DONE

## Review round 1 — real collection regression

2026-11-22, orchestrator request: exercise a collection through real yt-dlp rather than relying on the T8 mock's top-level `_type: playlist` object.

Fixed by adding `MetadataProjectionIT.playlistOnlyLinkIsRejectedBeforeProcessingItsFirstVideo`. A two-track XSPF playlist is served by the existing local WireMock proxy at `http://videos.example/collection.xspf`; both tracks have valid 59-second M4A media and explicit durations. Real yt-dlp recognizes XSPF as a collection and, with `--playlist-items 1 --print`, emits the first entry. The application's captured stdout is:

```json
{"title": "First talk", "duration": 59.0, "playlist_index": 1}
```

There is no collection `_type` in that output. This is the real projection that the old fake playlist scenario could not exercise. No processing service is called by the test. It sends the playlist URL through the Telegram update boundary and requires `Failed.` plus `This link is not a supported video.`, no audio download command, no Whisper/Ollama requests, no summary and no uploads. Both tracks are valid, so silently processing the first entry would fail these assertions.

The collection IS reachable offline; no external site or secrets are needed. Existing T8 Gherkin and its mocked legacy `_type` case were left intact; the new IT supplies the real collection regression requested by review.

Validation:

- `JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw test-compile failsafe:integration-test -Dit.test=MetadataProjectionIT > target/td7-tester-red.log 2>&1`: **2 passed**, zero failures/errors/skips (new playlist rejection plus existing oversized-description success).
- Actual collection process/event artifacts: `target/pipeline-it-6760166336972314804/{process.log,events.log,settings.json}`.
- The Developer's `playlist_index` fix arrived before this run compiled; despite the reused log filename, this is a green run against the fixed command, not a claimed baseline red run.
- Scoped `spotless:apply '-DspotlessFiles=.*MetadataProjectionIT\.java'`: passed; file already formatted. Log: `target/td7-tester-format-r1.log`.
- No clean/verify phase was run.

Earlier Q-tester-1 has been answered by Developer in questions.md with the logging reconciliation and unit coverage details; the earlier report's outstanding finding reflects the initial snapshot.

STATUS: DONE

## Step 3 — committed code and T14 fixture repair

Code checked: `378936c6cf5b3b1323366df4f47ccaf46113bf75` (`TD7: Fetch only needed yt-dlp fields and log failed jobs`).

Post-commit acceptance run before fixture changes:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw test-compile failsafe:integration-test -Dit.test=MetadataProjectionIT,CucumberIT '-Dcucumber.filter.tags=(@T8 or @T11) and not @manual_media' > target/td7-tester-postcommit.log 2>&1
```

Result: **120 Cucumber scenarios and both MetadataProjectionIT tests passed**, zero failures/errors (122 executed tests; 237 other discovered cases excluded by selection).

Orchestrator then supplied `tasks/TD7/comms/full-verify-1.log`, which reproduces four T14 failures. Root cause: `RetentionSupport.run` recognized metadata only by `--dump-single-json`. The new `--print` command fell into its download branch; without `-o`, that branch used the executable name as the output path and never established the real live-download directory or reached Whisper. This broke all four scenarios sharing the fake runner.

Fixed `src/it/java/com/video2podcast/functional/RetentionSupport.java`:

- Reuse `PipelineWorld.stage(args)` to identify metadata via `--skip-download`, independent of the output option.
- Return selected single-video JSON: `{"title":"Earlier video","duration":60,"is_live":false}`. Absent `_type` and `playlist_index` are omitted, as with real yt-dlp field selection; a null playlist_index would incorrectly identify a collection.

Audited all src/it command handling. No other obsolete metadata detection remains. The only remaining `--dump-single-json` is the intentional raw fixture-size probe in MetadataProjectionIT. No production code, features, checks or timeouts were changed; no tests were disabled or retried.

Focused verification after the repair:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw spotless:apply '-DspotlessFiles=.*RetentionSupport\.java' > target/td7-tester-retention-format.log 2>&1
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw test-compile failsafe:integration-test -Dit.test=CucumberIT '-Dcucumber.filter.tags=@T14' > target/td7-tester-retention.log 2>&1
```

Result: **all 46 T14 scenarios passed**, zero failures/errors, including:

- Runtime cleanup leaves a live download without job.json alone.
- Runtime cleanup leaves a live transcription alone after an hour.
- Audio claimed before expiry survives a minute tick while queued.
- A failed pipeline directory is removed without waiting for a minute.

Scoped Spotless and `git diff --check -- src/it` passed. The 311 discovered cases outside T14 were excluded by selection, not disabled.

**Ready for Orchestrator to run the full gate now**, with the updated RetentionSupport and new MetadataProjectionIT present. Tester did not run clean/verify. The fixture repair remains uncommitted for Orchestrator to include.

STATUS: DONE
