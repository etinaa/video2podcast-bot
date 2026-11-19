package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.DeliveryService.Claim;
import com.video2podcast.job.DeliveryService.Outcome;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeliveryServiceTest {

  private static final Instant T0 = Instant.parse("2026-11-19T12:30:00Z");
  private static final String ID = "job-1";
  private static final long OWNER = 42L;

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private JobStore store;
  private DeliveryService service;

  @BeforeEach
  void setUp() throws IOException {
    store = new JobStore(dir.resolve("work"), clock);
    service = new DeliveryService(store, clock);
    create(FileState.AVAILABLE, T0.plus(Duration.ofMinutes(30)));
  }

  private void create(FileState transcript, Instant expiresAt) throws IOException {
    JobRecord job =
        new JobRecord(
            ID,
            OWNER,
            4242L,
            7,
            "Title",
            60,
            T0.minusSeconds(600),
            Map.of(
                FileKind.AUDIO, new JobFile("audio.m4a", FileState.AVAILABLE, expiresAt, null),
                FileKind.TRANSCRIPT, new JobFile("transcript.txt", transcript, expiresAt, null)));
    store.create(job);
    Files.writeString(store.directory(ID).resolve("transcript.txt"), "text");
  }

  private FileState state(FileKind kind) {
    return store.load(ID).orElseThrow().files().get(kind).state();
  }

  @Test
  void anOwnerTapGrantsAndMovesTheFileToSending() {
    Claim claim = service.claim(ID, OWNER, FileKind.TRANSCRIPT);

    assertThat(claim.outcome()).isEqualTo(Outcome.GRANTED);
    assertThat(claim.file()).isEqualTo(store.directory(ID).resolve("transcript.txt"));
    assertThat(claim.job().files().get(FileKind.TRANSCRIPT).state()).isEqualTo(FileState.SENDING);
    assertThat(state(FileKind.TRANSCRIPT)).isEqualTo(FileState.SENDING);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void aTapJustBeforeExpiryIsGranted() {
    clock.advance(Duration.ofMinutes(30).minusNanos(1));

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome()).isEqualTo(Outcome.GRANTED);
  }

  @Test
  void aTapAtOrAfterExpiryIsExpiredAndChangesNothing() {
    clock.advance(Duration.ofMinutes(30));

    Claim claim = service.claim(ID, OWNER, FileKind.TRANSCRIPT);

    assertThat(claim.outcome()).isEqualTo(Outcome.EXPIRED);
    assertThat(claim.file()).isNull();
    assertThat(state(FileKind.TRANSCRIPT)).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void aFileWithoutExpiryTimeNeverExpiresAtTapTime() throws IOException {
    store.discard(ID);
    create(FileState.AVAILABLE, null);

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome()).isEqualTo(Outcome.GRANTED);
  }

  @Test
  void anotherUserIsRefusedAndChangesNothing() {
    Claim claim = service.claim(ID, OWNER + 1, FileKind.TRANSCRIPT);

    assertThat(claim.outcome()).isEqualTo(Outcome.NOT_OWNER);
    assertThat(claim.job()).isNotNull();
    assertThat(state(FileKind.TRANSCRIPT)).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void aSecondTapAfterTheSendIsAlreadySentEvenWhenRetentionHasPassed() {
    service.claim(ID, OWNER, FileKind.TRANSCRIPT);
    service.complete(ID, FileKind.TRANSCRIPT);
    clock.advance(Duration.ofHours(2));

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome())
        .isEqualTo(Outcome.ALREADY_SENT);
  }

  @Test
  void aTapWhileSendingIsInProgress() {
    service.claim(ID, OWNER, FileKind.TRANSCRIPT);
    clock.advance(Duration.ofHours(2));

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome())
        .isEqualTo(Outcome.IN_PROGRESS);
  }

  @Test
  void anExpiredFileStaysExpired() throws IOException {
    store.discard(ID);
    create(FileState.EXPIRED, T0.plusSeconds(100));

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome()).isEqualTo(Outcome.EXPIRED);
  }

  @Test
  void anUnknownOrInvalidJobIsExpired() {
    for (String id : new String[] {"nope", "", "../x", null}) {
      Claim claim = service.claim(id, OWNER, FileKind.TRANSCRIPT);
      assertThat(claim.outcome()).isEqualTo(Outcome.EXPIRED);
      assertThat(claim.job()).isNull();
    }
  }

  @Test
  void aMissingFileEntryIsExpired() throws IOException {
    store.discard(ID);
    store.create(
        new JobRecord(
            ID, OWNER, 1L, null, "T", 1, T0, Map.of(FileKind.AUDIO, JobFile.available("a"))));

    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome()).isEqualTo(Outcome.EXPIRED);
  }

  @Test
  void aMissingFileOnDiskExpiresTheEntryWithoutAGrant() throws IOException {
    Files.delete(store.directory(ID).resolve("transcript.txt"));

    Claim claim = service.claim(ID, OWNER, FileKind.TRANSCRIPT);

    assertThat(claim.outcome()).isEqualTo(Outcome.EXPIRED);
    assertThat(state(FileKind.TRANSCRIPT)).isEqualTo(FileState.EXPIRED);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void completeMovesSendingToSentAndSetsSentAt() {
    service.claim(ID, OWNER, FileKind.TRANSCRIPT);
    clock.advance(Duration.ofSeconds(5));

    JobRecord done = service.complete(ID, FileKind.TRANSCRIPT).orElseThrow();

    assertThat(done.files().get(FileKind.TRANSCRIPT).state()).isEqualTo(FileState.SENT);
    assertThat(done.files().get(FileKind.TRANSCRIPT).sentAt()).isEqualTo(T0.plusSeconds(5));
  }

  @Test
  void completeAndReleaseAreEmptyWhenTheFileIsNotSending() {
    assertThat(service.complete(ID, FileKind.TRANSCRIPT)).isEmpty();
    assertThat(service.release(ID, FileKind.TRANSCRIPT)).isEmpty();
    assertThat(service.complete("nope", FileKind.TRANSCRIPT)).isEmpty();
  }

  @Test
  void releaseMovesSendingBackToAvailableAndAllowsANewClaim() {
    service.claim(ID, OWNER, FileKind.TRANSCRIPT);

    JobRecord released = service.release(ID, FileKind.TRANSCRIPT).orElseThrow();

    assertThat(released.files().get(FileKind.TRANSCRIPT).state()).isEqualTo(FileState.AVAILABLE);
    assertThat(released.files().get(FileKind.TRANSCRIPT).sentAt()).isNull();
    assertThat(service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome()).isEqualTo(Outcome.GRANTED);
  }

  @Test
  void ofManyConcurrentTapsExactlyOneIsGranted() throws Exception {
    int taps = 8;
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(taps)) {
      List<Future<Outcome>> results = new ArrayList<>();
      for (int i = 0; i < taps; i++) {
        results.add(
            pool.submit(() -> {
              start.await();
              return service.claim(ID, OWNER, FileKind.TRANSCRIPT).outcome();
            }));
      }
      start.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> result : results) {
        outcomes.add(result.get());
      }
      assertThat(outcomes).filteredOn(o -> o == Outcome.GRANTED).hasSize(1);
      assertThat(outcomes).filteredOn(o -> o != Outcome.GRANTED).containsOnly(Outcome.IN_PROGRESS);
    }
  }

  @Test
  void aLostRaceReportsWhatTheWinnerDid() {
    // The state a loser sees after its transition missed: reload and classify.
    JobStore racing =
        new JobStore(dir.resolve("work"), clock) {
          @Override
          public Transition transition(String id, FileKind kind, FileState from, FileState to) {
            super.transition(id, kind, FileState.AVAILABLE, FileState.SENDING);
            super.transition(id, kind, FileState.SENDING, FileState.SENT);
            return Transition.STATE_MISMATCH;
          }
        };

    assertThat(new DeliveryService(racing, clock).claim(ID, OWNER, FileKind.TRANSCRIPT).outcome())
        .isEqualTo(Outcome.ALREADY_SENT);
  }

  @Test
  void aLostRaceAgainstAnExpiryIsExpired() {
    JobStore racing =
        new JobStore(dir.resolve("work"), clock) {
          @Override
          public Transition transition(String id, FileKind kind, FileState from, FileState to) {
            super.transition(id, kind, FileState.AVAILABLE, FileState.EXPIRED);
            return Transition.STATE_MISMATCH;
          }
        };

    assertThat(new DeliveryService(racing, clock).claim(ID, OWNER, FileKind.TRANSCRIPT).outcome())
        .isEqualTo(Outcome.EXPIRED);
  }

  @Test
  void aLostRaceAgainstADeletionIsExpiredWithoutAJob() {
    JobStore gone =
        new JobStore(dir.resolve("work"), clock) {
          @Override
          public Transition transition(String id, FileKind kind, FileState from, FileState to) {
            discard(id);
            return Transition.NOT_FOUND;
          }
        };
    Claim claim = new DeliveryService(gone, clock).claim(ID, OWNER, FileKind.TRANSCRIPT);
    assertThat(claim.outcome()).isEqualTo(Outcome.EXPIRED);
    assertThat(claim.job()).isNull();
  }

  @Test
  void aLostRaceWhileSendingIsInProgress() {
    JobStore racing =
        new JobStore(dir.resolve("work"), clock) {
          @Override
          public Transition transition(String id, FileKind kind, FileState from, FileState to) {
            super.transition(id, kind, FileState.AVAILABLE, FileState.SENDING);
            return Transition.STATE_MISMATCH;
          }
        };

    assertThat(new DeliveryService(racing, clock).claim(ID, OWNER, FileKind.TRANSCRIPT).outcome())
        .isEqualTo(Outcome.IN_PROGRESS);
  }
}
