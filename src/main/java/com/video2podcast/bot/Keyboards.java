package com.video2podcast.bot;

import java.util.List;

/** Inline keyboards and the callback data they carry. */
final class Keyboards {

  static final String ACCESS_PREFIX = "access:";
  static final String APPROVE = "approve";
  static final String DENY = "deny";

  private Keyboards() {}

  static List<InlineButton> accessRequest(long userId) {
    return List.of(
        new InlineButton("Approve", ACCESS_PREFIX + APPROVE + ":" + userId),
        new InlineButton("Deny", ACCESS_PREFIX + DENY + ":" + userId));
  }
}
