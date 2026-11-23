package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.video2podcast.job.HealthChecks;
import com.video2podcast.summary.OllamaClient;
import com.video2podcast.transcript.WhisperClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HealthConfigTest {

  private static AppProperties props(Duration timeout) {
    return new AppProperties(null, null, null, null, null, new AppProperties.Health(timeout), null);
  }

  private ApplicationContextRunner runner(Duration timeout) {
    return new ApplicationContextRunner()
        .withUserConfiguration(HealthConfig.class)
        .withBean(AppProperties.class, () -> props(timeout))
        .withBean(WhisperClient.class, () -> mock(WhisperClient.class))
        .withBean(OllamaClient.class, () -> mock(OllamaClient.class));
  }

  @Test
  void createsTheChecksAndTheStartupRunner() {
    runner(Duration.ofSeconds(3))
        .run(context -> {
          assertThat(context).hasSingleBean(HealthChecks.class);
          assertThat(context).hasSingleBean(ApplicationRunner.class);
        });
  }

  @Test
  void theStartupRunnerReturnsAtOnceAndStillChecksBothServices() throws Exception {
    WhisperClient whisper = mock(WhisperClient.class);
    OllamaClient ollama = mock(OllamaClient.class);
    org.mockito.Mockito.when(whisper.probe(Duration.ofSeconds(3)))
        .thenReturn(com.video2podcast.job.ProbeResult.up());
    org.mockito.Mockito.when(ollama.probe(Duration.ofSeconds(3)))
        .thenReturn(com.video2podcast.job.ProbeResult.up());
    new ApplicationContextRunner()
        .withUserConfiguration(HealthConfig.class)
        .withBean(AppProperties.class, () -> props(Duration.ofSeconds(3)))
        .withBean(WhisperClient.class, () -> whisper)
        .withBean(OllamaClient.class, () -> ollama)
        .run(context -> {
          context.getBean(ApplicationRunner.class).run(mock(ApplicationArguments.class));
          org.mockito.Mockito.verify(whisper, org.mockito.Mockito.timeout(2000))
              .probe(Duration.ofSeconds(3));
          org.mockito.Mockito.verify(ollama, org.mockito.Mockito.timeout(2000))
              .probe(Duration.ofSeconds(3));
        });
  }

  @Test
  void rejectsAMissingZeroOrNegativeTimeout() {
    for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      runner(bad).run(context -> assertThat(context).hasFailed());
    }
  }
}
