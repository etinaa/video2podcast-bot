# TD4 requirements: external-boundary scenario rewrite

Technical rewrite of the existing T8, T9 and TD2 scenarios, based on `SPEC.md`,
`tasks/todo.md`, `tasks/TD4/brief.md`, the analyst role and `whoami.feature`.
No new product behavior is specified. Original requirement tags remain scoped to
T8, T9 or TD2 by the feature tag; the R numbers below describe this rewrite.

## R1 — Only external stimuli and observations

Acceptance: every automated scenario starts work by sending a Telegram message
in a private chat, using the wording of `whoami.feature`. Preconditions configure
external mocks or fixtures; observations check Telegram calls, Whisper HTTP
requests, recorded process invocations or files. No step invokes a component,
reads an internal return value, or inspects a callback, thread or exception.

## R2 — Preserve the old coverage inventory

Acceptance: the tables below have one row for every original scenario, including
scenario outlines. Rewritten outlines retain their cases. Folded cases name the
destination. Removed cases identify existing unit tests. Ancillary internal
assertions moved out of retained scenarios are identified in the notes. No test
implementation is changed. Unit references are Java class and method names in
`src/test/java/com/video2podcast/` (media, transcript, job or config packages).

## R3 — Keep observable queue behavior

Acceptance: the queued stage edits the job's status message to
`Queued: position N`. N is the count of running and waiting jobs immediately
when this job is added (`JobQueue.size()`), including the newly added job.
The first job in an empty queue therefore shows position 1. A third job behind
one running and one waiting job shows position 3; after a job finishes
successfully or fails and the queue is empty, the next job shows position 1.
Feature assertions consistently say "the status text shows position N" and
check that outbound Telegram status edit, not a separate reply. This wording
and first-job behavior are the orchestrator's decision in
`A: TD4 architect review · orchestrator` and are now in T11 acceptance.
Future publication, callback ordering, throwable wrapping, serialization and
shutdown stay in `JobQueueTest`.

## R4 — Keep manual audio verification and defer wiring

Acceptance: real audio property checks retain `@manual_media`, including all
59/45/600-second cases and the 1250-second remainder. Mock fixtures assert only
arguments, bytes, filenames and HTTP order. `src/it`, `CucumberIT` and its filters
are untouched by TD4. T11 supplies step definitions and removes all three
exclusions (`not @T8`, `not @T9`, `not @TD2`) to enable the rewritten automated
scenarios. Manual scenarios stay manual. This task does not make the rewritten
scenarios executable today.

## Boundary and fixture conventions

- A Telegram message means an update delivered through the bot's normal update
  boundary with the whole application wired. A reply/status edit is an outbound
  Telegram Bot API call. No step may substitute a direct media/transcription call.
- Process mocks are the existing fake external-process boundary; yt-dlp metadata,
  download and ffmpeg WAV/split responses are configured independently. Defaults
  succeed unless a scenario overrides them. Files are written under the directory
  created for the link's job, never supplied as method arguments.
- For T9 the fake media commands create the declared chunk files and bytes. The
  source belongs to the yt-dlp download fixture, and WAV/chunks to ffmpeg. Whisper
  answers are configured per filename/attempt; a held answer remains within the
  configured timeout. In the two outlines with answer-timeout rows,
  `WHISPER_TIMEOUT=PT1S`; the dedicated timeout scenarios retain their own values.
  Ollama holds summarization so persisted transcription files can be inspected
  without exercising delivery or retention. After successful transcription,
  T9 file assertions run while Ollama's first answer remains held within its
  configured timeout. Do not release it or allow a summarization failure before
  those assertions: that failure would remove the job directory. The pending
  Whisper scenario checks files earlier, while Whisper is held; transcription
  failure scenarios check Telegram/HTTP observations without reaching Ollama.
- Assertions while a Whisper answer is held run after that HTTP request arrives.
  Manual real-tool cases override the process mocks and serve a known audio/video
  fixture at the specified URL. The source hash is captured after download and
  compared while transcription is held, before success cleanup.
- Failure scenarios assert the existing safe message in Telegram, never an enum.
  They do not require failed-job audio to survive the full pipeline: SPEC requires
  failed-job directory removal. Component retention/atomic-write details remain
  in unit tests. Absence of transcript files after a terminal failure is not
  asserted in Cucumber because directory cleanup would make it vacuous;
  `TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched` and
  `TranscriptionClientTest#aChunkFailureLeavesAnExistingTranscriptUntouched`
  cover transcript preservation at the component boundary. Retry timing without
  scheduled backoff is unit-level; request
  order/count remains observable at the HTTP mock.
