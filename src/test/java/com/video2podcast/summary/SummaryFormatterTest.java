package com.video2podcast.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.job.JobFailure;
import org.junit.jupiter.api.Test;

class SummaryFormatterTest {

  private static void assertSummaryFailed(String content) {
    assertThatThrownBy(() -> SummaryFormatter.format(content))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f.getMessage()).isEqualTo("Summary failed. Please try again later.");
            });
  }

  @Test
  void everyLineBecomesABulletAndMarkersAreRemoved() {
    String content = "- one\n* two\n• three\n– four\nfive";

    assertThat(SummaryFormatter.format(content)).isEqualTo("• one\n• two\n• three\n• four\n• five");
  }

  @Test
  void blankLinesAreDroppedAndLinesAreStripped() {
    assertThat(SummaryFormatter.format("  - one  \n\n \t\n   two\r\n")).isEqualTo("• one\n• two");
  }

  @Test
  void onlyOneMarkerIsRemoved() {
    assertThat(SummaryFormatter.format("- - nested")).isEqualTo("• - nested");
  }

  @Test
  void aMarkerWithoutWhitespaceAfterItStays() {
    assertThat(SummaryFormatter.format("-5 degrees\n*bold*\n–x"))
        .isEqualTo("• -5 degrees\n• *bold*\n• –x");
  }

  @Test
  void aMarkerFollowedByATabIsRemoved() {
    assertThat(SummaryFormatter.format("-\tone")).isEqualTo("• one");
  }

  @Test
  void aLineOfOnlyAMarkerIsEmpty() {
    assertThat(SummaryFormatter.format("-\n*\n•\n–\nx")).isEqualTo("• x");
  }

  @Test
  void aMarkerOnlyLineIsDroppedAndNoLineFails() {
    assertThat(SummaryFormatter.format("- \n- one")).isEqualTo("• one");
    assertSummaryFailed("- \n*  \n");
    assertSummaryFailed(" \n\t");
  }

  @Test
  void exactlyTheCapIsKeptUnchanged() {
    String line = "x".repeat(SummaryFormatter.SUMMARY_CAP - 2);

    String result = SummaryFormatter.format(line);

    assertThat(result).hasSize(1500).isEqualTo("• " + line);
  }

  @Test
  void trailingBulletsAreDroppedWhenOverTheCap() {
    String first = "a".repeat(700);
    String second = "b".repeat(700);
    String third = "c".repeat(700);

    String result = SummaryFormatter.format(first + "\n" + second + "\n" + third);

    assertThat(result).isEqualTo("• " + first + "\n• " + second);
    assertThat(result.length()).isLessThanOrEqualTo(1500);
  }

  @Test
  void twoBulletsExactlyAtTheCapAreKept() {
    String first = "a".repeat(748);
    String second = "b".repeat(747);

    String result = SummaryFormatter.format(first + "\n" + second);

    assertThat(result).hasSize(1500);
  }

  @Test
  void oneOverTheCapDropsTheLastBullet() {
    String first = "a".repeat(748);
    String second = "b".repeat(748);

    assertThat(SummaryFormatter.format(first + "\n" + second)).isEqualTo("• " + first);
  }

  @Test
  void aSingleOversizedBulletIsCutWithAnEllipsis() {
    String result = SummaryFormatter.format("x".repeat(2000));

    assertThat(result).hasSize(1500).startsWith("• xxx").endsWith("x…");
    assertThat(result.substring(0, 1499)).isEqualTo("• " + "x".repeat(1497));
  }

  @Test
  void aSingleBulletOneOverTheCapIsCut() {
    String result = SummaryFormatter.format("x".repeat(1499));

    assertThat(result).hasSize(1500).endsWith("x…");
  }

  @Test
  void theCutNeverSplitsASurrogatePair() {
    // "• " + 1496 chars + pair: the pair would start at index 1498 and end at 1499
    String content = "a".repeat(1496) + "😀" + "b".repeat(10);

    String result = SummaryFormatter.format(content);

    assertThat(result).hasSize(1499).endsWith("a…").doesNotContain("😀");
  }

  @Test
  void aPairEndingExactlyAtTheCutIsKept() {
    String content = "a".repeat(1495) + "😀" + "b".repeat(10);

    String result = SummaryFormatter.format(content);

    assertThat(result).hasSize(1500).endsWith("😀…");
  }

  @Test
  void clipKeepsAnAnswerThatFits() {
    assertThat(SummaryFormatter.clip("- a\n- b", 7)).isEqualTo("- a\n- b");
  }

  @Test
  void clipDropsWholeTrailingLines() {
    assertThat(SummaryFormatter.clip("- a\n- b\n- c", 7)).isEqualTo("- a\n- b");
    assertThat(SummaryFormatter.clip("- a\n- b\n- c", 6)).isEqualTo("- a");
  }

  @Test
  void clipCutsASingleLongLineWithoutAMarker() {
    assertThat(SummaryFormatter.clip("x".repeat(50), 10)).isEqualTo("x".repeat(10));
    assertThat(SummaryFormatter.clip("- a\n" + "y".repeat(50), 2)).isEqualTo("- ");
  }

  @Test
  void clipDoesNotSplitASurrogatePair() {
    assertThat(SummaryFormatter.clip("a".repeat(9) + "😀😀", 10)).isEqualTo("a".repeat(9));
    assertThat(SummaryFormatter.clip("a".repeat(8) + "😀😀", 10)).isEqualTo("a".repeat(8) + "😀");
  }

  @Test
  void clipDoesNotNormalizeMarkersOrAppendAnEllipsis() {
    assertThat(SummaryFormatter.clip("* a\n* b", 3)).isEqualTo("* a");
  }
}
