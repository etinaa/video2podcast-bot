package com.video2podcast.summary;

import java.util.ArrayList;
import java.util.List;

/** Splits text into ordered chunks of at most a given length, preferring to cut at whitespace. */
final class TextChunker {

  private TextChunker() {}

  /**
   * Text of at most {@code maxChars} is returned unchanged as one chunk. Otherwise every chunk is
   * stripped and has at most {@code maxChars} chars; together they hold the text minus whitespace.
   */
  static List<String> split(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return List.of(text);
    }
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int cut = text.length() - start <= maxChars ? text.length() : cutAfter(text, start, maxChars);
      String chunk = text.substring(start, cut).strip();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }
      start = cut;
    }
    return chunks;
  }

  /** The end of the chunk that starts at {@code start}; the rest of the text is longer than it. */
  private static int cutAfter(String text, int start, int maxChars) {
    int limit = start + maxChars;
    for (int i = limit; i >= start + maxChars / 2; i--) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i + 1;
      }
    }
    boolean splitsPair =
        Character.isHighSurrogate(text.charAt(limit - 1))
            && Character.isLowSurrogate(text.charAt(limit));
    return splitsPair && limit - 1 > start ? limit - 1 : limit;
  }
}
