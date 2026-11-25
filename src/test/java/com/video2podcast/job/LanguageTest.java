package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageTest {

  @Test
  void codesAndNames() {
    assertThat(Language.EN.code()).isEqualTo("en");
    assertThat(Language.EN.displayName()).isEqualTo("English");
    assertThat(Language.RU.code()).isEqualTo("ru");
    assertThat(Language.RU.displayName()).isEqualTo("Russian");
  }

  @Test
  void fromCodeIsExact() {
    assertThat(Language.fromCode("en")).contains(Language.EN);
    assertThat(Language.fromCode("ru")).contains(Language.RU);
    assertThat(Language.fromCode("RU")).isEmpty();
    assertThat(Language.fromCode(" ru")).isEmpty();
    assertThat(Language.fromCode("de")).isEmpty();
    assertThat(Language.fromCode("")).isEmpty();
    assertThat(Language.fromCode(null)).isEmpty();
  }
}
