package com.video2podcast.bot;

import java.util.List;

/** Inline keyboards and the callback data they carry. */
final class Keyboards {

  static final String ACCESS_PREFIX = "access:";
  static final String APPROVE = "approve";
  static final String DENY = "deny";

  static final String AUDIO_PREFIX = "audio:";
  static final String TRANSCRIPT_PREFIX = "transcript:";

  private Keyboards() {}

  /** One row, in this order. */
  static List<InlineButton> delivery(String jobId) {
    return List.of(
        new InlineButton("Download audio", AUDIO_PREFIX + jobId),
        new InlineButton("Download transcript", TRANSCRIPT_PREFIX + jobId));
  }

  static List<InlineButton> accessRequest(long userId) {
    return List.of(
        new InlineButton("Approve", ACCESS_PREFIX + APPROVE + ":" + userId),
        new InlineButton("Deny", ACCESS_PREFIX + DENY + ":" + userId));
  }
}
