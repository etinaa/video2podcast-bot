package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "app.telegram.bot-token=test-token",
      "app.telegram.admin-user-id=42",
      "WHISPER_TIMEOUT=PT2S"
    })
class WhisperTimeoutBindingTest {

  @Autowired AppProperties props;

  @Test
  void whisperTimeoutIsReadFromTheEnvironmentVariable() {
    assertThat(props.whisper().timeout()).isEqualTo(Duration.ofSeconds(2));
  }
}
