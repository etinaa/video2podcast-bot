VERDICT: APPROVED

## Findings
- [Suggestion] src/main/java/com/video2podcast/bot/DeliveryCallbackHandler.java:187, media/AudioPreparer.java:454, media/FfmpegRunner.java:512 - three identical private `deleteQuietly` helpers - one shared helper would remove the duplication; not blocking.
- [Suggestion] src/main/java/com/video2podcast/bot/DeliveryCallbackHandler.java:76-81 - the prefix is chosen twice (`isAudio ? AUDIO_PREFIX : TRANSCRIPT_PREFIX` and `startsWith`) - a small `prefix` local would read better.
- [Suggestion] src/main/java/com/video2podcast/bot/DeliveryCallbackHandler.java:178 - `extensionOf` returns the whole file name when there is no dot. Unreachable today (the source always has an extension, the output is `out.m4a`) - consider returning "m4a" in that case too.
- [Suggestion] src/test/java/com/video2podcast/bot/AudioButtonTest.java - the "two taps, one upload" case is sequential. The concurrent claim race is covered at service level (`DeliveryServiceTest.ofManyConcurrentTapsExactlyOneIsGranted`), so the handler behaviour follows. Acceptable.
- [Suggestion] src/test/java/com/video2podcast/media/AudioPreparerTest.java - no case for the lower-retry floor when `kbps * 4 / 5 < 8` but `kbps > 8` (e.g. 9 → 8). The logic is correct (`max(8, ...)`, retry when `lower < kbps`); the case is only missing for mutation strength.

No Critical or Important findings. Checked and found correct:
- Decision table of api.md: rows 1-3, inclusive 50 MiB for unchanged, strict `< LIMIT` and non-empty for encoded output, a conversion output that does not fit falls through to compression from the original source, the retry `kbps*4/5` equals floor(0.8*kbps) for integers (37→29, 128→102), no third encode, no retry at the 8 kbps floor, `out.m4a` removed on every failure path (encodeM4a and prepare), the source is never deleted (the handler deletes only when the prepared path differs from the claimed source).
- Bitrate arithmetic: floor(a/s/1000) with integer division equals floor(a/(s*1000)); duration <= 0 is treated as 1; the unit cases are right.
- Handler: claim, answer `Preparing audio…`, SENDING edit using the claim record, queue submit; the worker prepares, sends, completes, and does the final edit with the record from `complete` (a transcript sent while queued keeps its label). Failure path releases, edits back to AVAILABLE, sends the exact notice, all best effort; `RejectedExecutionException` goes through the same path; failure of `complete` keeps SENDING with no message; the task never rethrows (only `RuntimeException`, which matches the contract). The claim, not the clock, governs expiry in the queued task. A null summary message id skips edits (via the existing `editButtons` helper, tested).
- Gateway: `InputFile(file, fileName)`, caption with HTML parse mode, title and performer as plain fields (no markup interpretation); captions are the exact texts and need no escaping. Old 4-argument `sendAudio` removed from all implementers and test doubles.
- FileNames: transcript behaviour unchanged, shared sanitising, fallback `audio`, 256-unit title cut without splitting a surrogate pair. The SpotBugs NP fix (`getFileName()` null) is in the code and unit-tested.
- Security: ffmpeg args are a list (no shell); errors, paths and stderr stay in logs and `JobFailure` messages (tested `hasMessageNotContaining`); no new suppression in the patch.
- Wiring: `AudioPreparer` bean and `DeliveryCallbackHandler` constructor are updated, with config tests. `BotUpdateHandler` routes `audio:` only after the existing access checks (tested for an unknown user).

## Requirements
- R1: met - owner/not-owner/expired come from the T12 claim; routing is behind the access gate; stored chatId and summaryMessageId are used for every send and edit.
- R2: met - same Path returned for mp3/m4a at or below the limit (case-insensitive); threshold tested at 52,428,800 and +1.
- R3: met - other formats (including `source.mp4`) go to M4A at 128 kbps without `-ac`, with `-vn`; an oversized conversion falls to compression and is reported as COMPRESSED.
- R4: met - mono AAC, target arithmetic, one retry, strict verification, no oversized upload, failure mapping.
- R5: met - queue submission via `JobQueue`; claim before the answer; the in-progress / already-sent answers; SENT only after sendAudio. The shared single-worker ordering is for the Tester's IT.
- R6: met - sendAudio with filename, title, performer `Video2Podcast`, exact captions; no uploader metadata.
- R7: met - only the keyboard is edited, audio first and transcript second, transcript state from the claim/complete snapshot; the transcript is not touched; source and job.json kept; `out.m4a` deleted best effort.
- R8: met - release, restore, exact notice, no automatic retry, best-effort answers and edits, a complete failure keeps SENDING, a store failure before the claim gives the generic answer.
- R9 (dev zone): met - AudioPreparerTest, AudioButtonTest, FfmpegRunnerTest, FileNamesTest and config tests check behaviour (call arguments, bytes on disk, state, messages). The IT part belongs to the Tester.
