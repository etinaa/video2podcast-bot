package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Scheduled;

class RetentionSweeperTest {

  private static final Instant T0 = Instant.parse("2026-11-21T12:00:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-11-21T13:00:00Z");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final List<JobRecord> refreshed = new ArrayList<>();
  private JobStore store;
  private RetentionSweeper sweeper;

  @BeforeEach
  void setUp() {
    store = new JobStore(dir.resolve("work"), clock);
    sweeper = new RetentionSweeper(store, refreshed::add, clock);
  }

  private Path publish(String id, FileState audio, FileState transcript, Instant expiresAt)
      throws IOException {
    return publish(id, audio, transcript, expiresAt, 700);
  }

  private Path publish(
      String id, FileState audio, FileState transcript, Instant expiresAt, Integer messageId)
      throws IOException {
    Path jobDir = store.directory(id);
    Files.createDirectories(jobDir);
    Files.writeString(jobDir.resolve("audio.m4a"), "a");
    Files.writeString(jobDir.resolve("transcript.txt"), "t");
    store.create(
        new JobRecord(
            id,
            42L,
            4242L,
            messageId,
            "Title",
            60,
            T0,
            Map.of(
                FileKind.AUDIO, new JobFile("audio.m4a", audio, expiresAt, null),
                FileKind.TRANSCRIPT, new JobFile("transcript.txt", transcript, expiresAt, null))));
    return jobDir;
  }

  // --- sweep ---

