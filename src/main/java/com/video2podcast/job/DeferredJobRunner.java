package com.video2podcast.job;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retries the parked links from scratch. A pass walks a snapshot of the queue; each entry runs on
 * the shared {@link JobQueue} and the next one is submitted when it is done, so the scheduler
 * thread never waits for a job and user jobs slip in between entries. Logs carry the entry id,
 * never the link.
 */
public class DeferredJobRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DeferredJobRunner.class);

  /** A retry shows no stage progress: the status message keeps the parked text. */
  private static final JobProgress NO_PROGRESS =
      new JobProgress() {
        @Override
        public void downloading() {
          // silent on purpose
        }

        @Override
        public void transcribing(int n, int total) {
          // silent on purpose
        }

        @Override
        public void summarizing() {
          // silent on purpose
        }
      };

  private final DeferredJobs jobs;
  private final PipelineService pipeline;
  private final JobQueue queue;
  private final DeferredNotifier notifier;
  private final LongPredicate hasAccess;
  private final Clock clock;
  private final int maxAttempts;
  private final AtomicBoolean passRunning;

  public DeferredJobRunner(
      DeferredJobs jobs,
      PipelineService pipeline,
      JobQueue queue,
      DeferredNotifier notifier,
      LongPredicate hasAccess,
      Clock clock,
      int maxAttempts) {
    this(jobs, pipeline, queue, notifier, hasAccess, clock, maxAttempts, new AtomicBoolean());
  }

  /**
   * The public constructor delegates here so that SpotBugs does not flag the stored collaborators
   * as exposed representation (the same shape as {@code UrlMessageHandler}); {@code passRunning} is
   * true while a pass is in flight.
   */
  DeferredJobRunner(
      DeferredJobs jobs,
      PipelineService pipeline,
      JobQueue queue,
      DeferredNotifier notifier,
      LongPredicate hasAccess,
      Clock clock,
      int maxAttempts,
      AtomicBoolean passRunning) {
    this.passRunning = passRunning;
    this.jobs = jobs;
    this.pipeline = pipeline;
    this.queue = queue;
    this.notifier = notifier;
    this.hasAccess = hasAccess;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  /**
   * One pass over a snapshot of the queue. Returns at once; does nothing if a pass is still running
   * or the queue is empty.
   */
  @Scheduled(
      fixedDelayString = "${app.deferred.retry-interval}",
      initialDelayString = "${app.deferred.retry-interval}")
  public void retryAll() {
    if (!passRunning.compareAndSet(false, true)) {
      return;
    }
    List<String> ids = jobs.entries().stream().map(DeferredJob::id).toList();
    submitFrom(ids, 0);
  }

  private void submitFrom(List<String> ids, int index) {
    if (index >= ids.size()) {
      passRunning.set(false);
      return;
    }
    try {
      queue
          .submit(() -> retry(ids.get(index)))
          .whenComplete((result, error) -> {
            if (error instanceof CancellationException) {
              passRunning.set(false);
            } else {
              submitFrom(ids, index + 1);
            }
          });
    } catch (RejectedExecutionException e) {
      passRunning.set(false);
    }
  }

  /** The work of one entry, on the worker thread. */
  void retry(String id) {
    Optional<DeferredJob> found = jobs.find(id);
    if (found.isEmpty()) {
      return;
    }
    DeferredJob job = found.get();
    if (!hasAccess.test(job.ownerUserId())) {
      removeQuietly(id);
      if (LOG.isInfoEnabled()) {
        LOG.info("Deferred job {} dropped: the user has no access any more", id);
      }
      return;
    }
    try {
      pipeline.run(
          new JobRequest(
              JobStore.newJobId(), job.ownerUserId(), job.chatId(), job.url(), clock.instant()),
          NO_PROGRESS);
    } catch (JobFailure failure) {
      onFailure(job, failure);
      return;
    } catch (RuntimeException e) {
      onFailure(job, JobFailure.of(JobFailure.Kind.FAILED, e));
      return;
    }
    removeQuietly(id);
    notifier.completed(job.chatId(), job.statusMessageId());
  }

  private void onFailure(DeferredJob job, JobFailure failure) {
    if (Thread.currentThread().isInterrupted()) {
      return;
    }
    if (failure.isServiceOutage()) {
      onOutage(job, failure);
      return;
    }
    if (LOG.isWarnEnabled()) {
      LOG.warn("Deferred job {} failed: {}", job.id(), failure.kind());
    }
    removeQuietly(job.id());
    notifier.failed(job.chatId(), job.statusMessageId(), failure);
  }

  /**
   * The give-up decision uses the stored count plus one, so it still closes the entry at the limit
   * when the new count could not be written.
   */
  private void onOutage(DeferredJob job, JobFailure failure) {
    int attempts = job.attempts() + 1;
    try {
      if (jobs.recordFailedAttempt(job.id()).isEmpty()) {
        return;
      }
    } catch (DeferredJobsException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not record attempt {} of deferred job {}", attempts, job.id(), e);
      }
    }
    if (LOG.isInfoEnabled()) {
      LOG.info("Deferred job {} attempt {} ended in {}", job.id(), attempts, failure.kind());
    }
    if (attempts >= maxAttempts) {
      removeQuietly(job.id());
      notifier.gaveUp(job.chatId(), job.statusMessageId());
    }
  }

  private void removeQuietly(String id) {
    try {
      jobs.remove(id);
    } catch (DeferredJobsException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not remove deferred job {}", id, e);
      }
    }
  }
}
