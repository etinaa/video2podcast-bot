package com.video2podcast.job;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks that Whisper and Ollama answer, at startup (log only) and before every job. */
public class HealthChecks {

  private static final Logger LOG = LoggerFactory.getLogger(HealthChecks.class);
  private static final Duration GRACE = Duration.ofSeconds(1);

  private final ServiceProbe whisper;
  private final ServiceProbe ollama;
  private final Duration timeout;

  public HealthChecks(ServiceProbe whisper, ServiceProbe ollama, Duration timeout) {
    this.whisper = whisper;
    this.ollama = ollama;
    this.timeout = timeout;
  }

  /**
   * Runs both probes in parallel and waits at most the timeout plus one second. A probe that throws
   * or does not finish in time counts as down.
   */
  public Health check() {
    try {
      return await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ProbeResult interrupted = ProbeResult.down("interrupted");
      return new Health(interrupted, interrupted);
    }
  }

  /** Logs one line per service. Never throws. */
  public void logStartup() {
    try {
      Health health = check();
      log("whisper", health.whisper());
      log("ollama", health.ollama());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Health check: failed ({})", e.getClass().getSimpleName());
      }
    }
  }

  /**
   * @throws JobFailure {@code SERVICE_UNREACHABLE} or {@code SUMMARY_UNREACHABLE} when a service is
   *     down, {@code FAILED} when the calling thread is interrupted
   */
  public void requireReachable() {
    Health health;
    try {
      health = await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    if (!health.allReachable()) {
      throw JobFailure.unreachable(!health.whisper().reachable(), !health.ollama().reachable());
    }
  }

  private Health await() throws InterruptedException {
    FutureTask<ProbeResult> w = new FutureTask<>(() -> whisper.probe(timeout));
    FutureTask<ProbeResult> o = new FutureTask<>(() -> ollama.probe(timeout));
    Thread.ofVirtual().name("health-whisper").start(w);
    Thread.ofVirtual().name("health-ollama").start(o);
    try {
      long deadline = System.nanoTime() + timeout.plus(GRACE).toNanos();
      ProbeResult wr = result(w, deadline);
      ProbeResult or = result(o, deadline);
      return new Health(wr, or);
    } finally {
      // Interrupts a probe still running; nothing waits for one that ignores it.
      w.cancel(true);
      o.cancel(true);
    }
  }

  private static ProbeResult result(Future<ProbeResult> future, long deadline)
      throws InterruptedException {
    try {
      ProbeResult result =
          future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      return result == null ? ProbeResult.down("check failed") : result;
    } catch (TimeoutException e) {
      return ProbeResult.down("no answer in time");
    } catch (ExecutionException e) {
      return ProbeResult.down("check failed");
    }
  }

  private static void log(String service, ProbeResult result) {
    if (result.reachable()) {
      if (LOG.isInfoEnabled()) {
        LOG.info("Health check: {} reachable", service);
      }
    } else if (LOG.isWarnEnabled()) {
      LOG.warn("Health check: {} unreachable ({})", service, result.reason());
    }
  }
}