  @Test
  void sweepExpiresDueFilesAndRefreshesTheButtonsOnce() throws IOException {
    Path jobDir = publish("a", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    clock.advance(Duration.ofHours(1));

    sweeper.sweep();

    assertThat(jobDir).doesNotExist();
    assertThat(refreshed).hasSize(1);
    assertThat(refreshed.get(0).jobId()).isEqualTo("a");
    assertThat(refreshed.get(0).files().values()).allMatch(f -> f.state() == FileState.EXPIRED);
  }

  @Test
  void sweepDoesNothingBeforeTheDeadline() throws IOException {
    Path jobDir = publish("a", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    clock.advance(Duration.ofMinutes(59));

    sweeper.sweep();

    assertThat(jobDir.resolve("job.json")).exists();
    assertThat(refreshed).isEmpty();
  }

  @Test
  void sweepDoesNotRefreshWhenNothingExpiredNow() throws IOException {
    Path jobDir = publish("a", FileState.SENT, FileState.SENT, DEADLINE);

    sweeper.sweep();

    assertThat(jobDir).doesNotExist();
    assertThat(refreshed).isEmpty();
  }

  @Test
  void sweepLeavesJobsInProcessingAlone() throws IOException {
    Path live = store.directory("live");
    Files.createDirectories(live);
    Files.writeString(live.resolve("source.m4a"), "partial");
    clock.advance(Duration.ofHours(5));

    sweeper.sweep();

    assertThat(live.resolve("source.m4a")).hasContent("partial");
    assertThat(refreshed).isEmpty();
  }

  @Test
  void sweepIgnoresDirectoriesThatAreNotJobs() throws IOException {
    Path other = dir.resolve("work/not.a.job");
    Files.createDirectories(other);
    Files.writeString(other.resolve("keep.txt"), "keep me");

    sweeper.sweep();
    sweeper.recoverAtStartup();

    assertThat(other.resolve("keep.txt")).hasContent("keep me");
  }

  @Test
  void sweepWithoutWorkDirectoryIsHarmless() {
    sweeper.sweep();
    sweeper.recoverAtStartup();

    assertThat(refreshed).isEmpty();
    assertThat(dir.resolve("work")).doesNotExist();
  }

  @Test
  void sweepContinuesWithTheNextJobAfterAFailure() {
    JobStore failing = mock(JobStore.class);
    JobRecord record = new JobRecord("b", 1L, 2L, 3, "T", 1, T0, Map.of());
    when(failing.jobIds()).thenReturn(List.of("a", "b", "c"));
    when(failing.sweep(eq("a"), any(), anyBoolean())).thenThrow(new JobStoreException("io", null));
    when(failing.sweep(eq("b"), any(), anyBoolean()))
        .thenReturn(new JobStore.Swept(record, List.of(FileKind.AUDIO), false));
    when(failing.sweep(eq("c"), any(), anyBoolean()))
        .thenThrow(new IllegalArgumentException("bad id"));

    new RetentionSweeper(failing, refreshed::add, clock).sweep();

    assertThat(refreshed).containsExactly(record);
  }

  @Test
  void sweepSurvivesAFailureToListTheJobs() {
    JobStore failing = mock(JobStore.class);
    when(failing.jobIds()).thenThrow(new JobStoreException("io", null));

    new RetentionSweeper(failing, refreshed::add, clock).sweep();
    new RetentionSweeper(failing, refreshed::add, clock).recoverAtStartup();

    assertThat(refreshed).isEmpty();
  }

  @Test
  void sweepUsesTheConfiguredIntervalForBothDelays() throws NoSuchMethodException {
    Scheduled scheduled = RetentionSweeper.class.getMethod("sweep").getAnnotation(Scheduled.class);

    assertThat(scheduled.fixedDelayString()).isEqualTo("${app.jobs.sweep-interval}");
    assertThat(scheduled.initialDelayString()).isEqualTo("${app.jobs.sweep-interval}");
  }

  // --- startup ---

  @Test
  void startupRemovesDirectoriesWithoutUsableJobJson() throws IOException {
    Path orphan = store.directory("orphan");
    Files.createDirectories(orphan);
    Files.writeString(orphan.resolve("source.m4a"), "partial");
    Path corrupt = store.directory("corrupt");
    Files.createDirectories(corrupt);
    Files.writeString(corrupt.resolve("job.json"), "{");

    sweeper.recoverAtStartup();

    assertThat(orphan).doesNotExist();
    assertThat(corrupt).doesNotExist();
    assertThat(refreshed).isEmpty();
  }

  @Test
  void startupResetsAnInterruptedSendAndRefreshesTheButtons() throws IOException {
    Path jobDir = publish("a", FileState.SENDING, FileState.AVAILABLE, DEADLINE);

    sweeper.recoverAtStartup();

    assertThat(jobDir.resolve("audio.m4a")).exists();
    assertThat(store.load("a").orElseThrow().files().get(FileKind.AUDIO).state())
        .isEqualTo(FileState.AVAILABLE);
    assertThat(refreshed).hasSize(1);
    assertThat(refreshed.get(0).files().get(FileKind.AUDIO).state()).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void startupDoesNotRefreshAnUntouchedJob() throws IOException {
    publish("a", FileState.AVAILABLE, FileState.SENT, DEADLINE);

    sweeper.recoverAtStartup();

    assertThat(refreshed).isEmpty();
  }

  @Test
  void startupDoesNotRenewTheDeadline() throws IOException {
    publish("a", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    clock.advance(Duration.ofMinutes(10));

    sweeper.recoverAtStartup();

    assertThat(store.load("a").orElseThrow().files().get(FileKind.AUDIO).expiresAt())
        .isEqualTo(DEADLINE);
  }

  @Test
  void startupRefreshesAResetJobOnlyOnceWhenItAlsoExpires() throws IOException {
    Path jobDir = publish("a", FileState.SENDING, FileState.SENDING, DEADLINE);
    clock.advance(Duration.ofHours(1));

    sweeper.recoverAtStartup();

    assertThat(jobDir).doesNotExist();
    assertThat(refreshed).hasSize(1);
    assertThat(refreshed.get(0).files().values()).allMatch(f -> f.state() == FileState.EXPIRED);
  }

  @Test
  void startupDoesNotRefreshAResetJobWhoseDirectoryIsGone() {
    JobStore mocked = mock(JobStore.class);
    JobRecord record = new JobRecord("a", 1L, 2L, 3, "T", 1, T0, Map.of());
    when(mocked.jobIds()).thenReturn(List.of("a"));
    when(mocked.recover("a")).thenReturn(new JobStore.Recovered(JobStore.Recovery.RESET, record));
    when(mocked.sweep(eq("a"), any(), eq(true)))
        .thenReturn(new JobStore.Swept(record, List.of(), true));

    new RetentionSweeper(mocked, refreshed::add, clock).recoverAtStartup();

    assertThat(refreshed).isEmpty();
  }

  @Test
  void startupFallsBackToTheRecoveredJobWhenTheSweepReturnsNone() {
    JobStore mocked = mock(JobStore.class);
    JobRecord record = new JobRecord("a", 1L, 2L, 3, "T", 1, T0, Map.of());
    when(mocked.jobIds()).thenReturn(List.of("a"));
    when(mocked.recover("a")).thenReturn(new JobStore.Recovered(JobStore.Recovery.RESET, record));
    when(mocked.sweep(eq("a"), any(), eq(true))).thenReturn(JobStore.Swept.none());

    new RetentionSweeper(mocked, refreshed::add, clock).recoverAtStartup();

    assertThat(refreshed).containsExactly(record);
  }

  @Test
  void startupContinuesAfterAFailingJob() {
    JobStore mocked = mock(JobStore.class);
    JobRecord record = new JobRecord("b", 1L, 2L, 3, "T", 1, T0, Map.of());
    when(mocked.jobIds()).thenReturn(List.of("a", "b", "c"));
    when(mocked.recover("a")).thenThrow(new JobStoreException("io", null));
    when(mocked.recover("b")).thenReturn(new JobStore.Recovered(JobStore.Recovery.UNCHANGED, null));
    when(mocked.sweep(eq("b"), any(), eq(true)))
        .thenReturn(new JobStore.Swept(record, List.of(FileKind.AUDIO), true));
    when(mocked.recover("c")).thenThrow(new IllegalArgumentException("bad"));

    new RetentionSweeper(mocked, refreshed::add, clock).recoverAtStartup();

    assertThat(refreshed).containsExactly(record);
  }

  @Test
  void aRuntimeFailureOfOneJobDoesNotStopTheOthers() {
    JobStore mocked = mock(JobStore.class);
    JobRecord record = new JobRecord("b", 1L, 2L, 3, "T", 1, T0, Map.of());
    when(mocked.jobIds()).thenReturn(List.of("a", "b"));
    when(mocked.recover("a")).thenThrow(new IllegalStateException("boom"));
    when(mocked.recover("b")).thenReturn(new JobStore.Recovered(JobStore.Recovery.UNCHANGED, null));
    when(mocked.sweep(eq("a"), any(), anyBoolean())).thenThrow(new IllegalStateException("boom"));
    when(mocked.sweep(eq("b"), any(), anyBoolean()))
        .thenReturn(new JobStore.Swept(record, List.of(FileKind.AUDIO), false));

    new RetentionSweeper(mocked, refreshed::add, clock).sweep();
    new RetentionSweeper(mocked, refreshed::add, clock).recoverAtStartup();

    assertThat(refreshed).containsExactly(record, record);
  }

  @Test
  void aFailingRefresherDoesNotStopThePassOrStartup() throws IOException {
    publish("a", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    Path second = publish("b", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    clock.advance(Duration.ofHours(1));
    ButtonRefresher failing =
        job -> {
          throw new IllegalStateException("boom");
        };

    new RetentionSweeper(store, failing, clock).sweep();

    assertThat(second).doesNotExist();
    assertThat(store.directory("a")).doesNotExist();

    publish("c", FileState.AVAILABLE, FileState.AVAILABLE, DEADLINE);
    new RetentionSweeper(store, failing, clock).recoverAtStartup();
    assertThat(store.directory("c")).doesNotExist();
  }
}
