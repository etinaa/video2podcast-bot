VERDICT: APPROVED

## Findings
- [Suggestion] docs/host-setup.md:50 - "about 40 to 50 times faster than real time": 9 390 s in 3 min is 52x and in 4 min is 39x, so "about 40 to 50" is fine as rounding; "about 40 to 52" would match exactly. Not blocking.
- [Suggestion] docs/host-setup.md:53 - upload "about 2 min (11:40 to 11:42)" comes from Telegram message times, not from /tmp/v2p (bot.log/run.log have no upload lines). The row says its source, so it is acceptable.

## Round 2 findings re-checked against /tmp/v2p/mem2.log
- Compress "about 1 min": ffmpeg alive 11:38:55 to 11:39:51 (first run) and 12:52:52 to 12:53:48 (second run), about 55 s and 1 min. Fixed.
- Split "5 to 10 s": ffmpeg in one sample at 12:40:50 (second run) and two at 11:28:40 and 11:28:45 (first run). Fixed. The "one core fully used" claim is gone.
- Transcribe: whisper RSS/CPU active 12:41:15 to 12:43:47 (about 3 min, second run) and 11:29:05 to 11:32:38 (about 3.5 to 4 min, first run). Text says "3 to 4 min" with the source. Fixed. The 10 to 13 s curl figure is labelled as a curl measurement.
- Summary: end of transcription 12:43:47 to ffmpeg start 12:52:52 is about 9 min; 11:32:38 to 11:38:55 is about 6 min. Matches "6 to 9 min".
- Memory: whisper max 2 068 MB in the -mc 0 run (2 156 at 12:15, the experiment); the 2 390 sample is two servers summed and is correctly left out. Java max 186 MB, ffmpeg max 62 MB. "Up to 2.1 GB" is right.
- `-mc 0` flag, 51 933 907 B = 49.5 MiB, LIMIT_BYTES = 50 MiB, 37 -> 29 kbps retry: unchanged and correct as in round 2.

## Requirements
- R1 (docs state `-mc 0` and why): met
- R2 (measured timings and memory recorded, matching the evidence): met - every row now matches the process samples or names its source
- R3 (facts about the bot match src/main): met
