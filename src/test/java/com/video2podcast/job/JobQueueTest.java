package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobQueueTest {

  private final JobQueue queue = new JobQueue(Duration.ofMillis(200));

  @AfterEach
  void tearDown() {
    queue.close();
  }

  @Test
  void tasksRunInSubmissionOrderAndNeverAtTheSameTime() throws Exception {
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (int i = 0; i < 10; i++) {
      int id = i;
      futures.add(
          queue.submit(() -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(5);
            order.add(id);
            running.decrementAndGet();
          }));
    }
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

    assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    assertThat(maxRunning).hasValue(1);
  }

  @Test
  void aFailingTaskDoesNotStopTheTasksBehindIt() throws Exception {
    CompletableFuture<Void> failing =
        queue.submit(() -> {
          throw new IllegalStateException("boom");
        });
    List<String> ran = Collections.synchronizedList(new ArrayList<>());

    queue.submit(() -> ran.add("after")).get(5, TimeUnit.SECONDS);

    assertThat(ran).containsExactly("after");
    assertThat(failing).isCompletedExceptionally();
    assertThatThrownBy(() -> failing.get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasRootCauseMessage("boom");
  }

  @Test
  void sizeCountsWaitingAndRunningTasksUntilTheyFinish() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<Void> first =
        queue.submit(() -> {
          started.countDown();
          await(release);
        });
    CompletableFuture<Void> second = queue.submit(() -> {});
    started.await(5, TimeUnit.SECONDS);

    assertThat(queue.size()).isEqualTo(2);

    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    second.get(5, TimeUnit.SECONDS);
    assertThat(queue.size()).isZero();
  }

  @Test
  void sizeIsZeroInsideTheCallbackOfTheLastSucceededTask() throws Exception {
    AtomicInteger observed = new AtomicInteger(-1);
    CountDownLatch called = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.submit(() -> await(release));
    CompletableFuture<Void> last = queue.submit(() -> {});
    last.whenComplete((value, error) -> {
      observed.set(queue.size());
      called.countDown();
    });

    release.countDown();
    assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();

    assertThat(last.get(5, TimeUnit.SECONDS)).isNull();
    assertThat(observed).hasValue(0);
  }

  @Test
  void sizeIsZeroInsideTheCallbackOfTheLastFailedTask() throws Exception {
    IllegalStateException thrown = new IllegalStateException("boom");
    AtomicInteger observed = new AtomicInteger(-1);
    AtomicReference<Throwable> callbackError = new AtomicReference<>();
    CountDownLatch called = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.submit(() -> await(release));
    CompletableFuture<Void> last =
        queue.submit(() -> {
          throw thrown;
        });
    last.whenComplete((value, error) -> {
      observed.set(queue.size());
      callbackError.set(error);
      called.countDown();
    });

    release.countDown();
    assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> last.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCause(thrown);
    assertThat(callbackError.get()).isSameAs(thrown);
    assertThat(observed).hasValue(0);
  }

  @Test
  void sizeIsZeroInsideTheCallbackOfTheLastTaskThatThrowsAnError() throws Exception {
    AtomicInteger observed = new AtomicInteger(-1);
    CountDownLatch called = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    queue.submit(() -> await(release));
    CompletableFuture<Void> last =
        queue.submit(() -> {
          throw new AssertionError("boom");
        });
    last.whenComplete((value, error) -> {
      observed.set(queue.size());
      called.countDown();
    });

    release.countDown();
    assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> last.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Queued task ended abnormally");
    assertThat(observed).hasValue(0);
  }

  @Test
  void aFailedTaskIsNoLongerCounted() throws Exception {
    CompletableFuture<Void> failing =
        queue.submit(() -> {
          throw new IllegalStateException("boom");
        });
    queue.submit(() -> {}).get(5, TimeUnit.SECONDS);

    assertThat(failing).isCompletedExceptionally();
    assertThat(queue.size()).isZero();
  }

  @Test
  void aTaskThatThrowsAnErrorCompletesItsFutureAndDoesNotStopTheQueue() throws Exception {
    CompletableFuture<Void> broken =
        queue.submit(() -> {
          throw new AssertionError("boom");
        });
    queue.submit(() -> {}).get(5, TimeUnit.SECONDS);

    assertThat(broken).isCompletedExceptionally();
    assertThat(queue.size()).isZero();
  }

  @Test
  void closeWaitsForQueuedTasksAndThenRefusesNewOnes() {
    List<String> ran = Collections.synchronizedList(new ArrayList<>());
    queue.submit(() -> ran.add(slow("one")));
    queue.submit(() -> ran.add("two"));

    queue.close();

    assertThat(ran).containsExactly("one", "two");
    assertThatThrownBy(() -> queue.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
    assertThat(queue.size()).isZero();
  }

  @Test
  void closeGivesUpOnATaskThatDoesNotFinishInTime() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<Void> stuck =
        queue.submit(() -> {
          started.countDown();
          await(new CountDownLatch(1));
        });
    started.await(5, TimeUnit.SECONDS);

    queue.close();

    assertThat(stuck).isCancelled();
    assertThat(queue.size()).isZero();
  }

  @Test
  void closeCancelsTasksThatWereStillWaitingSoNobodyWaitsForeverAndTheCountIsRight()
      throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    List<String> ran = Collections.synchronizedList(new ArrayList<>());
    CompletableFuture<Void> stuck =
        queue.submit(() -> {
          started.countDown();
          await(new CountDownLatch(1));
        });
    CompletableFuture<Void> second = queue.submit(() -> ran.add("second"));
    CompletableFuture<Void> third = queue.submit(() -> ran.add("third"));
    started.await(5, TimeUnit.SECONDS);
    assertThat(queue.size()).isEqualTo(3);

    queue.close();

    assertThat(stuck).isCancelled();
    assertThat(second).isCancelled();
    assertThat(third).isCancelled();
    assertThat(queue.size()).isZero();
    assertThat(ran).isEmpty();
    assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
  }

  @Test
  void theDefaultQueueRunsTasks() throws Exception {
    try (JobQueue defaults = new JobQueue()) {
      defaults.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void closeStopsATaskThatNeverFinishesWhenInterrupted() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<Void> stuck =
        queue.submit(() -> {
          started.countDown();
          await(new CountDownLatch(1));
        });
    started.await(5, TimeUnit.SECONDS);
    Thread.currentThread().interrupt();

    queue.close();

    assertThat(Thread.interrupted()).isTrue();
    assertThat(stuck).isCancelled();
    assertThat(queue.size()).isZero();
  }

  private static String slow(String value) {
    sleep(50);
    return value;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
