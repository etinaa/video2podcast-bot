# T9 components

## What changes

| Component | Change | Why there |
|---|---|---|
| `transcript/WhisperClient` (new) | One HTTP call: send one WAV chunk to `whisper-server`, return its `text`. Uses `java.net.http.HttpClient` (JDK, no new dependency). Throws `WhisperException`. | Isolates HTTP and multipart so the retry policy is testable without a server. |
| `transcript/WhisperException` (new, checked) | Carries one flag, `connection` (true: no connection, connect timeout). Everything else (non-2xx, broken JSON, missing `text`, read timeout, I/O error) is `connection = false`. | Lets `TranscriptionClient` choose `SERVICE_UNREACHABLE` or `FAILED` without looking at JDK exception types. |
| `transcript/TranscriptMerger` (new, package-private, static) | Pure: `normalize(String)` and `merge(List<String>)`. | SPEC lists chunk merging as a pure unit test. |
| `transcript/TranscriptionClient` (new) | Orchestrates: progress callback, one send per chunk with one immediate retry, merge, write `transcript.txt`, delete `audio.wav` and `chunks/`. | The only public entry point T11 calls. |
| `job/JobFailure` | New kinds `NO_SPEECH`, `SERVICE_UNREACHABLE` with fixed messages; new factory `transcriptionFailed(Throwable)`. | Typed failures are the contract with T11 and the later DLQ task. |
| `config/AppProperties` | `Whisper(String baseUrl, Duration timeout)`. | New setting `app.whisper.timeout`. |
| `config/TranscriptConfig` (new) | Beans `WhisperClient` and `TranscriptionClient`. | Same pattern as `MediaConfig`. |
| `application.yml` | `app.whisper.timeout: ${WHISPER_TIMEOUT:PT10M}`. | |
| `SPEC.md` (orchestrator, Q-analyst-3) | List `WHISPER_TIMEOUT`; align the "Never" sentence about creating the transcript before a button tap (the file is created at transcription, only *sent* on tap). | Owner-agreed requirement. |
| `docs/decisions.md` | D6. | |

Not changed: `media/*` (already produces `audio.wav` and `chunks/chunk-NNN.wav`), `job/*` store and queue, `bot/*`. T9 is not wired into any flow yet (T11 does that): no user-visible behaviour, no functional `*IT`.

## JobFailure message for FAILED

`Kind.FAILED` already has the generic message "Could not process this video. Please try again later." (`of(FAILED)`, used by media). Transcription uses its own text through `JobFailure.transcriptionFailed(cause)`. `of(FAILED)` is unchanged, so existing tests stay valid.

## Diagram

```mermaid
flowchart LR
  T11[Pipeline T11, later] -->|transcribe jobDir, chunks, progress| TC[TranscriptionClient]
  FR[FfmpegRunner T8] -. produces audio.wav and chunks/ .-> JD[(job dir)]
  TC --> WC[WhisperClient]
  TC --> TM[TranscriptMerger]
  WC -->|POST /inference multipart| WS[whisper-server on host]
  TC -->|write transcript.txt, delete audio.wav and chunks/| JD
  TC -->|throws| JF[JobFailure]
  AP[AppProperties.Whisper] --> WC
```
