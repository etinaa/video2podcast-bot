package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.job.JobStore.Recovered;
import com.video2podcast.job.JobStore.Recovery;
import com.video2podcast.job.JobStore.Swept;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreRetentionTest {

  private static final Instant T0 = Instant.parse("2026-11-21T12:00:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-11-21T13:00:00Z");
  private static final Instant SENT_AT = Instant.parse("2026-11-21T12:10:00Z");
  private static final String ID = "job-1";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private JobStore store;
  private Path jobDir;

  @BeforeEach
  void setUp() throws IOException {
    store = new JobStore(dir.resolve("work"), clock);
    jobDir = store.directory(ID);
    Files.createDirectories(jobDir);
    Files.writeString(jobDir.resolve("audio.m4a"), "audio");
    Files.writeString(jobDir.resolve("transcript.txt"), "text");
  }

  private static JobFile file(String path, FileState state, Instant expiresAt, Instant sentAt) {
    return new JobFile(path, state, expiresAt, sentAt);
  }

  private void create(JobFile audio, JobFile transcript) {
    store.create(
        new JobRecord(
            ID,
            42L,
            4242L,
            700,
            "Title",
            60,
            T0,
            Map.of(FileKind.AUDIO, audio, FileKind.TRANSCRIPT, transcript)));
  }

  private void create(FileState audio, FileState transcript, Instant expiresAt) {
    create(
        file("audio.m4a", audio, expiresAt, audio == FileState.SENT ? SENT_AT : null),
        file(
            "transcript.txt",
            transcript,
            expiresAt,
            transcript == FileState.SENT ? SENT_AT : null));
  }

  private JobRecord stored() {
    return store.load(ID).orElseThrow();
  }

  // --- sweep ---

  @Test
  void sweepLeavesAJobWithoutJobJsonAlone() {
    assertThat(store.sweep(ID, DEADLINE, false)).isEqualTo(Swept.none());
    assertThat(jobDir.resolve("audio.m4a")).exists();
  }

  @Test
  void sweepLeavesAJobWithUnusableJobJsonAlone() throws IOException {
    Files.writeString(jobDir.resolve("job.json"), "{ not json");
    assertThat(store.sweep(ID, DEADLINE, true)).isEqualTo(Swept.none());
    assertThat(jobDir.resolve("audio.m4a")).exists();
  }

  @Test
  void noneIsEmpty() {
    Swept none = Swept.none();
    assertThat(none.job()).isNull();
    assertThat(none.expired()).isEmpty();
    assertThat(none.removed()).isFalse();
  }

  @Test
  void sweptKeepsACopyOfTheExpiredList() {
    List<FileKind> kinds = new java.util.ArrayList<>(List.of(FileKind.AUDIO));
    Swept swept = new Swept(null, kinds, false);
    kinds.clear();
    assertThat(swept.expired()).containsExactly(FileKind.AUDIO);
  }

  @Test
  void sweepLeavesWholeDirectoryAloneWhileAFileIsSending() {
    create(FileState.SENDING, FileState.AVAILABLE, DEADLINE);
    JobRecord before = stored();

    assertThat(store.sweep(ID, DEADLINE.plusSeconds(10), false)).isEqualTo(Swept.none());
    assertThat(store.sweep(ID, DEADLINE.plusSeconds(10), true)).isEqualTo(Swept.none());

    assertThat(stored()).isEqualTo(before);
    assertThat(jobDir.resolve("transcript.txt")).exists();
  }

  @Test
  void sweepKeepsFilesBeforeTheirDeadline() {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    JobRecord before = stored();

    Swept swept = store.sweep(ID, DEADLINE.minusNanos(1), false);

    assertThat(swept.expired()).isEmpty();
    assertThat(swept.removed()).isFalse();
    assertThat(swept.job()).isEqualTo(before);
    assertThat(stored()).isEqualTo(before);
    assertThat(jobDir.resolve("audio.m4a")).exists();
  }

  @Test
  void sweepExpiresBothFilesAtTheDeadlineAndRemovesTheDirectory() throws IOException {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    Files.createDirectories(jobDir.resolve("chunks"));
    Files.writeString(jobDir.resolve("chunks/chunk-000.wav"), "w");
    Files.writeString(jobDir.resolve("out.m4a"), "o");

    Swept swept = store.sweep(ID, DEADLINE, false);

    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).containsExactlyInAnyOrder(FileKind.AUDIO, FileKind.TRANSCRIPT);
    assertThat(swept.job().files().values()).allMatch(f -> f.state() == FileState.EXPIRED);
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void sweepExpiresOnlyTheFileThatIsDueAndKeepsTheOther() {
    create(
        file("audio.m4a", FileState.AVAILABLE, DEADLINE, null),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE.plusSeconds(60), null));

    Swept swept = store.sweep(ID, DEADLINE, false);

    assertThat(swept.removed()).isFalse();
    assertThat(swept.expired()).containsExactly(FileKind.AUDIO);
    assertThat(jobDir.resolve("audio.m4a")).doesNotExist();
    assertThat(jobDir.resolve("transcript.txt")).hasContent("text");
    JobRecord saved = stored();
    assertThat(saved.files().get(FileKind.AUDIO).state()).isEqualTo(FileState.EXPIRED);
    assertThat(saved.files().get(FileKind.TRANSCRIPT))
        .isEqualTo(file("transcript.txt", FileState.AVAILABLE, DEADLINE.plusSeconds(60), null));
    assertThat(swept.job()).isEqualTo(saved);
  }

  @Test
  void sweepKeepsTheSentLabelAndTimeWhenTheSiblingExpires() {
    create(
        file("audio.m4a", FileState.SENT, DEADLINE, SENT_AT),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE, null));

    Swept swept = store.sweep(ID, DEADLINE, false);

    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).containsExactly(FileKind.TRANSCRIPT);
    assertThat(swept.job().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENT);
    assertThat(swept.job().files().get(FileKind.AUDIO).sentAt()).isEqualTo(SENT_AT);
  }

  @Test
  void sweepKeepsAnUnexpiredFileAfterPartialDelivery() {
    create(
        file("audio.m4a", FileState.SENT, DEADLINE, SENT_AT),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE, null));

    Swept swept = store.sweep(ID, DEADLINE.minusSeconds(1), false);

    assertThat(swept.removed()).isFalse();
    assertThat(swept.expired()).isEmpty();
    assertThat(jobDir.resolve("transcript.txt")).exists();
    assertThat(stored().files().get(FileKind.AUDIO).sentAt()).isEqualTo(SENT_AT);
  }

  @Test
  void sweepRemovesTheDirectoryWhenEverythingIsAlreadyTerminalWithoutReportingExpiry()
      throws IOException {
    create(
        file("audio.m4a", FileState.SENT, DEADLINE, SENT_AT),
        file("transcript.txt", FileState.EXPIRED, DEADLINE, null));
    Files.delete(jobDir.resolve("transcript.txt"));

    Swept swept = store.sweep(ID, T0, false);

    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).isEmpty();
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void sweepDoesNotTreatAnEmptyFileMapAsFinished() {
    store.create(new JobRecord(ID, 42L, 4242L, null, "Title", 60, T0, null));

    Swept swept = store.sweep(ID, DEADLINE, true);

    assertThat(swept.removed()).isFalse();
    assertThat(jobDir.resolve("job.json")).exists();
  }

  @Test
  void sweepAtRuntimeNeverExpiresAFileWithoutDeadline() {
    create(FileState.AVAILABLE, FileState.AVAILABLE, null);
    JobRecord before = stored();

    Swept swept = store.sweep(ID, DEADLINE.plusSeconds(86_400), false);

    assertThat(swept.expired()).isEmpty();
    assertThat(swept.removed()).isFalse();
    assertThat(stored()).isEqualTo(before);
    assertThat(jobDir.resolve("audio.m4a")).exists();
  }

  @Test
  void sweepAtStartupExpiresFilesWithoutDeadline() {
    create(FileState.AVAILABLE, FileState.AVAILABLE, null);

    Swept swept = store.sweep(ID, T0, true);

    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).containsExactlyInAnyOrder(FileKind.AUDIO, FileKind.TRANSCRIPT);
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void sweepAtStartupKeepsASiblingWithAFutureDeadline() {
    create(
        file("audio.m4a", FileState.AVAILABLE, null, null),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE, null));

    Swept swept = store.sweep(ID, T0, true);

    assertThat(swept.expired()).containsExactly(FileKind.AUDIO);
    assertThat(swept.removed()).isFalse();
    assertThat(jobDir.resolve("audio.m4a")).doesNotExist();
    assertThat(jobDir.resolve("transcript.txt")).exists();
  }

  @Test
  void sweepIsHarmlessWhenTheFileIsAlreadyGone() throws IOException {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    Files.delete(jobDir.resolve("audio.m4a"));

    Swept swept = store.sweep(ID, DEADLINE, false);

    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).hasSize(2);
  }

  @Test
  void sweepFailsAndStaysRetryableWhenAFileCannotBeDeleted() throws IOException {
    Assumptions.assumeTrue(!"root".equals(System.getProperty("user.name")));
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    JobRecord before = stored();
    Files.setPosixFilePermissions(jobDir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assertThatThrownBy(() -> store.sweep(ID, DEADLINE, false))
          .isInstanceOf(JobStoreException.class);
    } finally {
      Files.setPosixFilePermissions(jobDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    assertThat(stored()).isEqualTo(before);
    assertThat(store.sweep(ID, DEADLINE, false).removed()).isTrue();
  }

  @Test
  void sweepRefusesAnInvalidJobId() {
    assertThatThrownBy(() -> store.sweep("../x", DEADLINE, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- recover ---

  @Test
  void recoverRemovesTheDirectoryWithoutJobJson() throws IOException {
    Files.createDirectories(jobDir.resolve("chunks"));
    Files.writeString(jobDir.resolve("chunks/c.wav"), "w");

    Recovered recovered = store.recover(ID);

    assertThat(recovered).isEqualTo(new Recovered(Recovery.REMOVED, null));
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void recoverRemovesTheDirectoryWithUnusableJobJson() throws IOException {
    Files.writeString(jobDir.resolve("job.json"), "null");

    assertThat(store.recover(ID).recovery()).isEqualTo(Recovery.REMOVED);
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void recoverResetsSendingFilesAndKeepsEverythingElse() {
    create(
        file("audio.m4a", FileState.SENDING, DEADLINE, null),
        file("transcript.txt", FileState.SENT, DEADLINE, SENT_AT));

    Recovered recovered = store.recover(ID);

    assertThat(recovered.recovery()).isEqualTo(Recovery.RESET);
    assertThat(recovered.job()).isEqualTo(stored());
    assertThat(stored().files().get(FileKind.AUDIO))
        .isEqualTo(file("audio.m4a", FileState.AVAILABLE, DEADLINE, null));
    assertThat(stored().files().get(FileKind.TRANSCRIPT))
        .isEqualTo(file("transcript.txt", FileState.SENT, DEADLINE, SENT_AT));
    assertThat(stored().summaryMessageId()).isEqualTo(700);
  }

  @Test
  void recoverResetsBothFilesWhenBothAreSending() {
    create(FileState.SENDING, FileState.SENDING, DEADLINE);

    assertThat(store.recover(ID).recovery()).isEqualTo(Recovery.RESET);
    assertThat(stored().files().values()).allMatch(f -> f.state() == FileState.AVAILABLE);
  }

  @Test
  void recoverDoesNotRecreateAMissingFile() throws IOException {
    create(FileState.SENDING, FileState.AVAILABLE, DEADLINE);
    Files.delete(jobDir.resolve("audio.m4a"));

    store.recover(ID);

    assertThat(jobDir.resolve("audio.m4a")).doesNotExist();
  }

  @Test
  void recoverLeavesAJobWithoutSendingFilesAsItIs() {
    create(FileState.AVAILABLE, FileState.SENT, DEADLINE);
    JobRecord before = stored();

    assertThat(store.recover(ID)).isEqualTo(new Recovered(Recovery.UNCHANGED, null));
    assertThat(stored()).isEqualTo(before);
  }

  // --- discard order ---

  @Test
  void discardKeepsJobJsonWhenAnotherFileCannotBeDeleted() throws IOException {
    Assumptions.assumeTrue(!"root".equals(System.getProperty("user.name")));
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    Path chunks = Files.createDirectories(jobDir.resolve("chunks"));
    Files.writeString(chunks.resolve("c.wav"), "w");
    Files.setPosixFilePermissions(chunks, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assertThatThrownBy(() -> store.discard(ID)).isInstanceOf(JobStoreException.class);
      assertThat(jobDir.resolve("job.json")).exists();
    } finally {
      Files.setPosixFilePermissions(chunks, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    store.discard(ID);
    assertThat(jobDir).doesNotExist();
  }

  // --- claim versus sweep ---

  @Test
  void aClaimThatWinsKeepsTheWholeDirectoryEvenAfterTheDeadline() {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    DeliveryService delivery = new DeliveryService(store, clock);
    clock.advance(Duration.ofMinutes(59));

    DeliveryService.Claim claim = delivery.claim(ID, 42L, FileKind.AUDIO);
    clock.advance(Duration.ofMinutes(2));
    Swept swept = store.sweep(ID, clock.instant(), false);

    assertThat(claim.outcome()).isEqualTo(DeliveryService.Outcome.GRANTED);
    assertThat(swept).isEqualTo(Swept.none());
    assertThat(jobDir.resolve("audio.m4a")).hasContent("audio");
    assertThat(jobDir.resolve("transcript.txt")).hasContent("text");
    assertThat(stored().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENDING);
    assertThat(stored().files().get(FileKind.TRANSCRIPT).state()).isEqualTo(FileState.AVAILABLE);

    assertThat(delivery.complete(ID, FileKind.AUDIO)).isPresent();
    Swept after = store.sweep(ID, clock.instant(), false);
    assertThat(after.removed()).isTrue();
    assertThat(after.expired()).containsExactly(FileKind.TRANSCRIPT);
    assertThat(after.job().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENT);
  }

  @Test
  void aReleasedSendAfterTheDeadlineIsExpiredOnTheNextPass() {
    create(FileState.AVAILABLE, FileState.SENT, DEADLINE);
    DeliveryService delivery = new DeliveryService(store, clock);
    DeliveryService.Claim claim = delivery.claim(ID, 42L, FileKind.AUDIO);
    clock.advance(Duration.ofHours(2));

    assertThat(store.sweep(ID, clock.instant(), false)).isEqualTo(Swept.none());
    delivery.release(ID, FileKind.AUDIO);
    Swept swept = store.sweep(ID, clock.instant(), false);

    assertThat(claim.outcome()).isEqualTo(DeliveryService.Outcome.GRANTED);
    assertThat(swept.removed()).isTrue();
    assertThat(swept.expired()).containsExactly(FileKind.AUDIO);
  }

  @Test
  void aSweepThatWinsLeavesTheLaterClaimWithoutAGrant() {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    DeliveryService delivery = new DeliveryService(store, clock);
    clock.advance(Duration.ofHours(1));

    Swept swept = store.sweep(ID, clock.instant(), false);
    DeliveryService.Claim audio = delivery.claim(ID, 42L, FileKind.AUDIO);
    DeliveryService.Claim transcript = delivery.claim(ID, 42L, FileKind.TRANSCRIPT);

    assertThat(swept.removed()).isTrue();
    assertThat(audio.outcome()).isEqualTo(DeliveryService.Outcome.EXPIRED);
    assertThat(audio.file()).isNull();
    assertThat(transcript.outcome()).isEqualTo(DeliveryService.Outcome.EXPIRED);
    assertThat(jobDir).doesNotExist();
    assertThat(store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(JobStore.Transition.NOT_FOUND);
  }

  @Test
  void aSweepThatExpiresOneFileRefusesATransitionOnThatFileOnly() {
    create(
        file("audio.m4a", FileState.AVAILABLE, DEADLINE, null),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE.plusSeconds(60), null));

    store.sweep(ID, DEADLINE, false);

    assertThat(store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(JobStore.Transition.STATE_MISMATCH);
    assertThat(store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(JobStore.Transition.APPLIED);
    assertThat(jobDir.resolve("transcript.txt")).hasContent("text");
  }

  // --- claim versus sweep, concurrently ---

  /** A clock whose first reading waits for a release, to hold a claim between its checks. */
  private static final class GatedClock extends Clock {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final Instant reading;
    private boolean armed = true;

    GatedClock(Instant reading) {
      this.reading = reading;
    }

    @Override
    public synchronized Instant instant() {
      if (armed) {
        armed = false;
        entered.countDown();
        await(release);
      }
      return reading;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static Thread start(Runnable action) {
    Thread thread = new Thread(action);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Waits until the thread is parked on a job lock inside the store. */
  private static void awaitQueuedOnJobLock(Thread thread) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      boolean inStore =
          Arrays.stream(thread.getStackTrace())
              .anyMatch(frame ->
                  frame.getClassName().equals(JobStore.class.getName())
                      && frame.getMethodName().equals("withLock"));
      if (thread.getState() == Thread.State.WAITING && inStore) {
        return;
      }
      Thread.sleep(5);
    }
    throw new IllegalStateException("thread never queued on the job lock");
  }

  /** Holds the job lock until released. Returns once the lock is held. */
  private Thread holdLock(CountDownLatch release) {
    CountDownLatch held = new CountDownLatch(1);
    Thread holder =
        start(() ->
            store.update(
                ID,
                job -> {
                  held.countDown();
                  await(release);
                  return job;
                }));
    await(held);
    return holder;
  }

  @Test
  void aClaimPastItsChecksLosesToASweepThatTakesTheLockFirst() throws Exception {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    GatedClock claimClock = new GatedClock(DEADLINE.minusSeconds(1));
    DeliveryService delivery = new DeliveryService(store, claimClock);
    AtomicReference<DeliveryService.Claim> claim = new AtomicReference<>();
    AtomicReference<Swept> swept = new AtomicReference<>();
    CountDownLatch releaseHolder = new CountDownLatch(1);

    Thread claimer = start(() -> claim.set(delivery.claim(ID, 42L, FileKind.AUDIO)));
    await(claimClock.entered);
    Thread holder = holdLock(releaseHolder);
    Thread sweeper = start(() -> swept.set(store.sweep(ID, DEADLINE.plusSeconds(1), false)));
    awaitQueuedOnJobLock(sweeper);
    claimClock.release.countDown();
    awaitQueuedOnJobLock(claimer);
    assertThat(swept.get()).isNull();

    releaseHolder.countDown();
    for (Thread thread : List.of(holder, sweeper, claimer)) {
      thread.join(10_000);
    }

    assertThat(swept.get().removed()).isTrue();
    assertThat(claim.get().outcome()).isEqualTo(DeliveryService.Outcome.EXPIRED);
    assertThat(claim.get().file()).isNull();
    assertThat(jobDir).doesNotExist();
  }

  @Test
  void aClaimThatTakesTheLockFirstProtectsTheWholeDirectoryFromASweepUntilItIsComplete()
      throws Exception {
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    GatedClock claimClock = new GatedClock(DEADLINE.minusSeconds(1));
    DeliveryService delivery = new DeliveryService(store, claimClock);
    AtomicReference<DeliveryService.Claim> claim = new AtomicReference<>();
    AtomicReference<Swept> swept = new AtomicReference<>();
    CountDownLatch releaseHolder = new CountDownLatch(1);

    Thread claimer = start(() -> claim.set(delivery.claim(ID, 42L, FileKind.AUDIO)));
    await(claimClock.entered);
    Thread holder = holdLock(releaseHolder);
    claimClock.release.countDown();
    awaitQueuedOnJobLock(claimer);
    Thread sweeper = start(() -> swept.set(store.sweep(ID, DEADLINE.plusSeconds(1), false)));
    awaitQueuedOnJobLock(sweeper);

    releaseHolder.countDown();
    for (Thread thread : List.of(holder, claimer, sweeper)) {
      thread.join(10_000);
    }

    assertThat(claim.get().outcome()).isEqualTo(DeliveryService.Outcome.GRANTED);
    assertThat(swept.get()).isEqualTo(Swept.none());
    assertThat(jobDir.resolve("audio.m4a")).hasContent("audio");
    assertThat(jobDir.resolve("transcript.txt")).hasContent("text");
    assertThat(stored().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENDING);
    assertThat(stored().files().get(FileKind.TRANSCRIPT).state()).isEqualTo(FileState.AVAILABLE);

    assertThat(store.sweep(ID, DEADLINE.plusSeconds(1), false)).isEqualTo(Swept.none());
    assertThat(delivery.complete(ID, FileKind.AUDIO)).isPresent();
    Swept after = store.sweep(ID, DEADLINE.plusSeconds(1), false);
    assertThat(after.removed()).isTrue();
    assertThat(after.expired()).containsExactly(FileKind.TRANSCRIPT);
    assertThat(after.job().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENT);
  }

  // --- symlinks ---

  private Path externalJob() throws IOException {
    Path outside = Files.createDirectories(dir.resolve("outside"));
    Files.writeString(outside.resolve("job.json"), "{ not json");
    Files.writeString(outside.resolve("sentinel.txt"), "keep me");
    return outside;
  }

  @Test
  void jobIdsSkipsASymlinkedJobDirectory() throws IOException {
    Path outside = externalJob();
    Files.createSymbolicLink(store.directory("linked"), outside);

    assertThat(store.jobIds()).containsExactly(ID);
  }

  @Test
  void recoverRemovesASymlinkedJobRootWithoutTouchingItsTarget() throws IOException {
    Path outside = externalJob();
    Path link = Files.createSymbolicLink(store.directory("linked"), outside);

    assertThat(store.recover("linked").recovery()).isEqualTo(Recovery.REMOVED);

    assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(outside.resolve("job.json")).hasContent("{ not json");
    assertThat(outside.resolve("sentinel.txt")).hasContent("keep me");
  }

  @Test
  void sweepIgnoresASymlinkedJobRootWithAUsableExternalRecord() throws IOException {
    Path outside = Files.createDirectories(dir.resolve("outside"));
    Files.writeString(outside.resolve("audio.m4a"), "external media");
    new JobStore(dir, clock)
        .create(
            new JobRecord(
                "outside",
                1L,
                2L,
                3,
                "T",
                1,
                T0,
                Map.of(FileKind.AUDIO, file("audio.m4a", FileState.AVAILABLE, DEADLINE, null))));
    Files.createSymbolicLink(store.directory("outside"), outside);

    assertThat(store.sweep("outside", DEADLINE.plusSeconds(1), false)).isEqualTo(Swept.none());

    assertThat(outside.resolve("audio.m4a")).hasContent("external media");
    assertThat(outside.resolve("job.json")).exists();
  }

  @Test
  void discardRemovesASymlinkedJobRootWithoutTouchingItsTarget() throws IOException {
    Path outside = externalJob();
    Path link = Files.createSymbolicLink(store.directory("linked"), outside);

    store.discard("linked");

    assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
    assertThat(outside.resolve("job.json")).exists();
    assertThat(outside.resolve("sentinel.txt")).hasContent("keep me");
  }

  @Test
  void aNestedSymlinkIsUnlinkedNotFollowed() throws IOException {
    Path outside = externalJob();
    create(FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    Files.createSymbolicLink(jobDir.resolve("chunks"), outside);

    assertThat(store.sweep(ID, DEADLINE, false).removed()).isTrue();

    assertThat(jobDir).doesNotExist();
    assertThat(outside.resolve("sentinel.txt")).hasContent("keep me");
    assertThat(outside.resolve("job.json")).exists();
  }

  // --- read and persist failures ---

  @Test
  void aReadFailureIsReportedAndNothingIsDeleted() throws IOException {
    Files.createDirectories(jobDir.resolve("job.json"));

    assertThatThrownBy(() -> store.recover(ID)).isInstanceOf(JobStoreException.class);
    assertThatThrownBy(() -> store.sweep(ID, DEADLINE, true)).isInstanceOf(JobStoreException.class);

    assertThat(jobDir.resolve("audio.m4a")).exists();
    assertThat(jobDir.resolve("transcript.txt")).exists();
  }

  @Test
  void aSweepThatCannotSaveStaysRetryable() throws IOException {
    create(
        file("audio.m4a", FileState.AVAILABLE, DEADLINE, null),
        file("transcript.txt", FileState.AVAILABLE, DEADLINE.plusSeconds(60), null));
    Path blocker = Files.createDirectories(jobDir.resolve("job.json.tmp"));

    assertThatThrownBy(() -> store.sweep(ID, DEADLINE, false))
        .isInstanceOf(JobStoreException.class);

    assertThat(jobDir.resolve("audio.m4a")).doesNotExist();
    assertThat(stored().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.AVAILABLE);

    Files.delete(blocker);
    Swept retry = store.sweep(ID, DEADLINE, false);
    assertThat(retry.expired()).containsExactly(FileKind.AUDIO);
    assertThat(stored().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.EXPIRED);
  }

  @Test
  void aRecoverThatCannotSaveKeepsTheSendingState() throws IOException {
    create(FileState.SENDING, FileState.AVAILABLE, DEADLINE);
    Path blocker = Files.createDirectories(jobDir.resolve("job.json.tmp"));

    assertThatThrownBy(() -> store.recover(ID)).isInstanceOf(JobStoreException.class);

    assertThat(stored().files().get(FileKind.AUDIO).state()).isEqualTo(FileState.SENDING);
    Files.delete(blocker);
    assertThat(store.recover(ID).recovery()).isEqualTo(Recovery.RESET);
  }
}
