# TD7 developer evidence (for the orchestrator to merge into evidence.md)

yt-dlp 2026.10.26, run 2026-11-22. Base options in every command: `--no-playlist --playlist-items 1 --no-warnings --skip-download`.

## Correction

The first evidence said a playlist link behaves "as before". That was wrong: `--print` shows the selected entry, not the playlist object, so `_type` is not `playlist` any more. The review found it.

## Final command

```
yt-dlp <base options> --print '%(.{title,duration,is_live,_type,playlist_index})j' -- <url>
```

`playlist_index` is set on an entry of a playlist-only link and absent on a single video. `parse` returns UNSUPPORTED when it is present (even `null`) or when `_type` is `playlist`/`multi_video`.

## Results

Playlist-only URL `https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI`:
```
{"title": "BELLAKEO (Video Oficial) - Peso Pluma, Anitta", "duration": 234, "is_live": false, "playlist_id": "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", "playlist_index": 1, "n_entries": 1}
```
(that run also printed playlist_id and n_entries; only `playlist_index` is needed) -> UNSUPPORTED.

`watch?v=P4U7-I4dELU&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI` (no playlist_index, one video, as before the change):
```
{"title": "\"Player has to be expelled!\" - Nepo on Magnus not playing Rapid against Artemiev", "duration": 156, "is_live": false}
```
-> accepted.

Single video `https://www.youtube.com/watch?v=jNQXAC9IVRw`:
```
{"title": "Me at the zoo", "duration": 19, "is_live": false}
```
-> accepted.

Also checked, the `playlist:` stage print shows the collection object for the playlist-only URL: `{"_type": "playlist", "id": "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"}`. It needs a second output line, so I did not use it.

Earlier results still hold: 156 s video, old `--dump-single-json` 11,717,291 bytes, new output 131 bytes (61 bytes for the 19 s video).

## Review fixes
- Important: collection check by `playlist_index` (+ `_type` kept as second guard); unit tests `anEntryOfAPlaylistOnlyLinkIsUnsupported`, `aNullPlaylistIndexCountsAsACollectionEntryToo`, command shape updated.
- Suggestion: the ERROR log in `UrlMessageHandler.runJob` is removed (PipelineService already logs the stack trace; `finish` logs the WARN once).
- Suggestion: `capture()`/`release()` moved to the end of `DefaultProcessRunnerTest`.
- Tester's `MetadataProjectionIT` still expects the old `--print` string; it must use `%(.{title,duration,is_live,_type,playlist_index})j`.
