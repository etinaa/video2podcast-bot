package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.profiles.active=dev")
class DevProfileDefaultsTest {

  @Autowired AppProperties props;

  @Test
  void usesLocalDefaultsWhenNothingIsSet() {
    assertThat(props.access().file()).isEqualTo(Path.of("data/allowed-users.json"));
    assertThat(props.jobs().workDir()).isEqualTo(Path.of("work"));
    assertThat(props.ollama().baseUrl()).isEqualTo("http://localhost:11434");
    assertThat(props.whisper().baseUrl()).isEqualTo("http://localhost:8081");
  }
}
