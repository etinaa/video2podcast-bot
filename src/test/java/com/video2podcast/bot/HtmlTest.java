package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HtmlTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "plain text|plain text",
        "a < b|a &lt; b",
        "a > b|a &gt; b",
        "Tom & Jerry|Tom &amp; Jerry",
        "<b>bold</b>|&lt;b&gt;bold&lt;/b&gt;",
        "<a href=\"x\">link</a>|&lt;a href=\"x\"&gt;link&lt;/a&gt;",
        "&lt;|&amp;lt;",
        "&&|&amp;&amp;",
        "O'Brien \"Bob\"|O'Brien \"Bob\"",
        "José © ñ|José © ñ",
        "Иван Петров|Иван Петров",
        "日本語 😀|日本語 😀"
      })
  void escapesOnlyWhatTelegramHtmlModeRequires(String input, String expected) {
    assertThat(Html.escape(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"<&>|&lt;&amp;&gt;", "&amp;|&amp;amp;"})
  void escapesAlreadyEscapedTextAgainSoItIsShownLiterally(String input, String expected) {
    assertThat(Html.escape(input)).isEqualTo(expected);
  }

  @Test
  void emptyStringStaysEmpty() {
    assertThat(Html.escape("")).isEmpty();
  }

  @Test
  void theAmpersandIsEscapedFirstSoTheOtherReplacementsAreNotEscapedTwice() {
    assertThat(Html.escape("<")).isEqualTo("&lt;");
    assertThat(Html.escape(">")).isEqualTo("&gt;");
  }
}
