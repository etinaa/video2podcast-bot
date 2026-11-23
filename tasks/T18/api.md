# T18 api

Package `com.video2podcast.job` unless stated. Exceptions are unchecked.

## `JobFailure` (changed)

```java
/** True for SERVICE_UNREACHABLE (Whisper, or both services) and SUMMARY_UNREACHABLE (Ollama). */
public boolean isServiceOutage();
```

## `DeferredJob` (new record, one queue entry)

```java
public record DeferredJob(
    String id, String url, long ownerUserId, long chatId,
    int statusMessageId, int attempts, Instant parkedAt) {
  public DeferredJob withAttempts(int attempts);
}
```

## `DeferredJobs` (new)

The queue and its file (`deferred-jobs.json`, always the sibling of `ACCESS_FILE`). Every method is `synchronized`; every change is written to the file first and becomes visible only after the write succeeded.

```java
public class DeferredJobs {
  /** Loads the file now. Missing file → empty. Not valid JSON or an invalid entry
   *  → renamed to <name>.corrupt-<epoch seconds>, ERROR log, empty queue.
   *  @throws DeferredJobsException if the file cannot be read or renamed */
  public DeferredJobs(Path file, Clock clock);

  /** Appends an entry (id = request.jobId(), attempts 0, parkedAt = clock.instant()). */
  public DeferredJob park(JobRequest request, int statusMessageId);   // throws DeferredJobsException on write failure

  /** Entries in queue order; an immutable copy. */
  public List<DeferredJob> entries();

  public Optional<DeferredJob> find(String id);

  /** attempts + 1; returns the updated entry, empty if the entry is gone. @throws DeferredJobsException */
  public Optional<DeferredJob> recordFailedAttempt(String id);

  /** @return false if there was no such entry. @throws DeferredJobsException */
  public boolean remove(String id);
}

public class DeferredJobsException extends RuntimeException {
  public DeferredJobsException(String message, Throwable cause);
}
```

`park` is called with the request of the first run, so `id` is its job id.

## `DeferredNotifier` (new, in `job/`, implemented in `bot/`)

Telegram side of the end of a retry. Every method is best effort and must not throw. A retry reports **no stage progress**: the runner passes a no-op `JobProgress` to the pipeline, so the parked status text stays untouched while the service is down (R4). The summary message itself is sent by the pipeline's `SummaryPublisher`.

```java
public interface DeferredNotifier {
  /** Edits the status message to "Done.". */
  void completed(long chatId, int statusMessageId);
  /** Sends "We could not process this link. Please send it again later." and edits the status to "Failed.". */
  void gaveUp(long chatId, int statusMessageId);
  /** Sends failure.getMessage() (HTML-escaped) as a new message and edits the status to "Failed.". */
  void failed(long chatId, int statusMessageId, JobFailure failure);
}
```

## `DeferredJobRunner` (new)

```java
public class DeferredJobRunner {
  public DeferredJobRunner(DeferredJobs jobs, PipelineService pipeline, JobQueue queue,
      DeferredNotifier notifier, LongPredicate hasAccess, Clock clock, int maxAttempts);

  /** One pass over a snapshot of the queue. Returns at once; entries run one at a time on the
   *  JobQueue, each submitted when the previous one completed. Does nothing if a pass is running
   *  or the queue is empty. Scheduled; tests call it directly. */
  @Scheduled(fixedDelayString = "${app.deferred.retry-interval}",
             initialDelayString = "${app.deferred.retry-interval}")
  public void retryAll();

  /** Package-private for tests: the work of one entry, run on the worker (steps in flows 3-6). */
  void retry(String id);
}
```

Rules of `retry(id)`, in order:

1. Entry not found → return.
2. `!hasAccess.test(ownerUserId)` → `remove`, one INFO log with the id, return (flow 6).
3. `pipeline.run(new JobRequest(JobStore.newJobId(), ownerUserId, chatId, url, clock.instant()), NO_PROGRESS)`, where `NO_PROGRESS` is a `JobProgress` that does nothing.
4. Returns normally → `remove(id)`, `notifier.completed(chatId, statusMessageId)`.
5. `JobFailure` with `isServiceOutage()` → if the thread is interrupted: return, entry unchanged. Else `recordFailedAttempt`; if the new `attempts >= maxAttempts` → `remove(id)`, `notifier.gaveUp(...)`; else nothing is sent or edited.
6. Any other `JobFailure` → `remove(id)`, `notifier.failed(chatId, statusMessageId, failure)`. Any other `RuntimeException` → same with `JobFailure.of(FAILED, e)`. If the thread is interrupted, leave the entry unchanged instead.
7. `DeferredJobsException` from `remove` / `recordFailedAttempt` is caught and logged (warn); the notifier call of that step still happens.

Logs carry the entry id, the attempt count and the failure kind; never the URL.

`@Scheduled` needs `@EnableScheduling`, already on `JobConfig`.

## `UrlMessageHandler` (changed)

Constructors gain a `DeferredJobs deferred` parameter (after `pipeline`). In `runJob`, the catch of `JobFailure f`:

```java
if (f.isServiceOutage() && tryPark(request, id)) { parked.set(true); } else { failure.set(f); }
```

`tryPark` calls `deferred.park(request, id)`; on `DeferredJobsException` it logs a warning and returns false (normal failure path). `finish` gets the `parked` flag: if parked it only calls `editStatus(chatId, statusId, DeferredMessages.PARKED)` (no failure message, no log of a failure beyond one INFO line). The `RuntimeException` branch is unchanged (not an outage).

## `bot/DeferredMessages` and `TelegramDeferredNotifier` (new)

