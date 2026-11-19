package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileNamesTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Budget meeting|Budget meeting.txt",
        "  Budget meeting  |Budget meeting.txt",
        "Q4/Plan: v1?|Q4_Plan_ v1_.txt",
        "R&D <notes>|R&D _notes_.txt",
        "   |transcript.txt"
      })
  void makesTheTitleSafe(String title, String expected) {
    assertThat(FileNames.transcriptName(title)).isEqualTo(expected);
  }

  @Test
  void replacesEveryForbiddenCharacter() {
    assertThat(FileNames.transcriptName("a\\b/c:d*e?f\"g<h>i|j"))
        .isEqualTo("a_b_c_d_e_f_g_h_i_j.txt");
  }

  @Test
  void replacesControlCharacters() {
    assertThat(FileNames.transcriptName("a\tb\nc\u0000d")).isEqualTo("a_b_c_d.txt");
  }

  @Test
  void aNullOrEmptyTitleBecomesTranscript() {
    assertThat(FileNames.transcriptName(null)).isEqualTo("transcript.txt");
    assertThat(FileNames.transcriptName("")).isEqualTo("transcript.txt");
  }

  @Test
  void cutsALongTitleToOneHundredUnits() {
    assertThat(FileNames.transcriptName("A".repeat(120))).isEqualTo("A".repeat(100) + ".txt");
    assertThat(FileNames.transcriptName("A".repeat(100))).isEqualTo("A".repeat(100) + ".txt");
  }

  @Test
  void doesNotSplitASurrogatePair() {
    String emoji = "😀";
    String title = "A".repeat(99) + emoji + "B";

    assertThat(FileNames.transcriptName(title)).isEqualTo("A".repeat(99) + ".txt");
    assertThat(FileNames.transcriptName("A".repeat(98) + emoji + "B"))
        .isEqualTo("A".repeat(98) + emoji + ".txt");
    assertThat(FileNames.transcriptName("A".repeat(97) + emoji + "B"))
        .isEqualTo("A".repeat(97) + emoji + "B.txt");
  }
}
