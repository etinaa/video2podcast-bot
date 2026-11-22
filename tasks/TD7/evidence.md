# TD7 evidence

Written by the orchestrator from the Developer's report (the Developer's write to this path was denied by a permission rule). yt-dlp 2026.10.26, run by hand on 2026-11-22.

## The failure

`yt-dlp --no-playlist --playlist-items 1 --no-warnings --skip-download --dump-single-json -- https://www.youtube.com/watch?v=P4U7-I4dELU` prints 11,711,676 bytes (the Developer measured 11,717,291 on a second run). `DefaultProcessRunner.MAX_OUTPUT_BYTES` is 8 MiB (8,388,608), so the JSON was cut and did not parse. The user saw "Could not process this video." and the log had no line about it. A wrapper around `yt-dlp` and `ffmpeg` (`/tmp/v2p/tools.log`) showed the metadata call exiting 0 and no download call after it.

## The new command, by hand

`--print '%(.{title,duration,is_live,_type})j'` replaces `--dump-single-json`.

| Video | Old `--dump-single-json` | New `--print` |
|---|---|---|
| `P4U7-I4dELU` (156 s) | 11,717,291 bytes | 131 bytes: `{"title": "...", "duration": 156, "is_live": false}` |
| `jNQXAC9IVRw` (19 s) | not run | 61 bytes: `{"title": "Me at the zoo", "duration": 19, "is_live": false}` |

Both commands exited 0 with an empty stderr (3.7 s and 2.8 s). A playlist link with `--no-playlist --playlist-items 1` prints the first video's fields with no `_type` (this is NOT "as before"; see the correction below). Not checked by hand: a live stream and a private video (covered by the existing T8 and T11 scenarios).

## Tests

- Developer: unit tests for the command shape, for a case where the old full dump would be over 8 MiB, for the runner failing visibly on a stdout over the limit, and for the failure log line (redaction, second line dropped, 200-character cut, blank message, no cause).
- Tester: `MetadataProjectionIT` (Telegram, real yt-dlp and ffmpeg against a local WireMock media proxy; the full metadata of the fixture is 9,440,086 bytes and the bot still delivers the summary). The 76 existing T8 scenarios and three T11 title scenarios pass (`tasks/TD7/comms/tester-report.md`).

## Correction after review round 1

Both reviewers found that `--print` shows the selected entry, not the playlist object, so a playlist-only URL lost its `_type` and would have been processed instead of rejected (the T8 playlist scenario passed only because its mock returned the old full-JSON shape). The final command is `--print '%(.{title,duration,is_live,_type,playlist_index})j'`; `parse` returns UNSUPPORTED when `playlist_index` is present (even `null`) or `_type` is `playlist`/`multi_video`.

Results by hand (yt-dlp 2026.10.26, base options `--no-playlist --playlist-items 1 --no-warnings --skip-download`):

- Playlist-only URL `https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI`: `{"title": "BELLAKEO (Video Oficial) - Peso Pluma, Anitta", "duration": 234, "is_live": false, "playlist_id": "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", "playlist_index": 1, "n_entries": 1}` -> UNSUPPORTED.
- `watch?v=P4U7-I4dELU&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI` (no `playlist_index`): `{"title": "...", "duration": 156, "is_live": false}` -> accepted.
- Single video `jNQXAC9IVRw`: `{"title": "Me at the zoo", "duration": 19, "is_live": false}` -> accepted.
- The `playlist:` stage print would show the collection object (`{"_type": "playlist", ...}`) but needs a second output line, so it was not used.
- Tester: `MetadataProjectionIT.playlistOnlyLinkIsRejectedBeforeProcessingItsFirstVideo` serves a two-track XSPF playlist from WireMock to the real yt-dlp (output `{"title":"First talk","duration":59.0,"playlist_index":1}`) and asserts the UNSUPPORTED reply with no download, Whisper, Ollama, summary or upload.
