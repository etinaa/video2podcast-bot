package com.video2podcast.summary;

import com.video2podcast.job.JobFailure;
import java.util.ArrayList;
import java.util.List;

/** Shapes model answers: bounds intermediate ones and turns the final one into capped bullets. */
final class SummaryFormatter {

  static final int SUMMARY_CAP = 1500;
  private static final String MARKERS = "-*•–";
  private static final String ELLIPSIS = "…";

  private SummaryFormatter() {}

  /**
   * Keeps {@code content} within {@code maxChars}: whole trailing lines are dropped, then a single
   * remaining line is cut. Nothing else is changed.
   */
  static String clip(String content, int maxChars) {
    if (content.length() <= maxChars) {
      return content;
    }
    List<String> lines = new ArrayList<>(List.of(content.split("\\R")));
    String joined = String.join("\n", lines);
    while (joined.length() > maxChars && lines.size() > 1) {
      lines.remove(lines.size() - 1);
      joined = String.join("\n", lines);
    }
    return joined.length() > maxChars ? cut(joined, maxChars) : joined;
  }

  /** The final answer: {@code "• "} lines, at most {@link #SUMMARY_CAP} UTF-16 units. */
  static String format(String content) {
    List<String> bullets = new ArrayList<>();
    for (String raw : content.split("\\R")) {
      String line = withoutMarker(raw.strip());
      if (!line.isEmpty()) {
        bullets.add("• " + line);
      }
    }
    if (bullets.isEmpty()) {
      throw JobFailure.summaryFailed(null);
    }
    String joined = String.join("\n", bullets);
    while (joined.length() > SUMMARY_CAP && bullets.size() > 1) {
      bullets.remove(bullets.size() - 1);
      joined = String.join("\n", bullets);
    }
    return joined.length() > SUMMARY_CAP ? cut(joined, SUMMARY_CAP - 1) + ELLIPSIS : joined;
  }

  /** A line of just one marker is empty; a marker is only a marker before whitespace. */
  private static String withoutMarker(String line) {
    if (line.isEmpty() || MARKERS.indexOf(line.charAt(0)) < 0) {
      return line;
    }
    if (line.length() == 1) {
      return "";
    }
    return Character.isWhitespace(line.charAt(1)) ? line.substring(1).strip() : line;
  }

  /** The first {@code max} chars, one less when the cut would split a surrogate pair. */
  private static String cut(String text, int max) {
    boolean splitsPair =
        Character.isHighSurrogate(text.charAt(max - 1))
            && Character.isLowSurrogate(text.charAt(max));
    return text.substring(0, splitsPair ? max - 1 : max);
  }
}
