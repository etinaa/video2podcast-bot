package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.access.AccessService;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"app.telegram.bot-token=test-token", "app.telegram.admin-user-id=42"})
class AppPropertiesTest {

  @Autowired AppProperties props;
  @Autowired AccessService accessService;

  @Test
  void bindsValuesFromConfiguration() {
    assertThat(props.telegram().botToken()).isEqualTo("test-token");
    assertThat(props.telegram().adminUserId()).isEqualTo(42L);
  }

  @Test
  void usesSpecDefaults() {
    assertThat(props.ollama().model()).isEqualTo("qwen3:32b");
    assertThat(props.ollama().baseUrl()).isEqualTo("http://host.containers.internal:11434");
    assertThat(props.whisper().baseUrl()).isEqualTo("http://host.containers.internal:8081");
    assertThat(props.whisper().timeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(props.access().file()).isEqualTo(Path.of("/data/allowed-users.json"));
    assertThat(props.access().requestCooldown()).isEqualTo(Duration.ofHours(24));
    assertThat(props.jobs().workDir()).isEqualTo(Path.of("/work"));
    assertThat(props.jobs().maxVideoDuration()).isEqualTo(Duration.ofHours(3));
    assertThat(props.jobs().retention()).isEqualTo(Duration.ofHours(1));
    assertThat(props.jobs().metadataTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(props.jobs().downloadTimeout()).isEqualTo(Duration.ofMinutes(30));
    assertThat(props.jobs().ffmpegTimeout()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void accessServiceKnowsTheConfiguredOwner() {
    assertThat(accessService.isOwner(42L)).isTrue();
    assertThat(accessService.isAllowed(43L)).isFalse();
  }
}
