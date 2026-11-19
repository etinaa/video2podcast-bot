package com.video2podcast.summary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

  @Test
  void estimateIsLengthOverThreeRoundedUp() {
    assertThat(TokenEstimator.estimate("")).isZero();
    assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
    assertThat(TokenEstimator.estimate("abc")).isEqualTo(1);
    assertThat(TokenEstimator.estimate("abcd")).isEqualTo(2);
    assertThat(TokenEstimator.estimate("a".repeat(300))).isEqualTo(100);
  }

  @Test
  void aSupplementaryCharacterCountsAsTwoUtf16Units() {
    assertThat(TokenEstimator.estimate("😀😀")).isEqualTo(2);
  }

  @Test
  void requestEstimateAddsBothTextsAndTheTemplateOverhead() {
    assertThat(TokenEstimator.requestEstimate("a".repeat(30), "b".repeat(31)))
        .isEqualTo(10 + 11 + 32);
  }
}
