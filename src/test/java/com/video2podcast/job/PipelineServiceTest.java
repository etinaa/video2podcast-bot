package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.video2podcast.bot.TelegramGatewayException;
import com.video2podcast.config.AppProperties;
import com.video2podcast.media.FfmpegRunner;
import com.video2podcast.media.VideoMetadata;
import com.video2podcast.media.YtDlpClient;
import com.video2podcast.summary.SummaryService;
import com.video2podcast.support.MutableClock;
import com.video2podcast.transcript.TranscriptionClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineServiceTest {

  private static final String URL = "https://videos.example/v";
  private static final String JOB_ID = "job-1";
  private static final Instant CREATED = Instant.parse("2026-11-19T10:00:00Z");
  private static final Duration RETENTION = Duration.ofMinutes(45);
  private static final AppProperties.Jobs JOBS =
      new AppProperties.Jobs(
          Path.of("w"),
          Duration.ofHours(3),
          RETENTION,
          Duration.ofMinutes(1),
          Duration.ofMinutes(30),
          Duration.ofMinutes(20),
          Duration.ofMinutes(1));
  private static final JobRequest REQUEST = new JobRequest(JOB_ID, 7, 555, URL, CREATED);

  @TempDir Path work;

  private final YtDlpClient ytDlp = mock(YtDlpClient.class);
  private final FfmpegRunner ffmpeg = mock(FfmpegRunner.class);
  private final TranscriptionClient transcription = mock(TranscriptionClient.class);
  private final SummaryService summary = mock(SummaryService.class);
  private final HealthChecks health = mock(HealthChecks.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:05:00Z"));
  private final List<String> events = new ArrayList<>();
  private final FakePublisher publisher = new FakePublisher();
  private JobStore store;
  private PipelineService pipeline;
  private Path dir;

  /** Records the stage reports and the order of the calls. */
  private final JobProgress progress =
      new JobProgress() {
        @Override
        public void downloading() {
          events.add("downloading");
        }

        @Override
        public void transcribing(int n, int total) {
          events.add("transcribing " + n + "/" + total);
        }

        @Override
        public void summarizing() {
          events.add("summarizing");
        }
      };

  private final class FakePublisher implements SummaryPublisher {
    int sent;
    JobRecord published;
    String text;
    RuntimeException failure;
    final List<Integer> withdrawn = new ArrayList<>();

    @Override
    public int publish(JobRecord job, String summaryText) {
      events.add("publish");
      if (failure != null) {
        throw failure;
      }
      published = job;
      text = summaryText;
      return 900 + ++sent;
    }

    @Override
    public void withdraw(JobRecord job, int messageId, String summaryText) {
      events.add("withdraw");
      withdrawn.add(messageId);
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    store = new JobStore(work, clock);
    pipeline =
        new PipelineService(
            ytDlp, ffmpeg, transcription, summary, store, publisher, clock, JOBS, health);
    dir = store.directory(JOB_ID);
    Path source = dir.resolve("source.m4a");
    Path wav = dir.resolve("audio.wav");
    List<Path> chunks =
        List.of(dir.resolve("chunks/chunk-000.wav"), dir.resolve("chunks/chunk-001.wav"));
    when(ytDlp.fetchMetadata(URL)).thenReturn(new VideoMetadata("A talk", Duration.ofSeconds(300)));
    when(ytDlp.downloadAudio(URL, dir))
        .thenAnswer(call -> {
          events.add("download");
          Files.writeString(source, "audio");
          return source;
        });
    when(ffmpeg.toWav(source, dir)).thenReturn(wav);
    when(ffmpeg.split(wav, dir)).thenReturn(chunks);
    when(transcription.transcribe(eq(dir), eq(chunks), any()))
        .thenAnswer(call -> {
          TranscriptionClient.ProgressListener listener = call.getArgument(2);
          listener.accept(1, 2);
          listener.accept(2, 2);
          Path transcript = dir.resolve("transcript.txt");
          Files.writeString(transcript, "héllo transcript", StandardCharsets.UTF_8);
          return transcript;
        });
    when(summary.summarize("héllo transcript")).thenReturn("• point");
  }

  @Test
  void runsTheStagesInOrderAndPublishesTheSummary() {
    pipeline.run(REQUEST, progress);

    assertThat(events)
        .containsExactly(
            "downloading",
            "download",
            "transcribing 1/2",
            "transcribing 2/2",
            "summarizing",
            "publish");
    assertThat(publisher.text).isEqualTo("• point");
  }

  @Test
  void storesTheRecordWithRetentionStartingAtTheSendTime() {
    pipeline.run(REQUEST, progress);

    JobRecord stored = store.load(JOB_ID).orElseThrow();
    Instant expiresAt = clock.instant().plus(RETENTION);
    assertThat(stored.jobId()).isEqualTo(JOB_ID);
    assertThat(stored.ownerUserId()).isEqualTo(7);
    assertThat(stored.chatId()).isEqualTo(555);
    assertThat(stored.title()).isEqualTo("A talk");
    assertThat(stored.durationSeconds()).isEqualTo(300);
    assertThat(stored.createdAt()).isEqualTo(CREATED);
    assertThat(stored.summaryMessageId()).isEqualTo(901);
    assertThat(stored.files())
        .containsOnly(
            org.assertj.core.api.Assertions.entry(
                FileKind.AUDIO, new JobFile("source.m4a", FileState.AVAILABLE, expiresAt, null)),
            org.assertj.core.api.Assertions.entry(
                FileKind.TRANSCRIPT,
                new JobFile("transcript.txt", FileState.AVAILABLE, expiresAt, null)));
  }

  @Test
  void thePublisherSeesTheRecordBeforeRetentionStarts() {
    pipeline.run(REQUEST, progress);

    assertThat(publisher.published.summaryMessageId()).isNull();
    assertThat(publisher.published.files().values())
        .allSatisfy(file -> assertThat(file.expiresAt()).isNull());
  }

  @Test
  void aSlowSummaryDoesNotUseUpTheRetention() {
    when(summary.summarize(any()))
        .thenAnswer(call -> {
          clock.advance(Duration.ofHours(2));
          return "• late";
        });

    pipeline.run(REQUEST, progress);

    assertThat(store.load(JOB_ID).orElseThrow().files().get(FileKind.AUDIO).expiresAt())
        .isEqualTo(clock.instant().plus(RETENTION));
  }

  @Test
  void keepsTheSourceTranscriptAndJobFileOnSuccess() {
    pipeline.run(REQUEST, progress);

    assertThat(dir.resolve("source.m4a")).exists();
    assertThat(dir.resolve("transcript.txt")).exists();
    assertThat(dir.resolve("job.json")).exists();
  }

  @Test
  void usesTheNameOfTheDownloadedFileForTheAudioEntry() throws IOException {
    Path mp4 = dir.resolve("source.mp4");
    doAnswer(
            call -> {
              Files.writeString(mp4, "video");
              return mp4;
            })
        .when(ytDlp)
        .downloadAudio(URL, dir);
    when(ffmpeg.toWav(mp4, dir)).thenReturn(dir.resolve("audio.wav"));

    pipeline.run(REQUEST, progress);

    assertThat(store.load(JOB_ID).orElseThrow().files().get(FileKind.AUDIO).path())
        .isEqualTo("source.mp4");
  }

  @Test
  void aJobFailureIsRethrownUnchangedAndTheDirectoryIsDeleted() {
    JobFailure failure = JobFailure.of(JobFailure.Kind.PRIVATE);
    when(ytDlp.fetchMetadata(URL)).thenThrow(failure);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress)).isSameAs(failure);

    assertThat(dir).doesNotExist();
    assertThat(events).containsExactly("downloading");
  }

  @Test
  void aFailureAfterTheTranscriptDeletesTheWholeDirectory() {
    JobFailure failure = JobFailure.of(JobFailure.Kind.SUMMARY_UNREACHABLE);
    when(summary.summarize(any())).thenThrow(failure);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress)).isSameAs(failure);

    assertThat(dir).doesNotExist();
    assertThat(events).doesNotContain("publish");
  }

  @Test
  void anUnexpectedExceptionBecomesAFailedJobWithTheCauseKept() {
    IllegalStateException cause = new IllegalStateException("boom");
    when(ffmpeg.toWav(any(), any())).thenThrow(cause);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(failure.getCause()).isSameAs(cause);
              assertThat(failure.getMessage()).doesNotContain("boom");
            });

    assertThat(dir).doesNotExist();
  }

  @Test
  void anUnreadableTranscriptIsATranscriptionFailure() {
    when(transcription.transcribe(any(), any(), any())).thenReturn(dir.resolve("missing.txt"));

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(failure.getMessage())
                  .isEqualTo("Transcription failed. Please try again later.");
            });

    assertThat(dir).doesNotExist();
  }

  @Test
  void aWorkDirectoryThatCannotBeCreatedFailsTheJob() throws IOException {
    Files.writeString(dir, "a file where the directory should be");

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED));

    assertThat(events).isEmpty();
    verify(ytDlp, never()).fetchMetadata(any());
  }

  @Test
  void aFailedSummarySendFailsTheJobWithoutWithdrawing() {
    publisher.failure = new TelegramGatewayException("down", null);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(failure.getMessage())
                  .isEqualTo("Could not process this video. Please try again later.");
            });

    assertThat(publisher.withdrawn).isEmpty();
    assertThat(dir).doesNotExist();
  }

  @Test
  void aFailedJobJsonWriteFailsTheJobBeforeAnythingIsSent() throws IOException {
    when(summary.summarize(any()))
        .thenAnswer(call -> {
          Files.createDirectory(dir.resolve("job.json"));
          return "• point";
        });

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED));

    assertThat(events).doesNotContain("publish");
    assertThat(dir).doesNotExist();
  }

  @Test
  void aFailedPublicationUpdateWithdrawsTheSummaryAndDeletesTheDirectory() {
    JobStore failing =
        new JobStore(work, clock) {
          @Override
          public java.util.Optional<JobRecord> update(
              String jobId, UnaryOperator<JobRecord> change) {
            throw new JobStoreException("disk full", null);
          }
        };
    pipeline =
        new PipelineService(
            ytDlp, ffmpeg, transcription, summary, failing, publisher, clock, JOBS, health);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress))
        .isInstanceOfSatisfying(
            JobFailure.class,
            failure -> assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED));

    assertThat(publisher.withdrawn).containsExactly(901);
    assertThat(dir).doesNotExist();
  }

  @Test
  void aVanishedJobFileDuringPublicationCountsAsAFailure() {
    JobStore vanishing =
        new JobStore(work, clock) {
          @Override
          public java.util.Optional<JobRecord> update(
              String jobId, UnaryOperator<JobRecord> change) {
            return java.util.Optional.empty();
          }
        };
    pipeline =
        new PipelineService(
            ytDlp, ffmpeg, transcription, summary, vanishing, publisher, clock, JOBS, health);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress)).isInstanceOf(JobFailure.class);

    assertThat(publisher.withdrawn).containsExactly(901);
    assertThat(dir).doesNotExist();
  }

  @Test
  void aDeletionErrorDoesNotReplaceTheOriginalFailure() {
    JobFailure failure = JobFailure.of(JobFailure.Kind.TIMEOUT);
    when(ytDlp.fetchMetadata(URL)).thenThrow(failure);
    JobStore stubborn = mock(JobStore.class);
    when(stubborn.directory(JOB_ID)).thenReturn(dir);
    doAnswer(
            call -> {
              throw new JobStoreException("cannot delete", null);
            })
        .when(stubborn)
        .discard(JOB_ID);
    pipeline =
        new PipelineService(
            ytDlp, ffmpeg, transcription, summary, stubborn, publisher, clock, JOBS, health);

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress)).isSameAs(failure);

    verify(stubborn).discard(JOB_ID);
  }

  @Test
  void anUnreachableServiceFailsTheJobBeforeAnyWork() {
    JobFailure down = JobFailure.unreachable(true, false);
    org.mockito.Mockito.doThrow(down).when(health).requireReachable();

    assertThatThrownBy(() -> pipeline.run(REQUEST, progress)).isSameAs(down);

    assertThat(events).isEmpty();
    assertThat(dir).doesNotExist();
    verify(ytDlp, never()).fetchMetadata(any());
    verify(ytDlp, never()).downloadAudio(any(), any());
  }

  @Test
  void theCheckRunsBeforeTheDirectoryAndTheFirstStageReport() {
    org.mockito.Mockito.doAnswer(
            call -> {
              assertThat(dir).doesNotExist();
              events.add("health");
              return null;
            })
        .when(health)
        .requireReachable();

    pipeline.run(REQUEST, progress);

    assertThat(events).first().isEqualTo("health");
    assertThat(events.get(1)).isEqualTo("downloading");
  }
}
