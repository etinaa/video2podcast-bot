package com.video2podcast.bot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class TelegramClientGateway implements TelegramGateway {

  private static final String HTML = "HTML";

  private final TelegramClient client;

  public TelegramClientGateway(TelegramClient client) {
    this.client = client;
  }

  @Override
  public int sendMessage(long chatId, String html, List<InlineButton> buttons) {
    SendMessage request =
        SendMessage.builder()
            .chatId(chatId)
            .text(html)
            .parseMode(HTML)
            .linkPreviewOptions(noLinkPreview())
            .replyMarkup(keyboard(buttons))
            .build();
    return call(() -> client.execute(request)).getMessageId();
  }

  @Override
  public void editMessage(long chatId, int messageId, String html, List<InlineButton> buttons) {
    EditMessageText request =
        EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(html)
            .parseMode(HTML)
            .linkPreviewOptions(noLinkPreview())
            .replyMarkup(keyboard(buttons))
            .build();
    call(() -> client.execute(request));
  }

  @Override
  public void editButtons(long chatId, int messageId, List<InlineButton> buttons) {
    InlineKeyboardMarkup markup =
        buttons.isEmpty() ? new InlineKeyboardMarkup(List.of()) : keyboard(buttons);
    EditMessageReplyMarkup request =
        EditMessageReplyMarkup.builder()
            .chatId(chatId)
            .messageId(messageId)
            .replyMarkup(markup)
            .build();
    call(() -> client.execute(request));
  }

  @Override
  public void sendDocument(long chatId, Path file, String fileName, String caption) {
    SendDocument request =
        SendDocument.builder()
            .chatId(chatId)
            .document(new InputFile(file.toFile(), fileName))
            .caption(caption)
            .parseMode(HTML)
            .build();
    call(() -> client.execute(request));
  }

  @Override
  public void sendAudio(
      long chatId, Path file, String fileName, String title, String performer, String caption) {
    SendAudio request =
        SendAudio.builder()
            .chatId(chatId)
            .audio(new InputFile(file.toFile(), fileName))
            .title(title)
            .performer(performer)
            .caption(caption)
            .parseMode(HTML)
            .build();
    call(() -> client.execute(request));
  }

  @Override
  public void answerCallback(String callbackQueryId, String text) {
    AnswerCallbackQuery request =
        AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).build();
    call(() -> client.execute(request));
  }

  private static LinkPreviewOptions noLinkPreview() {
    return LinkPreviewOptions.builder().isDisabled(true).build();
  }

  private static InlineKeyboardMarkup keyboard(List<InlineButton> buttons) {
    if (buttons.isEmpty()) {
      return null;
    }
    List<InlineKeyboardButton> row = new ArrayList<>();
    for (InlineButton button : buttons) {
      row.add(
          InlineKeyboardButton.builder()
              .text(button.text())
              .callbackData(button.callbackData())
              .build());
    }
    return new InlineKeyboardMarkup(List.of(new InlineKeyboardRow(row)));
  }

  private static <T> T call(TelegramCall<T> call) {
    try {
      return call.execute();
    } catch (TelegramApiException e) {
      throw new TelegramGatewayException("Telegram API call failed: " + e.getMessage(), e);
    }
  }

  @FunctionalInterface
  private interface TelegramCall<T> {
    T execute() throws TelegramApiException;
  }
}
