package com.video2podcast.job;

/** Sends the summary message of a finished job, and can take its buttons away again. */
public interface SummaryPublisher {

  /**
   * Sends the summary message with the two delivery buttons and returns its Telegram message id.
   *
   * @throws com.video2podcast.bot.TelegramGatewayException if Telegram refuses or cannot be reached
   */
  int publish(JobRecord job, String summary);

  /** Best effort: replaces the summary message by the same text without buttons. Never throws. */
  void withdraw(JobRecord job, int messageId, String summary);
}
