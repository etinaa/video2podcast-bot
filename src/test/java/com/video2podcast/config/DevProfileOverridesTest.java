package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The variables run.sh loads from .env must win over the dev defaults. */
@SpringBootTest(
    properties = {
      "spring.profiles.active=dev",
      "ACCESS_FILE=/custom/access.json",
      "WORK_DIR=/custom/work",
      "OLLAMA_BASE_URL=http://ollama.example:1",
      "WHISPER_BASE_URL=http://whisper.example:2"
    })
class DevProfileOverridesTest {

  @Autowired AppProperties props;

  @Test
  void environmentVariablesOverrideTheDevDefaults() {
    assertThat(props.access().file()).isEqualTo(Path.of("/custom/access.json"));
    assertThat(props.jobs().workDir()).isEqualTo(Path.of("/custom/work"));
    assertThat(props.ollama().baseUrl()).isEqualTo("http://ollama.example:1");
    assertThat(props.whisper().baseUrl()).isEqualTo("http://whisper.example:2");
  }
}
