package com.video2podcast.job;

import java.time.Instant;

/**
 * One parked link. Only what a retry from scratch needs is kept: the link, who asked and where, the
 * status message to keep up to date, and how many retries ended in an outage.
 */
public record DeferredJob(
    String id,
    String url,
    long ownerUserId,
    long chatId,
    int statusMessageId,
    int attempts,
    Instant parkedAt) {

  public DeferredJob withAttempts(int newAttempts) {
    return new DeferredJob(id, url, ownerUserId, chatId, statusMessageId, newAttempts, parkedAt);
  }
}
