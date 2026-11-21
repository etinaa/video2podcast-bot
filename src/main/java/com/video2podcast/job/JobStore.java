package com.video2podcast.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.video2podcast.storage.AtomicFiles;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one directory per job under the work directory, with the job's files and a {@code job.json}
 * that is the single source of truth about it. Every change to a job runs under that job's own lock
 * and is written atomically, so two button taps or a tap and the sweeper can never both win.
 */
public class JobStore {

  private static final Logger LOG = LoggerFactory.getLogger(JobStore.class);

  private static final String JOB_FILE = "job.json";
  private static final int LOCK_STRIPES = 64;
  private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

  /** The outcome of {@link #transition}. */
  public enum Transition {
    /** This caller changed the state. */
    APPLIED,
    /** The file was not in the expected state, for example because another tap got there first. */
    STATE_MISMATCH,
    /** There is no such job or the job has no such file. */
    NOT_FOUND
  }

  /** What {@link #recover} did to one job. */
  public enum Recovery {
    REMOVED,
    RESET,
    UNCHANGED
  }

  /** Result of {@link #recover}. {@code job} is the stored job after a RESET, otherwise null. */
  public record Recovered(Recovery recovery, JobRecord job) {}

  /**
   * Result of {@link #sweep}. {@code job} is the job after the change (null when there was no
   * usable job.json or the job was kept untouched with a SENDING file); {@code expired} lists files
   * this call moved to EXPIRED; {@code removed} is true when the directory is gone.
   */
  public record Swept(JobRecord job, List<FileKind> expired, boolean removed) {

    public Swept {
      expired = List.copyOf(expired);
    }

    public static Swept none() {
      return new Swept(null, List.of(), false);
    }
  }

  private final Path workDir;
  private final Clock clock;

  /**
   * A fixed set of locks, picked by job id. They are never created or dropped while the store runs,
   * so two callers working on the same job always meet at the same lock, and a few jobs sharing one
   * lock is harmless because no operation holds two jobs at once.
   */
  private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

