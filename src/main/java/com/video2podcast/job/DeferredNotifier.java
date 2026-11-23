package com.video2podcast.job;

/**
 * The Telegram side of the end of a deferred retry. Every method is best effort and must not throw.
 */
public interface DeferredNotifier {

  /** The summary was sent; marks the status message done. */
  void completed(long chatId, int statusMessageId);

  /**
   * The retries ran out; tells the user to send the link again later and marks the status failed.
   */
  void gaveUp(long chatId, int statusMessageId);

  /** The retry failed for a reason other than an outage. */
  void failed(long chatId, int statusMessageId, JobFailure failure);
}