```java
final class DeferredMessages {
  static final String PARKED  = "Service temporarily unavailable. We will send the result after it recovers.";
  static final String GAVE_UP = "We could not process this link. Please send it again later.";
}
public class TelegramDeferredNotifier implements DeferredNotifier {
  public TelegramDeferredNotifier(TelegramGateway gateway);
}
```

`completed`/`gaveUp`/`failed` edit to `Done.` / `Failed.` as `UrlMessageHandler` does, and send the messages with `gateway.sendMessage(chatId, html, List.of())`. Telegram errors are logged and dropped.

## Configuration (`AppProperties`, `application.yml`, `.env.example`)

```java
public record Deferred(Duration retryInterval, int maxAttempts) {}
// AppProperties gains: Deferred deferred
```

```yaml
app:
  deferred:
    retry-interval: ${DEFERRED_RETRY_INTERVAL:PT2H}
    max-attempts: ${DEFERRED_MAX_ATTEMPTS:12}
```

| Variable | Default | Meaning |
|---|---|---|
| `DEFERRED_RETRY_INTERVAL` | `PT2H` | delay between passes, and before the first pass after a start |
| `DEFERRED_MAX_ATTEMPTS` | `12` | retries per entry; must be at least 1 |

Validation at start: `retryInterval` positive, `maxAttempts >= 1`; otherwise the context fails with a clear message.

## Beans (`PipelineConfig`)

```java
@Bean DeferredJobs deferredJobs(AppProperties props, Clock clock) { return new DeferredJobs(props.access().file().resolveSibling("deferred-jobs.json"), clock); }
@Bean DeferredNotifier deferredNotifier(TelegramGateway gateway) { return new TelegramDeferredNotifier(gateway); }
@Bean DeferredJobRunner deferredJobRunner(DeferredJobs jobs, PipelineService pipeline, JobQueue queue,
    DeferredNotifier notifier, AccessService access, Clock clock, AppProperties props) {
  return new DeferredJobRunner(jobs, pipeline, queue, notifier, access::isAllowed, clock, props.deferred().maxAttempts());
}
```

## Fixed texts

| Where | Text |
|---|---|
| status edit when parked | `Service temporarily unavailable. We will send the result after it recovers.` |
| give-up message | `We could not process this link. Please send it again later.` |
| other failure on retry | the normal `JobFailure` message, then status `Failed.` |
| success | summary message with both buttons (T11), status `Done.` |

## Docs for the Developer

`docs/host-setup.md`: the two variables and the file `deferred-jobs.json` in the directory of `ACCESS_FILE`, the data volume (survives `podman compose down`, lost if the volume is removed). `SPEC.md`: storage line for `deferred-jobs.json`, the env var list, and criterion 14 extended with the deferral. `.env.example`: the two variables, commented, with defaults.

## Test hooks for the Tester

Functional tests (`DeferredJobIT`, Cucumber) do not call `DeferredJobRunner`. They drive the real `@Scheduled` pass with a short real interval: the IT profile sets `DEFERRED_RETRY_INTERVAL` to a few seconds (for example `PT2S`; the first pass comes one interval after start) and waits, with a timeout, for the observable result (Telegram calls on the fake API, `deferred-jobs.json` content, WireMock requests). Scenarios that must not see a retry (for example "repeated failure sends no new message" checked between passes) read the queue file's `attempts` to know which pass has run. `DEFERRED_MAX_ATTEMPTS` is set per scenario (for example 2) to reach the limit quickly. The queue file is `deferred-jobs.json` in the directory of `ACCESS_FILE`. A restart is a new application context over the same data directory.

`DeferredJobRunner.retryAll()` and `retry(id)` stay available to the Developer's unit tests, which use a fake `Clock`, a fake `JobQueue` task runner and a temp directory.

Scenario steps such as "the application clock advances by two hours and the next deferred retry interval elapses" map to two things: moving the test `Clock` bean (it sets `parkedAt` and the retention `expiresAt`; the scheduler does not read it), and waiting for the next real pass of the short interval. The scheduler is `fixedDelay` on real time by design, so the pass count is observed through `attempts` in the queue file or the WireMock journal, never through the clock.

### Controllable scheduler (answer to Q-tester-1)

Yes. The Tester may install a test-only `TaskScheduler` bean (named `taskScheduler`, like `RetentionSupport.Schedule`) that records Spring's real `@Scheduled` registrations and runs a registered `Runnable` on its own thread when the test releases it. Contract the design guarantees for this:

- Production code defines **no** `TaskScheduler` bean and no `SchedulingConfigurer`; `@EnableScheduling` on `JobConfig` is the only switch. The runner is registered only through the annotation on `DeferredJobRunner.retryAll()`: `fixedDelayString = "${app.deferred.retry-interval}"`, `initialDelayString = "${app.deferred.retry-interval}"`. So the captured registration shows delay = initial delay = the configured `DEFERRED_RETRY_INTERVAL` (`PT10M` in the feature), via `scheduleWithFixedDelay`.
- The other registration in the context is `RetentionSweeper.sweep()` (`${app.jobs.sweep-interval}`, default `PT1M`). Tell them apart by the Runnable's target (`DeferredJobRunner` vs `RetentionSweeper`) or by the delay values; the fixture releases them independently.
- `retryAll()` returns right after the first `submit` (flow 2), so releasing one invocation is "one pass". A released invocation while a pass is running returns at once, which is the overlap scenario. The shared `JobQueue` is the real one.
- Exact counts: one released invocation makes at most one retry per entry, so `attempts` is 0 before and 1 after the first released pass; the file shows it.
- A restart is a new context: a new fixture records new registrations with the configured delay, which is how "the wait restarts" is observed.
- Direct calls of `retryAll()`/`retry(id)` stay out of functional tests. One short real-interval smoke scenario may additionally show the automatic run.
