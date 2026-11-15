package com.video2podcast.bot;

import com.video2podcast.access.AccessService;
import java.util.List;
import java.util.Locale;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Entry point for every update. Only private chats are served. {@code /whoami} works for everyone
 * (so people can find the id the owner needs), and everything else passes the access check here
 * before any feature handler runs.
 */
public class BotUpdateHandler {

  static final String HELP =
      "Send me a video link and I'll reply with a summary. "
          + "Then tap a button to get the audio or the transcript.\n\n"
          + "/whoami - show your Telegram id";

  private final TelegramGateway gateway;
  private final AccessService access;
  private final UnknownUserHandler unknownUsers;

  public BotUpdateHandler(TelegramGateway gateway, AccessService access) {
    this.gateway = gateway;
    this.access = access;
    this.unknownUsers = new UnknownUserHandler(gateway, access);
  }

  public void handle(Update update) {
    if (update.hasCallbackQuery()) {
      handleCallback(update.getCallbackQuery());
    } else if (update.hasMessage()) {
      handleMessage(update.getMessage());
    }
  }

  private void handleMessage(Message message) {
    if (!isPrivateTextMessage(message)) {
      return;
    }
    Sender sender = senderOf(message);
    String command = commandOf(message.getText());
    if ("/whoami".equals(command)) {
      gateway.sendMessage(
          sender.chatId(), "Your Telegram id: <code>" + sender.userId() + "</code>", List.of());
    } else if (!access.isAllowed(sender.userId())) {
      unknownUsers.handle(sender, command, HELP);
    } else if ("/start".equals(command)) {
      gateway.sendMessage(sender.chatId(), HELP, List.of());
    }
  }

  private void handleCallback(CallbackQuery callback) {
    MaybeInaccessibleMessage message = callback.getMessage();
    if (message == null || !message.isUserMessage()) {
      return;
    }
    if (!access.isAllowed(callback.getFrom().getId())) {
      gateway.answerCallback(callback.getId(), "No access.");
    }
  }

  private static Sender senderOf(Message message) {
    User from = message.getFrom();
    String name =
        from.getLastName() == null
            ? from.getFirstName()
            : from.getFirstName() + " " + from.getLastName();
    return new Sender(from.getId(), message.getChatId(), from.getUserName(), name);
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
