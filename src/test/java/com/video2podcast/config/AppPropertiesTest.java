package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"app.telegram.bot-token=test-token", "app.telegram.admin-user-id=42"})
class AppPropertiesTest {

  @Autowired AppProperties props;

  @Test
  void bindsValuesFromConfiguration() {
    assertThat(props.telegram().botToken()).isEqualTo("test-token");
    assertThat(props.telegram().adminUserId()).isEqualTo(42L);
  }

  @Test
  void usesSpecDefaults() {
    assertThat(props.ollama().model()).isEqualTo("qwen3:32b");
    assertThat(props.ollama().baseUrl()).isEqualTo("http://host.docker.internal:11434");
    assertThat(props.whisper().baseUrl()).isEqualTo("http://host.docker.internal:8081");
    assertThat(props.access().file()).isEqualTo(Path.of("/data/allowed-users.json"));
    assertThat(props.access().requestCooldown()).isEqualTo(Duration.ofHours(24));
    assertThat(props.jobs().workDir()).isEqualTo(Path.of("/work"));
    assertThat(props.jobs().maxVideoDuration()).isEqualTo(Duration.ofHours(3));
    assertThat(props.jobs().retention()).isEqualTo(Duration.ofHours(1));
  }
}
