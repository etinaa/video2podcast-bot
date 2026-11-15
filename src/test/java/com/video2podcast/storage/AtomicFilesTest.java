package com.video2podcast.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {

  @TempDir Path dir;

  @Test
  void writesNewFilesAndCreatesMissingDirectories() throws IOException {
    Path file = dir.resolve("a/b/state.json");

    AtomicFiles.write(file, "one".getBytes(StandardCharsets.UTF_8));

    assertThat(file).hasContent("one");
  }

  @Test
  void replacesAnExistingFileAndLeavesNoTemporaryFile() throws IOException {
    Path file = dir.resolve("state.json");
    AtomicFiles.write(file, "first".getBytes(StandardCharsets.UTF_8));

    AtomicFiles.write(file, "second".getBytes(StandardCharsets.UTF_8));

    assertThat(file).hasContent("second");
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("state.json");
    }
  }

  @Test
  void failsWhenTheDirectoryCannotBeCreated() throws IOException {
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "a file, not a directory");

    assertThatThrownBy(() -> AtomicFiles.write(blocker.resolve("x.json"), new byte[0]))
        .isInstanceOf(IOException.class);
  }

  @Test
  void writeFullyKeepsWritingUntilTheBufferIsEmpty() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WritableByteChannel oneByteAtATime =
        new WritableByteChannel() {
          @Override
          public int write(ByteBuffer src) {
            out.write(src.get());
            return 1;
          }

          @Override
          public boolean isOpen() {
            return true;
          }

          @Override
          public void close() {}
        };

    AtomicFiles.writeFully(
        oneByteAtATime, ByteBuffer.wrap("complete file".getBytes(StandardCharsets.UTF_8)));

    assertThat(out).hasToString("complete file");
  }
}
