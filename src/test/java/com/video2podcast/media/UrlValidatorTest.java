package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.job.JobFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

  private final UrlValidator validator = new UrlValidator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://www.youtube.com/watch?v=abc",
        "HTTP://example.com/x",
        "https://8.8.8.8/x",
        "https://[2001:4860:4860::8888]/x",
        "https://example.com./x",
        "https://172.32.0.1/x",
        "https://100.128.0.1/x",
      })
  void acceptsPublicLinks(String url) {
    assertThat(validator.validate(url)).isEqualTo(url);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "https://example.com/a b",
        "https://example.com/a\nb",
        "not a url",
        "ftp://example.com/x",
        "file:///etc/passwd",
        "//example.com/x",
        "https:///x",
        "https://user@example.com/x",
        "http://localhost/x",
        "http://LOCALHOST./x",
        "http://app.localhost/x",
        "http://127.0.0.1/x",
        "http://0.0.0.0/x",
        "http://[::]/x",
        "http://[::1]/x",
        "http://169.254.169.254/x",
        "http://[fe80::1]/x",
        "http://[fe80::1%25eth0]/x",
        "http://10.0.0.1/x",
        "http://172.16.0.1/x",
        "http://192.168.1.1/x",
        "http://100.64.0.1/x",
        "http://[fc00::1]/x",
        "http://[fd12::1]/x",
        "http://[::ffff:127.0.0.1]/x",
        "http://[::ffff:10.0.0.1]/x",
        "http://2130706433/x",
        "http://127.1/x",
        "http://256.1.1.1/x",
        "http://[zzz]/x",
      })
  void rejectsEverythingElse(String url) {
    assertThatThrownBy(() -> validator.validate(url))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.UNSUPPORTED));
  }

  @Test
  void rejectsOverlongUrls() {
    String prefix = "https://example.com/";
    String longUrl = prefix + "a".repeat(2049 - prefix.length());
    assertThat(longUrl).hasSize(2049);
    assertThatThrownBy(() -> validator.validate(longUrl)).isInstanceOf(JobFailure.class);
    String limit = prefix + "a".repeat(2048 - prefix.length());
    assertThat(limit).hasSize(2048);
    assertThat(validator.validate(limit)).isEqualTo(limit);
  }
}
