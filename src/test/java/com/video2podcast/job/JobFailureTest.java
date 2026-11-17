package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JobFailureTest {

  @Test
  void fixedMessagesPerKind() {
    assertThat(JobFailure.of(JobFailure.Kind.PRIVATE).getMessage())
        .isEqualTo("This video is private or needs a sign-in.");
    assertThat(JobFailure.of(JobFailure.Kind.UNSUPPORTED).getMessage())
        .isEqualTo("This link is not a supported video.");
    assertThat(JobFailure.of(JobFailure.Kind.FAILED).getMessage())
        .isEqualTo("Could not process this video. Please try again later.");
    assertThat(JobFailure.of(JobFailure.Kind.TIMEOUT).getMessage())
        .isEqualTo("Processing took too long and was stopped.");
    assertThat(JobFailure.of(JobFailure.Kind.TOO_LONG).kind()).isEqualTo(JobFailure.Kind.TOO_LONG);
    assertThat(JobFailure.unsupportedLiveStream().getMessage())
        .isEqualTo("Live streams are not supported.");
  }

  @Test
  void tooLongFormatsTheLimit() {
    assertThat(JobFailure.tooLong(Duration.ofHours(3)).getMessage())
        .isEqualTo("The video is longer than 3 hours.");
    assertThat(JobFailure.tooLong(Duration.ofHours(1)).getMessage())
        .isEqualTo("The video is longer than 1 hour.");
    assertThat(JobFailure.tooLong(Duration.ofMinutes(90)).getMessage())
        .isEqualTo("The video is longer than 1 hour 30 minutes.");
    assertThat(JobFailure.tooLong(Duration.ofMinutes(1)).getMessage())
        .isEqualTo("The video is longer than 1 minute.");
    assertThat(JobFailure.tooLong(Duration.ZERO).getMessage())
        .isEqualTo("The video is longer than 0 minutes.");
  }

  @Test
  void causeIsKeptForLogsButNotInTheMessage() {
    Exception cause = new java.io.IOException("/secret/path");

    JobFailure failure = JobFailure.of(JobFailure.Kind.FAILED, cause);

    assertThat(failure.getCause()).isSameAs(cause);
    assertThat(failure.getMessage())
        .isEqualTo("Could not process this video. Please try again later.");
  }

  @Test
  void publicConstructorKeepsKindAndMessage() {
    JobFailure failure = new JobFailure(JobFailure.Kind.FAILED, "x");
    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure).hasMessage("x").hasNoCause();
  }
}
