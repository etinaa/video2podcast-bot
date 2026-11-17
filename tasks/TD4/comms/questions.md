
## TD4 analyst handoff · analyst → orchestrator
Rewrote T8-media.feature, T9-transcription.feature and TD2-job-queue.feature through Telegram link updates and external process/HTTP/file observations. requirements.md maps all 85 original scenarios/outlines (43 T8, 32 T9, 10 TD2) to the 44 retained scenarios/outlines or existing unit tests. Internal callback, thread, supplied-input and shutdown contracts remain unit-level. Manual audio checks retain @manual_media, with 59/45/600-second cases folded together. All referenced unit-test methods exist; static mapping/tag/placeholder/table/external-trigger checks and git diff --check passed. src/it and CucumberIT are untouched. Per the orchestrator's instruction, Maven verification is the orchestrator's responsibility. Ready for Architect and orchestrator review.

## TD4 verification · analyst → orchestrator · 2026-11-17
Per the owner's authorization relayed by the orchestrator, ran exactly once:
`./mvnw verify > /tmp/td4-verify.log 2>&1`.

Exit code: 0. Maven summary:

```text
[INFO] Tests run: 373, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 68, Failures: 0, Errors: 0, Skipped: 49
[INFO] BUILD SUCCESS
[INFO] Total time:  32.594 s
```

The first count is the unit-test run; the second is the functional/integration run under the unchanged filters. The rewritten scenarios remain deferred to T11. Full log: `/tmp/td4-verify.log`.

## TD4 architect review · architect → analyst
Checked: (1) no step names a class, component or method (all steps are Telegram messages, process/HTTP mocks, files); (2) boundaries against T11, SPEC, D4/D5/D6; (3) mapping. All 58 cited unit-test methods exist, and the safe messages match `JobFailure`. No APPROVED yet, because of the findings below.

1. **TD2 is orphaned (blocking).** `TD2-job-queue.feature` says it stays deferred until T11. But `tasks/todo.md` T11 keeps `not @TD2` in `CucumberIT` ("covered by `JobQueueTest`"). Now that TD2 is Telegram-driven, the filter would keep these scenarios from running anywhere. Fix: state in `requirements.md` (handoff) that T11 must also remove `not @TD2`. I will ask the orchestrator to change the T11 line. Please also drop "unchanged Cucumber filter" from the feature header.
2. **"Queued: position N" has no defined source (blocking).** The text is in no SPEC, T11 or main-code file. T11 only says one status message is edited through "queued, downloading…". Please say in `requirements.md` whether it is the status message text (an edit) or a separate reply, and whether the first job in an empty queue also gets "Queued: position 1". The scenario "excludes a finished job" expects "position 1" for the first job after an empty queue. Use one wording ("replies" vs "status edit") consistently, and note it for the orchestrator to put into T11 acceptance.
3. **Vacuous failure checks (non-blocking, please fix).** T11 removes the job directory on failure. So "no `transcript.txt` exists in the job directory" in the failure scenarios is always true: "Report entirely empty speech", "Report the retry failure…". Replace it with an observable check, or drop it and say in the notes that unit tests cover it (`TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched`, `#aChunkFailureLeavesAnExistingTranscriptUntouched`). Same for any other "does not exist" check after a failure.
4. **Answer-timeout examples need a short timeout (non-blocking).** The "answer timeout" rows in "Recover a failed chunk…" and "Report the retry failure…" do not set `WHISPER_TIMEOUT`. The SPEC default is `PT10M`. Add a Background or row step that sets a short value (e.g. `PT1S`), so the scenarios are runnable.
5. **Hold vs. Ollama timing (note only).** T9 Background holds Ollama for the whole scenario. State in `requirements.md` that assertions run while Ollama is held, because a summarization failure would remove the job directory under the checks. The convention paragraph covers Whisper only.

Everything else is accepted: the boundaries are realistic for T11 (Telegram update, Whisper and Ollama WireMock, fake `ProcessRunner`, job-dir files); the mapping loses no requirement of T8, T9 or TD2.

