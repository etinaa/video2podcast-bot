VERDICT: APPROVED

## Findings
- [Suggestion] docs/host-setup.md (peak memory paragraph, 2 h 36 min run) - "whisper-server up to 2.1 GB" is not what the logs give: the highest sample in /tmp/v2p/mem2.log is 2390 MB (12:16:52, 2.3 GiB) and in mem.log 1914 MB. The figure was there before this patch, so it is not a blocker; use "up to 2.3 GB" if the run behind it is the second one. The same sentence says "ffmpeg 62 MB" and "Java bot 186 MiB": both match (62, 186). Units are mixed (MB / MiB); `ps` RSS/1024 is MiB.
- [Suggestion] docs/host-setup.md (stages paragraph) - the "upload about 1 min (to the Telegram message time, 13:49)" and the download estimate are not in the logs I can check (they come from the Telegram message time); the text already says so. Fine.

## Round 4 findings
- 3 hour retry sentence vs the new row: fixed, both now say the 29 kbps retry ran on a real 2 h 58 min video, ended at 40 222 038 bytes, and the size of the first attempt was not recorded.
- Java bot memory: fixed, 186 MiB (2 h 36 min) and 673 MiB (2 h 58 min), matching mem2.log (186 before 12:30, 673 after 13:24).
- Compression about 3 min: fixed (ffmpeg samples 13:44:47 to 13:47:34, first run to 13:45:58, second 13:46:03 on).
- Download/WAV/split: now marked as not sampled and estimated. Fixed.
- "Telegram accepted it": now "the owner received it in Telegram", a statement of who confirmed it. Fixed.

## Verified against the logs
- 40 222 038 bytes, bit_rate 30 072 (30.1 kbps), duration 10 700.01 s: match manual_tests/*3h.m4a.
- 30 855 words (awk) in the 3h .txt; "I thought it was going to be a new house" appears 7 times.
- TOO_LONG at 13:24:26 in bot.log (line 57), before any download; the doc says log line at 13:24.
- Transcribe 13:29:38 to 13:35:47 (whisper-server CPU), about 6 min; summary about 9 min to 13:44:47; whisper-server peak 325 MiB, ffmpeg 68 MiB, in the 13:24+ window.
- The text now agrees with itself: the sentence "this retry is covered by tests but was not run on a real 3-hour video" is gone, and the memory paragraph is split per run.

## Requirements
- Docs-only patch; no T17 code requirement affected. Numbers in the new text match the logs.
