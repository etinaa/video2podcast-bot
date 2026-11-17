# TD2 contracts

No public signature, exception, HTTP, file or configuration contract changes. `JobQueue` keeps:

```java
public CompletableFuture<Void> submit(Runnable task);   // throws RejectedExecutionException after close()
public int size();
public void close();
```

## New guarantee (add to the Javadoc of `size()` and `submit()`)

When `JobQueue.run` completes the future returned by `submit` (normally, exceptionally or after an
Error), the task has already stopped counting toward `size()`. A `get()` waiter and a `whenComplete`
callback therefore see the updated size.

Out of scope, unchanged: completion by `close()` (`abandonUnfinished` cancels the future, then removes
it) and completion or cancellation by a caller, who owns the mutable returned future. No ordering is
promised for those, and no new cancellation contract is added. Javadoc wording must say "when the queue
completes the task's future".

## Required change in `JobQueue.run` (private)

```java
private void run(Runnable task, CompletableFuture<Void> result) {
  Throwable failure = new IllegalStateException("Queued task ended abnormally");
  try {
    if (!result.isDone()) {
      task.run();
      failure = null;
    }
  } catch (RuntimeException e) {
    LOG.error("Queued task failed", e);
    failure = e;
  } finally {
    // Leave the open set first, so size() is already correct when the future's waiters run.
    open.remove(result);
    // An Error skips both branches above and keeps the "abnormal" failure; the future must still complete.
    if (failure == null) {
      result.complete(null);
    } else {
      result.completeExceptionally(failure);
    }
  }
}
```

(The Developer may restructure, but these must hold: `open.remove(result)` runs before any completion call
on all three paths; the `Error` is not swallowed; a throwing `RuntimeException` still gives
`completeExceptionally(e)` with the original exception as the cause seen by `get()`; the error log line is
unchanged; no new fields.)

## Tests (`JobQueueTest`, owned by the Developer, unit tests)

Both new tests must fail on the current code and pass after the change. Pattern, so the callback runs on
the worker thread at completion time and the check is deterministic:

1. Submit a first task that blocks on a latch, so the second task is still queued.
2. Submit the last task (a no-op, or one that throws `IllegalStateException`).
3. Register `last.whenComplete((v, e) -> observed.set(queue.size()))` (an `AtomicInteger` initialised to a
   value other than 0, e.g. -1) before releasing the latch.
4. Release the latch and first await the callback's `CountDownLatch` with a timeout, without calling
   `get` or `join` on `last`. A waiter in `get(timeout)` can help run a non-async callback on the test
   thread (`timedGet` calls `postComplete`), which would hide the race and make the red run
   non-deterministic.
5. Only after the callback has been observed, call `last.get(timeout)` to assert the original outcome
   (normal, or `ExecutionException` with the thrown exception as cause), then assert `observed` is 0.

Names: `sizeIsZeroInsideTheCallbackOfTheLastSucceededTask` and
`sizeIsZeroInsideTheCallbackOfTheLastFailedTask`.

An `Error` case is optional: it would kill the worker thread via the uncaught-exception handler, so only
add it if the Developer can keep it quiet (the three-path guarantee is reviewed in the code).

`sizeCountsWaitingAndRunningTasksUntilTheyFinish` stays unchanged. The one-off 200-run loop is local and
not kept.
