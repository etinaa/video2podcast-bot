package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.BotUpdateHandler;
import com.video2podcast.bot.DeliveryCallbackHandler;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.bot.TelegramGateway;
import com.video2podcast.bot.UrlMessageHandler;
import com.video2podcast.job.RetentionSweeper;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TelegramConfigTest {

  private static AppProperties props(String token, long admin, boolean enabled) {
    return new AppProperties(
        new AppProperties.Telegram(token, admin, enabled, ""),
        new AppProperties.Access(Path.of("a.json"), Duration.ofHours(24)),
        new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ofMinutes(10)),
        new AppProperties.Whisper("http://localhost:8081", Duration.ofMinutes(10)),
        new AppProperties.Jobs(
            Path.of("w"),
            Duration.ofHours(3),
            Duration.ofHours(1),
            Duration.ofMinutes(1),
            Duration.ofMinutes(30),
            Duration.ofMinutes(30),
            Duration.ofMinutes(1)),
        new AppProperties.Health(Duration.ofSeconds(3)));
  }

  private ApplicationContextRunner runner(String token, long admin, boolean enabled) {
    return new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfig.class)
        .withBean(AppProperties.class, () -> props(token, admin, enabled))
        .withBean("retentionSweeper", RetentionSweeper.class, () -> mock(RetentionSweeper.class))
        .withBean(AccessService.class, () -> mock(AccessService.class))
        .withBean(UrlMessageHandler.class, () -> mock(UrlMessageHandler.class))
        .withBean(DeliveryCallbackHandler.class, () -> mock(DeliveryCallbackHandler.class))
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
  void resendsUndeliveredRequestsAtStartup() {
    runner("123:abc", 1L, true)
        .run(context ->
            assertThatCode(() -> context.getBean(ApplicationRunner.class).run(null))
                .doesNotThrowAnyException());
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

  @org.junit.jupiter.api.Test
  void theClientDoesNotResendARequestWhoseConnectionDropped() {
    com.github.tomakehurst.wiremock.WireMockServer server =
        new com.github.tomakehurst.wiremock.WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                .dynamicPort());
    server.start();
    try {
      server.stubFor(
          com.github.tomakehurst.wiremock.client.WireMock.post(
                  com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
              .willReturn(
                  com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                      .withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));
      AppProperties props =
          new AppProperties(
              new AppProperties.Telegram("123:abc", 1L, true, "http://localhost:" + server.port()),
              props("123:abc", 1L, true).access(),
              props("123:abc", 1L, true).ollama(),
              props("123:abc", 1L, true).whisper(),
              props("123:abc", 1L, true).jobs(),
              props("123:abc", 1L, true).health());

      org.telegram.telegrambots.meta.generics.TelegramClient client =
          new TelegramConfig().telegramClient(props);
      org.assertj.core.api.Assertions.assertThatThrownBy(() ->
              client.execute(
                  org.telegram.telegrambots.meta.api.methods.send.SendMessage.builder()
                      .chatId(1L)
                      .text("x")
                      .build()))
          .isInstanceOf(org.telegram.telegrambots.meta.exceptions.TelegramApiException.class);

      assertThat(server.getAllServeEvents()).hasSize(1);
    } finally {
      server.stop();
    }
  }
}
