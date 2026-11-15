package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FileStateTest {

  @Test
  void onlyTheDocumentedMovesAreAllowed() {
    assertThat(allowedFrom(FileState.AVAILABLE))
        .containsExactlyInAnyOrder(FileState.SENDING, FileState.EXPIRED);
    assertThat(allowedFrom(FileState.SENDING))
        .containsExactlyInAnyOrder(FileState.SENT, FileState.AVAILABLE);
  }

  @Test
  void sentAndExpiredAreFinal() {
    assertThat(allowedFrom(FileState.SENT)).isEmpty();
    assertThat(allowedFrom(FileState.EXPIRED)).isEmpty();
  }

  @Test
  void aStateNeverMovesToItself() {
    for (FileState state : FileState.values()) {
      assertThat(state.canMoveTo(state)).isFalse();
    }
  }

  private static Set<FileState> allowedFrom(FileState from) {
    Set<FileState> allowed = EnumSet.noneOf(FileState.class);
    for (FileState to : FileState.values()) {
      if (from.canMoveTo(to)) {
        allowed.add(to);
      }
    }
    return allowed;
  }
}
