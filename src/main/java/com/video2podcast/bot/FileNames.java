package com.video2podcast.bot;

/** Names for the files the bot uploads. */
final class FileNames {

  private static final int MAX_STEM = 100;
  private static final String FORBIDDEN = "\\/:*?\"<>|";
  private static final String FALLBACK = "transcript";

  private FileNames() {}

  /** The upload name for a transcript: the title made safe for a file name, plus ".txt". */
  static String transcriptName(String title) {
    StringBuilder safe = new StringBuilder();
    String text = title == null ? "" : title;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      safe.append(FORBIDDEN.indexOf(c) >= 0 || Character.isISOControl(c) ? '_' : c);
    }
    String stem = clip(safe.toString().strip());
    return (stem.isEmpty() ? FALLBACK : stem) + ".txt";
  }

  private static String clip(String text) {
    if (text.length() <= MAX_STEM) {
      return text;
    }
    int end = MAX_STEM;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }
}
