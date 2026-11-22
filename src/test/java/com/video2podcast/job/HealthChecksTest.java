package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HealthChecksTest {

  private static final Duration TIMEOUT = Duration.ofMillis(200);
  private static final ServiceProbe UP = t -> ProbeResult.up();

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(HealthChecks.class);

  @BeforeEach
  void attach() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(logs);
    Thread.interrupted();
  }

  private static ServiceProbe down(String reason) {
    return t -> ProbeResult.down(reason);
  }

  private List<String> lines() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Test
  void bothUpMeansAllReachableAndNothingIsThrown() {
    HealthChecks checks = new HealthChecks(UP, UP, TIMEOUT);

    assertThat(checks.check().allReachable()).isTrue();
    checks.requireReachable();
  }

  @Test
  void healthIsReachableOnlyWhenBothAre() {
    assertThat(new Health(ProbeResult.up(), ProbeResult.down("x")).allReachable()).isFalse();
    assertThat(new Health(ProbeResult.down("x"), ProbeResult.up()).allReachable()).isFalse();
  }

  @Test
  void whisperDownGivesTheTranscriptionFailure() {
    HealthChecks checks = new HealthChecks(down("connection refused"), UP, TIMEOUT);

    assertThatThrownBy(checks::requireReachable)
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
              assertThat(f).hasMessage("The transcription server is unreachable.");
            });
  }

  @Test
  void ollamaDownGivesTheSummaryFailure() {
    HealthChecks checks = new HealthChecks(UP, down("connection refused"), TIMEOUT);

    assertThatThrownBy(checks::requireReachable)
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.SUMMARY_UNREACHABLE));
  }

  @Test
  void bothDownGivesTheCombinedMessage() {
    HealthChecks checks = new HealthChecks(down("a"), down("b"), TIMEOUT);

    assertThatThrownBy(checks::requireReachable)
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
              assertThat(f)
                  .hasMessage(
                      "The transcription server is unreachable. The summary server is unreachable.");
            });
  }

  @Test
  void aProbeThatThrowsCountsAsDown() {
    ServiceProbe broken =
        t -> {
          throw new IllegalStateException("http://secret/host");
        };

    Health health = new HealthChecks(broken, UP, TIMEOUT).check();

    assertThat(health.whisper()).isEqualTo(ProbeResult.down("check failed"));
    assertThat(health.ollama().reachable()).isTrue();
  }

  @Test
  void aProbeThatReturnsNullCountsAsDown() {
    Health health = new HealthChecks(t -> null, UP, TIMEOUT).check();

    assertThat(health.whisper()).isEqualTo(ProbeResult.down("check failed"));
  }

  @Test
  void aProbeThatOverrunsIsCutOffAfterTheTimeoutPlusOneSecond() {
    CountDownLatch never = new CountDownLatch(1);
    ServiceProbe hanging =
        t -> {
          try {
            never.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return ProbeResult.up();
        };
    HealthChecks checks = new HealthChecks(hanging, hanging, TIMEOUT);

    long start = System.nanoTime();
    Health health = checks.check();
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(health.whisper()).isEqualTo(ProbeResult.down("no answer in time"));
    assertThat(health.ollama()).isEqualTo(ProbeResult.down("no answer in time"));
    assertThat(took).isBetween(Duration.ofMillis(1000), Duration.ofMillis(3500));
  }

  @Test
  void theProbesGetTheConfiguredTimeoutAndRunInParallel() {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    CountDownLatch both = new CountDownLatch(2);
    ServiceProbe probe =
        t -> {
          assertThat(t).isEqualTo(TIMEOUT);
          peak.accumulateAndGet(running.incrementAndGet(), Math::max);
          both.countDown();
          try {
            both.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          running.decrementAndGet();
          return ProbeResult.up();
        };

    assertThat(new HealthChecks(probe, probe, TIMEOUT).check().allReachable()).isTrue();
    assertThat(peak).hasValue(2);
  }

  @Test
  void anInterruptedCheckReportsBothDownAndKeepsTheFlag() {
    HealthChecks checks = new HealthChecks(UP, UP, TIMEOUT);
    Thread.currentThread().interrupt();

    Health health = checks.check();

    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    assertThat(health.whisper()).isEqualTo(ProbeResult.down("interrupted"));
    assertThat(health.ollama()).isEqualTo(ProbeResult.down("interrupted"));
  }

  @Test
  void anInterruptedRequireReachableFailsTheJobAndKeepsTheFlag() {
    HealthChecks checks = new HealthChecks(UP, UP, TIMEOUT);
    Thread.currentThread().interrupt();

    assertThatThrownBy(checks::requireReachable)
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED));
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void startupLogsOneLinePerServiceWithTheReasonOnly() {
    new HealthChecks(UP, down("connection refused"), TIMEOUT).logStartup();

    assertThat(lines())
        .containsExactly(
            "Health check: whisper reachable",
            "Health check: ollama unreachable (connection refused)");
    assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
    assertThat(logs.list.get(1).getLevel()).isEqualTo(Level.WARN);
    assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
  }

  @Test
  void startupLogsUnreachableWhisperAndReachableOllama() {
    new HealthChecks(down("host not found"), UP, TIMEOUT).logStartup();

    assertThat(lines())
        .containsExactly(
            "Health check: whisper unreachable (host not found)", "Health check: ollama reachable");
  }

  @Test
  void requireReachableLogsNothing() {
    new HealthChecks(UP, UP, TIMEOUT).requireReachable();

    assertThat(logs.list).isEmpty();
  }

  @Test
  void aProbeThatIgnoresInterruptionDoesNotHoldTheCheckBeyondTheDeadline() {
    CountDownLatch release = new CountDownLatch(1);
    ServiceProbe stubborn =
        t -> {
          while (true) {
            try {
              release.await();
              return ProbeResult.up();
            } catch (InterruptedException ignored) {
              // keeps waiting until the test releases it
            }
          }
        };
    HealthChecks checks = new HealthChecks(stubborn, stubborn, TIMEOUT);

    try {
      long start = System.nanoTime();
      Health health = checks.check();
      Duration took = Duration.ofNanos(System.nanoTime() - start);

      assertThat(health.whisper()).isEqualTo(ProbeResult.down("no answer in time"));
      assertThat(health.ollama()).isEqualTo(ProbeResult.down("no answer in time"));
      assertThat(release.getCount()).isEqualTo(1);
      assertThat(took).isLessThan(Duration.ofMillis(3500));
    } finally {
      release.countDown();
    }
  }

  @Test
  void anInterruptedCallerDoesNotWaitForAStubbornProbe() {
    CountDownLatch release = new CountDownLatch(1);
    ServiceProbe stubborn =
        t -> {
          while (true) {
            try {
              release.await();
              return ProbeResult.up();
            } catch (InterruptedException ignored) {
              // keeps waiting until the test releases it
            }
          }
        };
    HealthChecks checks = new HealthChecks(stubborn, UP, Duration.ofSeconds(30));
    Thread.currentThread().interrupt();

    try {
      long start = System.nanoTime();
      checks.check();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    } finally {
      release.countDown();
    }
  }
}
