# T17 codec regression — tester report

Implemented the @T17 scenario outline in src/it using real ffmpeg and ffprobe:
- E-AC-3 5.1 in MP4 named source.m4a.
- Opus in MP4 named source.m4a.
- AAC in ADTS named source.mp3.

Setup verifies the actual codec, container, channel count, 60-second duration and size below 1,000,000 bytes. ADTS duration uses counted AAC-LC frames because its format duration is a bitrate estimate. Existing assertions verify the uploaded AAC-only M4A, conversion caption, source preservation, output cleanup and SENT state; the new channel assertion requires one or two channels.

Updated the existing conversion assertions and overflow fault fixture for explicit stereo (-ac 2). Updated the retention process fixture to allow real ffprobe after codec detection was introduced.

Verification (2026-11-22):
- Scoped ./mvnw spotless:apply: passed for the four changed src/it files.
- Exact tester-role full verify command: BUILD SUCCESS, 7:09, finished 22:51:03 +02:00. Log: target/tester-verify.log.
- 862 unit tests passed; 387/387 Cucumber scenarios passed, including all three @T17 examples. Failsafe reports five skipped framework/container entries, with no scenario skips and no failures/errors.
- JaCoCo coverage gates, Spotless, SpotBugs/FindSecBugs and PMD passed.
- git diff --check passed.
- No clean command was run. No production, unit-test, feature, index or branch changes made by the tester.

Earlier runs: one packaging archive error (preserved in target/tester-verify-first-attempt.log); another run exposed the fixed ADTS-duration and retention dispatcher issues and a transcription answer-wait timeout (target/tester-verify-second-attempt.log). The transcription timeout did not recur in the successful full run.

STATUS: DONE
