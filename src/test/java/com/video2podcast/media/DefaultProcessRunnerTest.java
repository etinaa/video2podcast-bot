package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobFailure.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Uses {@code /bin/sh} as the controlled helper process; no media tools are needed. */
class DefaultProcessRunnerTest {

  private final DefaultProcessRunner runner = new DefaultProcessRunner();

  @TempDir Path dir;

  private static List<String> sh(String script) {
    return List.of("/bin/sh", "-c", script);
  }

  @Test
  void capturesOutputAndExitCode() {
    ProcessResult result = runner.run(sh("echo out; echo err >&2; exit 3"), Duration.ofSeconds(10));

    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.stdout()).isEqualTo("out\n");
    assertThat(result.stderr()).isEqualTo("err\n");
  }

  @Test
  void largeOutputOnBothStreamsDoesNotDeadlock() {
    ProcessResult result =
        runner.run(
            sh(
                "head -c 2000000 /dev/zero | tr '\\0' a; head -c 2000000 /dev/zero | tr '\\0' b >&2"),
            Duration.ofSeconds(30));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).hasSize(2_000_000);
    assertThat(result.stderr()).hasSize(2_000_000);
  }

  @Test
  void stdoutOverTheCapFailsVisiblyAfterItIsDrained() {
    ListAppender<ILoggingEvent> log = capture();
    try {
      assertThatThrownBy(() ->
              runner.run(sh("head -c 9000000 /dev/zero | tr '\\0' a"), Duration.ofSeconds(60)))
          .isInstanceOfSatisfying(
              JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED))
          .hasRootCauseMessage(
              "stdout is over " + DefaultProcessRunner.MAX_OUTPUT_BYTES + " bytes");
    } finally {
      release(log);
    }

    assertThat(log.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "/bin/sh wrote more than "
                + DefaultProcessRunner.MAX_OUTPUT_BYTES
                + " bytes to stdout");
  }

  @Test
  void stdoutOfExactlyTheCapIsKept() {
    ProcessResult result =
        runner.run(
            sh("head -c " + DefaultProcessRunner.MAX_OUTPUT_BYTES + " /dev/zero | tr '\\0' a"),
            Duration.ofSeconds(60));

    assertThat(result.stdout()).hasSize(DefaultProcessRunner.MAX_OUTPUT_BYTES);
  }

  @Test
  void stderrOverTheCapIsCutButStillDrainedAndDoesNotFail() {
    ProcessResult result =
        runner.run(
            sh("echo ok; head -c 9000000 /dev/zero | tr '\\0' b >&2"), Duration.ofSeconds(60));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEqualTo("ok\n");
    assertThat(result.stderr()).hasSize(DefaultProcessRunner.MAX_OUTPUT_BYTES);
  }

  @Test
  void timeoutKillsTheWholeTree() throws Exception {
    Path pidFile = dir.resolve("child.pid");
    long start = System.nanoTime();

    assertThatThrownBy(() ->
            runner.run(sh("sleep 60 & echo $! > '" + pidFile + "'; wait"), Duration.ofSeconds(1)))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.TIMEOUT));

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(12));
    long childPid = Long.parseLong(Files.readString(pidFile).trim());
    Optional<ProcessHandle> child = ProcessHandle.of(childPid);
    for (int i = 0; i < 50 && child.isPresent() && child.get().isAlive(); i++) {
      Thread.sleep(100);
    }
    assertThat(child.map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test
  void lingeringPipeHolderReturnsWithinTheCleanupBudget() throws Exception {
    Path pidFile = dir.resolve("orphan.pid");
    long start = System.nanoTime();
    ProcessResult result;
    try {
      result =
          runner.run(
              sh("echo hello; sleep 60 & echo $! > '" + pidFile + "'; exit 0"),
              Duration.ofSeconds(30));
    } finally {
      killOrphan(pidFile);
    }

    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(took).isGreaterThan(Duration.ofSeconds(3)).isLessThan(Duration.ofSeconds(9));
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEqualTo("hello\n");
  }

  private static void killOrphan(Path pidFile) throws IOException {
    if (Files.exists(pidFile)) {
      long pid = Long.parseLong(Files.readString(pidFile).trim());
      ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void missingBinaryIsFailed() {
    assertThatThrownBy(() -> runner.run(List.of("/no/such/binary"), Duration.ofSeconds(5)))
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED));
  }

  @Test
  void nonPositiveTimeoutIsRejected() {
    assertThatThrownBy(() -> runner.run(sh("true"), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> runner.run(sh("true"), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> runner.run(sh("true"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void interruptKillsTheWholeTreeAndKeepsTheFlag() throws Exception {
    Path shellPidFile = dir.resolve("sh.pid");
    Path childPidFile = dir.resolve("child.pid");
    Throwable[] thrown = new Throwable[1];
    boolean[] flag = new boolean[1];
    Thread thread =
        new Thread(() -> {
          try {
            runner.run(
                sh(
                    "sleep 60 & echo $! > '"
                        + childPidFile
                        + "'; echo $$ > '"
                        + shellPidFile
                        + "'; wait"),
                Duration.ofSeconds(60));
          } catch (JobFailure e) {
            thrown[0] = e;
          }
          flag[0] = Thread.currentThread().isInterrupted();
        });
    thread.start();
    long shellPid = 0;
    long childPid = 0;
    try {
      shellPid = awaitPid(shellPidFile);
      childPid = awaitPid(childPidFile);
      thread.interrupt();
      thread.join(10_000);

      assertThat(thread.isAlive()).isFalse();
      assertThat(thrown[0])
          .isInstanceOfSatisfying(
              JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED));
      assertThat(flag[0]).isTrue();
      assertThat(diesWithin(shellPid)).as("shell process ended").isTrue();
      assertThat(diesWithin(childPid)).as("child process ended").isTrue();
    } finally {
      thread.interrupt();
      destroy(shellPid);
      destroy(childPid);
    }
  }

  @Test
  void argumentsReachTheProcessLiterallyWithoutSideEffects() throws Exception {
    Path sentinel = dir.resolve("sentinel");
    Path spaced = Files.createDirectory(dir.resolve("my job dir"));
    List<String> hostile =
        List.of(
            "https://example.com/a?x=1&y=2;z=$(touch '" + sentinel + "')|`touch " + sentinel + "`",
            "; touch " + sentinel + " #",
            "$HOME ${HOME} *",
            spaced + "/source.%(ext)s",
            "--",
            "");
    List<String> command = new java.util.ArrayList<>();
    command.addAll(
        List.of("/bin/sh", "-c", "for a in \"$@\"; do printf '%s\\0' \"$a\"; done", "helper"));
    command.addAll(hostile);

    ProcessResult result = runner.run(command, Duration.ofSeconds(10));

    assertThat(result.exitCode()).isZero();
    String[] received = result.stdout().split("\0", -1);
    assertThat(List.of(received).subList(0, received.length - 1))
        .containsExactlyElementsOf(hostile);
    assertThat(sentinel).doesNotExist();
  }

  @Test
  void childCannotWaitForInput() {
    ProcessResult result = runner.run(sh("cat; echo done"), Duration.ofSeconds(10));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEqualTo("done\n");
  }

  private static long awaitPid(Path file) throws Exception {
    for (int i = 0; i < 200; i++) {
      if (Files.exists(file) && !Files.readString(file).isBlank()) {
        return Long.parseLong(Files.readString(file).trim());
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no pid written to " + file);
  }

  private static boolean diesWithin(long pid) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
        Thread.sleep(100);
      } else {
        return true;
      }
    }
    return false;
  }

  private static void destroy(long pid) {
    if (pid > 0) {
      ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  private static ListAppender<ILoggingEvent> capture() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(DefaultProcessRunner.class)).addAppender(appender);
    return appender;
  }

  private static void release(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(DefaultProcessRunner.class)).detachAppender(appender);
  }
}
