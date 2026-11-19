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
      "OLLAMA_NUM_CTX=4096",
      "OLLAMA_TIMEOUT=PT3S"
    })
class OllamaBindingTest {

  @Autowired AppProperties props;

  @Test
  void contextAndTimeoutAreReadFromTheEnvironment() {
    assertThat(props.ollama().numCtx()).isEqualTo(4096);
    assertThat(props.ollama().timeout()).isEqualTo(Duration.ofSeconds(3));
  }
}
