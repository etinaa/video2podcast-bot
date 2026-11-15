package com.video2podcast.bot;

import java.util.List;
import java.util.Locale;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

public class BotUpdateHandler {

  private final TelegramGateway gateway;

  public BotUpdateHandler(TelegramGateway gateway) {
    this.gateway = gateway;
  }

  public void handle(Update update) {
    if (!update.hasMessage()) {
      return;
    }
    Message message = update.getMessage();
    if (!isPrivateTextMessage(message)) {
      return;
    }
    if ("/whoami".equals(commandOf(message.getText()))) {
      long userId = message.getFrom().getId();
      gateway.sendMessage(
          message.getChatId(), "Your Telegram id: <code>" + userId + "</code>", List.of());
    }
  }

  private static boolean isPrivateTextMessage(Message message) {
    return Boolean.TRUE.equals(message.getChat().isUserChat())
        && message.getFrom() != null
        && message.hasText();
  }

  /** Returns the lower-cased command without a "@BotName" suffix, e.g. "/whoami". */
  private static String commandOf(String text) {
    String firstToken = text.strip().split("\\s+", 2)[0];
    int at = firstToken.indexOf('@');
    String command = at < 0 ? firstToken : firstToken.substring(0, at);
    return command.toLowerCase(Locale.ROOT);
  }
}
