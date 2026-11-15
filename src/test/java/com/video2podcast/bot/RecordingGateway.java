package com.video2podcast.bot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test double that records outgoing messages. */
class RecordingGateway implements TelegramGateway {

  record Sent(long chatId, String html, List<InlineButton> buttons) {}

  final List<Sent> messages = new ArrayList<>();

  @Override
  public int sendMessage(long chatId, String html, List<InlineButton> buttons) {
    messages.add(new Sent(chatId, html, buttons));
    return messages.size();
  }

  @Override
  public void editMessage(long chatId, int messageId, String html, List<InlineButton> buttons) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void sendDocument(long chatId, Path file, String fileName, String caption) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void sendAudio(long chatId, Path file, String title, String performer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void answerCallback(String callbackQueryId, String text) {
    throw new UnsupportedOperationException();
  }
}
