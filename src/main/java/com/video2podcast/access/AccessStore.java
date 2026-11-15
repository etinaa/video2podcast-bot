package com.video2podcast.access;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code allowed-users.json}. A write goes to a temporary file in the same
 * directory that is then renamed over the target, so a crash never leaves a half-written file.
 * Callers are responsible for serializing access (see {@link AccessService}).
 */
public class AccessStore {

  private static final Logger LOG = LoggerFactory.getLogger(AccessStore.class);

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .serializationInclusion(JsonInclude.Include.NON_NULL)
          .build();

  private final Path file;

  public AccessStore(Path file) {
    this.file = file;
  }

  /**
   * Returns the stored state, or an empty state when the file does not exist. A file that is not
   * valid JSON is renamed to {@code <name>.corrupt-<epoch seconds>} and an empty state is returned,
   * so the bot fails closed: only the owner has access until users are added again.
   */
  public AccessState load() {
    if (!Files.exists(file)) {
      return AccessState.empty();
    }
    try {
      AccessState parsed = parse(Files.readAllBytes(file));
      if (parsed != null) {
        return parsed;
      }
      Path aside =
          file.resolveSibling(file.getFileName() + ".corrupt-" + Instant.now().getEpochSecond());
      Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
      LOG.error(
          "{} is not a valid allowlist, moved to {}. Starting with an empty allowlist.",
          file,
          aside);
      return AccessState.empty();
    } catch (IOException e) {
      throw new AccessStoreException("Cannot read " + file, e);
    }
  }

  /** Returns the parsed state, or null when the content is not valid JSON or is incomplete. */
  private static AccessState parse(byte[] bytes) {
    try {
      AccessState state = MAPPER.readValue(bytes, AccessState.class);
      return isComplete(state) ? state : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean isComplete(AccessState state) {
    return state != null
        && state.users().stream().allMatch(u -> u.id() != 0 && u.addedAt() != null)
        && state.pending().stream().allMatch(p -> p.id() != 0 && p.requestedAt() != null)
        && state.denials().stream().allMatch(d -> d.userId() != 0 && d.deniedAt() != null);
  }

  public void save(AccessState state) {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Files.createDirectories(
          Objects.requireNonNull(
              file.toAbsolutePath().getParent(), "allowlist file has no parent"));
      byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
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
    } catch (IOException e) {
      throw new AccessStoreException("Cannot write " + file, e);
    }
  }

  /** A channel may accept only part of the buffer per call, so keep writing until it is empty. */
  static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }
}
