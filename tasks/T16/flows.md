# T16 flows

## 1. Startup, both services up, or one or both down

```mermaid
sequenceDiagram
  participant S as Spring Boot
  participant R as ApplicationRunner (HealthConfig)
  participant V as virtual thread
  participant H as HealthChecks
  participant W as Whisper
  participant O as Ollama
  S->>R: context refreshed
  R->>V: start, return at once
  S->>S: long polling starts (not waiting for the check)
  V->>H: logStartup()
  par parallel, each at most timeout
    H->>W: GET /
    H->>O: GET /api/version
  end
  W-->>H: any HTTP status, or error / no answer
  O-->>H: any HTTP status, or error / no answer
  H->>H: log "whisper reachable" or "whisper unreachable (reason)", same for ollama
```
The bot starts in every case. An exception in `logStartup` is caught and logged as one line.

## 2. Job, both services up (main flow, unchanged after the check)

```mermaid
sequenceDiagram
  participant U as User
  participant UMH as UrlMessageHandler
  participant Q as JobQueue
  participant PS as PipelineService
  participant H as HealthChecks
  U->>UMH: link
  UMH->>U: Queued: position N
  UMH->>Q: submit
  Q->>PS: run(request)
  PS->>H: requireReachable()
  H-->>PS: ok
  PS->>PS: createDirectories, Downloading..., download, transcribe, summarize (T9-T11)
```

## 3. Job, Whisper down, Ollama down, or both (failure)

```mermaid
sequenceDiagram
  participant U as User
  participant UMH as UrlMessageHandler
  participant Q as JobQueue
  participant PS as PipelineService
  participant H as HealthChecks
  participant X as yt-dlp
  U->>UMH: link
  UMH->>U: Queued: position N
  UMH->>Q: submit
  Q->>PS: run(request)
  PS->>H: requireReachable()
  H-->>PS: JobFailure.unreachable(whisperDown, ollamaDown)
  Note over PS,X: no directory, no metadata call, no download
  PS->>PS: run() catch: store.discard(jobId), nothing to remove
  PS-->>UMH: JobFailure (via the future)
  UMH->>U: "The transcription server is unreachable." / "The summary server is unreachable." / both sentences
  UMH->>U: edit status to Failed.
```
Other users' messages and taps are handled meanwhile by the polling thread. Their jobs wait behind this one for at most `app.health.timeout`.

## 4. Service goes down mid-job

Unchanged (T9: `SERVICE_UNREACHABLE` after the chunk retry; T10: `SUMMARY_UNREACHABLE`). The directory is deleted by `PipelineService.run`.

## 5. Failures that need no new design (reached by existing flows)

| Case | Flow |
|---|---|
| Private, unsupported, too-long URL | existing: `UrlValidator` (before queueing) or `YtDlpClient` (T8/T11) |
| Callback from a non-allowlisted user | existing: `BotUpdateHandler` access gate (T4-T7) |
| Restart with pending access requests | existing: `AccessStore` reload (T5-T7) |

T16 adds tests for these only where `requirements.md` says an earlier task did not cover them; no code changes.
