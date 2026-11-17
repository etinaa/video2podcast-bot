package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.JobFailure.Kind;
import org.junit.jupiter.api.Test;

class StderrClassifierTest {

  @Test
  void privateMarkers() {
    assertThat(StderrClassifier.classify("ERROR: Private video")).isEqualTo(Kind.PRIVATE);
    assertThat(StderrClassifier.classify("Sign in to confirm")).isEqualTo(Kind.PRIVATE);
    assertThat(StderrClassifier.classify("members-only content")).isEqualTo(Kind.PRIVATE);
    assertThat(StderrClassifier.classify("confirm your age")).isEqualTo(Kind.PRIVATE);
  }

  @Test
  void unsupportedUrl() {
    assertThat(StderrClassifier.classify("ERROR: Unsupported URL: x")).isEqualTo(Kind.UNSUPPORTED);
  }

  @Test
  void privateWinsWhenBothMatch() {
    assertThat(StderrClassifier.classify("Unsupported URL, private")).isEqualTo(Kind.PRIVATE);
  }

  @Test
  void otherwiseFailed() {
    assertThat(StderrClassifier.classify("HTTP 500")).isEqualTo(Kind.FAILED);
    assertThat(StderrClassifier.classify("")).isEqualTo(Kind.FAILED);
    assertThat(StderrClassifier.classify(null)).isEqualTo(Kind.FAILED);
  }
}
