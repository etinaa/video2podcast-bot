VERDICT: CHANGES_REQUESTED

## Findings
- [Important] docs/host-setup.md:24 (context line after the patch) - still says the 29 kbps retry "was not run on a real 3-hour video", but the new row on line 48 says it was run (37 -> 29 kbps, 40 222 038 bytes). The document now contradicts itself; update the sentence.
- [Important] docs/host-setup.md:22 (context line) - "Java bot 190 MB" peak memory is left as is, while the patch adds 672 MB for the 2 h 58 min run. Update the line to give both figures (186 vs 672 MB, 673 MB is the highest sample in /tmp/v2p/mem2.log).
- [Suggestion] docs/host-setup.md (stages paragraph) - compression is "about 4 min", but the two ffmpeg runs in mem2.log span 13:44:47 to about 13:47:40, i.e. about 3 min. Together with the upload (file at 13:49:08) it is about 4 min; say "about 3 min". The "download, WAV and split about 1 min" has no sample behind it (the first ffmpeg sample is 13:29:13, the job start is not in bot.log); say so or drop the figure.
- [Suggestion] docs/host-setup.md:48 - "first attempt at 37 kbps was over the limit" is inferred from the two ffmpeg runs (13:44:47-13:45:58 and 13:46:03-13:47:40) and from the code (48 MiB target gives 37 kbps, retry is 37*4/5 = 29). The size of the first file is not recorded, and by the 5% rule of thumb 37 kbps over 10 700 s gives about 52.0 MB, below 50 MiB (52.43 MB). Say the size was not recorded. Also "Telegram accepted it, sent without a size error" has no evidence in bot.log (it holds only the TOO_LONG line); state who confirmed it.
- [Suggestion] docs/host-setup.md:46 peak 672 MB: the highest sample is 673 MB.

## Verified
- Word count 30 855 (awk field count; `wc -w` gives 30 863) and "new house" appears 14 times, 7 of them as the full sentence: the Language paragraph holds.
- 40 222 038 bytes, 30 072 bit/s (30.07 kbps), 10 700.01 s: match the file and ffprobe.
- TOO_LONG at 13:24:26 in bot.log, before any download.
- whisper-server peak 325 MB, transcription 13:29:38-13:35:47 (about 6 min), summary to about 13:44:47 (about 9 min).
- Language is fixed to "en" in WhisperClient.java:113.

## Requirements
- Docs-only patch; no code or requirement of T17 is affected. Numbers must match the logs: not all do (see findings).
