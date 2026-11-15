package com.video2podcast.access;

import java.time.Instant;

/**
 * An access request waiting for the owner. {@code requestMessageId} is the id of the owner's
 * Approve/Deny message and is null until that message has been sent.
 */
public record PendingRequest(
    long id, String username, String displayName, Instant requestedAt, Integer requestMessageId) {

  PendingRequest withRequestMessageId(int messageId) {
    return new PendingRequest(id, username, displayName, requestedAt, messageId);
  }
}
