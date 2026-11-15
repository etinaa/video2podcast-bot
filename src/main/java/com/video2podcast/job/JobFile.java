package com.video2podcast.job;

import java.time.Instant;

/**
 * One file of a job. {@code path} is a plain file name inside the job directory. {@code expiresAt}
 * is null until the summary message has been sent, and {@code sentAt} is null until the file was
 * delivered.
 */
public record JobFile(String path, FileState state, Instant expiresAt, Instant sentAt) {

  public static JobFile available(String path) {
    return new JobFile(path, FileState.AVAILABLE, null, null);
  }

  JobFile moveTo(FileState next, Instant sentAt) {
    return new JobFile(path, next, expiresAt, sentAt);
  }

  JobFile expiringAt(Instant expiresAt) {
    return new JobFile(path, state, expiresAt, sentAt);
  }
}
