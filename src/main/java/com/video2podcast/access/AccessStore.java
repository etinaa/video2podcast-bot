package com.video2podcast.access;

import com.video2podcast.storage.AtomicFiles;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code allowed-users.json}. A write goes to a temporary file in the same
 * directory that is then renamed over the target, so a crash never leaves a half-written file.
 * Callers are responsible for serializing access (see {@link AccessService}).
 */
public class AccessStore {

  private static final Logger LOG = LoggerFactory.getLogger(AccessStore.class);

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
      AccessState state = Json.MAPPER.readValue(bytes, AccessState.class);
      return isComplete(state) ? state : null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean isComplete(AccessState state) {
    return state != null
        && state.users().stream().allMatch(u -> u.id() != 0 && u.addedAt() != null)
        && state.pending().stream()
            .allMatch(p -> p.id() != 0 && p.requestedAt() != null && p.displayName() != null)
        && state.denials().stream().allMatch(d -> d.userId() != 0 && d.deniedAt() != null);
  }

  public void save(AccessState state) {
    try {
      AtomicFiles.write(
          file, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
    } catch (IOException e) {
      throw new AccessStoreException("Cannot write " + file, e);
    }
  }
}
