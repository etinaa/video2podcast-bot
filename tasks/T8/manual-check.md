# T8 manual real-media check

Date: 2026-11-17. Tools: `yt-dlp 2026.10.26`, `ffmpeg 9.0.2` (Homebrew, macOS), Java 27.0.1. Run through the real T8 classes (`DefaultProcessRunner`, `YtDlpClient`, `FfmpegRunner`, `UrlValidator`) from `target/classes` with `jshell`, the work directory outside the repository. Commit under test: `46d17b0`.

## Short real video

URL `https://www.youtube.com/watch?v=tUkD-EMW0so` (owner's test clip).

| Step | Result |
|---|---|
| `fetchMetadata` | title returned, duration `PT33S` |
| `downloadAudio` | `source.m4a` (m4a preferred), AAC 44.1 kHz stereo, 527,333 bytes, 32.554 s |
| `toWav` | `audio.wav`, `pcm_s16le`, 16000 Hz, 1 channel, 32.554 s |
| `split` | 1 chunk `chunk-000.wav`, `pcm_s16le`, 16000 Hz, mono, 32.554 s (same length as the WAV) |
| Source unchanged | SHA-256 of `source.m4a` identical before and after re-running `toWav` and `split` (`ada3c37849269c91...`) |

## Controlled inputs (16 kHz mono sine WAV, `ffmpeg` lavfi) through `split`

| Input | Chunks | Chunk durations | Total |
|---|---|---|---|
| 45 s | 1 | 45.0 | 45.0 |
| 59 s | 1 | 59.0 | 59.0 |
| 600 s | 1 (no empty trailing chunk) | 600.0 | 600.0 |
| 1250 s | 3 | 600.0, 600.0, 50.0 | 1250.0 |

All chunks are `pcm_s16le`, 16000 Hz, 1 channel, named `chunk-000.wav` ... in playback order. Coverage is complete: the totals equal the input lengths, so nothing was dropped or duplicated.

## Failure mapping against real `yt-dlp` stderr

| Input | Kind | Message |
|---|---|---|
| `https://example.com/` | `UNSUPPORTED` | This link is not a supported video. |
| `https://www.youtube.com/watch?v=aaaaaaaaaaa` (no such video) | `FAILED` | Could not process this video. Please try again later. |
| `ftp://example.com/x` | `UNSUPPORTED` (before any process) | This link is not a supported video. |
| `http://127.0.0.1/x` | `UNSUPPORTED` (before any process) | This link is not a supported video. |
| Real 33 s video with a 10 s limit | `TOO_LONG` | The video is longer than 0 minutes. |

## Not covered

- A video of about 3 hours and the real timeout/kill path with `yt-dlp`: T17.
- A real private or age-restricted video: no sample was available, so `PRIVATE` is covered by the stderr table in unit tests only.

## Findings

- The `TOO_LONG` message formats the limit in whole hours and minutes, so a limit below one minute reads "0 minutes". Not reachable with the default `PT3H` or any realistic setting; noted here only.
