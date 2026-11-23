package com.video2podcast.bot;

import java.nio.file.Path;
import java.util.List;

/** The only way the rest of the bot talks to Telegram. All text is sent as HTML. */
public interface TelegramGateway {

  /** Sends an HTML message with an optional single row of buttons and returns its message id. */
  int sendMessage(long chatId, String html, List<InlineButton> buttons);

  /** Replaces the text and buttons of a message this bot sent earlier. */
  void editMessage(long chatId, int messageId, String html, List<InlineButton> buttons);

  /**
   * Replaces only the buttons of a message this bot sent earlier; the text stays. An empty list
   * removes the keyboard.
   *
   * @throws TelegramGatewayException if Telegram refuses or cannot be reached
   */
  void editButtons(long chatId, int messageId, List<InlineButton> buttons);

  void sendDocument(long chatId, Path file, String fileName, String caption);

  /**
   * Sends an audio file that plays in Telegram's player.
   *
   * @param fileName shown to the user, e.g. "Budget meeting.m4a"
   * @param caption HTML, may be null
   * @param duration length in seconds shown by the player; zero or negative leaves it out
   * @throws TelegramGatewayException if Telegram refuses or cannot be reached
   */
  void sendAudio(
      long chatId,
      Path file,
      String fileName,
      String title,
      String performer,
      String caption,
      long duration);

  /** Acknowledges a button tap, optionally showing a short notice to the user. */
  void answerCallback(String callbackQueryId, String text);
}
