# T8 requirements — media preparation

Status: analyst draft for team design review. Date: 2026-11-16.

## Objective and authority

Prepare one supported video's source audio and ordered Whisper-compatible WAV chunks. The consumers are T9 (transcription) and T11 (pipeline). Authority: `SPEC.md`, T8 in `tasks/todo.md`, and the owner answers in `tasks/T8/brief.md`; the brief is the agreed T8 scope. T8 depends only on T1. Existing architectural contracts are in `api.md`; implementation details there do not override the brief.

T8 adds no Telegram handler or functional `*IT` test. Gherkin describes observable process arguments, results, failures and files for later acceptance automation; it does not imply that Cucumber wiring must ship in T8. Source filenames in scenarios follow the architect's current contract.

## Requirements and acceptance criteria

### R1 — URL structure and length

Accept only parseable HTTP or HTTPS URLs with a host, at most 2048 characters, no whitespace or control characters and no userinfo (including credentials). Validate before both metadata lookup and download; preserve an accepted URL rather than interpreting it as a command.

Acceptance:
- A normal HTTPS video link, an HTTP link, and a link with query parameters pass unchanged. Scheme comparison is case-insensitive.
- Exactly 2048 characters passes; 2049 fails. Null, empty, blank, relative, missing-host and non-HTTP(S) inputs fail.
- Spaces, tabs, CR/LF and control characters anywhere fail; userinfo fails even with an empty password.
- Invalid input gives `JobFailure(UNSUPPORTED)` with a safe message and starts no process.

### R2 — Reject local literal destinations without DNS

Reject localhost and literal loopback, private and link-local IPv4/IPv6 destinations. Do not resolve DNS names during validation.

Acceptance:
- Reject case-insensitive `localhost`; the architectural guardrail also rejects `.localhost` subdomains and a trailing dot.
- Reject 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 `::1`, `fc00::/7`, `fe80::/10`, and private/loopback IPv4-mapped IPv6 literals.
- Public literals such as `8.8.8.8` and `[2606:4700:4700::1111]` pass.
- A syntactically valid DNS host passes without a resolver lookup, even if its eventual address could be private. This is the agreed validation limit, not complete SSRF protection against DNS or redirects.
- Architecture additionally rejects unspecified addresses, shared 100.64/10 and ambiguous numeric IPv4 forms; these are documented guardrails in `api.md`.

### R3 — Safe process invocation and output capture

All external commands use `ProcessRunner.run(List<String> args, Duration timeout)` with a real implementation executing an argument list, without shell interpolation. Capture stdout and stderr separately and return the exit code; nonzero exits remain available to the media-specific classifier.

Acceptance:
- Query ampersands, semicolons, dollar signs and spaces in a filesystem path remain data in their own arguments; no shell side effect occurs.
- Every yt-dlp URL is a single final argument immediately after `--`.
- Both output streams are drained without deadlock, including when both exceed pipe-buffer capacity. The architecture's bounded capture must continue draining discarded output.
- A failed start becomes `JobFailure(FAILED)` with a safe message. Interruption stops the process, preserves the interrupt flag and reports FAILED, per `api.md`.

### R4 — Configurable deadlines and process-tree termination

Provide metadata, download and ffmpeg deadlines under the existing jobs configuration (`app.jobs` in YAML). Defaults are PT1M, PT30M and PT30M respectively. Architecture names: `metadata-timeout`, `download-timeout`, `ffmpeg-timeout`; env overrides `METADATA_TIMEOUT`, `DOWNLOAD_TIMEOUT`, `FFMPEG_TIMEOUT`.

Acceptance:
- Metadata uses its deadline, audio download uses its deadline, and both ffmpeg operations use the ffmpeg deadline. Overrides reach the actual invocation.
- When a deadline expires, terminate the process and its children and fail with `JobFailure(TIMEOUT)`. Do not leave a running child or wait indefinitely for output-reader completion.
- A timeout remains TIMEOUT through the media wrapper; it is not reclassified by stderr or converted to success.
- Nonpositive deadlines are rejected before starting a process (architectural argument contract).

### R5 — Metadata before download and duration limit

Obtain title and duration from yt-dlp without downloading media. Enforce `jobs.maxVideoDuration` (environment `MAX_VIDEO_DURATION`, default PT3H) before the caller may download.

Acceptance:
- Metadata for title `Example talk`, duration 1250 seconds, yields that title and duration; no audio download occurs during lookup.
- Duration 10800 seconds passes with the default; 10800.1 and 10801 fail as TOO_LONG, with no subsequent download. Do not round a value down across the boundary.
- A configured PT2H limit accepts 7200 and rejects 7201 seconds; the safe explanation reflects that configured limit.
- Missing/null duration and live streams are UNSUPPORTED. The architecture also rejects nonnumeric/nonpositive duration; malformed JSON or missing/blank title is FAILED.
- Metadata failure prevents subsequent media preparation. T11 owns sequencing the separate metadata and download calls.

### R6 — Download one audio source with m4a preference

Download with selector `bestaudio[ext=m4a]/bestaudio` into the supplied per-job directory; return the actual downloaded file for reuse by T9 and T13. Select a single video, never expand a collection into multiple jobs/downloads.

Acceptance:
- Prefer m4a when available; fallback audio (for example WebM) is valid and is not re-encoded merely for Telegram.
- Use a fixed output template within the job directory (`source.%(ext)s` in the draft contract), not a title-derived path.
- A video link with playlist context processes only that video. Playlist-only/collection metadata is not accepted as one video's metadata; no collection audio download starts.
- Exit zero without a completed source file is FAILED; `.part`/`.ytdl` artifacts are not a completed source.

### R7 — Whisper WAV conversion

