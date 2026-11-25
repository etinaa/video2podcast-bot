package com.video2podcast.summary;

import com.video2podcast.job.Language;

/** The one size formula, used for the budget and for the truncation check. */
final class TokenEstimator {

  static final int TEMPLATE_OVERHEAD = 32;

  private TokenEstimator() {}

  /** Deliberately low, so that the estimate is high: Cyrillic needs more tokens per character. */
  static int charsPerToken(Language language) {
    return switch (language) {
      case EN -> 3;
      case RU -> 2;
    };
  }

  /** UTF-16 units divided by {@link #charsPerToken}, rounded up. */
  static int estimate(String text, Language language) {
    int perToken = charsPerToken(language);
    return (text.length() + perToken - 1) / perToken;
  }

  static long requestEstimate(String system, String user, Language language) {
    return (long) estimate(system, language) + estimate(user, language) + TEMPLATE_OVERHEAD;
  }
}
