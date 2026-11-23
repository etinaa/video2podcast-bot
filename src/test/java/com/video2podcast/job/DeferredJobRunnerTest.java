package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.video2podcast.support.MutableClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

class DeferredJobRunnerTest {

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final PipelineService pipeline = mock(PipelineService.class);
  private final JobQueue queue = new JobQueue(Duration.ofMillis(200));
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicBoolean access = new AtomicBoolean(true);
  private DeferredJobs jobs;
  private DeferredJobRunner runner;

  private final DeferredNotifier notifier =
      new DeferredNotifier() {
        @Override
        public void completed(long chatId, int statusMessageId) {
          events.add("completed " + chatId + " " + statusMessageId);
        }

        @Override
        public void gaveUp(long chatId, int statusMessageId) {
          events.add("gaveUp " + chatId + " " + statusMessageId);
        }

        @Override
        public void failed(long chatId, int statusMessageId, JobFailure failure) {
          events.add("failed " + chatId + " " + statusMessageId + " " + failure.kind());
        }
      };

  private void setUp(int maxAttempts) {
    jobs = new DeferredJobs(dir.resolve("deferred-jobs.json"), clock);
    LongPredicate hasAccess = user -> access.get() && user == 7;
    runner = new DeferredJobRunner(jobs, pipeline, queue, notifier, hasAccess, clock, maxAttempts);
  }

  private String park(String id, String url) {
    jobs.park(new JobRequest(id, 7, 555, url, T0), 42);
    return id;
  }

