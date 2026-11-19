package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.job.JobStore.Transition;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobStoreTest {

  private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
  private static final String ID = "job-1";

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private JobStore store;

  @BeforeEach
  void setUp() {
    store = new JobStore(dir.resolve("work"), clock);
  }

  private static JobRecord job(String id) {
    return new JobRecord(
        id,
        42L,
        4242L,
        null,
        "Title <b> & more",
        3725,
        T0,
        Map.of(
            FileKind.AUDIO, JobFile.available("audio.m4a"),
            FileKind.TRANSCRIPT, JobFile.available("transcript.txt")));
  }

  // --- persistence ---

  @Test
  void aCreatedJobRoundTripsWithEveryField() {
    JobRecord original =
        job(ID)
            .published(77, T0.plus(Duration.ofHours(1)))
            .withFile(
                FileKind.TRANSCRIPT,
                new JobFile(
                    "transcript.txt", FileState.SENT, T0.plusSeconds(5), T0.plusSeconds(3)));

    store.create(original);

    assertThat(new JobStore(dir.resolve("work"), clock).load(ID)).contains(original);
  }

  @Test
  void aJobWithoutSummaryMessageOrSendTimeRoundTrips() {
    JobRecord original = job(ID);

    store.create(original);

    JobRecord loaded = store.load(ID).orElseThrow();
    assertThat(loaded.summaryMessageId()).isNull();
    assertThat(loaded.files().get(FileKind.AUDIO).sentAt()).isNull();
    assertThat(loaded).isEqualTo(original);
  }

  @Test
  void createdJobsLiveInTheirOwnDirectoryNextToTheirFiles() throws IOException {
    store.create(job(ID));
    Files.writeString(store.directory(ID).resolve("audio.m4a"), "bytes");

    assertThat(store.directory(ID).resolve("job.json")).isRegularFile();
    assertThat(store.resolve(ID, store.load(ID).orElseThrow().files().get(FileKind.AUDIO)))
        .hasContent("bytes");
  }

  @Test
  void missingJobsAreEmpty() {
    assertThat(store.load("nope")).isEmpty();
    assertThat(store.update("nope", j -> j)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{ not json",
        "null",
        "[]",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\"} trailing",
        "{\"jobId\":\"other\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\"}",
        "{\"jobId\":\"job-1\",\"createdAt\":\"2026-11-15T10:00:00Z\"}",
        "{\"jobId\":\"job-1\",\"title\":\"t\"}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"state\":\"AVAILABLE\"}}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"path\":\"a.m4a\"}}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"path\":\"../../etc/passwd\",\"state\":\"AVAILABLE\"}}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":null}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"path\":\"..\",\"state\":\"AVAILABLE\"}}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"path\":\"\",\"state\":\"AVAILABLE\"}}}",
        "{\"jobId\":\"job-1\",\"title\":\"t\",\"createdAt\":\"2026-11-15T10:00:00Z\",\"files\":{\"AUDIO\":{\"path\":\"a\\\\b\",\"state\":\"AVAILABLE\"}}}"
      })
  void anUnusableJobJsonIsTreatedAsMissing(String content) throws IOException {
    Files.createDirectories(store.directory(ID));
    Files.writeString(store.directory(ID).resolve("job.json"), content);

    assertThat(store.load(ID)).isEmpty();
  }

  @Test
  void anUnreadableJobJsonFailsLoudly() throws IOException {
    Files.createDirectories(store.directory(ID).resolve("job.json"));

    assertThatThrownBy(() -> store.load(ID)).isInstanceOf(JobStoreException.class);
  }

  @Test
  void aJobThatCannotBeWrittenFailsLoudly() throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("work"), "a file where the work directory should be");

    assertThatThrownBy(() -> store.create(job(ID))).isInstanceOf(JobStoreException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../x",
        "a/b",
        "..",
        ".",
        "",
        "a b",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "a\\b"
      })
  void jobIdsThatCouldEscapeTheWorkDirectoryAreRefused(String id) {
    assertThatThrownBy(() -> store.directory(id)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.load(id)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.discard(id)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullJobIdIsRefused() {
    assertThatThrownBy(() -> store.directory(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatedJobIdsAreValidAndFitTheCallbackLimit() {
    String id = JobStore.newJobId();

    assertThat(store.directory(id)).isNotNull();
    assertThat(("transcript:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(64);
    assertThat(JobStore.newJobId()).isNotEqualTo(id);
  }

  // --- update ---

  @Test
  void updateChangesTheStoredJobAndReturnsIt() {
    store.create(job(ID));

    JobRecord changed = store.update(ID, j -> j.published(9, T0.plusSeconds(3600))).orElseThrow();

    assertThat(changed.summaryMessageId()).isEqualTo(9);
    assertThat(store.load(ID)).contains(changed);
    assertThat(changed.files().values())
        .allSatisfy(file -> assertThat(file.expiresAt()).isEqualTo(T0.plusSeconds(3600)));
  }

  @Test
  void anUpdateMustKeepTheJobId() {
    store.create(job(ID));

    assertThatThrownBy(
            () -> store.update(ID, j -> new JobRecord("other", 1, 1, null, "t", 1, T0, Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.load(ID)).contains(job(ID));
  }

  @Test
  void anUpdateCannotBringASentFileBack() {
    store.create(job(ID));
    store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);
    store.transition(ID, FileKind.AUDIO, FileState.SENDING, FileState.SENT);

    assertThatThrownBy(
            () -> store.update(ID, j -> j.withFile(FileKind.AUDIO, JobFile.available("audio.m4a"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.load(ID).orElseThrow().files().get(FileKind.AUDIO).state())
        .isEqualTo(FileState.SENT);
  }

  @Test
  void anUpdateCannotDropAFile() {
    store.create(job(ID));

    assertThatThrownBy(
            () -> store.update(ID, j -> new JobRecord(ID, 42L, 4242L, null, "t", 1, T0, Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.load(ID)).contains(job(ID));
  }

  @Test
  void creatingAJobThatAlreadyExistsIsRefusedAndKeepsTheStoredOne() {
    store.create(job(ID));
    store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);
    JobRecord stored = store.load(ID).orElseThrow();

    assertThatThrownBy(() -> store.create(job(ID))).isInstanceOf(IllegalStateException.class);
    assertThat(store.load(ID)).contains(stored);
  }

  @Test
  void deletingAndRecreatingAJobWhileOthersUpdateItNeverCorruptsIt() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        runs.add(
            pool.submit(() -> {
              for (int n = 0; n < 200; n++) {
                store.discard(ID);
                try {
                  store.create(job(ID));
                } catch (IllegalStateException alreadyRecreated) {
                  // another thread was faster; the job is there, which is all this test needs
                }
                store.update(ID, j -> j);
              }
            }));
      }
      for (Future<?> run : runs) {
        run.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(store.load(ID)).contains(job(ID));
  }

  // --- transitions ---

  @Test
  void aFileMovesThroughSendingToSentAndRecordsWhenItWasSent() {
    store.create(job(ID));

    assertThat(store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(Transition.APPLIED);
    clock.advance(Duration.ofSeconds(30));
    assertThat(store.transition(ID, FileKind.TRANSCRIPT, FileState.SENDING, FileState.SENT))
        .isEqualTo(Transition.APPLIED);

    JobFile transcript = store.load(ID).orElseThrow().files().get(FileKind.TRANSCRIPT);
    assertThat(transcript.state()).isEqualTo(FileState.SENT);
    assertThat(transcript.sentAt()).isEqualTo(T0.plusSeconds(30));
    assertThat(store.load(ID).orElseThrow().files().get(FileKind.AUDIO).state())
        .isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void aFailedSendGoesBackToAvailableWithoutASendTime() {
    store.create(job(ID));
    store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);

    assertThat(store.transition(ID, FileKind.AUDIO, FileState.SENDING, FileState.AVAILABLE))
        .isEqualTo(Transition.APPLIED);

    JobFile audio = store.load(ID).orElseThrow().files().get(FileKind.AUDIO);
    assertThat(audio.state()).isEqualTo(FileState.AVAILABLE);
    assertThat(audio.sentAt()).isNull();
  }

  @Test
  void aTransitionFromTheWrongCurrentStateChangesNothing() {
    store.create(job(ID));
    store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);

    assertThat(store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(Transition.STATE_MISMATCH);
    assertThat(store.load(ID).orElseThrow().files().get(FileKind.AUDIO).state())
        .isEqualTo(FileState.SENDING);
  }

  @Test
  void aTransitionTheStateMachineForbidsIsAProgrammingError() {
    store.create(job(ID));

    assertThatThrownBy(
            () -> store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AVAILABLE")
        .hasMessageContaining("SENT");
    assertThatThrownBy(
            () -> store.transition(ID, FileKind.AUDIO, FileState.SENT, FileState.AVAILABLE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transitionsOnMissingJobsOrFilesReportNotFound() {
    store.create(
        new JobRecord(ID, 1, 1, null, "t", 1, T0, Map.of(FileKind.AUDIO, JobFile.available("a"))));

    assertThat(store.transition("nope", FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(Transition.NOT_FOUND);
    assertThat(store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING))
        .isEqualTo(Transition.NOT_FOUND);
  }

  @Test
  void whenManyTapsRaceExactlyOneWins() throws Exception {
    store.create(job(ID));
    int taps = 24;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(taps);
    try {
      List<Future<Transition>> results = new ArrayList<>();
      for (int i = 0; i < taps; i++) {
        results.add(
            pool.submit(() -> {
              start.await();
              return store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);
            }));
      }
      start.countDown();

      List<Transition> outcomes = new ArrayList<>();
      for (Future<Transition> result : results) {
        outcomes.add(result.get());
      }

      assertThat(outcomes).filteredOn(t -> t == Transition.APPLIED).hasSize(1);
      assertThat(outcomes).filteredOn(t -> t == Transition.STATE_MISMATCH).hasSize(taps - 1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void changesToTheTwoFilesOfAJobNeverOverwriteEachOther() throws Exception {
    store.create(job(ID));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Transition> audio =
          pool.submit(
              () -> store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING));
      Future<Transition> transcript =
          pool.submit(() ->
              store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING));
      assertThat(audio.get()).isEqualTo(Transition.APPLIED);
      assertThat(transcript.get()).isEqualTo(Transition.APPLIED);
    } catch (ExecutionException e) {
      throw new AssertionError(e);
    } finally {
      pool.shutdownNow();
    }

    Map<FileKind, JobFile> files = store.load(ID).orElseThrow().files();
    assertThat(files.values()).allSatisfy(f -> assertThat(f.state()).isEqualTo(FileState.SENDING));
  }

  @Test
  void deleteFailsLoudlyWhenAFileCannotBeRemoved() throws IOException {
    store.create(job(ID));
    Path jobDir = store.directory(ID);
    Files.setPosixFilePermissions(jobDir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      Assumptions.assumeFalse(
          jobDir.toFile().canWrite(), "running as a user that ignores permissions");

      assertThatThrownBy(() -> store.discard(ID)).isInstanceOf(JobStoreException.class);
    } finally {
      Files.setPosixFilePermissions(jobDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  // --- listing and deleting ---

  @Test
  void jobIdsListsOnlyJobDirectories() throws IOException {
    assertThat(store.jobIds()).isEmpty();
    store.create(job("a"));
    store.create(job("b"));
    Files.writeString(dir.resolve("work/stray-file"), "x");
    Files.createDirectories(dir.resolve("work/not a job id"));

    assertThat(store.jobIds()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void deleteRemovesTheWholeDirectoryAndIsRepeatable() throws IOException {
    store.create(job(ID));
    Files.writeString(store.directory(ID).resolve("audio.m4a"), "bytes");
    Files.createDirectories(store.directory(ID).resolve("chunks"));
    Files.writeString(store.directory(ID).resolve("chunks/1.wav"), "wav");

    store.discard(ID);
    store.discard(ID);

    assertThat(store.directory(ID)).doesNotExist();
    assertThat(store.load(ID)).isEmpty();
  }
}
