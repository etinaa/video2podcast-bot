# TD7 brief

Found at Checkpoint 4 (2026-11-22), owner's report: "смотри последний скриншот" (a Telegram screenshot: "Failed." and "Could not process this video. Please try again later." for https://www.youtube.com/watch?v=P4U7-I4dELU).

Diagnosis (orchestrator, wrappers around yt-dlp/ffmpeg, `/tmp/v2p/tools.log`): the metadata call succeeds (exit 0) but its stdout is 11,711,676 bytes, above `DefaultProcessRunner.MAX_OUTPUT_BYTES` (8 MiB); the cut JSON does not parse, `FAILED`, no log line.

Task type: technical (owner's choice). Approach: fetch only the needed fields with `--print` (owner's choice), plus a visible failure on an oversize output and logging of every `JobFailure`.

Agreed task: see TD7 in `tasks/todo.md`.
