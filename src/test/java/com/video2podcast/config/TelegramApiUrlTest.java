package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;

class TelegramApiUrlTest {

  @Test
  void blankOrMissingMeansTheRealApi() {
    assertThat(TelegramApiUrl.parse(null)).isEqualTo(TelegramUrl.DEFAULT_URL);
    assertThat(TelegramApiUrl.parse("  ")).isEqualTo(TelegramUrl.DEFAULT_URL);
  }

  @Test
  void parsesSchemeHostAndExplicitPort() {
    TelegramUrl url = TelegramApiUrl.parse("http://localhost:8123");

    assertThat(url.getSchema()).isEqualTo("http");
    assertThat(url.getHost()).isEqualTo("localhost");
    assertThat(url.getPort()).isEqualTo(8123);
  }

  @Test
  void usesTheDefaultPortOfTheScheme() {
    assertThat(TelegramApiUrl.parse("http://tg.local").getPort()).isEqualTo(80);
    assertThat(TelegramApiUrl.parse(" https://tg.local ").getPort()).isEqualTo(443);
  }
}