Convert the source to a temporary 16 kHz, mono PCM WAV under the same job directory (`audio.wav`, PCM signed 16-bit per the architecture). Keep the downloaded source for later audio delivery.

Acceptance:
- ffmpeg receives the source and output paths as distinct arguments and requests 16000 Hz, one channel, audio without video, PCM WAV.
- On success the WAV exists and the source remains unchanged.
- Nonzero ffmpeg exit or missing expected output is FAILED; deadline expiry is TIMEOUT.
- T8 performs no AAC Telegram preparation, size-driven compression or upload.

### R8 — Ordered chunks around ten minutes

Split the WAV at approximately 600-second intervals into WAV files in the job directory. Return every produced chunk in playback order, including a shorter final chunk. Keep 16 kHz mono audio throughout.

Acceptance:
- A 1250-second recording produces approximately 600, 600 and 50 seconds of audio in order; there is no dropped or duplicated interval.
- A 45-second recording produces one chunk; an exact 600-second input has no empty trailing chunk.
- Command construction uses a 600-second segment target. File enumeration order cannot change playback order (`chunk-000.wav`, `chunk-001.wav`, ... in the draft).
- Nonzero split exit, or exit zero with no chunks, is FAILED. Stale chunks from a previous attempt are not returned as new output.
- Packet-boundary timing is allowed: tests must not demand sample-exact 600-second lengths from the segment muxer.

### R9 — Central yt-dlp stderr classification

Use one case-insensitive substring table for all nonzero yt-dlp exits, including metadata and download.

| Priority | Substrings (any match) | Kind |
|---|---|---|
| 1 | `private`, `sign in`, `members-only`, `age` | PRIVATE |
| 2 | `unsupported url` | UNSUPPORTED |
| 3 | no match, including empty stderr | FAILED |

Acceptance:
- Mixed case matches the same row. A message matching PRIVATE and UNSUPPORTED is PRIVATE.
- The kind does not depend on whether the failure occurred during lookup or download.
- Match only on a nonzero exit; warning text on a successful metadata response is not itself a failure.
- The table above preserves the literal substrings approved in todo/brief. The architect's initial narrower table needs alignment or an explicit owner decision; broad `age` can match unrelated words, a known tradeoff recorded in research.

### R10 — Typed, safe failures

Expose `JobFailure` carrying one of PRIVATE, UNSUPPORTED, TOO_LONG, FAILED, TIMEOUT and an English message suitable for future chat delivery. Raw stderr and stack traces are diagnostic log content only.

Acceptance:
- Each kind has a clear explanation (private/sign-in, unsupported/live, duration limit, processing failure, or timeout).
- Messages contain no raw stderr, stack trace, executable details, local path or input URL. Metadata/title text is not interpolated into failure messages.
- Log nonzero tool stderr for diagnosis, with bounded output per the architectural contract; no Telegram call is made in T8.
- Current safe-message strings in `api.md` meet this requirement; exact wording is an API choice, not newly mandated product copy.

## Verification and handoff

- Developer: JUnit tests with a fake ProcessRunner for validation, metadata, download selection, error mapping and ffmpeg command/file handling. For R7/R8, assert the requested conversion/segmentation arguments, returned fixture paths in order, stale-file handling, and FAILED/TIMEOUT branches. A stub may create fixture files to exercise collection and missing-output handling; this proves wrapper behavior only. It does not prove that ffmpeg created valid audio, preserved source bytes, or covered the recording without gaps/duplication.
- Developer: controlled local helper-process tests (no installed media binaries) prove real ProcessRunner pipe draining, argument preservation and child termination; a runner stub cannot prove those behaviors.
- Task's targeted command from the repository root: `./mvnw test -Dtest='UrlValidatorTest,YtDlpClientTest,ChunkSplitterTest'`. Include additional process/ffmpeg tests when named by the implementation.
- Project gate before commit: `./mvnw clean verify`, including at least 90% line and branch coverage, Spotless, SpotBugs and PMD. No automated test needs internet, Telegram secrets or installed media binaries.
- Manual real-media check: download a short supported real video in the dev profile. With actual yt-dlp/ffmpeg installed, verify the WAV and each chunk are 16000 Hz mono PCM, compare source hashes before/after conversion/splitting, and inspect duration/order and full recording coverage. Also use controlled 45-, 600- and 1250-second audio inputs to check one short chunk, no empty trailing chunk at the boundary, and the approximately 600/600/50-second remainder case. Compare reconstructed chunk audio with the WAV to check no dropped/duplicated audio, allowing packet-boundary timing. Record commands, tool versions and results; this analyst step does not claim these checks ran.
- Scenarios tagged `@manual_media` specify these real-tool output obligations. They are manual checks in T8 and candidates for later real-media acceptance automation; stub-created WAVs must never be reported as satisfying them. `@stubbed_media` scenarios verify command/fixture handling only. Neither tag introduces T8 functional `*IT` tests, an installed-media-binary CI dependency or a runtime audio-verification feature.
- Tester: no new `*IT` or Cucumber runner obligation in T8, per brief. Reuse these scenarios when T11 integrates the pipeline.
- Design review notes are appended to `comms/questions.md` in A: Q-architect-1. Resolve classification alignment and playlist-only handling before design approval.

## Out of scope

AudioPreparer, AAC delivery encoding and the 50 MB rule (T13); Whisper requests, transcript merging and temporary-WAV deletion after transcription (T9); Ollama (T10); Telegram acknowledgements/progress/messages, queue integration, job.json updates and pipeline failure-directory cleanup (T11); delivery buttons (T12/T13); retention/restart sweeping (T14); container packaging (T15); playlist expansion, subscriptions, non-English transcription and DNS/redirect-based network filtering beyond the agreed literal-host validation policy.
