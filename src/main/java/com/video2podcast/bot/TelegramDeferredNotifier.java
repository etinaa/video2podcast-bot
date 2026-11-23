package com.video2podcast.bot;

import com.video2podcast.job.DeferredNotifier;
import com.video2podcast.job.JobFailure;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tells the user how a deferred retry ended. Every Telegram problem is logged and dropped. */
public class TelegramDeferredNotifier implements DeferredNotifier {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramDeferredNotifier.class);

  private final TelegramGateway gateway;

  public TelegramDeferredNotifier(TelegramGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public void completed(long chatId, int statusMessageId) {
    editStatus(chatId, statusMessageId, "Done.");
  }

  @Override
  public void gaveUp(long chatId, int statusMessageId) {
    send(chatId, DeferredMessages.GAVE_UP);
    editStatus(chatId, statusMessageId, "Failed.");
  }

  @Override
  public void failed(long chatId, int statusMessageId, JobFailure failure) {
    send(chatId, Html.escape(failure.getMessage()));
    editStatus(chatId, statusMessageId, "Failed.");
  }

  private void send(long chatId, String html) {
    try {
      gateway.sendMessage(chatId, html, List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not send a deferred-job message to chat {}", chatId, e);
      }
    }
  }

  private void editStatus(long chatId, int statusMessageId, String text) {
    try {
      gateway.editMessage(chatId, statusMessageId, text, List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not edit the status message {} in chat {}", statusMessageId, chatId, e);
      }
    }
  }
}
