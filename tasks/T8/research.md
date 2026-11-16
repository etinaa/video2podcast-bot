# T8 research

Research date: 2026-11-16. Sources below were read for this analyst draft. No real download, ffmpeg conversion or process-tree experiment was run; documentation establishes an approach, not runtime proof.

## Primary sources

| Source | Fact taken from it | Consequence for T8 | Accessed |
|---|---|---|---|
| [yt-dlp official README](https://github.com/yt-dlp/yt-dlp#usage-and-options) | `--dump-json` emits information per video without media download by default. `--no-playlist` selects the video when a URL refers to both a video and a playlist. Audio selection supports extension filtering and slash-separated fallbacks. | Use metadata first and the agreed `bestaudio[ext=m4a]/bestaudio` selector. A playlist-only URL needs an additional collection check; `--no-playlist` alone is insufficient evidence of a single-video guarantee. | 2026-11-16 |
| [FFmpeg audio options](https://ffmpeg.org/ffmpeg.html#Audio-Options) | `-ar` controls sampling frequency, `-ac` channel count; `-vn` excludes video and the audio codec can be selected. | Request 16000 Hz, one channel, PCM s16le for the temporary WAV. The source download remains available for later delivery. | 2026-11-16 |
| [FFmpeg segment muxer](https://ffmpeg.org/ffmpeg-formats.html#segment) | `segment_time` sets the target duration. Segmentation occurs at packet/keyframe boundaries, so requested times are not an exact cutting guarantee. Numbered output patterns are supported. | Use 600-second targets, preserve chronological order and retain a shorter final segment. Avoid acceptance criteria requiring every chunk to be exactly 600 seconds. | 2026-11-16 |
| [Java ProcessBuilder](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html) | The builder takes a command list and starts a subprocess. | Pass executable and each argument separately. Shell quoting is not required or appropriate around individual list elements. This does not itself prevent a tool interpreting an argument as an option: retain `--` before the URL. | 2026-11-16 |
| [Java Process](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Process.html) | Undrained process streams can block execution. Timed waits and explicit destruction are available; `descendants()` returns a snapshot, not a continuously updated process tree. | Drain stdout/stderr concurrently and test termination with a real controlled child process. Do not assume killing the parent stops children, or that one descendant snapshot eliminates every spawn race. | 2026-11-16 |
| [Java InetAddress](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html) | Provides address classification and name/address lookup methods. Site-local and link-local classification are separate operations. | Apply literal-address policy without resolving DNS hosts. Explicitly include IPv6 unique-local `fc00::/7`; do not rely solely on a site-local predicate to implement the agreed private-address rule. | 2026-11-16 |

Java links above are SE 25 reference documentation for established APIs, not evidence that the complete implementation has been verified on the project's Java 27 runtime. Compilation and process tests on Java 27 remain implementation checks.

## Repository evidence

| Source | Finding | Read |
|---|---|---|
| [`brief.md`](brief.md) and [`todo.md`](../todo.md) T8 | Owner agreed deadlines, validation boundaries, no DNS resolution, typed failures, missing-duration/live rejection, T13 exclusion, T1-only dependency and no T8 functional `*IT` tests. | 2026-11-16 |
| [`SPEC.md`](../../SPEC.md) | Default maximum PT3H, mono 16 kHz WAV, roughly ten-minute chunks, one-video scope, no shell-built commands and no raw diagnostics in chat. Pipeline cleanup and Telegram responses belong to later integration. | 2026-11-16 |
| [`plan.md`](../plan.md) | Media becomes input to transcription and the pipeline. Its older T3 dependency arrow and one-commit description lag the newer task brief/team rules; the current brief and CLAUDE workflow govern T8. | 2026-11-16 |
| [`api.md`](api.md), [`flows.md`](flows.md), [`data-flow.md`](data-flow.md), [`components.md`](components.md) | Architecture supplies filenames, exact command lists, safe copy, metadata parsing and output limits. The analyst responded to Q-architect-1 with mismatches and edge cases. | 2026-11-16 |
| [`AppProperties.java`](../../src/main/java/com/video2podcast/config/AppProperties.java) and [`application.yml`](../../src/main/resources/application.yml) | Configuration currently lives at `app.jobs`; max duration and retention already exist. Three process deadlines are an additive T8 change. | 2026-11-16 |
| [`whoami.feature`](../../src/features/whoami.feature) | Existing scenarios use English Given/When/Then and observable outcomes. No existing media step vocabulary is available to reuse. | 2026-11-16 |

## Conclusions and review notes

- The brief's literal stderr substring table is the product authority: `private`, `sign in`, `members-only`, `age` → PRIVATE; `unsupported URL` → UNSUPPORTED; other nonzero exits → FAILED. The initial architecture narrows matching. Broad `age` can accidentally match unrelated errors (for example “page”); changing the agreed table requires a product decision through the orchestrator. Scenarios currently preserve the agreement.
- DNS is deliberately not resolved during validation. Redirects, downstream extractor requests and DNS rebinding are not prevented by this validation contract. Do not describe T8 as complete network isolation.
- Exact 10800-second acceptance and fractional values above it need explicit boundary tests. The architecture's upward rounding is compatible with the default whole-second limit; no rounding may allow an over-limit video.
- Architectural output caps must keep draining after the cap. A terminated parent can leave output streams open in a child; deadline tests must catch indefinite reader waits.
- Process-tree termination order differs between the initial API and flow documents. The observable contract is termination plus TIMEOUT; the architect must align the algorithm and bound cleanup waits.
- Source output, temporary WAV and chunks are preparation artifacts. The SPEC's prohibition on creating files before a button conflicts if read literally with its own required download/transcription flow. The explicit T8/T9 tasks and Audio Delivery section establish that source/WAV/transcript preparation is required before the summary, while Telegram delivery/compressed delivery audio is deferred. T8 does not add any delivery action.

## Handoff

See [`requirements.md`](requirements.md), [`T8-media.feature`](../../src/features/T8-media.feature), and the append-only [`comms/questions.md`](comms/questions.md). These are design inputs for review, not a claim of implemented or passing functionality.