  @AfterEach
  void tearDown() {
    queue.close();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached in 10 s");
      }
      Thread.sleep(5);
    }
  }

  @Test
  void aSuccessfulRetryRunsFromScratchWithANewJobIdAndNoProgress() {
    setUp(12);
    clock.advance(Duration.ofHours(2));
    park("first", "https://v/1");
    doAnswer(
            call -> {
              JobProgress progress = call.getArgument(1);
              progress.downloading();
              progress.transcribing(1, 2);
              progress.summarizing();
              return null;
            })
        .when(pipeline)
        .run(any(), any());

    runner.retry("first");

    ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
    verify(pipeline).run(request.capture(), any());
    assertThat(request.getValue().jobId()).isNotBlank().isNotEqualTo("first");
    assertThat(request.getValue().url()).isEqualTo("https://v/1");
    assertThat(request.getValue().ownerUserId()).isEqualTo(7);
    assertThat(request.getValue().chatId()).isEqualTo(555);
    assertThat(request.getValue().createdAt()).isEqualTo(clock.instant());
    assertThat(jobs.entries()).isEmpty();
    assertThat(events).containsExactly("completed 555 42");
  }

  @Test
  void anUnknownEntryDoesNothing() {
    setUp(12);

    runner.retry("gone");

    verify(pipeline, never()).run(any(), any());
    assertThat(events).isEmpty();
  }

  @Test
  void anOutageCountsAnAttemptAndSaysNothing() {
    setUp(3);
    park("a", "https://v/1");
    doThrow(JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE)).when(pipeline).run(any(), any());

    runner.retry("a");
    runner.retry("a");

    assertThat(jobs.find("a").orElseThrow().attempts()).isEqualTo(2);
    assertThat(events).isEmpty();
  }

  @Test
  void theLastAllowedAttemptGivesUpOnce() {
    setUp(2);
    park("a", "https://v/1");
    doThrow(JobFailure.of(JobFailure.Kind.SUMMARY_UNREACHABLE)).when(pipeline).run(any(), any());

    runner.retry("a");
    assertThat(events).isEmpty();
    runner.retry("a");

    assertThat(jobs.entries()).isEmpty();
    assertThat(events).containsExactly("gaveUp 555 42");
  }

  @Test
  void anAttemptThatCannotBeSavedStillGivesUpAtTheLimit() throws Exception {
    setUp(1);
    park("a", "https://v/1");
    Files.createDirectory(dir.resolve("deferred-jobs.json.tmp"));
    doThrow(JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE)).when(pipeline).run(any(), any());

    runner.retry("a");

    assertThat(events).containsExactly("gaveUp 555 42");
    assertThat(jobs.entries()).hasSize(1);
  }

  @Test
  void anOutageForAnEntryThatIsGoneSendsNothing() {
    setUp(1);
    park("a", "https://v/1");
    doAnswer(
            call -> {
              jobs.remove("a");
              throw JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE);
            })
        .when(pipeline)
        .run(any(), any());

    runner.retry("a");

    assertThat(events).isEmpty();
  }

  @Test
  void anotherFailureClosesTheEntryWithItsMessage() {
    setUp(12);
    park("a", "https://v/1");
    doThrow(JobFailure.of(JobFailure.Kind.PRIVATE)).when(pipeline).run(any(), any());

    runner.retry("a");

    assertThat(jobs.entries()).isEmpty();
    assertThat(events).containsExactly("failed 555 42 PRIVATE");
  }

  @Test
  void anUnexpectedExceptionClosesTheEntryAsFailed() {
    setUp(12);
    park("a", "https://v/1");
    doThrow(new IllegalStateException("boom")).when(pipeline).run(any(), any());

    runner.retry("a");

    assertThat(jobs.entries()).isEmpty();
    assertThat(events).containsExactly("failed 555 42 FAILED");
  }

  @Test
  void aRetryThatCannotRemoveTheEntryStillTellsTheUser() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    Files.createDirectory(dir.resolve("deferred-jobs.json.tmp"));

    runner.retry("a");

    assertThat(events).containsExactly("completed 555 42");
    assertThat(jobs.entries()).hasSize(1);
  }

  @Test
  void aFailureOnAnInterruptedThreadLeavesTheEntryAlone() {
    setUp(1);
    park("a", "https://v/1");
    park("b", "https://v/2");
    doAnswer(
            call -> {
              Thread.currentThread().interrupt();
              throw JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE);
            })
        .when(pipeline)
        .run(any(), any());

    try {
      runner.retry("a");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }

    assertThat(jobs.find("a").orElseThrow().attempts()).isZero();
    assertThat(events).isEmpty();
  }

  @Test
  void aUserWithoutAccessIsDroppedSilently() {
    setUp(12);
    park("a", "https://v/1");
    access.set(false);

    runner.retry("a");

    assertThat(jobs.entries()).isEmpty();
    verify(pipeline, never()).run(any(), any());
    assertThat(events).isEmpty();
  }

  @Test
  void dropForNoAccessSurvivesAFailedWrite() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    access.set(false);
    Files.createDirectory(dir.resolve("deferred-jobs.json.tmp"));

    runner.retry("a");

    verify(pipeline, never()).run(any(), any());
    assertThat(events).isEmpty();
  }

  @Test
  void retryAllRunsEveryEntryOnceInOrder() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    park("b", "https://v/2");
    park("c", "https://v/3");
    List<String> urls = new CopyOnWriteArrayList<>();
    doAnswer(
            call -> {
              urls.add(call.<JobRequest>getArgument(0).url());
              throw JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE);
            })
        .when(pipeline)
        .run(any(), any());

    runner.retryAll();
    await(() -> urls.size() == 3 && queue.size() == 0);

    assertThat(urls).containsExactly("https://v/1", "https://v/2", "https://v/3");
    assertThat(jobs.entries()).extracting(DeferredJob::attempts).containsExactly(1, 1, 1);
  }

  @Test
  void aNewPassIsPossibleOnceThePreviousOneEnded() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    doThrow(JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE)).when(pipeline).run(any(), any());

    runner.retryAll();
    await(() -> jobs.find("a").orElseThrow().attempts() == 1);
    // a call made while the first pass is still winding down does nothing, so keep calling
    await(() -> {
      runner.retryAll();
      return jobs.find("a").orElseThrow().attempts() == 2;
    });
  }

  @Test
  void aPassDoesNotOverlapAnotherPass() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            call -> {
              inside.countDown();
              release.await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(pipeline)
        .run(any(), any());

    runner.retryAll();
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    runner.retryAll();
    release.countDown();
    await(() -> !events.isEmpty());

    verify(pipeline, org.mockito.Mockito.times(1)).run(any(), any());
  }

  @Test
  void anEmptyQueueStartsNothing() {
    setUp(12);

    runner.retryAll();
    runner.retryAll();

    assertThat(queue.size()).isZero();
    verify(pipeline, never()).run(any(), any());
  }

  @Test
  void aClosedQueueEndsThePassAndAllowsTheNextOne() {
    setUp(12);
    park("a", "https://v/1");
    queue.close();

    runner.retryAll();
    runner.retryAll();

    verify(pipeline, never()).run(any(), any());
    assertThat(jobs.entries()).hasSize(1);
  }

  @Test
  void aCancelledEntryEndsThePass() throws Exception {
    setUp(12);
    park("a", "https://v/1");
    park("b", "https://v/2");
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch left = new CountDownLatch(1);
    doAnswer(
            call -> {
              inside.countDown();
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              try {
                throw JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE);
              } finally {
                left.countDown();
              }
            })
        .when(pipeline)
        .run(any(), any());

    runner.retryAll();
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    queue.close();
    assertThat(left.await(10, TimeUnit.SECONDS)).isTrue();
    // the failure handler runs right after the mock returns
    Thread.sleep(100);

    verify(pipeline, org.mockito.Mockito.times(1)).run(any(), any());
    assertThat(jobs.entries()).extracting(DeferredJob::attempts).containsExactly(0, 0);
    assertThat(events).isEmpty();
  }

  @Test
  void retryAllIsScheduledWithAFullIntervalDelay() throws Exception {
    Scheduled scheduled =
        DeferredJobRunner.class.getMethod("retryAll").getAnnotation(Scheduled.class);

    assertThat(scheduled.fixedDelayString()).isEqualTo("${app.deferred.retry-interval}");
    assertThat(scheduled.initialDelayString()).isEqualTo("${app.deferred.retry-interval}");
  }
}
