package com.video2podcast.bot;

/** The fixed texts of the deferred-job flow. */
final class DeferredMessages {

  static final String PARKED =
      "Service temporarily unavailable. We will send the result after it recovers.";
  static final String GAVE_UP = "We could not process this link. Please send it again later.";

  private DeferredMessages() {}
}
