# T18 flows

## 1. Park: a service is down on the first run

```mermaid
sequenceDiagram
  participant U as User
  participant UMH as UrlMessageHandler
  participant W as job-worker (JobQueue)
  participant PS as PipelineService
  participant DJ as DeferredJobs
  participant TG as Telegram
  U->>UMH: link
  UMH->>TG: "Queued: position N" (status message id S)
  UMH->>W: submit(runJob)
  W->>PS: run(request, StatusReporter)
  PS-->>W: JobFailure SERVICE_UNREACHABLE / SUMMARY_UNREACHABLE (directory already deleted)
  W->>DJ: park(request, S) writes deferred-jobs.json
  Note over W: outcome = parked
  W-->>UMH: future completes
  UMH->>TG: edit S: "Service temporarily unavailable. We will send the result after it recovers."
```

No second message, no "Failed.". If `park` throws `DeferredJobsException`, the failure is reported the normal way (failure message + "Failed.") and logged.

## 2. Scheduled pass, entries run one at a time

```mermaid
sequenceDiagram
  participant Sch as Spring scheduler
  participant DR as DeferredJobRunner
  participant DJ as DeferredJobs
  participant Q as JobQueue
  Sch->>DR: retryAll() every DEFERRED_RETRY_INTERVAL
  alt a pass is still running
    DR-->>Sch: return (no overlap)
  else
    DR->>DJ: snapshot of entries
    DR->>Q: submit(retry(entry 1))
    Q-->>DR: completed
    DR->>Q: submit(retry(entry 2))
    Note over Q: user jobs submitted meanwhile run between entries
    Q-->>DR: completed
    Note over DR: after the last entry the pass ends
  end
```

The scheduler thread returns right after the first `submit`; later entries are submitted from the completion callback. If `submit` throws `RejectedExecutionException` (shutdown) the pass ends and entries stay.

## 3. Retry succeeds

```mermaid
sequenceDiagram
  participant DR as DeferredJobRunner
  participant AS as access (LongPredicate)
  participant DN as DeferredNotifier
  participant PS as PipelineService
  participant DJ as DeferredJobs
  participant TG as Telegram
  Note over DR: on the job-worker, no progress edits (status keeps the parked text)
  DR->>DJ: find(id) (still there?)
  DR->>AS: test(ownerUserId) true
  DR->>PS: run(new JobRequest, no-op progress)
  PS->>TG: summary message with both buttons
  PS-->>DR: returns
  DR->>DJ: remove(id)
  DR->>DN: completed(chat, status) → edit status "Done."
```

## 4. Retry: service still down

```mermaid
sequenceDiagram
  participant DR as DeferredJobRunner
  participant PS as PipelineService
  participant DJ as DeferredJobs
  participant DN as DeferredNotifier
  DR->>PS: run(new JobRequest, no-op progress)
  PS-->>DR: JobFailure outage
  DR->>DJ: recordFailedAttempt(id) → attempts + 1
  alt attempts < DEFERRED_MAX_ATTEMPTS
    Note over DR: nothing is sent or edited
  else limit reached (12th failed retry)
    DR->>DJ: remove(id)
    DR->>DN: gaveUp(chat, status)
    Note over DN: sends "We could not process this link. Please send it again later."<br/>and edits the status to "Failed."
  end
```

## 5. Retry: any other failure closes the entry

```mermaid
sequenceDiagram
  participant DR as DeferredJobRunner
  participant PS as PipelineService
  participant DJ as DeferredJobs
  participant DN as DeferredNotifier
  DR->>PS: run(...)
  PS-->>DR: JobFailure (PRIVATE, UNSUPPORTED, TOO_LONG, FAILED, TIMEOUT, NO_SPEECH) or RuntimeException
  DR->>DJ: remove(id)
  DR->>DN: failed(chat, status, failure) (RuntimeException → FAILED)
  Note over DN: sends the normal failure message, edits the status to "Failed."
```

## 6. User lost access while waiting

```mermaid
sequenceDiagram
  participant DR as DeferredJobRunner
  participant AS as access (LongPredicate)
  participant DJ as DeferredJobs
  DR->>AS: test(ownerUserId) false
  DR->>DJ: remove(id)
  Note over DR: no pipeline run, no message, one log line (id only)
```

The check is made right before each retry, so access removed at any moment before the retry starts is honoured. The owner (`ADMIN_USER_ID`) always passes.

## 7. Restart

```mermaid
sequenceDiagram
  participant Spring
  participant DJ as DeferredJobs
  participant F as deferred-jobs.json
  participant DR as DeferredJobRunner
  Spring->>DJ: construct → load()
  alt file missing
    DJ-->>Spring: empty queue
  else valid
    DJ->>F: read; queue order and attempts kept
  else corrupt
    DJ->>F: rename to deferred-jobs.json.corrupt-<epoch s>; ERROR log; empty queue
  end
  Note over DR: first pass one full interval after start (initialDelay = interval), so a restart restarts the wait
```

## 8. Failures around the retry

- **Interrupted (shutdown):** if the worker thread is interrupted (`Thread.interrupted` / flag set, or the future is cancelled) when the retry ends with a failure, the entry is left as it was: no attempt counted, no message. The next start retries it.
- **Crash during a retry:** the entry is unchanged in the file, so the next start retries it. A crash after the summary was sent and before `remove` can send the summary twice; accepted.
- **Telegram fails** (edit, message, summary already sent): logged, dropped, never changes the entry. A failing summary publish inside the pipeline is a `FAILED` (flow 5).
- **`deferred-jobs.json` cannot be written** during `recordFailedAttempt` or `remove`: logged; the in-memory state is not changed (write first, then swap, as `AccessService`), so the entry will be seen again on the next pass. The retry outcome message is still sent. Consequences, accepted and not hidden:
  - `remove` fails after a successful retry: the summary was already sent, the entry stays, and the next pass sends the **summary a second time** (the same duplicate as a crash before `remove`). It repeats each pass until a write succeeds or the attempts run out.
  - `remove` fails after give-up or a non-outage failure: the user got the final message, and the next pass sends it again.
  - `recordFailedAttempt` fails: the count does not grow, so the entry can get more than `DEFERRED_MAX_ATTEMPTS` retries while the disk is unwritable.
  - `park` fails: nothing is promised; the user gets the normal failure message (flow 1).
- **Unexpected exception in a retry task:** caught in the task, logged, treated as flow 5 (entry closed with the `FAILED` message), so one entry never stops the pass.
