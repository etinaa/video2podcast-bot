VERDICT: CHANGES_REQUESTED

## Findings
- [Important] docs/host-setup.md:50 (stage table, "Compress to fit Telegram ... about 2 min") - /tmp/v2p/mem2.log samples ffmpeg every 5 s: in the second run it is alive 12:52 (10 s) + 12:53 (50 s), about 1 min; in the first run 11:39 (55 s). The log gives about 1 min, not 2. Fix the number or say where 2 min comes from (e.g. includes the output check).
- [Important] docs/host-setup.md:48 (stage table, "Convert to WAV and split ... about 1 min, one core fully used") - mem2.log shows ffmpeg for only 5 s at 12:40 in the -mc 0 run (10 s at 11:28 in the first run). The 55 s sample at 11:39 is the compression, not the split. "About 1 min" is not backed by the logs; either measure it again or write what the log shows (5 to 10 s). The "one core fully used" claim has no source I could see (the CPU column of the ffmpeg samples was not checked in the doc).
- [Important] docs/host-setup.md:49 (Transcribe "about 3 min", "about 50 times faster", chunk 10 to 13 s) - there are no timestamps in /tmp/v2p/whisper.log, and mem2.log gives only whisper RSS (it rises at 12:41 and drops at 12:47 in the second run, about 4 min in the first run 11:28 to 11:32). 9 390 s in 3 min is 52x, and 16 chunks at 10 to 13 s is 2.7 to 3.5 min, so the numbers agree with each other, but the source of the 10 to 13 s is not in the logs I was given. State the source (owner's run / curl on c0.wav) or soften to "3 to 4 min".
- [Suggestion] docs/host-setup.md:50-52 - download 16 s, summary "6 and 9 min", upload "2 min" come from the owner's runs / Telegram times and cannot be checked in /tmp/v2p; fine if the orchestrator confirms the owner gave them.
- [Suggestion] docs/host-setup.md:55 - "whisper-server 0.4 to 2.1 GB": mem2.log has 385 MB (first run) up to 2 066 MB (12:44, second run); the 2 390 MB sample at 12:16 is two servers summed during the -mc experiment, so not counting it is right. Java 186 MB and ffmpeg 62 MB match. OK; "0.4" is the first-run minimum while the -mc 0 run starts at about 9 to 50 MB, so "up to 2.1 GB" would be simpler.

## Checked and correct
- `-mc 0` flag, `temperature=0.0` (WhisperClient.java:114), no `temperature_inc` anywhere in src/main.
- `LIMIT_BYTES = 50 MiB` (AudioPreparer.java:20), TARGET 48 MiB, retry at kbps*4/5 (37 -> 29), MIN 8, MAX 128.
- 42 kbps for 9 390 s (48 MiB*8/9390 = 42.8 -> 42); 51 933 907 B = 44.2 kbps = 49.5 MiB; 16 chunks (15 x 600 s + 390.3 s, whisper.log); 5% overhead and the 3-hour case (37 kbps -> about 52.6 MB > 52.43 MB, 29 kbps retry) are arithmetically right; the doc says the retry was not run on a real 3-hour video.
- Qwen `num_ctx` 8192, 100% GPU, 21 to 22 GB (ollama ps, mem2.log).
- Spotless: markdown is not part of spotless (not run; the orchestrator ran the build).

## Requirements
- R1 (docs state `-mc 0` and why): met
- R2 (measured timings and memory recorded, matching the evidence): not met - three stage timings do not match the logs (see Important)
- R3 (facts about the bot match src/main): met