- Progress checks refer to edits of the job's status message, not callbacks.
  The explicit supplied-order/outside-directory/replacement tests are unit-only:
  a new Telegram link does not accept arbitrary chunk lists or resume an old job.
- DNS-free URL validation remains unit-level. The process mock accepts literal
  URLs (including unresolvable.example); no network resolution is needed.

## Out of scope

New behavior, production code, unit tests, step definitions, integration tests,
runner/filter changes, T11 implementation, T18 deferred retry, delivery buttons,
and commits. No external research was necessary; local approved sources and
existing tests supply the contract (reviewed 2026-11-17).

## Old-to-new mapping

### T8-media.feature

| # | Original scenario | Disposition | New scenario or existing unit test | Notes |
|---|---|---|---|---|
| 1 | Accept a public HTTP URL unchanged | Rewritten | Pass public link messages unchanged to yt-dlp | DNS-free validation remains in UrlValidatorTest#acceptsPublicLinks; the mock never resolves these hosts. |
| 2 | Reject an invalid URL before starting a tool | Removed | UrlValidatorTest#rejectsEverythingElse | Internal contract; retain the existing unit test. |
| 3 | Enforce the inclusive URL length limit | Removed | UrlValidatorTest#rejectsOverlongUrls | Internal contract; retain the existing unit test. |
| 4 | Reject a local or restricted literal host without DNS | Rewritten | Reject restricted link messages before invoking yt-dlp | The no-DNS implementation detail stays in UrlValidatorTest#rejectsEverythingElse. |
| 5 | Do not extend private IPv4 ranges to public neighbours | Folded as examples | Pass public link messages unchanged to yt-dlp | Public neighbours are the final three examples; DNS-free validation remains unit-level. |
| 6 | Validate again at each yt-dlp entry point | Removed | YtDlpClientTest#invalidUrlStartsNoProcess | Internal contract; retain the existing unit test. |
| 7 | Keep URL punctuation as a single data argument | Rewritten | Pass URL punctuation literally to yt-dlp | Actual shell avoidance is covered by DefaultProcessRunnerTest#argumentsReachTheProcessLiterallyWithoutSideEffects. |
| 8 | Preserve a job directory containing spaces as one output argument | Removed | YtDlpClientTest#hostileUrlAndSpacedDirectoryStayOneArgumentEach | Internal contract; retain the existing unit test. |
| 9 | Capture both streams and a nonzero exit without deadlock | Removed | DefaultProcessRunnerTest#largeOutputOnBothStreamsDoesNotDeadlock | Internal contract; retain the existing unit test. |
| 10 | Continue draining output after capture limits are reached | Removed | DefaultProcessRunnerTest#outputIsCappedButStillDrained | Internal contract; retain the existing unit test. |
| 11 | Report a missing executable safely | Removed | DefaultProcessRunnerTest#missingBinaryIsFailed; JobFailureTest#causeIsKeptForLogsButNotInTheMessage | Internal contract; retain the existing unit test. |
| 12 | Stop execution when its waiting thread is interrupted | Removed | DefaultProcessRunnerTest#interruptKillsTheWholeTreeAndKeepsTheFlag | Internal contract; retain the existing unit test. |
| 13 | Apply default stage deadlines | Removed | AppPropertiesTest#usesSpecDefaults; YtDlpClientTest#metadataCommandAndResult; YtDlpClientTest#downloadCommandAndResult; FfmpegRunnerTest#toWavCommandAndResult; FfmpegRunnerTest#splitCommandAndOrderedResult | Internal contract; retain the existing unit test. |
| 14 | Apply configured stage deadlines | Removed | YtDlpClientTest#configuredLimitAndTimeoutsAreUsed; FfmpegRunnerTest#toWavCommandAndResult; FfmpegRunnerTest#splitCommandAndOrderedResult | Internal contract; retain the existing unit test. |
| 15 | Kill the process tree on deadline expiry | Removed | DefaultProcessRunnerTest#timeoutKillsTheWholeTree | Internal contract; retain the existing unit test. |
| 16 | Reject a nonpositive execution deadline | Removed | DefaultProcessRunnerTest#nonPositiveTimeoutIsRejected | Internal contract; retain the existing unit test. |
| 17 | Preserve a timeout through every media stage | Rewritten | Report media stage timeouts in chat |  |
| 18 | Read a title and duration without downloading audio | Removed | YtDlpClientTest#metadataCommandAndResult | Internal contract; retain the existing unit test. |
| 19 | Enforce the configured duration inclusively before download | Rewritten | Enforce the duration limit before downloading |  |
| 20 | Explain the configured duration limit | Rewritten | Explain the configured duration limit |  |
| 21 | Reject live or unusable metadata before downloading | Rewritten | Reject live or unusable metadata in chat |  |
| 22 | Retain source audio with the requested format preference | Rewritten | Retain downloaded source audio |  |
| 23 | Process one video when its URL also includes playlist context | Rewritten | Process one video when its URL also includes playlist context |  |
| 24 | Do not expand a playlist-only URL into audio downloads | Rewritten | Do not expand a playlist-only URL into audio downloads |  |
| 25 | Reject a successful download without a completed source file | Rewritten | Report a download without a completed source file |  |
| 26 | Produce a Whisper-compatible WAV while retaining the source | Rewritten | Produce a Whisper-compatible WAV while retaining the source |  |
| 27 | Split audio into ordered chunks including the final remainder | Rewritten | Split audio into ordered chunks including the final remainder |  |
| 28 | Keep a short source as one chunk | Rewritten | Keep short recordings as one nonempty chunk |  |
| 29 | Return numbered chunks in order regardless of filesystem enumeration | Rewritten | Send numbered chunk files in playback order |  |
| 30 | Exclude stale chunks when splitting again | Removed | ChunkSplitterTest#deleteExistingRemovesOnlyChunks; FfmpegRunnerTest#splitCommandAndOrderedResult | Internal contract; retain the existing unit test. |
| 31 | Reject a split that produced no chunks | Rewritten | Report a split without chunks |  |
| 32 | Report ffmpeg failure without yt-dlp classification | Rewritten | Report ffmpeg errors safely in chat |  |
| 33 | Classify nonzero yt-dlp stderr case-insensitively | Rewritten | Explain yt-dlp failures safely in chat |  |
| 34 | Use the same error classification at both yt-dlp stages | Rewritten | Explain sign-in failures at either yt-dlp stage |  |
| 35 | Do not classify warning text after successful metadata extraction | Rewritten | Continue after successful metadata with warning text |  |
| 36 | Keep diagnostic content out of safe failure messages | Removed | JobFailureTest#fixedMessagesPerKind; JobFailureTest#tooLongFormatsTheLimit; JobFailureTest#causeIsKeptForLogsButNotInTheMessage | Internal contract; retain the existing unit test. |
| 37 | Do not interpret shell punctuation in an argument | Removed | DefaultProcessRunnerTest#argumentsReachTheProcessLiterallyWithoutSideEffects | Internal contract; retain the existing unit test. |
| 38 | Continue draining both streams after reaching capture limits | Removed | DefaultProcessRunnerTest#bothStreamsAreCappedAndDrainedWhenBothExceedTheCap | Internal contract; retain the existing unit test. |
| 39 | Reject conversion success without the expected WAV | Rewritten | Report conversion without the expected WAV |  |
| 40 | Retain short recordings without empty trailing chunks | Folded as examples | Keep short recordings as one nonempty chunk | 45 and 600 second examples remain manual. |
| 41 | Reject split success without chunks | Folded as example | Report a split without chunks | Duplicate of the same zero-output failure; one retained case. |
| 42 | Request the WAV format without claiming fixture audio was converted | Rewritten | Request Whisper WAV options for a link message |  |
| 43 | Request ten-minute segments and return ordered fixture paths | Rewritten | Request ten-minute segments for a link message |  |

