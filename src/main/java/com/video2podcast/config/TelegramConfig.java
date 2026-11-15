package com.video2podcast.config;

import com.video2podcast.bot.BotUpdateHandler;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.bot.TelegramClientGateway;
import com.video2podcast.bot.TelegramGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "app.telegram",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TelegramConfig {

  @Bean
  TelegramClient telegramClient(AppProperties props) {
    return new OkHttpTelegramClient(
        requireToken(props), TelegramApiUrl.parse(props.telegram().apiUrl()));
  }

  @Bean
  TelegramGateway telegramGateway(TelegramClient client) {
    return new TelegramClientGateway(client);
  }

  @Bean
  BotUpdateHandler botUpdateHandler(TelegramGateway gateway) {
    return new BotUpdateHandler(gateway);
  }

  @Bean
  TelegramBot telegramBot(AppProperties props, BotUpdateHandler handler) {
    return new TelegramBot(requireToken(props), handler);
  }

  private static String requireToken(AppProperties props) {
    String token = props.telegram().botToken();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "app.telegram.bot-token (TELEGRAM_BOT_TOKEN) is required when the Telegram bot is enabled");
    }
    return token;
  }
}
