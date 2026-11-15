package com.video2podcast.job;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The content of a job's {@code job.json}. {@code summaryMessageId} is null until the summary
 * message has been sent.
 */
public record JobRecord(
    String jobId,
    long ownerUserId,
    long chatId,
    Integer summaryMessageId,
    String title,
    long durationSeconds,
    Instant createdAt,
    Map<FileKind, JobFile> files) {

  public JobRecord {
    files = files == null ? Map.of() : Map.copyOf(files);
  }

  public JobRecord withFile(FileKind kind, JobFile file) {
    Map<FileKind, JobFile> changed = new HashMap<>(files);
    changed.put(kind, file);
    return new JobRecord(
        jobId, ownerUserId, chatId, summaryMessageId, title, durationSeconds, createdAt, changed);
  }

  /** Records the summary message and starts the retention clock for every file. */
  public JobRecord published(int messageId, Instant expiresAt) {
    Map<FileKind, JobFile> changed = new HashMap<>();
    files.forEach((kind, file) -> changed.put(kind, file.expiringAt(expiresAt)));
    return new JobRecord(
        jobId, ownerUserId, chatId, messageId, title, durationSeconds, createdAt, changed);
  }
}