### T9-transcription.feature

| # | Original scenario | Disposition | New scenario or existing unit test | Notes |
|---|---|---|---|---|
| 1 | Send WAV bytes with the explicit English JSON contract | Rewritten | Send WAV bytes with the explicit English JSON contract |  |
| 2 | Merge all chunks in the supplied playback order | Rewritten | Merge all chunks in the supplied playback order |  |
| 3 | Include every chunk of a video at the three-hour limit | Rewritten | Include every chunk of a video at the three-hour limit |  |
| 4 | Include the short final chunk | Rewritten | Include the short final chunk |  |
| 5 | Collapse segment whitespace and preserve Unicode and punctuation | Rewritten | Collapse segment whitespace and preserve Unicode and punctuation |  |
| 6 | Do not publish or clean up while a later answer is pending | Rewritten | Do not publish or clean up while a later answer is pending |  |
| 7 | Skip empty speech without retry or extra separators | Rewritten | Skip empty speech without retry or extra separators |  |
| 8 | Entirely empty speech fails without deleting audio | Rewritten | Report entirely empty speech in chat | Failure-time audio retention stays in TranscriptionClientTest#entirelyEmptySpeechIsNoSpeech and #secondFailureDecidesTheKind; transcript preservation stays in TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched and TranscriptionClientTest#aChunkFailureLeavesAnExistingTranscriptUntouched. No post-failure transcript-absence assertion remains because the pipeline removes failed jobs. |
| 9 | Broken successful responses are retried rather than called silence | Rewritten | Broken successful responses are retried rather than called silence |  |
| 10 | Recover a failed chunk without resending earlier work | Rewritten | Recover a failed chunk without resending earlier work | No scheduled retry delay stays in TranscriptionClientTest#retriesOnceAndKeepsGoing. |
| 11 | A valid empty answer on retry continues to the next chunk | Rewritten | A valid empty answer on retry continues to the next chunk |  |
| 12 | The retry failure determines the final kind and stops later chunks | Rewritten | Report the retry failure and stop later chunk requests | Failure-time audio retention stays in TranscriptionClientTest#entirelyEmptySpeechIsNoSpeech and #secondFailureDecidesTheKind; transcript preservation stays in TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched and TranscriptionClientTest#aChunkFailureLeavesAnExistingTranscriptUntouched. No post-failure transcript-absence assertion remains because the pipeline removes failed jobs. |
| 13 | Bind the per-attempt answer timeout | Removed | AppPropertiesTest#usesSpecDefaults; WhisperTimeoutBindingTest#whisperTimeoutIsReadFromTheEnvironmentVariable; WhisperClientTest#theHttpClientHasAFiveSecondConnectTimeoutAndNeverFollowsRedirects | Internal contract; retain the existing unit test. |
| 14 | Enforce the configured answer timeout on a connected server | Rewritten | Enforce the configured answer timeout on a connected server |  |
| 15 | Bound a connection that cannot be established | Removed | WhisperClientTest#aConnectTimeoutIsAConnectionError; WhisperClientTest#twoConnectTimeoutsGiveServiceUnreachable | Internal contract; retain the existing unit test. |
| 16 | Each attempt receives a fresh answer wait | Rewritten | Each attempt receives a fresh answer wait |  |
| 17 | Report each chunk before its first request but not again on retry | Rewritten | Report each chunk in Telegram before its first request |  |
| 18 | Stop progress reporting at the terminally failing chunk | Rewritten | Stop Telegram progress at the terminally failing chunk |  |
| 19 | Raw server diagnostics do not enter the safe failure message | Rewritten | Raw server diagnostics do not enter the safe failure message |  |
| 20 | Remove temporary audio after persisting a complete transcript | Rewritten | Remove temporary audio after persisting a complete transcript | Deletion ordering stays in the existing unit flow; unchanged pre-existing job.json is asserted in TranscriptionClientTest#anAudioDeletionFailureStillLetsEveryLaterDeletionRun. Its baseline success cleanup is covered by TranscriptionClientTest#writesMergedTranscriptAndRemovesTemporaryAudio. |
| 21 | A failed transcript write does not trigger temporary audio deletion | Removed | TranscriptionClientTest#failedWriteIsFailedAndDeletesNothing | Internal contract; retain the existing unit test. |
| 22 | Collapse nonbreaking whitespace between words | Rewritten | Collapse nonbreaking whitespace between words |  |
| 23 | A failed atomic replacement leaves no partial final transcript | Removed | TranscriptionClientTest#failedWriteIsFailedAndDeletesNothing; TranscriptionClientTest#aFailedReplacementLeavesThePriorTranscriptBytesIntact | Internal contract; retain the existing unit test. |
| 24 | Cleanup errors after persistence preserve success and permit remaining deletions | Removed | TranscriptionClientTest#deletionProblemsDoNotFailTheTranscription; TranscriptionClientTest#anAudioDeletionFailureStillLetsEveryLaterDeletionRun | Internal contract; retain the existing unit test. |
| 25 | Reject invalid job inputs before making HTTP requests | Removed | TranscriptionClientTest#rejectsBadArguments; TranscriptionAssembledTest#aJobDirThatIsAFileFailsWithoutARequest | Internal contract; retain the existing unit test. |
| 26 | Null arguments are caller programming errors | Removed | TranscriptionClientTest#rejectsBadArguments | Internal contract; retain the existing unit test. |
| 27 | An unavailable chunk fails locally without misclassifying the server | Removed | TranscriptionAssembledTest#anUnavailableChunkIsRetriedThenFailedAndLaterChunksAreNotSent | Internal contract; retain the existing unit test. |
| 28 | Supplied chunk order wins over filename order | Removed | TranscriptionAssembledTest#theSuppliedOrderWinsOverTheFilenameOrder | Internal contract; retain the existing unit test. |
| 29 | Accept a supplied chunk outside the usual chunks directory | Removed | TranscriptionClientTest#missingChunksDirectoryIsOnlyLogged; WhisperClientTest#sendsTheFourPartsAndReturnsRawText | Internal contract; retain the existing unit test. |
| 30 | Replace an existing transcript on success without resuming it | Removed | TranscriptionClientTest#successReplacesAnExistingTranscript | Internal contract; retain the existing unit test. |
| 31 | A failed run preserves an existing transcript | Removed | TranscriptionClientTest#aChunkFailureLeavesAnExistingTranscriptUntouched; TranscriptionClientTest#noSpeechLeavesAnExistingTranscriptUntouched; TranscriptionClientTest#aFailedReplacementLeavesThePriorTranscriptBytesIntact | Internal contract; retain the existing unit test. |
| 32 | A failed progress callback stops before sending its chunk | Removed | TranscriptionClientTest#throwingProgressPropagatesAndWritesNothing | Internal contract; retain the existing unit test. |