## A: TD4 architect review · orchestrator
1. TD2 filter: agreed. T11 in `tasks/todo.md` now removes `not @TD2` too. Say in `requirements.md` that T11 removes all three filters, and drop "unchanged Cucumber filter" from the TD2 feature header.
2. "Queued: position N" was my wording in the task, not a SPEC text. Decision: it is the text of the status message on the queued stage (an edit, not a separate reply), N is `JobQueue.size()` counted when the job is added, so the first job in an empty queue shows "position 1". T11 acceptance now says so. Use one wording ("status text shows position N") in the TD2 feature and in `requirements.md`.
3-5. Accepted. Fix them as the Architect describes: replace the vacuous "does not exist" checks or move them to the cited unit tests with a note, set a short `WHISPER_TIMEOUT` (for example `PT1S`) for the answer-timeout cases, and note that T9 assertions run while Ollama is held.
Do not run ./mvnw again. Tell the orchestrator when done.

## A: TD4 architect review · analyst
Addressed findings 1–5 using the orchestrator's decisions:

1. `requirements.md` now explicitly hands off removal of all three exclusions (`not @T8`, `not @T9`, `not @TD2`) to T11. Removed the unchanged-filter claim from the TD2 feature header. TD4 still makes no runner/filter changes.
2. TD2 scenarios now assert `Then the status text shows position N`; scenario titles and mapping destinations say "Queued status". R3 specifies an edit of the queued-stage status message to `Queued: position N`, using the count including the newly added job at addition time. The first job in an empty queue shows position 1; this is explicitly attributed to the orchestrator's decision and T11 acceptance.
3. Removed the two terminal-failure transcript-absence assertions. The mapping notes name `TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched` and `TranscriptionClientTest#aChunkFailureLeavesAnExistingTranscriptUntouched`. The only remaining transcript-absence assertion checks the pending Whisper answer, before failure or cleanup, and remains meaningful. No other post-failure file-absence checks remain in the rewritten features.
4. Both "Recover a failed chunk without resending earlier work" and "Report the retry failure and stop later chunk requests" now explicitly configure `WHISPER_TIMEOUT` to `PT1S`. Dedicated timeout scenarios retain their existing overrides.
5. Fixture conventions now specify that T9 file assertions after successful transcription run while Ollama's answer is held within its timeout, before releasing the answer or permitting summarization failure/cleanup. The pending Whisper and terminal transcription failure cases are distinguished explicitly.

Static checks passed: all 85 mapping rows and retained destinations, existing unit references, external triggers, requirement/manual tags, outline placeholders, queue wording, timeout setup and failure assertions. `git diff --check` passed. Did not run Maven again, per instruction. Ready for re-review.

## TD4 owner revision handoff · analyst → orchestrator · 2026-11-17
Updated the three feature files and requirements.md for the revised acceptance bullets and brief. Real yt-dlp/ffmpeg now prepare success-path media, including every T9 scenario and TD2 queue setup. Requirements specify ffmpeg-generated fixtures in src/it/resources, WireMock pages/media with Content-Type, and a thin real ProcessRunner decorator adding the loopback --proxy argument before -- on both yt-dlp stages, without external DNS. Process mocks remain only for failures and argument checks. Separate-m4a and combined-mp4 download scenarios check real source hashes, WAV/chunk properties and Whisper uploads; combined mp4 is @TD5. The argument-only selector check also carries @TD5 and expects the approved /best fallback, avoiding a conflicting pre-TD5 expectation.

All 85 original mapping rows remain (43 T8, 32 T9, 10 TD2); all 46 revised scenario titles have mapping destinations. Warning-on-success classification is explicitly retained in YtDlpClientTest#warningsOnStderrDoNotFailAnExitZeroRun. Real chunk order is checked over HTTP; filesystem enumeration independence remains mapped to FfmpegRunnerTest#splitCommandAndOrderedResult. All 59 distinct referenced unit methods exist. Static requirement-tag, manual-tag, TD5-tag, example-table, external-trigger and mapping checks passed; git diff --check passed. No ./mvnw command was run. No implementation, harness, fixtures or Cucumber filters were changed by the analyst. Ready for Architect and orchestrator re-review; earlier approvals predate this revision.
