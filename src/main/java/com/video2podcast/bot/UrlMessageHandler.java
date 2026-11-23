package com.video2podcast.bot;

import com.video2podcast.job.DeferredJobs;
import com.video2podcast.job.DeferredJobsException;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobRequest;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.PipelineService;
import com.video2podcast.media.UrlValidator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a link message of an allowed user into a queued job. The first message the user gets, which
 * is also the acknowledgement, shows the queue position and is then edited through the stages.
 */
public class UrlMessageHandler {

  private static final Logger LOG = LoggerFactory.getLogger(UrlMessageHandler.class);

  private static final int CAUSE_LIMIT = 200;
  private static final Pattern UNSAFE_WORD = Pattern.compile("\\S*[/\\\\]\\S*");
  private static final Duration STATUS_WAIT = Duration.ofSeconds(30);

  private final TelegramGateway gateway;
  private final UrlValidator validator;
  private final JobQueue queue;
  private final PipelineService pipeline;
  private final DeferredJobs deferred;
  private final Clock clock;
  private final Duration statusWait;

  public UrlMessageHandler(
      TelegramGateway gateway,
      UrlValidator validator,
      JobQueue queue,
      PipelineService pipeline,
      DeferredJobs deferred,
      Clock clock) {
    this(gateway, validator, queue, pipeline, deferred, clock, STATUS_WAIT);
  }

  /** {@code statusWait} is how long a started job waits for its status message to exist. */
  UrlMessageHandler(
      TelegramGateway gateway,
      UrlValidator validator,
      JobQueue queue,
      PipelineService pipeline,
      DeferredJobs deferred,
      Clock clock,
      Duration statusWait) {
    this.gateway = gateway;
    this.validator = validator;
    this.queue = queue;
    this.pipeline = pipeline;
    this.deferred = deferred;
    this.clock = clock;
    this.statusWait = statusWait;
  }

  /** {@code text} is the whole message text; it is stripped here. */
  void handle(Sender sender, String text) {
    String url = text.strip();
    try {
      validator.validate(url);
    } catch (JobFailure failure) {
      tellFailure(sender.chatId(), failure);
      return;
    }
    JobRequest request =
        new JobRequest(JobStore.newJobId(), sender.userId(), sender.chatId(), url, clock.instant());
    CompletableFuture<Integer> statusId = new CompletableFuture<>();
    AtomicReference<JobFailure> failure = new AtomicReference<>();
    AtomicBoolean parked = new AtomicBoolean();
    CompletableFuture<Void> future;
    try {
      future = queue.submit(() -> runJob(request, statusId, failure, parked));
    } catch (RejectedExecutionException e) {
      tellFailure(sender.chatId(), JobFailure.of(JobFailure.Kind.FAILED, e));
      return;
    }
    int id;
    try {
      id = gateway.sendMessage(sender.chatId(), "Queued: position " + queue.size(), List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not send the queued message to chat {}", sender.chatId(), e);
      }
      statusId.completeExceptionally(e);
      return;
    }
    statusId.complete(id);
    future.whenCompleteAsync(
        (result, error) ->
            finish(request.jobId(), sender.chatId(), id, failure.get(), parked.get(), error),
        Thread::startVirtualThread);
  }

  /** Runs on the worker thread. It never throws for an expected failure. */
  private void runJob(
      JobRequest request,
      CompletableFuture<Integer> statusId,
      AtomicReference<JobFailure> failure,
      AtomicBoolean parked) {
    int id;
    try {
      id = statusId.get(statusWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      return;
    } catch (TimeoutException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("No status message for job {} within {}", request.jobId(), statusWait);
      }
      failure.set(JobFailure.of(JobFailure.Kind.FAILED, e));
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (LOG.isWarnEnabled()) {
        LOG.warn("Interrupted while waiting for the status message of job {}", request.jobId());
      }
      failure.set(JobFailure.of(JobFailure.Kind.FAILED, e));
      return;
    }
    try {
      pipeline.run(request, new StatusReporter(gateway, request.chatId(), id));
    } catch (JobFailure f) {
      if (f.isServiceOutage() && tryPark(request, id)) {
        parked.set(true);
        // On the worker, before the next task: a retry of this entry cannot run, and so cannot
        // edit the status, until this edit is done.
        editStatus(request.chatId(), id, DeferredMessages.PARKED);
      } else {
        failure.set(f);
      }
    } catch (RuntimeException e) {
      // PipelineService has logged the stack trace; finish logs the failure.
      failure.set(JobFailure.of(JobFailure.Kind.FAILED, e));
    }
  }

  /** Keeps the link for a retry after the service is back; false if that could not be saved. */
  private boolean tryPark(JobRequest request, int statusId) {
    try {
      deferred.park(request, statusId);
      return true;
    } catch (DeferredJobsException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not park job {}", request.jobId(), e);
      }
      return false;
    }
  }

  /** Runs after the job has left the queue. Every Telegram problem is logged and dropped. */
  private void finish(
      String jobId,
      long chatId,
      int statusId,
      JobFailure failure,
      boolean parked,
      Throwable error) {
    if (parked) {
      if (LOG.isInfoEnabled()) {
        LOG.info("Job {} parked until the service recovers", jobId);
      }
      return;
    }
    JobFailure outcome =
        failure == null && error != null ? JobFailure.of(JobFailure.Kind.FAILED, error) : failure;
    if (outcome == null) {
      editStatus(chatId, statusId, "Done.");
    } else {
      logFailure(jobId, outcome);
      tellFailure(chatId, outcome);
      editStatus(chatId, statusId, "Failed.");
    }
  }

  /**
   * The kind and the cause as its class and a sanitized first message line: any word with a slash
   * (a URL or a path) is replaced and the text is cut, so no stderr dump gets into the log.
   */
  private static void logFailure(String jobId, JobFailure failure) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("Job {} failed: {} (cause: {})", jobId, failure.kind(), describeCause(failure));
    }
  }

  private static String describeCause(JobFailure failure) {
    Throwable cause = failure.getCause();
    if (cause == null) {
      return "none";
    }
    String name = cause.getClass().getSimpleName();
    String message = cause.getMessage();
    if (message == null || message.isBlank()) {
      return name;
    }
    String line = message.strip().lines().findFirst().orElse("");
    String safe = UNSAFE_WORD.matcher(line).replaceAll("<redacted>").strip();
    if (safe.length() > CAUSE_LIMIT) {
      safe = safe.substring(0, CAUSE_LIMIT) + "...";
    }
    return name + ": " + safe;
  }

  private void tellFailure(long chatId, JobFailure failure) {
    try {
      gateway.sendMessage(chatId, Html.escape(failure.getMessage()), List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not send a failure message to chat {}", chatId, e);
      }
    }
  }

  private void editStatus(long chatId, int statusId, String text) {
    try {
      gateway.editMessage(chatId, statusId, text, List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not edit the status message {} in chat {}", statusId, chatId, e);
      }
    }
  }
}
