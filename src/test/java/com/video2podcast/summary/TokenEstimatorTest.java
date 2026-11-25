package com.video2podcast.summary;

import static com.video2podcast.job.Language.EN;
import static com.video2podcast.job.Language.RU;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

  @Test
  void estimateIsLengthOverThreeRoundedUp() {
    assertThat(TokenEstimator.estimate("", EN)).isZero();
    assertThat(TokenEstimator.estimate("a", EN)).isEqualTo(1);
    assertThat(TokenEstimator.estimate("abc", EN)).isEqualTo(1);
    assertThat(TokenEstimator.estimate("abcd", EN)).isEqualTo(2);
    assertThat(TokenEstimator.estimate("a".repeat(300), EN)).isEqualTo(100);
  }

  @Test
  void russianCountsTwoCharactersPerToken() {
    assertThat(TokenEstimator.charsPerToken(RU)).isEqualTo(2);
    assertThat(TokenEstimator.charsPerToken(EN)).isEqualTo(3);
    assertThat(TokenEstimator.estimate("", RU)).isZero();
    assertThat(TokenEstimator.estimate("я", RU)).isEqualTo(1);
    assertThat(TokenEstimator.estimate("яя", RU)).isEqualTo(1);
    assertThat(TokenEstimator.estimate("яяя", RU)).isEqualTo(2);
    assertThat(TokenEstimator.requestEstimate("я".repeat(30), "я".repeat(31), RU))
        .isEqualTo(15 + 16 + 32);
  }

  @Test
  void aSupplementaryCharacterCountsAsTwoUtf16Units() {
    assertThat(TokenEstimator.estimate("😀😀", EN)).isEqualTo(2);
  }

  @Test
  void requestEstimateAddsBothTextsAndTheTemplateOverhead() {
    assertThat(TokenEstimator.requestEstimate("a".repeat(30), "b".repeat(31), EN))
        .isEqualTo(10 + 11 + 32);
  }
}
