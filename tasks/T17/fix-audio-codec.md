# T17 fix: player-incompatible codec inside .m4a/.mp3

Found in the manual smoke run: YouTube gave `.m4a` with an E-AC-3 5.1 track (384 kbps, Dolby). `AudioPreparer` row 1 (T13, `tasks/T13/api.md`) sends a source unchanged by size and extension only, so Telegram received a file its player cannot play. Sample: `manual_tests/Переговоры_Как_получить_230_миллионов_за_минуту.m4a`.

## Required behaviour

- Row 1 of the decision table becomes: size <= LIMIT **and** (ext `m4a` with audio codec `aac`, or ext `mp3` with codec `mp3`) → `UNCHANGED`. The codec comes from `ffprobe` (first audio stream, `codec_name`).
- Anything else with size <= LIMIT (incl. E-AC-3/Opus/AAC-in-wrong-container inside `.m4a`, any codec in `.mp3` other than mp3) goes to row 2: `encodeM4a(..., 128, mono=false)`.
- Multichannel sources must give stereo at most: encodeM4a adds `-ac 2` when not mono (mono keeps `-ac 1`).
- If ffprobe fails or prints nothing usable: treat the codec as unknown → row 2 (never UNCHANGED).
- Oversized path (row 3) is unchanged. No change of job.json, configuration, texts or captions.
- Probing goes through the existing `ProcessRunner`/`FfmpegRunner` style (list of arguments, no shell, same timeout handling, raw stderr in the log only). No SpotBugs/PMD suppression without owner approval.

## Roles
- Developer: code + unit tests (`src/main`, `src/test`). Tester: step definitions and fixtures for the new T13 scenario outline (`@T17`, the end of `src/features/T13-audio-button.feature`, written by the orchestrator; the Tester may ask for wording changes via comms). Real ffmpeg generates the E-AC-3/Opus/ADTS fixtures (`-c:a eac3 -ac 6`, etc.).
- No Architect/Analyst (technical task).
