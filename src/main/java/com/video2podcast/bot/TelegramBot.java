package com.video2podcast.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Registers with the long-polling starter and forwards every update to {@link BotUpdateHandler}.
 */
public class TelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramBot.class);

  private final String botToken;
  private final BotUpdateHandler handler;

  public TelegramBot(String botToken, BotUpdateHandler handler) {
    this.botToken = botToken;
    this.handler = handler;
  }

  @Override
  public String getBotToken() {
    return botToken;
  }

  @Override
  public LongPollingUpdateConsumer getUpdatesConsumer() {
    return this;
  }

  @Override
  public void consume(Update update) {
    try {
      handler.handle(update);
    } catch (RuntimeException e) {
      Integer updateId = update.getUpdateId();
      LOG.error("Failed to handle Telegram update {}", updateId, e);
    }
  }
}
