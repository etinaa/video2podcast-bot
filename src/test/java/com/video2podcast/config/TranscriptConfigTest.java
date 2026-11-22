package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.transcript.TranscriptionClient;
import com.video2podcast.transcript.WhisperClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TranscriptConfigTest {

  private static AppProperties props() {
    return new AppProperties(
        null,
        null,
        null,
        new AppProperties.Whisper("http://localhost:8081", Duration.ofMinutes(10)),
        null,
        null);
  }

  @Test
  void createsTheTranscriptionBeans() {
    new ApplicationContextRunner()
        .withBean(AppProperties.class, TranscriptConfigTest::props)
        .withUserConfiguration(TranscriptConfig.class)
        .run(context -> {
          assertThat(context).hasSingleBean(WhisperClient.class);
          assertThat(context).hasSingleBean(TranscriptionClient.class);
        });
  }
}
