package com.video2podcast.media;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns a downloaded source into a 16 kHz mono WAV and cuts it into ten-minute chunks. */
public class FfmpegRunner {

  private static final Logger LOG = LoggerFactory.getLogger(FfmpegRunner.class);

  private final ProcessRunner runner;
  private final AppProperties.Jobs jobs;

  public FfmpegRunner(ProcessRunner runner, AppProperties.Jobs jobs) {
    this.runner = runner;
    this.jobs = jobs;
  }

  /**
   * Creates {@code audio.wav} (16 kHz, mono, pcm_s16le) in {@code jobDir} and returns it.
   *
   * @throws JobFailure FAILED or TIMEOUT
   */
  public Path toWav(Path source, Path jobDir) {
    Path wav = jobDir.resolve("audio.wav");
    try {
      Files.deleteIfExists(wav);
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            source.toString(),
            "-vn",
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            wav.toString()));
    if (!nonEmptyFile(wav)) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    return wav;
  }

  /**
   * Splits {@code wav} into {@code <jobDir>/chunks/chunk-NNN.wav} of about ten minutes.
   *
   * @return the chunks in order
   */
  public List<Path> split(Path wav, Path jobDir) {
    if (!Files.isRegularFile(wav)) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    Path chunksDir = jobDir.resolve("chunks");
    try {
      Files.createDirectories(chunksDir);
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    ChunkSplitter.deleteExisting(chunksDir);
    run(ChunkSplitter.splitArgs(wav, chunksDir));
    List<Path> chunks = ChunkSplitter.collect(chunksDir);
    if (chunks.isEmpty()) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    return chunks;
  }

  /**
   * Writes an M4A (AAC) file without video to {@code target}, replacing any file there.
   *
   * @return target
   * @throws JobFailure FAILED (non-zero exit, no or empty output) or TIMEOUT; no output is left
   *     behind
   */
  public Path encodeM4a(Path source, Path target, int kbps, boolean mono) {
    try {
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    List<String> args = new ArrayList<>();
    args.addAll(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i"));
    args.add(source.toString());
    args.add("-vn");
    if (mono) {
      args.addAll(List.of("-ac", "1"));
    }
    args.addAll(List.of("-c:a", "aac", "-b:a", kbps + "k", "-movflags", "+faststart"));
    args.add(target.toString());
    try {
      run(args);
      if (!nonEmptyFile(target)) {
        throw JobFailure.of(JobFailure.Kind.FAILED);
      }
    } catch (RuntimeException e) {
      deleteQuietly(target);
      throw e;
    }
    return target;
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Could not delete {}", file, e);
    }
  }

  private void run(List<String> args) {
    ProcessResult result = runner.run(args, jobs.ffmpegTimeout());
    if (result.exitCode() != 0) {
      String stderr = result.stderr() == null ? "" : result.stderr();
      if (LOG.isWarnEnabled()) {
        LOG.warn("ffmpeg exited with {}: {}", result.exitCode(), YtDlpClient.forLog(stderr));
      }
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
  }

  private static boolean nonEmptyFile(Path file) {
    try {
      return Files.isRegularFile(file) && Files.size(file) > 0;
    } catch (IOException e) {
      return false;
    }
  }
}
