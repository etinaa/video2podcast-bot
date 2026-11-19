package com.video2podcast.bot;

import com.video2podcast.job.JobProgress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Edits the status message of one job as it moves through the stages. */
final class StatusReporter implements JobProgress {

  private static final Logger LOG = LoggerFactory.getLogger(StatusReporter.class);

  private final TelegramGateway gateway;
  private final long chatId;
  private final int messageId;
  private String lastText;

  StatusReporter(TelegramGateway gateway, long chatId, int messageId) {
    this.gateway = gateway;
    this.chatId = chatId;
    this.messageId = messageId;
  }

  @Override
  public void downloading() {
    show("Downloading…");
  }

  @Override
  public void transcribing(int n, int total) {
    show("Transcribing " + n + "/" + total + "…");
  }

  @Override
  public void summarizing() {
    show("Summarizing…");
  }

  /** Telegram rejects an edit that changes nothing, so an equal text is not sent. */
  private void show(String text) {
    if (text.equals(lastText)) {
      return;
    }
    lastText = text;
    try {
      gateway.editMessage(chatId, messageId, text, List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not edit the status message {}: {}", messageId, e.getMessage());
      }
    }
  }
}
