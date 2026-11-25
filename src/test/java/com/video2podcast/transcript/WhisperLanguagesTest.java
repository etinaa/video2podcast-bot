package com.video2podcast.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WhisperLanguagesTest {

  @ParameterizedTest
  @CsvSource({
    "russian,ru",
    "english,en",
    "german,de",
    "chinese,zh",
    "spanish,es",
    "cantonese,yue",
    "haitian creole,ht",
    "  RuSsIaN\t,ru",
    "GERMAN,de"
  })
  void aFullNameGivesItsCode(String name, String code) {
    assertThat(WhisperLanguages.toCode(name)).isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ru", "en", "de", "zz", "yue", "RU", " De "})
  void aCodeIsReturnedInLowerCase(String code) {
    assertThat(WhisperLanguages.toCode(code)).isEqualTo(code.strip().toLowerCase());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"klingon", "<b>de</b>", "d", "dees", "d1", "дe", "ру", "", "  ", "de\nru"})
  void anythingElseIsUnknown(String value) {
    assertThat(WhisperLanguages.toCode(value)).isEqualTo("unknown");
  }

  @Test
  void nullIsUnknown() {
    assertThat(WhisperLanguages.toCode(null)).isEqualTo("unknown");
  }
}
