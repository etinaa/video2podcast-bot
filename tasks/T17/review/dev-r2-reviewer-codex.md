VERDICT: CHANGES_REQUESTED

## Findings
- [Important] docs/host-setup.md:54 - The recorded ffmpeg stage times do not match the cited five-second process samples. In the second run, `/tmp/v2p/mem2.log` has no ffmpeg at 12:40:45, has it at 12:40:50, and has already entered transcription at 12:40:55: WAV conversion/splitting therefore took roughly 5–10 seconds, not about one minute. Compression (line 57) appears from 12:52:52 through 12:53:48, absent at 12:52:47 and 12:53:53: roughly one minute, not two. The first run independently shows the same pattern (11:28:40–11:28:45 and 11:38:55–11:39:51). Correct these rows from the measured intervals, or identify separate timing evidence and explain the different boundaries. Accurate stage measurements are the central deliverable of this documentation patch.

## Requirements
Requirement numbers below describe the documentation scope of round 2; T17 has no separate requirements or Architect documents.

- R1: met - The launch command documents `-mc 0` and the observed repetition fix. Installed `whisper-server --help` confirms the max-context flag; the retained second transcript removes the quoted looping phrase. `WhisperClient.body` sends `temperature=0.0` and does not send `temperature_inc`.
- R2: not met - Stage timings and memory are recorded, but WAV preparation and compression timings contradict the supplied process samples; see the finding. Approximately three minutes of transcription and six/nine minutes of summary are consistent with the run windows. Java 186 MiB and ffmpeg 62 MiB peaks support the rounded documented values; ordinary transcription RSS reaches about 389/2068 MiB across the two runs. Ollama's model allocation is explicitly sourced to `ollama ps`.
- R3: met - Both retained long-run M4A files are exactly 51,933,907 bytes, above 50,000,000 and below 52,428,800; playback/Telegram acceptance are owner-run observations. The calculated real bitrate is 44.246 kbps and size is 49.528 MiB. `AudioPreparer` uses 50 MiB, computes 37 kbps for 10,800 seconds and retries at 29 kbps; its unit test covers that retry. The docs explicitly disclose that a real three-hour retry was not exercised.
- R4: met - The docs record unchanged chunk/context/keep-alive settings and no model downgrade, and distinguish the short incompatible-codec conversion from unchanged AAC delivery. This patch does not claim that all 15 SPEC criteria have been checked off; final T17 acceptance remains outside this docs-only review.

## Review evidence
- Reviewed the fixed `dev-r2.patch` and the complete touched document. The document hash `6cf576f04899594bf000c2e4d9b298347b848b1a` matches the patch post-image, so the inspected full file represents the submitted patch.
- Read my round-1 report: it was APPROVED with no findings, so no prior corrections remain to recheck. This round changes no production code or tests.
- Checked `/tmp/v2p/run.log`, `mem2.log`, the sampling script, Whisper logs/experiment script, retained manual-run artifacts, relevant production code, configuration, retry unit test and D15. Telegram playback/message timing and detailed experiment outcomes remain owner-supplied evidence rather than independently repeated observations.
- Reviewed correctness, readability, architecture, security and performance. Supplied check: Spotless clean. `git diff --check -- docs/host-setup.md` passed. No Maven command, source mutation, index change or other reviewer's report was used.
