package com.video2podcast.job;

import com.video2podcast.storage.AtomicFiles;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The links waiting for Whisper or Ollama to come back, kept in {@code deferred-jobs.json}. Every
 * change is written to the file first and becomes visible only after the write succeeded, so a
 * failed write leaves the previous queue in place. The file holds user links: never log it.
 */
public final class DeferredJobs {

  private static final Logger LOG = LoggerFactory.getLogger(DeferredJobs.class);

  /** The shape of the file. */
  private record Content(List<DeferredJob> jobs) {}

  private final Path file;
  private final Clock clock;
  private final List<DeferredJob> queue = new ArrayList<>();

  /**
   * Loads the file now. A missing file is an empty queue. A file that is not valid JSON or has an
   * invalid entry is renamed to {@code <name>.corrupt-<epoch seconds>} and the queue starts empty.
   *
   * @throws DeferredJobsException if the file cannot be read or renamed
   */
  public DeferredJobs(Path file, Clock clock) {
    this.file = file;
    this.clock = clock;
    this.queue.addAll(load());
  }

  private List<DeferredJob> load() {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      Optional<List<DeferredJob>> parsed = parse(Files.readAllBytes(file));
      if (parsed.isPresent()) {
        return parsed.get();
      }
      Path aside =
          file.resolveSibling(file.getFileName() + ".corrupt-" + clock.instant().getEpochSecond());
      Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
      if (LOG.isErrorEnabled()) {
        LOG.error(
            "{} is not a valid queue, moved to {}. Starting with an empty queue.", file, aside);
      }
      return List.of();
    } catch (IOException e) {
      throw new DeferredJobsException("Cannot read " + file, e);
    }
  }

  /** The entries, or empty when the content is not valid JSON or has an invalid entry. */
  private static Optional<List<DeferredJob>> parse(byte[] bytes) {
    try {
      Content content = Json.MAPPER.readValue(bytes, Content.class);
      if (content == null) {
        return Optional.empty();
      }
      if (content.jobs() == null) {
        return Optional.of(List.of());
      }
      return content.jobs().stream().allMatch(DeferredJobs::isValid)
          ? Optional.of(List.copyOf(content.jobs()))
          : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static boolean isValid(DeferredJob job) {
    return job != null
        && job.id() != null
        && !job.id().isBlank()
        && job.url() != null
        && !job.url().isBlank()
        && job.ownerUserId() != 0
        && job.chatId() != 0
        && job.statusMessageId() != 0
        && job.parkedAt() != null
        && job.attempts() >= 0;
  }

  /**
   * Appends an entry for {@code request}, with the job id of the first run, no attempts and the
   * current time as the parking time.
   *
   * @throws DeferredJobsException if the file cannot be written
   */
  public synchronized DeferredJob park(JobRequest request, int statusMessageId) {
    Instant now = clock.instant();
    DeferredJob job =
        new DeferredJob(
            request.jobId(),
            request.url(),
            request.ownerUserId(),
            request.chatId(),
            statusMessageId,
            0,
            now);
    List<DeferredJob> next = new ArrayList<>(queue);
    next.add(job);
    replace(next);
    return job;
  }

  /** The entries in queue order, as an immutable copy. */
  public synchronized List<DeferredJob> entries() {
    return List.copyOf(queue);
  }

  public synchronized Optional<DeferredJob> find(String id) {
    return queue.stream().filter(job -> job.id().equals(id)).findFirst();
  }

  /**
   * Adds one to the attempt count of an entry, keeping its place in the queue.
   *
   * @return the updated entry, empty if there is no such entry
   * @throws DeferredJobsException if the file cannot be written
   */
  public synchronized Optional<DeferredJob> recordFailedAttempt(String id) {
    Optional<DeferredJob> found = find(id);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    DeferredJob updated = found.get().withAttempts(found.get().attempts() + 1);
    replace(queue.stream().map(job -> job.id().equals(id) ? updated : job).toList());
    return Optional.of(updated);
  }

  /**
   * @return false if there was no such entry
   * @throws DeferredJobsException if the file cannot be written
   */
  public synchronized boolean remove(String id) {
    if (find(id).isEmpty()) {
      return false;
    }
    replace(queue.stream().filter(job -> !job.id().equals(id)).toList());
    return true;
  }

  /** Writes {@code next} and, only when that worked, makes it the queue. */
  private void replace(List<DeferredJob> next) {
    try {
      AtomicFiles.write(
          file, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Content(next)));
    } catch (IOException e) {
      throw new DeferredJobsException("Cannot write " + file, e);
    }
    queue.clear();
    queue.addAll(next);
  }
}
