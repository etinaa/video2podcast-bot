package com.video2podcast.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptMergerTest {

  @Test
  void normalizeTrimsAndCollapsesAllWhitespace() {
    assertThat(TranscriptMerger.normalize("  First\n\tpart.\r\n  Next  "))
        .isEqualTo("First part. Next");
    assertThat(TranscriptMerger.normalize("Café — it's ready!")).isEqualTo("Café — it's ready!");
  }

  @Test
  void normalizeCollapsesNonBreakingAndEmSpaces() {
    assertThat(TranscriptMerger.normalize("\u00A0First\u00A0\u00A0part.\u2003Next\u2003"))
        .isEqualTo("First part. Next");
  }

  @Test
  void normalizeOfNullOrBlankIsEmpty() {
    assertThat(TranscriptMerger.normalize(null)).isEmpty();
    assertThat(TranscriptMerger.normalize(" \t\r\n ")).isEmpty();
  }

  @Test
  void mergeJoinsWithOneSpaceAndOneNewline() {
    assertThat(TranscriptMerger.merge(List.of(" Hello\n world ", "", "Next one\n")))
        .isEqualTo("Hello world Next one\n");
  }

  @Test
  void mergeOfNothingIsEmpty() {
    assertThat(TranscriptMerger.merge(List.of())).isEmpty();
    assertThat(TranscriptMerger.merge(Arrays.asList(null, " ", ""))).isEmpty();
  }
}