### TD2-job-queue.feature

| # | Original scenario | Disposition | New scenario or existing unit test | Notes |
|---|---|---|---|---|
| 1 | Waiting and running tasks both count | Rewritten | Queued status includes running and waiting jobs | Only the count visible in Telegram is retained. Internal count/completion guarantees: JobQueueTest#sizeCountsWaitingAndRunningTasksUntilTheyFinish |
| 2 | A completed task leaves remaining work counted | Rewritten | Queued status excludes a finished job | Only the count visible in Telegram is retained. Internal count/completion guarantees: JobQueueTest#aCompletedTaskLeavesTheOtherWorkCounted |
| 3 | A caller sees zero after observing completion of the last task | Rewritten | Queued status excludes a finished job | Only the count visible in Telegram is retained. Internal count/completion guarantees: JobQueueTest#sizeCountsWaitingAndRunningTasksUntilTheyFinish; JobQueueTest#aFailedTaskIsNoLongerCounted; JobQueueTest#aTaskThatThrowsAnErrorCompletesItsFutureAndDoesNotStopTheQueue |
| 4 | The last task callback observes the updated count | Removed | JobQueueTest#sizeIsZeroInsideTheCallbackOfTheLastSucceededTask; JobQueueTest#sizeIsZeroInsideTheCallbackOfTheLastFailedTask; JobQueueTest#sizeIsZeroInsideTheCallbackOfTheLastTaskThatThrowsAnError | Internal contract; retain the existing unit test. |
| 5 | Failure does not prevent the next task from running | Removed | JobQueueTest#aFailingTaskDoesNotStopTheTasksBehindIt; JobQueueTest#aTaskThatThrowsAnErrorCompletesItsFutureAndDoesNotStopTheQueue | Internal contract; retain the existing unit test. |
| 6 | A runtime failure keeps the existing error log | Removed | JobQueueTest#aRuntimeFailureIsLoggedOnceAsAnErrorWithTheOriginalThrowable | Internal contract; retain the existing unit test. |
| 7 | Tasks execute one at a time in submission order | Removed | JobQueueTest#tasksRunInSubmissionOrderAndNeverAtTheSameTime | Internal contract; retain the existing unit test. |
| 8 | Graceful close finishes accepted work and rejects new work | Removed | JobQueueTest#closeWaitsForQueuedTasksAndThenRefusesNewOnes | Internal contract; retain the existing unit test. |
| 9 | Forced close cancels unfinished work | Removed | JobQueueTest#closeGivesUpOnATaskThatDoesNotFinishInTime; JobQueueTest#closeCancelsTasksThatWereStillWaitingSoNobodyWaitsForeverAndTheCountIsRight | Internal contract; retain the existing unit test. |
| 10 | Interrupted close preserves the interrupt flag | Removed | JobQueueTest#closeStopsATaskThatNeverFinishesWhenInterrupted | Internal contract; retain the existing unit test. |


## Validation and handoff

The mapping contains all 85 original scenarios/outlines: 43 T8, 32 T9 and 10 TD2.
The rewritten files contain 44 scenarios/outlines. Static checks verify mapping
completeness, unit-test references, requirement/manual tags, example-table shape
and external message triggers. `git diff --check` passes. The single authorized
Maven verification before these review fixes returned BUILD SUCCESS (373 unit
tests; 68 functional/integration tests, 49 skipped; no failures or errors), as
recorded in `comms/questions.md`. Per the orchestrator's latest instruction,
Maven is not rerun for these text fixes. Architect and orchestrator review remain
the TD4 acceptance gate.
