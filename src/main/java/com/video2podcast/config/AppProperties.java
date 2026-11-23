package com.video2podcast.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Telegram telegram,
    Access access,
    Ollama ollama,
    Whisper whisper,
    Jobs jobs,
    Health health,
    Deferred deferred) {

  /** {@code apiUrl} overrides the Telegram Bot API base URL (blank means the real API). */
  public record Telegram(String botToken, long adminUserId, boolean enabled, String apiUrl) {}

  public record Access(Path file, Duration requestCooldown) {}

  /**
   * {@code numCtx} is sent as {@code options.num_ctx} on every request; {@code timeout} is how long
   * one request may wait for its answer.
   */
  public record Ollama(String baseUrl, String model, int numCtx, Duration timeout) {}

  /** {@code timeout} is how long one chunk may wait for its answer. */
  public record Whisper(String baseUrl, Duration timeout) {}

  /** {@code timeout} is how long one service check may take, at startup and before each job. */
  public record Health(Duration timeout) {}

  public record Jobs(
      Path workDir,
      Duration maxVideoDuration,
      Duration retention,
      Duration metadataTimeout,
      Duration downloadTimeout,
      Duration ffmpegTimeout,
      Duration sweepInterval) {}

  /**
   * {@code retryInterval} is the wait between two passes over the parked links (and before the
   * first one after a start); {@code maxAttempts} is how many retries an entry gets.
   */
  public record Deferred(Duration retryInterval, int maxAttempts) {

    public Deferred {
      if (retryInterval == null || retryInterval.isZero() || retryInterval.isNegative()) {
        throw new IllegalArgumentException("DEFERRED_RETRY_INTERVAL must be a positive duration");
      }
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("DEFERRED_MAX_ATTEMPTS must be at least 1");
      }
    }
  }
}