  public JobStore(Path workDir, Clock clock) {
    this.workDir = workDir;
    this.clock = clock;
    for (int i = 0; i < locks.length; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  public static String newJobId() {
    return UUID.randomUUID().toString();
  }

  /**
   * The directory of a job. A job id that does not look like one this bot generates is refused, so
   * an id from a button tap can never point outside the work directory.
   */
  public Path directory(String jobId) {
    if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
      throw new IllegalArgumentException("Not a valid job id: " + jobId);
    }
    return workDir.resolve(jobId);
  }

  /** The path of one of the job's files. */
  public Path resolve(String jobId, JobFile file) {
    return directory(jobId).resolve(file.path());
  }

  /**
   * Creates the job directory (if needed) and writes its {@code job.json}.
   *
   * @throws IllegalStateException if the job already exists, so a stored job is never replaced
   */
  public void create(JobRecord job) {
    withLock(
        job.jobId(),
        () -> {
          if (read(job.jobId()).isPresent()) {
            throw new IllegalStateException("Job " + job.jobId() + " already exists");
          }
          save(job);
        });
  }

  /** The stored job, or empty if it does not exist or its {@code job.json} is unusable. */
  public Optional<JobRecord> load(String jobId) {
    return withLock(jobId, () -> read(jobId));
  }

  /**
   * Changes a job under its lock. Returns the new content, or empty if the job does not exist. The
   * change may not touch file states except along the allowed transitions, so this cannot bring a
   * sent file back; use {@link #transition} to move a file.
   *
   * @throws IllegalArgumentException if the change drops a file, moves one along a forbidden
   *     transition or changes the job id
   */
  public Optional<JobRecord> update(String jobId, UnaryOperator<JobRecord> change) {
    return withLock(
        jobId,
        () ->
            read(jobId)
                .map(job -> {
                  JobRecord changed = change.apply(job);
                  if (!jobId.equals(changed.jobId())) {
                    throw new IllegalArgumentException("A change must keep the job id");
                  }
                  checkStates(job, changed);
                  save(changed);
                  return changed;
                }));
  }

  private static void checkStates(JobRecord before, JobRecord after) {
    for (Map.Entry<FileKind, JobFile> entry : before.files().entrySet()) {
      JobFile old = entry.getValue();
      JobFile now = after.files().get(entry.getKey());
      if (now == null) {
        throw new IllegalArgumentException("A change must keep the " + entry.getKey() + " file");
      }
      if (now.state() != old.state() && !old.state().canMoveTo(now.state())) {
        throw new IllegalArgumentException(
            "A file cannot move from " + old.state() + " to " + now.state());
      }
    }
  }

  /**
   * Moves one file from {@code expected} to {@code next} if, and only if, it is in {@code expected}
   * right now. When several callers race, exactly one gets {@link Transition#APPLIED}.
   *
   * @throws IllegalArgumentException if {@code expected} may never move to {@code next}
   */
  public Transition transition(String jobId, FileKind kind, FileState expected, FileState next) {
    if (!expected.canMoveTo(next)) {
      throw new IllegalArgumentException("A file cannot move from " + expected + " to " + next);
    }
    return withLock(
        jobId,
        () -> {
          Optional<JobRecord> job = read(jobId);
          JobFile file = job.map(j -> j.files().get(kind)).orElse(null);
          if (file == null) {
            return Transition.NOT_FOUND;
          }
          if (file.state() != expected) {
            return Transition.STATE_MISMATCH;
          }
          Instant sentAt = next == FileState.SENT ? clock.instant() : file.sentAt();
          save(job.orElseThrow().withFile(kind, file.moveTo(next, sentAt)));
          return Transition.APPLIED;
        });
  }

  /** The ids of all job directories, in no particular order. */
  public List<String> jobIds() {
    if (!Files.isDirectory(workDir)) {
      return List.of();
    }
    try (Stream<Path> children = Files.list(workDir)) {
      return children
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .map(path -> String.valueOf(path.getFileName()))
          .filter(name -> JOB_ID.matcher(name).matches())
          .toList();
    } catch (IOException e) {
      throw new JobStoreException("Cannot list " + workDir, e);
    }
  }

  /**
   * Startup only. Under the job lock: if there is no usable job.json, deletes the whole directory;
   * else moves every SENDING file back to AVAILABLE and saves. Expiry dates and sentAt stay as they
   * were, and no file is recreated.
   *
   * @throws JobStoreException on an I/O error
   */
  public Recovered recover(String jobId) {
    return withLock(
        jobId,
        () -> {
          Optional<JobRecord> stored = read(jobId);
          if (stored.isEmpty()) {
            deleteRecursively(directory(jobId));
            return new Recovered(Recovery.REMOVED, null);
          }
          JobRecord job = stored.get();
          boolean interrupted = false;
          for (Map.Entry<FileKind, JobFile> entry : job.files().entrySet()) {
            JobFile file = entry.getValue();
            if (file.state() == FileState.SENDING) {
              job = job.withFile(entry.getKey(), file.moveTo(FileState.AVAILABLE, file.sentAt()));
              interrupted = true;
            }
          }
          if (!interrupted) {
            return new Recovered(Recovery.UNCHANGED, null);
          }
          save(job);
          return new Recovered(Recovery.RESET, job);
        });
  }

  /**
   * Expires the files whose deadline has come and removes the directory once nothing is left to
   * download. A job with a SENDING file, or without a usable job.json, is left alone. A file
   * without a deadline expires only when {@code atStartup}.
   *
   * @throws JobStoreException on an I/O error; the stored state stays consistent for a retry
   */
  public Swept sweep(String jobId, Instant now, boolean atStartup) {
    return withLock(
        jobId,
        () -> {
          Optional<JobRecord> stored = read(jobId);
          if (stored.isEmpty() || hasSendingFile(stored.get())) {
            return Swept.none();
          }
          JobRecord job = stored.get();
          List<FileKind> expired = new ArrayList<>();
          for (Map.Entry<FileKind, JobFile> entry : job.files().entrySet()) {
            JobFile file = entry.getValue();
            if (file.state() == FileState.AVAILABLE && isDue(file, now, atStartup)) {
              deleteFile(resolve(jobId, file));
              job = job.withFile(entry.getKey(), file.moveTo(FileState.EXPIRED, file.sentAt()));
              expired.add(entry.getKey());
            }
          }
          if (isFinished(job)) {
            deleteRecursively(directory(jobId));
            return new Swept(job, expired, true);
          }
          if (!expired.isEmpty()) {
            save(job);
          }
          return new Swept(job, expired, false);
        });
  }

  private static boolean hasSendingFile(JobRecord job) {
    return job.files().values().stream().anyMatch(file -> file.state() == FileState.SENDING);
  }

  private static boolean isDue(JobFile file, Instant now, boolean atStartup) {
    return file.expiresAt() == null ? atStartup : !now.isBefore(file.expiresAt());
  }

  private static boolean isFinished(JobRecord job) {
    return !job.files().isEmpty()
        && job.files().values().stream()
            .allMatch(file -> file.state() == FileState.SENT || file.state() == FileState.EXPIRED);
  }

  /** A failure after the first file leaves it AVAILABLE but gone; the next pass finishes it. */
  private static void deleteFile(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new JobStoreException("Cannot delete " + file, e);
    }
  }

