package com.video2podcast.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Telegram telegram, Access access, Ollama ollama, Whisper whisper, Jobs jobs) {

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

  public record Jobs(
      Path workDir,
      Duration maxVideoDuration,
      Duration retention,
      Duration metadataTimeout,
      Duration downloadTimeout,
      Duration ffmpegTimeout,
      Duration sweepInterval) {}
}
