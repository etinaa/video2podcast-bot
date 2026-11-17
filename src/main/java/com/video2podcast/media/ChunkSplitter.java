package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** The pure part of splitting: the ffmpeg segment arguments and the list of produced chunks. */
final class ChunkSplitter {

  static final int SEGMENT_SECONDS = 600;

  private static final Pattern CHUNK = Pattern.compile("chunk-\\d{3}\\.wav");

  private ChunkSplitter() {}

  static List<String> splitArgs(Path wav, Path chunksDir) {
    // ffmpeg reads % in the output pattern as a format marker; %% is a literal percent sign.
    String pattern = chunksDir.toString().replace("%", "%%") + "/chunk-%03d.wav";
    return List.of(
        "ffmpeg",
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        wav.toString(),
        "-f",
        "segment",
        "-segment_time",
        String.valueOf(SEGMENT_SECONDS),
        "-c",
        "copy",
        pattern);
  }

  /** The chunk files in {@code chunksDir}, sorted by name. */
  static List<Path> collect(Path chunksDir) {
    List<Path> chunks = list(chunksDir);
    chunks.sort(Comparator.comparing(ChunkSplitter::name));
    return chunks;
  }

  /** Deletes the chunk files left in {@code chunksDir} by an earlier run. */
  static void deleteExisting(Path chunksDir) {
    try {
      for (Path stale : list(chunksDir)) {
        Files.deleteIfExists(stale);
      }
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
  }

  private static String name(Path path) {
    return Objects.toString(path.getFileName(), "");
  }

  private static List<Path> list(Path chunksDir) {
    List<Path> found = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(chunksDir)) {
      for (Path path : stream) {
        if (CHUNK.matcher(name(path)).matches() && Files.isRegularFile(path)) {
          found.add(path);
        }
      }
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    return found;
  }
}
