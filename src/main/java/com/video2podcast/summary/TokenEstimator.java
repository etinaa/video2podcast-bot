package com.video2podcast.summary;

/** The one size formula, used for the budget and for the truncation check. */
final class TokenEstimator {

  static final int CHARS_PER_TOKEN = 3;
  static final int TEMPLATE_OVERHEAD = 32;

  private TokenEstimator() {}

  /** Deliberately high for English: UTF-16 units divided by three, rounded up. */
  static int estimate(String text) {
    return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
  }

  static long requestEstimate(String system, String user) {
    return (long) estimate(system) + estimate(user) + TEMPLATE_OVERHEAD;
  }
}
