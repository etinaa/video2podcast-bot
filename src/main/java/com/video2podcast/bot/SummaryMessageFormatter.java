package com.video2podcast.bot;

/** Builds the HTML of the summary message. */
final class SummaryMessageFormatter {

  static final int TITLE_MAX = 200;

  private static final String ELLIPSIS = "…";
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int SECONDS_PER_HOUR = 3600;

  private SummaryMessageFormatter() {}

  /** {@code <b>title</b>\nDuration: 1h 42m\n\n• a\n• b} with title and bullets HTML-escaped. */
  static String format(String title, long durationSeconds, String bullets) {
    return "<b>"
        + Html.escape(displayTitle(title))
        + "</b>\nDuration: "
        + formatDuration(durationSeconds)
        + "\n\n"
        + Html.escape(bullets);
  }

  /**
   * Whitespace runs become one space, then strip, then cut to {@link #TITLE_MAX} UTF-16 units. A
   * cut title ends with an ellipsis and is exactly that long, unless the cut would split a
   * surrogate pair, in which case it is one unit shorter.
   */
  static String displayTitle(String title) {
    String clean = title.replaceAll("\\s+", " ").strip();
    if (clean.length() <= TITLE_MAX) {
      return clean;
    }
    int end = TITLE_MAX - ELLIPSIS.length();
    if (Character.isHighSurrogate(clean.charAt(end - 1))) {
      end--;
    }
    return clean.substring(0, end) + ELLIPSIS;
  }

  /** {@code 1h 42m}, {@code 5m} or {@code 45s}; minutes are floored. */
  static String formatDuration(long seconds) {
    long hours = seconds / SECONDS_PER_HOUR;
    long minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    if (minutes > 0) {
      return minutes + "m";
    }
    return seconds + "s";
  }
}
