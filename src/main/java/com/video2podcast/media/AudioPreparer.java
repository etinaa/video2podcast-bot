package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the audio of a job fit Telegram's upload limit. A small MP3 or M4A is sent as it is, any
 * other small source becomes M4A, and a big one is compressed to mono AAC. Knows nothing about
 * Telegram or job state.
 */
public class AudioPreparer {

  private static final Logger LOG = LoggerFactory.getLogger(AudioPreparer.class);

  static final long LIMIT_BYTES = 50L * 1024 * 1024;
  static final long TARGET_BYTES = 48L * 1024 * 1024;
  static final int CONVERT_KBPS = 128;
  static final int MIN_KBPS = 8;
  static final int MAX_KBPS = 128;
  static final String OUTPUT_NAME = "out.m4a";

  private final FfmpegRunner ffmpeg;

  public AudioPreparer(FfmpegRunner ffmpeg) {
    this.ffmpeg = ffmpeg;
  }

  /**
   * The file to upload for {@code source}. An encoded file is {@code out.m4a} next to the source.
   *
   * @param durationSeconds a value of 0 or less is treated as 1
   * @throws JobFailure FAILED or TIMEOUT; no {@code out.m4a} is left behind
   */
  public PreparedAudio prepare(Path source, long durationSeconds) {
    long size = sizeOf(source);
    Path out = source.resolveSibling(OUTPUT_NAME);
    try {
      if (size <= LIMIT_BYTES) {
        if (isMp3OrM4a(source)) {
          return new PreparedAudio(source, PreparedAudio.Mode.UNCHANGED, 0);
        }
        ffmpeg.encodeM4a(source, out, CONVERT_KBPS, false);
        if (fits(out)) {
          return new PreparedAudio(out, PreparedAudio.Mode.CONVERTED, CONVERT_KBPS);
        }
      }
      return compress(source, out, durationSeconds);
    } catch (RuntimeException e) {
      deleteQuietly(out);
      throw e;
    }
  }

  /** The bitrate that would put {@code durationSeconds} of audio at the target size. */
  static int targetKbps(long durationSeconds) {
    long seconds = Math.max(durationSeconds, 1);
    long kbps = TARGET_BYTES * 8 / seconds / 1000;
    return (int) Math.min(MAX_KBPS, Math.max(MIN_KBPS, kbps));
  }

  private PreparedAudio compress(Path source, Path out, long durationSeconds) {
    int kbps = targetKbps(durationSeconds);
    ffmpeg.encodeM4a(source, out, kbps, true);
    if (fits(out)) {
      return new PreparedAudio(out, PreparedAudio.Mode.COMPRESSED, kbps);
    }
    int lower = Math.max(MIN_KBPS, kbps * 4 / 5);
    if (lower < kbps) {
      ffmpeg.encodeM4a(source, out, lower, true);
      if (fits(out)) {
        return new PreparedAudio(out, PreparedAudio.Mode.COMPRESSED, lower);
      }
    }
    LOG.warn("Audio is still over the upload limit after compression");
    throw JobFailure.of(JobFailure.Kind.FAILED);
  }

  private static boolean fits(Path file) {
    long size = sizeOf(file);
    return size > 0 && size < LIMIT_BYTES;
  }

  private static boolean isMp3OrM4a(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".mp3") || name.endsWith(".m4a");
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Could not delete {}", file, e);
    }
  }
}
