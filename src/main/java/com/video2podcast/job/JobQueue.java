package com.video2podcast.job;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs tasks one at a time, in the order they were submitted. The local models are the bottleneck,
 * so summaries and audio preparation share this one worker. A task that throws is logged and does
 * not stop the tasks behind it.
 */
public class JobQueue implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(JobQueue.class);
  private static final Duration DEFAULT_SHUTDOWN_WAIT = Duration.ofSeconds(30);

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(task -> new Thread(task, "job-worker"));
  private final Set<CompletableFuture<Void>> open = ConcurrentHashMap.newKeySet();
  private final Duration shutdownWait;

  public JobQueue() {
    this(DEFAULT_SHUTDOWN_WAIT);
  }

  /** {@code shutdownWait} is how long {@link #close()} lets queued tasks finish. */
  public JobQueue(Duration shutdownWait) {
    this.shutdownWait = shutdownWait;
  }

  /**
   * Queues a task. The returned future completes when the task has run, exceptionally if it threw,
   * and is cancelled if the queue is stopped before the task could finish. When the queue completes
   * the future, the task has already stopped counting toward {@link #size()}, so a waiter or a
   * {@code whenComplete} callback sees the updated size.
   *
   * @throws RejectedExecutionException after {@link #close()}
   */
  public CompletableFuture<Void> submit(Runnable task) {
    CompletableFuture<Void> result = new CompletableFuture<>();
    open.add(result);
    try {
      worker.execute(() -> run(task, result));
    } catch (RejectedExecutionException e) {
      open.remove(result);
      throw e;
    }
    return result;
  }

  /**
   * How many tasks are waiting or running, which is also the position a new task would get. A task
   * stops counting before the queue completes its future.
   */
  public int size() {
    return open.size();
  }

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
      // An Error skips both branches above and keeps the "abnormal" failure; the future must still
      // complete so nobody waits forever.
      if (failure == null) {
        result.complete(null);
      } else {
        result.completeExceptionally(failure);
      }
    }
  }

  /** Stops accepting tasks and waits for the queued ones to finish. */
  @Override
  public void close() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warn("Job queue did not finish within {}, stopping it", shutdownWait);
        abandonUnfinished();
      }
    } catch (InterruptedException e) {
      abandonUnfinished();
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Interrupts the running task and drops the waiting ones. Their futures are cancelled, so nothing
   * that waits for a result hangs, and they stop counting toward {@link #size()}.
   */
  private void abandonUnfinished() {
    worker.shutdownNow();
    open.forEach(result -> {
      result.cancel(false);
      open.remove(result);
    });
  }
}
