package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Update;

class TelegramBotTest {

  private final BotUpdateHandler handler = mock(BotUpdateHandler.class);
  private final TelegramBot bot = new TelegramBot("token-1", handler);

  @Test
  void exposesTheTokenAndItselfAsTheUpdateConsumer() {
    assertThat(bot.getBotToken()).isEqualTo("token-1");
    assertThat(bot.getUpdatesConsumer()).isSameAs(bot);
  }

  @Test
  void forwardsUpdatesToTheHandler() {
    Update update = new Update();

    bot.consume(update);

    verify(handler).handle(update);
  }

  @Test
  void keepsPollingWhenTheHandlerFails() {
    doThrow(new IllegalStateException("boom")).when(handler).handle(any());

    assertThatCode(() -> bot.consume(new Update())).doesNotThrowAnyException();
  }
}
