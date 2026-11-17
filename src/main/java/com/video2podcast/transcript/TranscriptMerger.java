package com.video2podcast.transcript;

import java.util.List;
import java.util.regex.Pattern;

/** Joins the texts of the chunks into one plain-text transcript. */
final class TranscriptMerger {

  private static final Pattern WHITESPACE =
      Pattern.compile("[\\s\\u00A0]+", Pattern.UNICODE_CHARACTER_CLASS);

  private TranscriptMerger() {}

  /** Trims and collapses every run of whitespace to one space; null gives an empty string. */
  static String normalize(String text) {
    if (text == null) {
      return "";
    }
    return WHITESPACE.matcher(text).replaceAll(" ").strip();
  }

  /** Normalized, non-empty texts joined by one space plus one newline; "" if nothing is left. */
  static String merge(List<String> texts) {
    StringBuilder out = new StringBuilder();
    for (String text : texts) {
      String normalized = normalize(text);
      if (normalized.isEmpty()) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(normalized);
    }
    if (out.isEmpty()) {
      return "";
    }
    return out.append('\n').toString();
  }
}
