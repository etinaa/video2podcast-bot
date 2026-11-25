package com.video2podcast.job;

import com.video2podcast.config.AppProperties;
import com.video2podcast.media.FfmpegRunner;
import com.video2podcast.media.VideoMetadata;
import com.video2podcast.media.YtDlpClient;
import com.video2podcast.summary.SummaryService;
import com.video2podcast.transcript.TranscriptionClient;
import com.video2podcast.transcript.TranscriptionClient.Transcription;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one job from the link to the sent summary message. It knows nothing about Telegram: stage
 * reports and the summary message go through {@link JobProgress} and {@link SummaryPublisher}.
 */
public class PipelineService {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineService.class);

  private static final String TRANSCRIPT_FILE = "transcript.txt";

  private final YtDlpClient ytDlp;
  private final FfmpegRunner ffmpeg;
  private final TranscriptionClient transcription;
  private final SummaryService summary;
  private final JobStore store;
  private final SummaryPublisher publisher;
  private final Clock clock;
  private final AppProperties.Jobs jobs;
  private final HealthChecks health;

  public PipelineService(
      YtDlpClient ytDlp,
      FfmpegRunner ffmpeg,
      TranscriptionClient transcription,
      SummaryService summary,
      JobStore store,
      SummaryPublisher publisher,
      Clock clock,
      AppProperties.Jobs jobs,
      HealthChecks health) {
    this.ytDlp = ytDlp;
    this.ffmpeg = ffmpeg;
    this.transcription = transcription;
    this.summary = summary;
    this.store = store;
    this.publisher = publisher;
    this.clock = clock;
    this.jobs = jobs;
    this.health = health;
  }

  /**
   * Runs one job to the point where the summary message is sent and its retention has started. On
   * every failure the job directory is deleted before the exception leaves.
   *
   * @throws JobFailure the failure of a stage unchanged, {@code SERVICE_UNREACHABLE} or {@code
   *     SUMMARY_UNREACHABLE} raised before the work starts, or {@code FAILED} for anything else
   */
  public void run(JobRequest request, JobProgress progress) {
    try {
      execute(request, progress);
    } catch (JobFailure failure) {
      deleteDirectory(request.jobId());
      throw failure;
    } catch (RuntimeException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Job {} failed", request.jobId(), e);
      }
      deleteDirectory(request.jobId());
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
  }

  private void execute(JobRequest request, JobProgress progress) {
    health.requireReachable();
    String jobId = request.jobId();
    Path dir = store.directory(jobId);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw JobFailure.of(JobFailure.Kind.FAILED, e);
    }
    progress.downloading();
    VideoMetadata meta = ytDlp.fetchMetadata(request.url());
    Path source = ytDlp.downloadAudio(request.url(), dir);
    Path wav = ffmpeg.toWav(source, dir);
    List<Path> chunks = ffmpeg.split(wav, dir);
    Transcription transcribed = transcription.transcribe(dir, chunks, progress::transcribing);
    progress.summarizing();
    String bullets =
        summary.summarize(readTranscript(transcribed.transcript()), transcribed.language());

    JobRecord record =
        new JobRecord(
            jobId,
            request.ownerUserId(),
            request.chatId(),
            null,
            meta.title(),
            meta.duration().toSeconds(),
            request.createdAt(),
            Map.of(
                FileKind.AUDIO,
                    JobFile.available(Objects.requireNonNull(source.getFileName()).toString()),
                FileKind.TRANSCRIPT, JobFile.available(TRANSCRIPT_FILE)));
    store.create(record);
    int messageId = publisher.publish(record, bullets);
    Instant expiresAt = clock.instant().plus(jobs.retention());
    try {
      store
          .update(jobId, stored -> stored.published(messageId, expiresAt))
          .orElseThrow(() -> new JobStoreException("job.json of " + jobId + " is gone", null));
    } catch (RuntimeException e) {
      publisher.withdraw(record, messageId, bullets);
      throw e;
    }
  }

  private static String readTranscript(Path transcript) {
    try {
      return Files.readString(transcript, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw JobFailure.transcriptionFailed(e);
    }
  }

  private void deleteDirectory(String jobId) {
    try {
      store.discard(jobId);
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not delete the directory of job {}", jobId, e);
      }
    }
  }
}
