# T9 research — Whisper transcription contract

Research date: 2026-11-17. These are design findings, not a fresh real-host smoke test or evidence of implemented behavior.

## External primary sources consulted

| Source | Fact taken | Implication for T9 | Accessed |
|---|---|---|---|
| [whisper.cpp server README](https://github.com/ggml-org/whisper.cpp/blob/master/examples/server/README.md) | The example accepts WAV uploads using multipart `file` and `response_format=json`; its default inference path is `/inference`. | Verify multipart upload at that path against the configured host. Conversion remains in T8. | 2026-11-17 |
| [whisper.cpp server source](https://raw.githubusercontent.com/ggml-org/whisper.cpp/master/examples/server/server.cpp), `get_req_parameters`, `output_str` and inference response branches | Request parsing accepts `language`; normal JSON output contains top-level `text`. Text assembly adds segment newlines. | Send `language=en` explicitly and normalize segment whitespace before merging. Additional metadata need not be interpreted. | 2026-11-17 |

These are moving upstream references, not a pinned version of the owner's installed server. T3's captured local responses below are the repository's compatibility baseline. Upstream documentation does not prescribe this application's retries, deadlines, safe messages or file retention; those come from the agreed task.

## Repository evidence

| Source | Finding and use | Read |
|---|---|---|
| [`brief.md`](brief.md), T9 in [`todo.md`](../todo.md) | Owner has settled empty speech, retry classification, per-chunk deadlines, exact output format and post-write WAV/chunk cleanup. DLQ and two-hour retries are deferred. No T9 functional `*IT` tests. | 2026-11-17 |
| [`SPEC.md`](../../SPEC.md) | Forced English, approximately ten-minute WAV chunks, full transcript coverage, inclusive default three-hour input limit, user-safe failures and deferred delivery. The environment list still needs WHISPER_TIMEOUT. | 2026-11-17 |
| [`plan.md`](../plan.md) | Transcription feeds summary and T11. Its older dependency graph/one-commit wording lags todo and the team workflow; do not use it to remove T8 dependency or bypass design review. | 2026-11-17 |
| [`host-setup.md`](../../docs/host-setup.md), Whisper contract | T3's real 7-second capture used POST `/inference`, multipart `file`, `response_format=json`, `language=en`; optional temperature 0.0. Success contains wrapped text; errors can be plain text. Reachability from Podman remains unverified, to settle in T15. | 2026-11-17 |
| [`whisper-inference-ok.json`](../../src/test/resources/fixtures/whisper-inference-ok.json) | Actual text has a leading space, inner segment newline and final newline. Expected normalized content: `Hello, this is a short test of the video to podcast but the meeting is on Tuesday at 10 o'clock, and we will discuss the budget.` plus one LF. | 2026-11-17 |
| [`whisper-inference-bad-request.txt`](../../src/test/resources/fixtures/whisper-inference-bad-request.txt), [`whisper-inference-verbose.json`](../../src/test/resources/fixtures/whisper-inference-verbose.json) | Bad request fixture is `Invalid request`; verbose fixture contains timestamps/segments. Tests must not require JSON parsing for non-2xx bodies or use verbose metadata as transcript text. | 2026-11-17 |
| [`FfmpegRunner.java`](../../src/main/java/com/video2podcast/media/FfmpegRunner.java), [`ChunkSplitter.java`](../../src/main/java/com/video2podcast/media/ChunkSplitter.java) | T8 uses audio.wav, chunks/chunk-NNN.wav, a 600-second split target and a sorted nonempty list. T9 should consume that order and retain source.*. | 2026-11-17 |
| [`AppProperties.java`](../../src/main/java/com/video2podcast/config/AppProperties.java), [`application.yml`](../../src/main/resources/application.yml) | Whisper currently has only baseUrl. Timeout property/default/env binding are additive T9 changes, not already available configuration. | 2026-11-17 |
| [`JobFailure.java`](../../src/main/java/com/video2podcast/job/JobFailure.java) | NO_SPEECH and SERVICE_UNREACHABLE are absent; current FAILED factory copy is generic media copy. T9 must expose its agreed transcription-specific text without changing media behavior. | 2026-11-17 |
| [`whoami.feature`](../../src/features/whoami.feature), [`T8-media.feature`](../../src/features/T8-media.feature), [`TD2-job-queue.feature`](../../src/features/TD2-job-queue.feature) | English observable steps and requirement tags are existing conventions. There are no existing Whisper steps to reuse. Component examples can describe caller-visible behavior while functional automation is deferred. | 2026-11-17 |
| [`CucumberIT.java`](../../src/it/java/com/video2podcast/functional/CucumberIT.java) | Filter currently excludes T8 and TD2 only. Newly added T9 scenarios need explicit exclusion while there are no T9 step definitions; ask Tester rather than edit its zone. | 2026-11-17 |

## Conclusions and review risks

- A valid empty string is successful silence. Missing, null or non-string text is not speech evidence and belongs to broken-response retry. This distinction prevents a server contract regression from being mislabeled NO_SPEECH.
- Retry classification is based on the retry's failure, not the original one. A refused connection followed by 400 is FAILED; 400 followed by refusal is SERVICE_UNREACHABLE. All non-2xx statuses are covered, including 400; retrying only 5xx would contradict the brief.
- A progress event precedes each chunk's first attempt, including empty chunks; it is not a per-attempt notification. The denominator stays the original input size.
- A short answer-timeout override proves timeout/retry behavior without long-running tests. Delaying a connected WireMock response does not prove the five-second connect deadline.
- Persist the complete transcript before deleting temporary audio. A chunk failure must not leave a partial successful transcript. Transcript-write failure must preserve inputs. Cleanup after successful persistence can itself partially fail; the architecture must specify that local-I/O boundary instead of promising atomic deletion across files. See Q-analyst-1.
- SPEC's Never sentence forbids pre-button transcript creation while its assumptions and T9 explicitly require it. The agreed T9 behavior is pre-summary disk creation with delivery on demand; Q-analyst-3 requests alignment by the owning role.
- No real local Whisper request was sent during this analysis. T3 fixtures establish request/response compatibility; full real-service transcription quality, GPU operation and Podman reachability remain later runtime checks.

## Handoff

See [`requirements.md`](requirements.md), [`T9-transcription.feature`](../../src/features/T9-transcription.feature), and [`comms/questions.md`](comms/questions.md). Requirements map one-to-one to R tags in the feature. These files are review inputs, not implementation approval or a report of passing tests.
