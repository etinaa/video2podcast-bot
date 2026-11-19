package com.video2podcast.bot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test double that records outgoing messages. */
class RecordingGateway implements TelegramGateway {

  record Sent(long chatId, String html, List<InlineButton> buttons) {}

  record Answer(String callbackId, String text) {}

  final List<Sent> messages = new ArrayList<>();

  record Edit(long chatId, int messageId, String html, List<InlineButton> buttons) {}

  final List<Answer> answers = new ArrayList<>();
  final List<Edit> edits = new ArrayList<>();

  /** Chats for which sending fails, to simulate a user who blocked the bot. */
  final java.util.Set<Long> failingChats = new java.util.HashSet<>();

  boolean failEdits;

  @Override
  public int sendMessage(long chatId, String html, List<InlineButton> buttons) {
    if (failMessages || failingChats.contains(chatId)) {
      throw new TelegramGatewayException("chat " + chatId + " is unreachable", null);
    }
    messages.add(new Sent(chatId, html, buttons));
    return messages.size();
  }

  @Override
  public void editMessage(long chatId, int messageId, String html, List<InlineButton> buttons) {
    if (failEdits) {
      throw new TelegramGatewayException("cannot edit", null);
    }
    edits.add(new Edit(chatId, messageId, html, buttons));
  }

  record Buttons(long chatId, int messageId, List<InlineButton> buttons) {}

  record Document(long chatId, Path file, String fileName, String caption) {}

  final List<Buttons> buttonEdits = new ArrayList<>();
  final List<Document> documents = new ArrayList<>();

  /** Fails the next document sends, to simulate a Telegram error. */
  boolean failDocuments;

  boolean failAnswers;
  boolean failButtons;
  boolean failMessages;

  @Override
  public void editButtons(long chatId, int messageId, List<InlineButton> buttons) {
    if (failButtons) {
      throw new TelegramGatewayException("cannot edit buttons", null);
    }
    buttonEdits.add(new Buttons(chatId, messageId, buttons));
  }

  @Override
  public void sendDocument(long chatId, Path file, String fileName, String caption) {
    if (failDocuments) {
      throw new TelegramGatewayException("cannot send document", null);
    }
    documents.add(new Document(chatId, file, fileName, caption));
  }

  @Override
  public void sendAudio(long chatId, Path file, String title, String performer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void answerCallback(String callbackQueryId, String text) {
    if (failAnswers) {
      throw new TelegramGatewayException("cannot answer", null);
    }
    answers.add(new Answer(callbackQueryId, text));
  }
}
