package com.video2podcast.config;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.BotUpdateHandler;
import com.video2podcast.bot.DeliveryCallbackHandler;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.bot.TelegramClientGateway;
import com.video2podcast.bot.TelegramGateway;
import com.video2podcast.bot.UrlMessageHandler;
import okhttp3.OkHttpClient;
import org.springframework.boot.ApplicationRunner;
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
    requireAdmin(props);
    // OkHttp resends a request whose connection dropped before a response. For an upload that
    // could deliver a file twice behind the one-send guard, so the retry is switched off.
    OkHttpClient http = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
    return new OkHttpTelegramClient(
        http, requireToken(props), TelegramApiUrl.parse(props.telegram().apiUrl()));
  }

  @Bean
  TelegramGateway telegramGateway(TelegramClient client) {
    return new TelegramClientGateway(client);
  }

  @Bean
  BotUpdateHandler botUpdateHandler(
      TelegramGateway gateway,
      AccessService access,
      UrlMessageHandler urls,
      DeliveryCallbackHandler delivery) {
    return new BotUpdateHandler(gateway, access, urls, delivery);
  }

  /** At startup, tell the owner about requests that could not be delivered earlier. */
  @Bean
  ApplicationRunner resendPendingRequests(BotUpdateHandler handler) {
    return args -> handler.resendPendingNotifications();
  }

  @Bean
  TelegramBot telegramBot(AppProperties props, BotUpdateHandler handler) {
    return new TelegramBot(requireToken(props), handler);
  }

  private static void requireAdmin(AppProperties props) {
    if (props.telegram().adminUserId() <= 0) {
      throw new IllegalStateException(
          "app.telegram.admin-user-id (ADMIN_USER_ID) is required when the Telegram bot is enabled."
              + " Send /whoami to the bot to find your id.");
    }
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
