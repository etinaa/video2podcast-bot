package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkSplitterTest {

  @TempDir Path dir;

  private static void write(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x");
  }

  @Test
  void segmentArgumentsUseTenMinutes() {
    List<String> args = ChunkSplitter.splitArgs(Path.of("/w/a.wav"), Path.of("/w/chunks"));

    assertThat(args)
        .containsSubsequence("-f", "segment", "-segment_time", "600", "-c", "copy")
        .endsWith("/w/chunks/chunk-%03d.wav");
    assertThat(ChunkSplitter.SEGMENT_SECONDS).isEqualTo(600);
  }

  @Test
  void splitEscapesPercentInTheOutputPattern() {
    List<String> args = ChunkSplitter.splitArgs(Path.of("/w/a.wav"), Path.of("/w/50%/chunks"));

    assertThat(args.get(args.size() - 1)).isEqualTo("/w/50%%/chunks/chunk-%03d.wav");
  }

  @Test
  void collectSortsByNameAndIgnoresOtherFiles() throws IOException {
    Path chunks = dir.resolve("chunks");
    write(chunks.resolve("chunk-001.wav"));
    write(chunks.resolve("chunk-000.wav"));
    write(chunks.resolve("chunk-1.wav"));
    write(chunks.resolve("chunk-001.wav.tmp"));

    assertThat(ChunkSplitter.collect(chunks))
        .containsExactly(chunks.resolve("chunk-000.wav"), chunks.resolve("chunk-001.wav"));
  }

  @Test
  void deleteExistingRemovesOnlyChunks() throws IOException {
    Path chunks = dir.resolve("chunks");
    write(chunks.resolve("chunk-000.wav"));
    write(chunks.resolve("keep.txt"));

    ChunkSplitter.deleteExisting(chunks);

    assertThat(chunks.resolve("chunk-000.wav")).doesNotExist();
    assertThat(chunks.resolve("keep.txt")).exists();
  }

  @Test
  void missingChunksDirIsFailed() {
    Path missing = dir.resolve("nope");

    assertThatThrownBy(() -> ChunkSplitter.collect(missing))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED));
    assertThatThrownBy(() -> ChunkSplitter.deleteExisting(missing))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED));
  }
}
