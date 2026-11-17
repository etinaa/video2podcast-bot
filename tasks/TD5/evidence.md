# TD5 evidence

Written by the orchestrator from the Developer's report. Real `yt-dlp` 2026.10.26, `ffmpeg` 9.0.2.

Source: `ffmpeg -f lavfi -i testsrc=d=5:s=320x240:r=15 -f lavfi -i sine=d=5 -c:v libx264 -c:a aac -shortest srv/combined.mp4` (5 s, video and sound in one file), served by `python3 -m http.server 8765 --bind 127.0.0.1`.

| Selector | Command (after `yt-dlp --no-playlist --playlist-items 1 --no-warnings`) | Result |
|---|---|---|
| New: `bestaudio[ext=m4a]/bestaudio/best` | `-f ... -o "out/source.%(ext)s" -- http://127.0.0.1:8765/combined.mp4` | exit 0, "Downloading 1 format(s): mp4", `out/source.mp4` (68968 bytes) |
| Old: `bestaudio[ext=m4a]/bestaudio` | same | exit 1, `ERROR: [generic] combined: Requested format is not available` |

An earlier check by the orchestrator showed that a separate m4a file still downloads as `source.m4a` with the old selector, and the new one keeps `bestaudio[ext=m4a]` first, so a source with a separate audio track is unchanged.