  /**
   * Deletes the job directory with everything in it, {@code job.json} last, so a failure in the
   * middle leaves the job known and the next pass retries. Deleting a missing job does nothing.
   */
  public void discard(String jobId) {
    Path dir = directory(jobId);
    withLock(jobId, () -> deleteRecursively(dir));
  }

  private Optional<JobRecord> read(String jobId) {
    Path dir = directory(jobId);
    if (Files.isSymbolicLink(dir)) {
      return Optional.empty();
    }
    Path file = dir.resolve(JOB_FILE);
    try {
      JobRecord job = Json.MAPPER.readValue(Files.readAllBytes(file), JobRecord.class);
      if (isUsable(job, jobId)) {
        return Optional.of(job);
      }
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (JsonProcessingException e) {
      LOG.warn("{} is not valid JSON", file);
    } catch (IOException e) {
      throw new JobStoreException("Cannot read " + file, e);
    }
    return Optional.empty();
  }

  private static boolean isUsable(JobRecord job, String jobId) {
    return job != null
        && jobId.equals(job.jobId())
        && job.title() != null
        && job.createdAt() != null
        && job.files().values().stream().allMatch(JobStore::isUsable);
  }

  private static boolean isUsable(JobFile file) {
    return file != null
        && file.state() != null
        && file.path() != null
        && isPlainFileName(file.path());
  }

  /** A name inside the job directory: no path separators and not a relative reference. */
  private static boolean isPlainFileName(String name) {
    return !name.isEmpty()
        && !".".equals(name)
        && !"..".equals(name)
        && name.indexOf('/') < 0
        && name.indexOf('\\') < 0;
  }

  private void save(JobRecord job) {
    Path file = directory(job.jobId()).resolve(JOB_FILE);
    try {
      AtomicFiles.write(file, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(job));
    } catch (IOException e) {
      throw new JobStoreException("Cannot write " + file, e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try {
      if (Files.isSymbolicLink(dir)) {
        // Only the link goes, never what it points to.
        Files.delete(dir);
        return;
      }
    } catch (IOException e) {
      throw new JobStoreException("Cannot delete " + dir, e);
    }
    Path jobFile = dir.resolve(JOB_FILE);
    try (Stream<Path> paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .filter(path -> !path.equals(dir) && !path.equals(jobFile))
          .forEach(JobStore::deleteUnchecked);
      Files.deleteIfExists(jobFile);
      Files.deleteIfExists(dir);
    } catch (IOException | UncheckedIOException e) {
      throw new JobStoreException("Cannot delete " + dir, e);
    }
  }

  private static void deleteUnchecked(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private <T> T withLock(String jobId, Supplier<T> action) {
    directory(jobId);
    ReentrantLock lock = locks[Math.floorMod(jobId.hashCode(), locks.length)];
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private void withLock(String jobId, Runnable action) {
    withLock(
        jobId,
        () -> {
          action.run();
          return null;
        });
  }
}
