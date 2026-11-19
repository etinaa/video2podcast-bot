package com.video2podcast.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The state side of a download button tap. It decides whether a tap may send a file and moves the
 * file along {@code AVAILABLE -> SENDING -> SENT}, so a file is sent once even when taps race.
 * Knows nothing about Telegram.
 */
public class DeliveryService {

  /** What a tap is allowed to do. */
  public enum Outcome {
    GRANTED,
    EXPIRED,
    NOT_OWNER,
    ALREADY_SENT,
    IN_PROGRESS
  }

  /**
   * @param job the stored job after the claim (the file is SENDING when GRANTED); null when the job
   *     does not exist
   * @param file the file to send; set only when GRANTED
   */
  public record Claim(Outcome outcome, JobRecord job, Path file) {}

  private final JobStore store;
  private final Clock clock;

  public DeliveryService(JobStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * Checks a tap and, when it is allowed, moves the file AVAILABLE to SENDING. Exactly one of
   * several concurrent callers gets GRANTED.
   *
   * @throws JobStoreException on an I/O error
   */
  public Claim claim(String jobId, long userId, FileKind kind) {
    Optional<JobRecord> loaded = load(jobId);
    if (loaded.isEmpty()) {
      return refused(Outcome.EXPIRED, null);
    }
    JobRecord job = loaded.get();
    if (job.ownerUserId() != userId) {
      return refused(Outcome.NOT_OWNER, job);
    }
    JobFile file = job.files().get(kind);
    if (file == null) {
      return refused(Outcome.EXPIRED, job);
    }
    return switch (file.state()) {
      case SENT -> refused(Outcome.ALREADY_SENT, job);
      case EXPIRED -> refused(Outcome.EXPIRED, job);
      case SENDING -> refused(Outcome.IN_PROGRESS, job);
      case AVAILABLE -> claimAvailable(job, kind, file);
    };
  }

  /**
   * SENDING to SENT. Returns the stored job, or empty if it is gone or the file was not SENDING.
   */
  public Optional<JobRecord> complete(String jobId, FileKind kind) {
    return move(jobId, kind, FileState.SENDING, FileState.SENT);
  }

  /** SENDING to AVAILABLE after a failed send. Returns the stored job, or empty as above. */
  public Optional<JobRecord> release(String jobId, FileKind kind) {
    return move(jobId, kind, FileState.SENDING, FileState.AVAILABLE);
  }

  private Claim claimAvailable(JobRecord job, FileKind kind, JobFile file) {
    Instant expiresAt = file.expiresAt();
    if (expiresAt != null && !clock.instant().isBefore(expiresAt)) {
      return refused(Outcome.EXPIRED, job);
    }
    Path path = store.resolve(job.jobId(), file);
    if (!Files.isRegularFile(path)) {
      store.transition(job.jobId(), kind, FileState.AVAILABLE, FileState.EXPIRED);
      return refused(Outcome.EXPIRED, job);
    }
    JobStore.Transition result =
        store.transition(job.jobId(), kind, FileState.AVAILABLE, FileState.SENDING);
    if (result == JobStore.Transition.APPLIED) {
      return new Claim(Outcome.GRANTED, store.load(job.jobId()).orElse(job), path);
    }
    return lostRace(job.jobId(), kind);
  }

  private Claim lostRace(String jobId, FileKind kind) {
    Optional<JobRecord> job = store.load(jobId);
    FileState state = job.map(j -> j.files().get(kind)).map(JobFile::state).orElse(null);
    if (state == FileState.SENT) {
      return refused(Outcome.ALREADY_SENT, job.get());
    }
    if (state == FileState.SENDING) {
      return refused(Outcome.IN_PROGRESS, job.get());
    }
    return refused(Outcome.EXPIRED, job.orElse(null));
  }

  private Optional<JobRecord> move(String jobId, FileKind kind, FileState from, FileState to) {
    if (store.transition(jobId, kind, from, to) != JobStore.Transition.APPLIED) {
      return Optional.empty();
    }
    return store.load(jobId);
  }

  private Optional<JobRecord> load(String jobId) {
    try {
      return store.load(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Claim refused(Outcome outcome, JobRecord job) {
    return new Claim(outcome, job, null);
  }
}
