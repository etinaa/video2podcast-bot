package com.video2podcast.bot;

import com.video2podcast.job.FileState;
import java.util.List;

/** Inline keyboards and the callback data they carry. */
final class Keyboards {

  static final String ACCESS_PREFIX = "access:";
  static final String APPROVE = "approve";
  static final String DENY = "deny";

  static final String AUDIO_PREFIX = "audio:";
  static final String TRANSCRIPT_PREFIX = "transcript:";

  private Keyboards() {}

  /** One row, in this order. */
  static List<InlineButton> delivery(String jobId) {
    return delivery(jobId, FileState.AVAILABLE, FileState.AVAILABLE);
  }

  /** One row, audio first. Each label follows the state of its file; the callback data does not. */
  static List<InlineButton> delivery(String jobId, FileState audio, FileState transcript) {
    return List.of(
        new InlineButton(audioLabel(audio), AUDIO_PREFIX + jobId),
        new InlineButton(transcriptLabel(transcript), TRANSCRIPT_PREFIX + jobId));
  }

  private static String audioLabel(FileState state) {
    return switch (state) {
      case AVAILABLE -> "Download audio";
      case SENDING -> "Preparing audio…";
      case SENT -> "Audio sent";
      case EXPIRED -> "Audio expired";
    };
  }

  private static String transcriptLabel(FileState state) {
    return switch (state) {
      case AVAILABLE -> "Download transcript";
      case SENDING -> "Sending transcript…";
      case SENT -> "Transcript sent";
      case EXPIRED -> "Transcript expired";
    };
  }

  static List<InlineButton> accessRequest(long userId) {
    return List.of(
        new InlineButton("Approve", ACCESS_PREFIX + APPROVE + ":" + userId),
        new InlineButton("Deny", ACCESS_PREFIX + DENY + ":" + userId));
  }
}
