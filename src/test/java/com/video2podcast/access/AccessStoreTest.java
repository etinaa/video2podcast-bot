package com.video2podcast.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessStoreTest {

  private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

  @TempDir Path dir;

  @Test
  void missingFileGivesEmptyState() {
    AccessStore store = new AccessStore(dir.resolve("allowed-users.json"));

    assertThat(store.load()).isEqualTo(AccessState.empty());
  }

  @Test
  void savedStateIsReadBackAfterRestart() {
    Path file = dir.resolve("nested/allowed-users.json");
    AccessState state =
        new AccessState(
            List.of(new AllowedUser(7, "ann", T0, 1)),
            List.of(new PendingRequest(8, null, "Bob <b>", T0, 55)),
            List.of(new Denial(9, T0)));

    new AccessStore(file).save(state);

    assertThat(new AccessStore(file).load()).isEqualTo(state);
  }

  @Test
  void saveReplacesTheFileAndLeavesNoTempFiles() throws IOException {
    Path file = dir.resolve("allowed-users.json");
    AccessStore store = new AccessStore(file);
    store.save(AccessState.empty());

    store.save(new AccessState(List.of(new AllowedUser(1, "a", T0, 0)), null, null));

    assertThat(store.load().users()).hasSize(1);
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("allowed-users.json");
    }
  }

  @Test
  void corruptFileIsMovedAsideAndTreatedAsEmpty() throws IOException {
    Path file = dir.resolve("allowed-users.json");
    Files.writeString(file, "{ not json");

    AccessState state = new AccessStore(file).load();

    assertThat(state).isEqualTo(AccessState.empty());
    assertThat(file).doesNotExist();
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .singleElement()
          .asString()
          .startsWith("allowed-users.json.corrupt-");
    }
  }

  @Test
  void unreadableFileFailsLoudly() throws IOException {
    Path file = dir.resolve("allowed-users.json");
    Files.createDirectory(file);

    assertThatThrownBy(() -> new AccessStore(file).load())
        .isInstanceOf(AccessStoreException.class)
        .hasMessageContaining("read");
  }

  @Test
  void unwritableLocationFailsLoudly() throws IOException {
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "a file, not a directory");

    assertThatThrownBy(() -> new AccessStore(blocker.resolve("x.json")).save(AccessState.empty()))
        .isInstanceOf(AccessStoreException.class)
        .hasMessageContaining("write");
  }
}
