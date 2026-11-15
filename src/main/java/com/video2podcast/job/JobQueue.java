package com.video2podcast.job;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
  private final AtomicInteger unfinished = new AtomicInteger();
  private final Duration shutdownWait;

  public JobQueue() {
    this(DEFAULT_SHUTDOWN_WAIT);
  }

  /** {@code shutdownWait} is how long {@link #close()} lets queued tasks finish. */
  public JobQueue(Duration shutdownWait) {
    this.shutdownWait = shutdownWait;
  }

  /**
   * Queues a task. The returned future completes when the task has run, exceptionally if it threw.
   *
   * @throws RejectedExecutionException after {@link #close()}
   */
  public CompletableFuture<Void> submit(Runnable task) {
    unfinished.incrementAndGet();
    try {
      return CompletableFuture.runAsync(() -> run(task), worker);
    } catch (RejectedExecutionException e) {
      unfinished.decrementAndGet();
      throw e;
    }
  }

  /** How many tasks are waiting or running, which is also the position a new task would get. */
  public int size() {
    return unfinished.get();
  }

  private void run(Runnable task) {
    try {
      task.run();
    } catch (RuntimeException e) {
      LOG.error("Queued task failed", e);
      throw e;
    } finally {
      unfinished.decrementAndGet();
    }
  }

  /** Stops accepting tasks and waits for the queued ones to finish. */
  @Override
  public void close() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.warn("Job queue did not finish within {}, stopping it", shutdownWait);
        worker.shutdownNow();
      }
    } catch (InterruptedException e) {
      worker.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
