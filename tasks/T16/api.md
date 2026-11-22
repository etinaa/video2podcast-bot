# T16 api

## Java contracts

```java
package com.video2podcast.job;

/** One reachability check of an external service. */
@FunctionalInterface
public interface ServiceProbe {
  /** Never throws for an unreachable service; returns the result instead. */
  ProbeResult probe(Duration timeout);
}

/** reason is empty when reachable, otherwise a fixed phrase (see below). */
public record ProbeResult(boolean reachable, String reason) {
  public static ProbeResult up() { return new ProbeResult(true, ""); }
  public static ProbeResult down(String reason) { return new ProbeResult(false, reason); }
}

public record Health(ProbeResult whisper, ProbeResult ollama) {
  public boolean allReachable();
}

public class HealthChecks {
  public HealthChecks(ServiceProbe whisper, ServiceProbe ollama, Duration timeout);

  /**
   * Runs both probes in parallel and waits at most timeout + 1 second. A probe that throws or does
   * not finish in time counts as down ("no answer in time" / "check failed").
   */
  public Health check();

  /** check() and one log line per service. Catches everything; never throws. */
  public void logStartup();

  /**
   * @throws JobFailure when a service is down: JobFailure.unreachable(whisperDown, ollamaDown)
   * @throws JobFailure FAILED if the calling thread is interrupted (flag restored)
   */
  public void requireReachable();
}
```

`JobFailure` gains:

```java
/**
 * Whisper down only: SERVICE_UNREACHABLE, "The transcription server is unreachable."
 * Ollama down only:  SUMMARY_UNREACHABLE, "The summary server is unreachable."
 * Both down:         SERVICE_UNREACHABLE, "The transcription server is unreachable. The summary server is unreachable."
 * @throws IllegalArgumentException if both arguments are false
 */
public static JobFailure unreachable(boolean whisperDown, boolean ollamaDown);
```

Client methods (package-private use is not enough: `HealthConfig` is in `config/`, so they are public):

```java
// transcript.WhisperClient
public ProbeResult probe(Duration timeout);   // GET <base-url>/
// summary.OllamaClient
public ProbeResult probe(Duration timeout);   // GET <base-url>/api/version
```

`PipelineService` constructor: `(YtDlpClient, FfmpegRunner, TranscriptionClient, SummaryService, JobStore, SummaryPublisher, Clock, AppProperties.Jobs, HealthChecks)` (`HealthChecks` added last). `PipelineService.run` Javadoc `@throws JobFailure` also lists `SERVICE_UNREACHABLE` and `SUMMARY_UNREACHABLE` raised before the work starts.

## Probe rules (both clients)

- Request: `GET`, no body, no headers beyond the defaults, `HttpRequest.timeout(timeout)`, redirects not followed (the clients' existing `HttpClient`).
- **Reachable = any HTTP status code arrived** (200, 404, 500...). The body is discarded (`BodyHandlers.discarding()`). Reason: the service answers HTTP, so a later request can reach it; it does not depend on a `/health` route that every whisper.cpp build has, and test stubs that answer 404 for unknown paths count as up.
- Unreachable reasons (fixed phrases, never the URL, a message of the exception or a stack trace):

| Cause | `reason` |
|---|---|
| `ConnectException` | `connection refused` |
| `UnresolvedAddressException` | `host not found` |
| `HttpConnectTimeoutException`, `HttpTimeoutException` | `no answer in time` |
| other `IOException` | `i/o error (<ExceptionSimpleName>)` |
| `InterruptedException` | `interrupted` (thread flag restored) |
| a probe that throws or overruns inside `HealthChecks` | `check failed` / `no answer in time` |

## Log lines (`HealthChecks.logStartup`, logger `com.video2podcast.job.HealthChecks`)

Exactly one line per service, in this order (Whisper, then Ollama):

```
INFO  Health check: whisper reachable
WARN  Health check: whisper unreachable (connection refused)
INFO  Health check: ollama reachable
WARN  Health check: ollama unreachable (no answer in time)
```

No URL, no host, no stack trace. Per-job failures are logged by the existing `UrlMessageHandler.logFailure` (`Job <id> failed: SERVICE_UNREACHABLE (cause: none)`); `requireReachable` itself logs nothing and `JobFailure.unreachable` has no cause.

## User messages (existing texts, plain English)

| Down | Message | `JobFailure.Kind` |
|---|---|---|
| Whisper | `The transcription server is unreachable.` | `SERVICE_UNREACHABLE` |
| Ollama | `The summary server is unreachable.` | `SUMMARY_UNREACHABLE` |
| both | `The transcription server is unreachable. The summary server is unreachable.` | `SERVICE_UNREACHABLE` |

Sent as one message by `UrlMessageHandler.tellFailure`, followed by the status edit `Failed.`. Order the user sees: `Queued: position N`, the failure message, `Failed.` on the first message.

## Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `app.health.timeout` | `HEALTH_TIMEOUT` | `PT3S` | Time one probe may take, at startup and per job. Must be positive. |

`AppProperties` gains the last component `Health health`, `record Health(Duration timeout)`. The existing `app.whisper.base-url` and `app.ollama.base-url` are reused. No change to `job.json`, `allowed-users.json`, HTTP endpoints of the bot or Telegram messages other than the combined text above.

## Notes for the Tester

- The probe sends `GET /` to the Whisper stub and `GET /api/version` to the Ollama stub. A WireMock stub with no mapping answers 404 and counts as reachable, so the existing ITs need no change. A "down" case is a stopped stub or a closed port.
- Nothing downloaded: no `yt-dlp` call and no job directory exist after a failed check.
- Set `app.health.timeout` short (for example `PT1S`) in ITs that test a service that does not answer.
