package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.summary.LlmClient;
import com.video2podcast.summary.OllamaClient;
import com.video2podcast.summary.SummaryService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SummaryConfigTest {

  private static AppProperties props() {
    return new AppProperties(
        null,
        null,
        new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ofMinutes(10)),
        null,
        null,
        null);
  }

  @Test
  void createsTheSummaryBeans() {
    new ApplicationContextRunner()
        .withBean(AppProperties.class, SummaryConfigTest::props)
        .withUserConfiguration(SummaryConfig.class)
        .run(context -> {
          assertThat(context).hasSingleBean(SummaryService.class);
          assertThat(context.getBean(LlmClient.class)).isInstanceOf(OllamaClient.class);
        });
  }

  @Test
  void startupFailsForABlankModelOrANonPositiveTimeout() {
    for (AppProperties.Ollama bad :
        new AppProperties.Ollama[] {
          new AppProperties.Ollama("http://localhost:11434", " ", 8192, Duration.ofMinutes(10)),
          new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ZERO),
          new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ofSeconds(-1))
        }) {
      new ApplicationContextRunner()
          .withBean(AppProperties.class, () -> new AppProperties(null, null, bad, null, null, null))
          .withUserConfiguration(SummaryConfig.class)
          .run(context -> assertThat(context).hasFailed());
    }
  }
}
