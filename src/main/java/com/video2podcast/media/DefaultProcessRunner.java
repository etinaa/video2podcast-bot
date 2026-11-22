package com.video2podcast.media;

import com.video2podcast.job.JobFailure;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a process without a shell, reads both output streams on their own threads and kills the
 * whole process tree when the timeout ends. Nothing here waits without a bound. A stdout above the
 * limit fails the call, because a cut text is never a valid answer; a cut stderr only loses log
 * detail.
 */
public class DefaultProcessRunner implements ProcessRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultProcessRunner.class);

  static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
  private static final Duration CLEANUP_BUDGET = Duration.ofSeconds(5);
  private static final long GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

  /** Collects up to {@link #MAX_OUTPUT_BYTES} and keeps reading (and dropping) the rest. */
  private static final class Reader implements Runnable {
    private final InputStream in;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean truncated;

    Reader(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      byte[] chunk = new byte[8192];
      try {
        int n = in.read(chunk);
        while (n >= 0) {
          append(chunk, n);
          n = in.read(chunk);
        }
      } catch (IOException ignored) {
        // The stream was closed under us (cleanup) or the process died: keep what we have.
      }
    }

    private synchronized void append(byte[] data, int length) {
      int room = MAX_OUTPUT_BYTES - buffer.size();
      if (room > 0) {
        buffer.write(data, 0, Math.min(room, length));
      }
      if (length > room) {
        truncated = true;
      }
    }

    synchronized boolean truncated() {
      return truncated;
    }

    synchronized String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  /** A started process; closing it closes all three of its streams. */
  private static final class Child implements AutoCloseable {
    private final Process process;

    private Child(Process process) {
      this.process = process;
    }

    static Child start(List<String> args) {
      try {
        Child child = new Child(new ProcessBuilder(args).start());
        // Nothing is ever sent to the child; closing stdin keeps a reader from waiting on it.
        closeQuietly(child.process.getOutputStream());
        return child;
      } catch (IOException e) {
        throw JobFailure.of(JobFailure.Kind.FAILED, e);
      }
    }

    InputStream stdout() {
      return process.getInputStream();
    }

    InputStream stderr() {
      return process.getErrorStream();
    }

    boolean waitFor(long nanos) throws InterruptedException {
      return process.waitFor(nanos, TimeUnit.NANOSECONDS);
    }

    int exitCode() {
      return process.exitValue();
    }

    void killTree() {
      List<ProcessHandle> descendants = process.descendants().toList();
      descendants.forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }

    @Override
    public void close() {
      closeQuietly(process.getInputStream());
      closeQuietly(process.getErrorStream());
      closeQuietly(process.getOutputStream());
    }
  }

  @Override
  public ProcessResult run(List<String> args, Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try (Child child = Child.start(args)) {
      Reader out = new Reader(child.stdout());
      Reader err = new Reader(child.stderr());
      Thread outThread = daemon(out, "process-stdout");
      Thread errThread = daemon(err, "process-stderr");
      outThread.start();
      errThread.start();
      try {
        if (!child.waitFor(timeout.toNanos())) {
          killAndCleanUp(child, outThread, errThread);
          throw JobFailure.of(JobFailure.Kind.TIMEOUT);
        }
        long deadline = System.nanoTime() + CLEANUP_BUDGET.toNanos();
        join(outThread, deadline);
        join(errThread, deadline);
        child.close();
        join(outThread, System.nanoTime() + GRACE_NANOS);
        join(errThread, System.nanoTime() + GRACE_NANOS);
        if (out.truncated()) {
          throw outputTooLarge(args);
        }
        return new ProcessResult(child.exitCode(), out.text(), err.text());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        child.killTree();
        throw JobFailure.of(JobFailure.Kind.FAILED, e);
      }
    }
  }

  private static JobFailure outputTooLarge(List<String> args) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("{} wrote more than {} bytes to stdout", args.get(0), MAX_OUTPUT_BYTES);
    }
    return JobFailure.of(
        JobFailure.Kind.FAILED,
        new IllegalStateException("stdout is over " + MAX_OUTPUT_BYTES + " bytes"));
  }

  private static Thread daemon(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }

  private static void killAndCleanUp(Child child, Thread outThread, Thread errThread)
      throws InterruptedException {
    long deadline = System.nanoTime() + CLEANUP_BUDGET.toNanos();
    child.killTree();
    child.waitFor(Math.max(0, deadline - System.nanoTime()));
    child.close();
    join(outThread, deadline);
    join(errThread, deadline);
  }

  private static void closeQuietly(Closeable stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // Nothing left to do for a stream that will not close.
    }
  }

  private static void join(Thread thread, long deadlineNanos) throws InterruptedException {
    long left = deadlineNanos - System.nanoTime();
    if (left > 0) {
      thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
    }
  }
}
