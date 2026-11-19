package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SummaryMessageFormatterTest {

  @ParameterizedTest
  @CsvSource({
    "0,0s",
    "45,45s",
    "59,59s",
    "60,1m",
    "119,1m",
    "300,5m",
    "3599,59m",
    "3600,1h 0m",
    "3659,1h 0m",
    "6120,1h 42m",
    "10800,3h 0m"
  })
  void formatsTheDuration(long seconds, String expected) {
    assertThat(SummaryMessageFormatter.formatDuration(seconds)).isEqualTo(expected);
  }

  @Test
  void formatsTitleDurationAndBullets() {
    assertThat(SummaryMessageFormatter.format("A talk", 6120, "• one\n• two"))
        .isEqualTo("<b>A talk</b>\nDuration: 1h 42m\n\n• one\n• two");
  }

  @Test
  void escapesTitleAndBullets() {
    assertThat(SummaryMessageFormatter.format("<i>A & B</i>", 30, "• x < y & z > w"))
        .isEqualTo(
            "<b>&lt;i&gt;A &amp; B&lt;/i&gt;</b>\nDuration: 30s\n\n• x &lt; y &amp; z &gt; w");
  }

  @Test
  void collapsesAndStripsWhitespaceInTheTitle() {
    assertThat(SummaryMessageFormatter.displayTitle("  a \n\t b   c  ")).isEqualTo("a b c");
  }

  @Test
  void keepsATitleOfExactlyTheMaximumLength() {
    String title = "x".repeat(SummaryMessageFormatter.TITLE_MAX);

    assertThat(SummaryMessageFormatter.displayTitle(title)).isEqualTo(title);
  }

  @Test
  void cutsALongerTitleToTheMaximumLengthEndingInAnEllipsis() {
    String shown = SummaryMessageFormatter.displayTitle("x".repeat(201));

    assertThat(shown).hasSize(SummaryMessageFormatter.TITLE_MAX).isEqualTo("x".repeat(199) + "…");
  }

  @Test
  void neverSplitsASurrogatePair() {
    // The pair starts at index 198, so a cut at 199 would leave a lone high surrogate.
    String title = "x".repeat(198) + "😀" + "tail";

    String shown = SummaryMessageFormatter.displayTitle(title);

    assertThat(shown).isEqualTo("x".repeat(198) + "…");
  }

  @Test
  void keepsAWholePairThatEndsRightBeforeTheEllipsis() {
    String title = "x".repeat(197) + "😀" + "tail";

    assertThat(SummaryMessageFormatter.displayTitle(title))
        .isEqualTo("x".repeat(197) + "😀" + "…")
        .hasSize(SummaryMessageFormatter.TITLE_MAX);
  }
}
