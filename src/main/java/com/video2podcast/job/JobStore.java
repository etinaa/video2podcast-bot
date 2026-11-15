package com.video2podcast.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.video2podcast.storage.AtomicFiles;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

  private final Path workDir;
  private final Clock clock;
  private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public JobStore(Path workDir, Clock clock) {
    this.workDir = workDir;
    this.clock = clock;
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

  /** Creates the job directory (if needed) and writes its {@code job.json}. */
  public void create(JobRecord job) {
    withLock(job.jobId(), () -> save(job));
  }

  /** The stored job, or empty if it does not exist or its {@code job.json} is unusable. */
  public Optional<JobRecord> load(String jobId) {
    return withLock(jobId, () -> read(jobId));
  }

  /** Changes a job under its lock. Returns the new content, or empty if the job does not exist. */
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
                  save(changed);
                  return changed;
                }));
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
          .filter(Files::isDirectory)
          .map(path -> String.valueOf(path.getFileName()))
          .filter(name -> JOB_ID.matcher(name).matches())
          .toList();
    } catch (IOException e) {
      throw new JobStoreException("Cannot list " + workDir, e);
    }
  }

  /** Deletes the job directory with everything in it. Deleting a missing job does nothing. */
  public void delete(String jobId) {
    Path dir = directory(jobId);
    withLock(
        jobId,
        () -> {
          deleteRecursively(dir);
          return null;
        });
    locks.remove(jobId);
  }

  private Optional<JobRecord> read(String jobId) {
    Path file = directory(jobId).resolve(JOB_FILE);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      JobRecord job = Json.MAPPER.readValue(Files.readAllBytes(file), JobRecord.class);
      if (isUsable(job, jobId)) {
        return Optional.of(job);
      }
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
    try (Stream<Path> paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (IOException | UncheckedIOException e) {
      throw new JobStoreException("Cannot delete " + dir, e);
    }
  }

  private <T> T withLock(String jobId, Supplier<T> action) {
    directory(jobId);
    ReentrantLock lock = locks.computeIfAbsent(jobId, id -> new ReentrantLock());
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
