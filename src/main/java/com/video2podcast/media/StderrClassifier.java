package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.util.List;
import java.util.Locale;

/**
 * The one table that turns yt-dlp's stderr into a failure kind. The bare {@code age} marker is
 * broad (it also matches "language"); that tradeoff was accepted by the owner.
 */
final class StderrClassifier {

  private static final List<String> PRIVATE_MARKERS =
      List.of("private", "sign in", "members-only", "age");
  private static final List<String> UNSUPPORTED_MARKERS = List.of("unsupported url");

  private StderrClassifier() {}

  static JobFailure.Kind classify(String stderr) {
    String text = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
    if (containsAny(text, PRIVATE_MARKERS)) {
      return JobFailure.Kind.PRIVATE;
    }
    if (containsAny(text, UNSUPPORTED_MARKERS)) {
      return JobFailure.Kind.UNSUPPORTED;
    }
    return JobFailure.Kind.FAILED;
  }

  private static boolean containsAny(String text, List<String> markers) {
    return markers.stream().anyMatch(text::contains);
  }
}
