package com.video2podcast.config;

import java.net.URI;
import org.telegram.telegrambots.meta.TelegramUrl;

/** Turns the {@code app.telegram.api-url} setting into the client's {@link TelegramUrl}. */
final class TelegramApiUrl {

  private TelegramApiUrl() {}

  /** A blank value means the real Telegram API. */
  static TelegramUrl parse(String apiUrl) {
    if (apiUrl == null || apiUrl.isBlank()) {
      return TelegramUrl.DEFAULT_URL;
    }
    URI uri = URI.create(apiUrl.trim());
    int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
    return TelegramUrl.builder().schema(uri.getScheme()).host(uri.getHost()).port(port).build();
  }
}
