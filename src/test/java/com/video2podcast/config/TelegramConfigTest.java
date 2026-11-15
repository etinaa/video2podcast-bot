package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.BotUpdateHandler;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.bot.TelegramGateway;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TelegramConfigTest {

  private static AppProperties props(String token, long admin, boolean enabled) {
    return new AppProperties(
        new AppProperties.Telegram(token, admin, enabled, ""),
        new AppProperties.Access(Path.of("a.json"), Duration.ofHours(24)),
        new AppProperties.Ollama("http://localhost:11434", "m"),
        new AppProperties.Whisper("http://localhost:8081"),
        new AppProperties.Jobs(Path.of("w"), Duration.ofHours(3), Duration.ofHours(1)));
  }

  private ApplicationContextRunner runner(String token, long admin, boolean enabled) {
    return new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfig.class)
        .withBean(AppProperties.class, () -> props(token, admin, enabled))
        .withBean(AccessService.class, () -> mock(AccessService.class))
        .withPropertyValues("app.telegram.enabled=" + enabled);
  }

  @Test
  void createsTheBotWhenEnabled() {
    runner("123:abc", 1L, true)
        .run(context -> {
          assertThat(context).hasSingleBean(TelegramBot.class);
          assertThat(context).hasSingleBean(TelegramGateway.class);
          assertThat(context).hasSingleBean(BotUpdateHandler.class);
          assertThat(context.getBean(TelegramBot.class).getBotToken()).isEqualTo("123:abc");
        });
  }

  @Test
  void createsNothingWhenDisabled() {
    runner("123:abc", 1L, false)
        .run(context -> assertThat(context).doesNotHaveBean(TelegramBot.class));
  }

  @Test
  void failsFastWithAClearMessageWhenTheTokenIsMissing() {
    runner(" ", 1L, true)
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseMessage(
                    "app.telegram.bot-token (TELEGRAM_BOT_TOKEN) is required when the Telegram bot is enabled"));
  }

  @Test
  void failsFastWithAClearMessageWhenTheAdminIdIsMissing() {
    runner("123:abc", 0L, true)
        .run(context ->
            assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("app.telegram.admin-user-id (ADMIN_USER_ID) is required")
                .hasMessageContaining("/whoami"));
  }
}
