package com.video2podcast.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Asks yt-dlp for a video's metadata and downloads its audio. */
public class YtDlpClient {

  private static final Logger LOG = LoggerFactory.getLogger(YtDlpClient.class);

  private static final int LOG_LIMIT = 2000;
  private static final Set<String> COLLECTIONS = Set.of("playlist", "multi_video");

  private final ProcessRunner runner;
  private final UrlValidator validator;
  private final AppProperties.Jobs jobs;

  public YtDlpClient(ProcessRunner runner, UrlValidator validator, AppProperties.Jobs jobs) {
    this.runner = runner;
    this.validator = validator;
    this.jobs = jobs;
  }

  /**
   * @throws JobFailure UNSUPPORTED (bad URL, live or no duration), TOO_LONG, PRIVATE, FAILED,
   *     TIMEOUT
   */
  public VideoMetadata fetchMetadata(String url) {
    validator.validate(url);
    ProcessResult result =
        runner.run(
            List.of(
                "yt-dlp",
                "--no-playlist",
                "--playlist-items",
                "1",
                "--no-warnings",
                "--skip-download",
                "--dump-single-json",
                "--",
                url),
            jobs.metadataTimeout());
    requireSuccess(result);
    return parse(result.stdout());
  }

  /**
   * Downloads into {@code jobDir} as {@code source.<ext>} and returns that file.
   *
   * @throws JobFailure as for {@link #fetchMetadata}
   */
  public Path downloadAudio(String url, Path jobDir) {
    validator.validate(url);
    if (!Files.isDirectory(jobDir)) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    for (Path stale : sourceFiles(jobDir, true)) {
      try {
        Files.deleteIfExists(stale);
      } catch (IOException e) {
        throw JobFailure.of(JobFailure.Kind.FAILED, e);
      }
    }
    // yt-dlp reads % in -o as a template marker, and the work directory is configurable.
    String template = jobDir.toString().replace("%", "%%") + "/source.%(ext)s";
    ProcessResult result =
        runner.run(
            List.of(
                "yt-dlp",
                "--no-playlist",
                "--playlist-items",
                "1",
                "--no-warnings",
                "-f",
                "bestaudio[ext=m4a]/bestaudio",
                "-o",
                template,
                "--",
                url),
            jobs.downloadTimeout());
    requireSuccess(result);
    List<Path> sources = sourceFiles(jobDir, false);
    if (sources.size() != 1) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    return sources.get(0);
  }

  private void requireSuccess(ProcessResult result) {
    if (result.exitCode() != 0) {
      String stderr = result.stderr() == null ? "" : result.stderr();
      if (LOG.isWarnEnabled()) {
        LOG.warn("yt-dlp exited with {}: {}", result.exitCode(), forLog(stderr));
      }
      throw JobFailure.of(StderrClassifier.classify(stderr));
    }
  }

  private VideoMetadata parse(String stdout) {
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(stdout);
    } catch (IOException | RuntimeException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    if (root == null || !root.isObject()) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    if (COLLECTIONS.contains(root.path("_type").asText(""))) {
      throw JobFailure.of(JobFailure.Kind.UNSUPPORTED);
    }
    JsonNode title = root.path("title");
    if (!title.isTextual() || title.asText().isBlank()) {
      throw JobFailure.of(JobFailure.Kind.FAILED);
    }
    JsonNode live = root.path("is_live");
    if (live.isBoolean() && live.booleanValue()) {
      throw JobFailure.unsupportedLiveStream();
    }
    JsonNode seconds = root.path("duration");
    if (!seconds.isNumber() || seconds.decimalValue().signum() <= 0) {
      throw JobFailure.of(JobFailure.Kind.UNSUPPORTED);
    }
    // Round up, so 10800.1 s never slips under a 3 h limit.
    long whole =
        seconds
            .decimalValue()
            .setScale(0, RoundingMode.CEILING)
            .min(BigDecimal.valueOf(Long.MAX_VALUE / 2))
            .longValueExact();
    Duration duration = Duration.ofSeconds(whole);
    if (duration.compareTo(jobs.maxVideoDuration()) > 0) {
      throw JobFailure.tooLong(jobs.maxVideoDuration());
    }
    return new VideoMetadata(title.asText(), duration);
  }

  /** {@code source.*} files in {@code jobDir}; partial downloads only when {@code withPartial}. */
  private static List<Path> sourceFiles(Path jobDir, boolean withPartial) {
    List<Path> found = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir, "source.*")) {
      for (Path path : stream) {
        String name = Objects.toString(path.getFileName(), "");
        boolean partial = name.endsWith(".part") || name.endsWith(".ytdl");
        if (Files.isRegularFile(path) && (withPartial || !partial)) {
          found.add(path);
        }
      }
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    return found;
  }

  static String forLog(String stderr) {
    String text = stderr.length() > LOG_LIMIT ? stderr.substring(0, LOG_LIMIT) : stderr;
    return text.replace('\r', ' ').replace('\n', ' ');
  }
}
