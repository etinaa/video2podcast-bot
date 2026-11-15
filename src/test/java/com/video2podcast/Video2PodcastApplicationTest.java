package com.video2podcast;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class Video2PodcastApplicationTest {

  @Test
  void mainStartsTheApplicationContext() {
    assertThatCode(() ->
            Video2PodcastApplication.main(
                new String[] {
                  "--spring.main.web-application-type=none",
                  "--app.telegram.bot-token=test-token",
                  "--app.telegram.admin-user-id=42"
                }))
        .doesNotThrowAnyException();
  }
}
