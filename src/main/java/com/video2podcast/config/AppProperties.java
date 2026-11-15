package com.video2podcast.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Telegram telegram, Access access, Ollama ollama, Whisper whisper, Jobs jobs) {

  public record Telegram(String botToken, long adminUserId, boolean enabled) {}

  public record Access(Path file, Duration requestCooldown) {}

  public record Ollama(String baseUrl, String model) {}

  public record Whisper(String baseUrl) {}

  public record Jobs(Path workDir, Duration maxVideoDuration, Duration retention) {}
}
