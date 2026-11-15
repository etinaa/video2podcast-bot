package com.video2podcast.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Writes a file so that a crash never leaves it half-written: the bytes go to {@code <name>.tmp} in
 * the same directory, are flushed, and the temporary file is then renamed over the target. Callers
 * must not write the same file from two threads at once.
 */
public final class AtomicFiles {

  private AtomicFiles() {}

  public static void write(Path file, byte[] bytes) throws IOException {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.createDirectories(Objects.requireNonNull(file.toAbsolutePath().getParent(), "no parent"));
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
      writeFully(channel, ByteBuffer.wrap(bytes));
      channel.force(true);
    }
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** A channel may accept only part of the buffer per call, so keep writing until it is empty. */
  static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }
}
