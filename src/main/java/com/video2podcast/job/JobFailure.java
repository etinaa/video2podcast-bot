package com.video2podcast.job;

import java.time.Duration;

/**
 * A job failed in a way the user may be told about. The message is one of a few fixed English
 * sentences and never contains stderr, paths or the URL.
 */
public class JobFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the job failed. */
  public enum Kind {
    PRIVATE,
    UNSUPPORTED,
    TOO_LONG,
    FAILED,
    TIMEOUT,
    NO_SPEECH,
    SERVICE_UNREACHABLE,
    SUMMARY_UNREACHABLE,
    UNSUPPORTED_LANGUAGE
  }

  private final Kind kind;

  public JobFailure(Kind kind, String safeMessage) {
    this(kind, safeMessage, null);
  }

  /** The cause is for logs only; it is never part of the message. */
  private JobFailure(Kind kind, String safeMessage, Throwable cause) {
    super(safeMessage, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** True when Whisper or Ollama (or both) could not be reached, so a later try may succeed. */
  public boolean isServiceOutage() {
    return kind == Kind.SERVICE_UNREACHABLE || kind == Kind.SUMMARY_UNREACHABLE;
  }

  /** A failure with the fixed message of {@code kind}; {@code TOO_LONG} uses {@link #tooLong}. */
  public static JobFailure of(Kind kind) {
    return new JobFailure(kind, messageOf(kind));
  }

  /** Like {@link #of(Kind)}, keeping {@code cause} for the log. */
  public static JobFailure of(Kind kind, Throwable cause) {
    return new JobFailure(kind, messageOf(kind), cause);
  }

  /**
   * The failure for a service that did not answer the check before a job.
   *
   * @throws IllegalArgumentException if both arguments are false
   */
  public static JobFailure unreachable(boolean whisperDown, boolean ollamaDown) {
    if (whisperDown && ollamaDown) {
      return new JobFailure(
          Kind.SERVICE_UNREACHABLE,
          messageOf(Kind.SERVICE_UNREACHABLE) + " " + messageOf(Kind.SUMMARY_UNREACHABLE));
    }
    if (whisperDown) {
      return of(Kind.SERVICE_UNREACHABLE);
    }
    if (ollamaDown) {
      return of(Kind.SUMMARY_UNREACHABLE);
    }
    throw new IllegalArgumentException("no service is down");
  }

  public static JobFailure unsupportedLiveStream() {
    return new JobFailure(Kind.UNSUPPORTED, "Live streams are not supported.");
  }

  public static JobFailure tooLong(Duration limit) {
    return new JobFailure(Kind.TOO_LONG, "The video is longer than " + formatLimit(limit) + ".");
  }

  /**
   * The refusal of a video in another language. {@code code} comes from Whisper's language list
   * (letters only, or "unknown").
   */
  public static JobFailure unsupportedLanguage(String code) {
    return new JobFailure(
        Kind.UNSUPPORTED_LANGUAGE,
        messageOf(Kind.UNSUPPORTED_LANGUAGE) + " Detected language: " + code + ".");
  }

  /** A {@code FAILED} with the transcription message; {@code cause} may be null. */
  public static JobFailure transcriptionFailed(Throwable cause) {
    return new JobFailure(Kind.FAILED, "Transcription failed. Please try again later.", cause);
  }

  /** A {@code FAILED} with the summary message; {@code cause} may be null. */
  public static JobFailure summaryFailed(Throwable cause) {
    return new JobFailure(Kind.FAILED, "Summary failed. Please try again later.", cause);
  }

  private static String messageOf(Kind kind) {
    return switch (kind) {
      case PRIVATE -> "This video is private or needs a sign-in.";
      case UNSUPPORTED -> "This link is not a supported video.";
      case TOO_LONG -> "The video is too long.";
      case FAILED -> "Could not process this video. Please try again later.";
      case TIMEOUT -> "Processing took too long and was stopped.";
      case NO_SPEECH -> "No speech was found in this video.";
      case SERVICE_UNREACHABLE -> "The transcription server is unreachable.";
      case SUMMARY_UNREACHABLE -> "The summary server is unreachable.";
      case UNSUPPORTED_LANGUAGE -> "Only English and Russian videos are supported.";
    };
  }

  private static String formatLimit(Duration limit) {
    long hours = limit.toHours();
    long minutes = limit.toMinutesPart();
    StringBuilder text = new StringBuilder();
    if (hours > 0) {
      text.append(hours).append(hours == 1 ? " hour" : " hours");
    }
    if (minutes > 0) {
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(minutes).append(minutes == 1 ? " minute" : " minutes");
    }
    return text.isEmpty() ? "0 minutes" : text.toString();
  }
}
