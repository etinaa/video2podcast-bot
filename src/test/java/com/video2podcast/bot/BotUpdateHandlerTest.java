package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

class BotUpdateHandlerTest {

  private final RecordingGateway gateway = new RecordingGateway();
  private final BotUpdateHandler handler = new BotUpdateHandler(gateway);

  @ParameterizedTest
  @ValueSource(strings = {"/whoami", "/WhoAmI", "  /whoami  ", "/whoami@SomeBot", "/whoami extra"})
  void whoamiRepliesWithTheSendersIdInAPrivateChat(String text) {
    handler.handle(update(message("private", 555L, 123L, text)));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(555L);
          assertThat(sent.html()).isEqualTo("Your Telegram id: <code>123</code>");
          assertThat(sent.buttons()).isEmpty();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"group", "supergroup", "channel"})
  void ignoresMessagesFromNonPrivateChats(String chatType) {
    handler.handle(update(message(chatType, -1L, 123L, "/whoami")));

    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresMessagesWithoutASender() {
    Message message = message("private", 555L, 123L, "/whoami");
    message.setFrom(null);

    handler.handle(update(message));

    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresMessagesWithoutText() {
    handler.handle(update(message("private", 555L, 123L, null)));

    assertThat(gateway.messages).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "/start", "/whoamix", "whoami"})
  void ignoresOtherText(String text) {
    handler.handle(update(message("private", 555L, 123L, text)));

    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresUpdatesWithoutAMessage() {
    Update update = new Update();
    update.setCallbackQuery(new CallbackQuery());

    handler.handle(update);

    assertThat(gateway.messages).isEmpty();
  }

  private static Update update(Message message) {
    Update update = new Update();
    update.setMessage(message);
    return update;
  }

  private static Message message(String chatType, long chatId, long userId, String text) {
    return Message.builder()
        .messageId(1)
        .chat(Chat.builder().id(chatId).type(chatType).build())
        .from(User.builder().id(userId).firstName("Test").isBot(false).build())
        .text(text)
        .build();
  }
}
