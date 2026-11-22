VERDICT: APPROVED

## Round 1 findings
- [Important] playlist-only URL lost `_type`: fixed. `PRINT_FIELDS` now includes `playlist_index`; `YtDlpClient.parse` returns UNSUPPORTED when `playlist_index` is present (a JSON null is not a missing node, so it counts too) or `_type` is `playlist`/`multi_video`. Evidence.md records real yt-dlp output for a playlist-only URL (index 1 -> UNSUPPORTED), `watch?v=..&list=..` and a single video (no index -> accepted). Unit tests cover index 1 and null index, and the command shape. The Tester's `MetadataProjectionIT.playlistOnlyLinkIsRejectedBeforeProcessingItsFirstVideo` uses real yt-dlp output.
- [Suggestion] double logging of unexpected RuntimeException: fixed. The handler's ERROR log is gone; `PipelineService` logs the stack trace and `finish` logs the single WARN line. The new comment says so.
- [Suggestion] test helpers between methods: fixed. `capture()`/`release()` are now at the end of `DefaultProcessRunnerTest`.

## Findings
- [Suggestion] src/main/java/com/video2podcast/bot/UrlMessageHandler.java:131 - a RuntimeException thrown by something other than `PipelineService.run` (for example a mock or a future change inside the `run` call path) would now leave no stack trace; only the WARN line with the class and a short message remains. Acceptable today, because `PipelineService` logs every RuntimeException it sees.

## Requirements
- R1: met - `--print` with title, duration, is_live, _type, playlist_index; collections, live, duration, title and TOO_LONG checks hold; the over-8-MiB full dump case is tested.
- R2: met - stdout over the limit fails with FAILED and a WARN with the command name and limit; exact-cap stdout is kept; an over-cap stderr is cut without failing.
- R3: met - one WARN per ending JobFailure in `finish` with job id, kind and a redacted, cut, single-line cause; blank and no-cause tested.
- R4: met - unit tests for the command shape, oversize runner output, playlist entries and the failure log line.
