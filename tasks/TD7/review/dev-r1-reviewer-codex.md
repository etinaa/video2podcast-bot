VERDICT: CHANGES_REQUESTED

## Findings
- [Important] src/main/java/com/video2podcast/media/YtDlpClient.java:57 - The default `--print` stage prints each selected video's object, rather than the top-level collection object returned by `--dump-single-json`. For a playlist-only URL, `--no-playlist` does not prevent collection extraction and `--playlist-items 1` selects its first entry; the projection then contains that video's title/duration and no collection `_type`. Consequently `parse` accepts it and the pipeline downloads/processes the first video instead of rejecting the collection as UNSUPPORTED. This violates TD7's explicit requirement to preserve collection checks and T8 R6. Preserve collection identity in the bounded metadata output (or explicitly reject collections before accepting an entry), and add a regression exercising real yt-dlp collection output; the current mocked collection JSON tests cannot catch this change. The evidence statement that this is “as before” needs correction: the previous full dump retained the top-level collection type.

## Requirements
TD7 has no requirements.md or task-specific architecture artifacts (technical task); R1–R4 below correspond to its four acceptance bullets in tasks/todo.md.

- R1: not met - Field projection and manual evidence for both public videos are present, but collection rejection is lost. Live, duration, title and TOO_LONG parsing remains unchanged.
- R2: met - Captured stdout above 8 MiB throws FAILED with a command/limit WARN instead of returning truncated text; exact-limit and overflow unit tests cover the boundary. Stderr remains bounded and drained.
- R3: met - Terminal pipeline failures reach one WARN in the reporting handler with job id, kind and a bounded, sanitized first cause-message line; tests cover once-only logging, redaction, long/blank messages and absent causes.
- R4: not met - The requested unit tests exist, but “behaviour for every other input is unchanged” fails for collections. Existing collection mocks supply the old top-level shape and hide the regression.

## Review evidence
- Reviewed tasks/TD7/review/dev-r1.patch and all six touched files in full; their git object hashes match the patch's post-image hashes. Checked inherited T8/T11 contracts, features and callers. No source changes or Maven invocation.
- Accepted the orchestrator's supplied spotless:check and verify -Dno-it results (801 unit tests, BUILD SUCCESS). Full clean verification, mutation gate and owner checkpoint remain task-level verification outside this review.
- Offline probe with installed yt-dlp 2026.10.26: passed a synthetic playlist containing one known-format video to YoutubeDL.process_ie_result with noplaylist=True, playlist_items="1", skip_download=True, simulate=True and the new video-stage forceprint template. It printed `{"title": "Talk", "duration": 5}` while the returned top-level result retained `_type=playlist` (the full-dump shape). No network or files were needed. This confirms the command-stage regression independently of mocked application metadata.
- Did not perform mutation testing: reviewer.md prohibits editing source or running ./mvnw.
