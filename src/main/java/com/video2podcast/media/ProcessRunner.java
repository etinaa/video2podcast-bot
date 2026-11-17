package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.time.Duration;
import java.util.List;

/** The only seam to the operating system; tests and the functional harness replace it. */
public interface ProcessRunner {

  /**
   * Runs {@code args[0]} with {@code args[1..]} (no shell) and waits at most {@code timeout}. A
   * non-zero exit is reported in the result, not thrown.
   *
   * @throws JobFailure {@code TIMEOUT} when the process had to be killed, {@code FAILED} when it
   *     cannot start or the thread is interrupted
   */
  ProcessResult run(List<String> args, Duration timeout);
}
