package com.video2podcast.media;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
