package com.video2podcast.config;

import com.video2podcast.job.HealthChecks;
import com.video2podcast.summary.OllamaProbe;
import com.video2podcast.transcript.WhisperClient;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the service checks; the startup check never delays or stops the bot. */
@Configuration(proxyBeanMethods = false)
public class HealthConfig {

  @Bean
  HealthChecks healthChecks(WhisperClient whisper, OllamaProbe ollama, AppProperties props) {
    Duration timeout = props.health().timeout();
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("app.health.timeout must be positive");
    }
    return new HealthChecks(whisper::probe, ollama::probe, timeout);
  }

  @Bean
  ApplicationRunner startupHealthCheck(HealthChecks health) {
    return args -> Thread.ofVirtual().name("startup-health-check").start(health::logStartup);
  }
}
