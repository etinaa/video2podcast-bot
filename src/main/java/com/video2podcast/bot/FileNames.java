package com.video2podcast.bot;

/** Names for the files the bot uploads. */
final class FileNames {

  private static final int MAX_STEM = 100;
  private static final String FORBIDDEN = "\\/:*?\"<>|";
  private static final String TRANSCRIPT_FALLBACK = "transcript";
  private static final String AUDIO_FALLBACK = "audio";

  private FileNames() {}

  /** The upload name for a transcript: the title made safe for a file name, plus ".txt". */
  static String transcriptName(String title) {
    return name(title, TRANSCRIPT_FALLBACK, "txt");
  }

  /** The upload name for audio: the title made safe for a file name, a dot and the extension. */
  static String audioName(String title, String extension) {
    return name(title, AUDIO_FALLBACK, extension);
  }

  private static String name(String title, String fallback, String extension) {
    StringBuilder safe = new StringBuilder();
    String text = title == null ? "" : title;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      safe.append(FORBIDDEN.indexOf(c) >= 0 || Character.isISOControl(c) ? '_' : c);
    }
    String stem = limit(safe.toString().strip(), MAX_STEM);
    return (stem.isEmpty() ? fallback : stem) + "." + extension;
  }

  /** The text cut to at most {@code max} UTF-16 units, never inside a surrogate pair. */
  static String limit(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    int end = max;
    if (Character.isHighSurrogate(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(0, end);
  }
}
