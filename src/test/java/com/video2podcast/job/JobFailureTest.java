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
  void transcriptionKindsHaveTheirOwnMessages() {
    assertThat(JobFailure.of(JobFailure.Kind.NO_SPEECH).getMessage())
        .isEqualTo("No speech was found in this video.");
    assertThat(JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE).getMessage())
        .isEqualTo("The transcription server is unreachable.");

    Exception cause = new java.io.IOException("/secret/path");
    JobFailure failure = JobFailure.transcriptionFailed(cause);
    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo("Transcription failed. Please try again later.");
    assertThat(failure.getCause()).isSameAs(cause);
    assertThat(JobFailure.transcriptionFailed(null).getCause()).isNull();
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
  void summaryFailuresHaveTheirOwnMessages() {
    assertThat(JobFailure.of(JobFailure.Kind.SUMMARY_UNREACHABLE).getMessage())
        .isEqualTo("The summary server is unreachable.");
    Exception cause = new java.io.IOException("/secret/path");
    JobFailure failure = JobFailure.summaryFailed(cause);
    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo("Summary failed. Please try again later.");
    assertThat(failure.getCause()).isSameAs(cause);
    assertThat(JobFailure.summaryFailed(null).getCause()).isNull();
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

  @Test
  void unreachableNamesTheServicesThatAreDown() {
    JobFailure whisper = JobFailure.unreachable(true, false);
    assertThat(whisper.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
    assertThat(whisper).hasMessage("The transcription server is unreachable.").hasNoCause();

    JobFailure ollama = JobFailure.unreachable(false, true);
    assertThat(ollama.kind()).isEqualTo(JobFailure.Kind.SUMMARY_UNREACHABLE);
    assertThat(ollama).hasMessage("The summary server is unreachable.");

    JobFailure both = JobFailure.unreachable(true, true);
    assertThat(both.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
    assertThat(both)
        .hasMessage("The transcription server is unreachable. The summary server is unreachable.");
  }

  @Test
  void unreachableNeedsAServiceThatIsDown() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> JobFailure.unreachable(false, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
