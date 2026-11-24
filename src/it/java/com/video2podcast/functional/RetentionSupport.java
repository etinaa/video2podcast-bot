package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.video2podcast.media.DefaultProcessRunner;
import com.video2podcast.media.ProcessResult;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** T14 boundary fixtures. No cleanup, recovery or delivery service is invoked by the tests. */
final class RetentionSupport {
  final TranscriptWorld w;
  Schedule schedule = new Schedule();
  final List<List<String>> commands = new CopyOnWriteArrayList<>();
  final CountDownLatch downloadReceived = new CountDownLatch(1);
  final CountDownLatch releaseDownload = new CountDownLatch(1);
  final Map<String, byte[]> liveBefore = new TreeMap<>();
  Map<String, byte[]> restartBefore;
  byte[] accessBefore, sentinelBefore;
  volatile Path liveDir;
  boolean restarting, holdDownload, failDownload;
  String uploadMethod = "senddocument";
  int rejectMessage = -1;
  int restartCommandCount;

  RetentionSupport(TranscriptWorld world) {
    w = world;
  }

  void baseline() throws Exception {
    w.start();
    remember();
  }

  void remember() throws Exception {
    w.before.clear();
    w.before.putAll(w.snapshot());
  }

  void tick() throws Exception {
    remember();
    int requests = w.events.size();
    schedule.pass();
    assertThat(w.events.subList(requests, w.events.size()))
        .noneMatch(c -> c.method().equals("answercallbackquery"));
  }

  void restart() throws Exception {
    restartBefore = w.snapshot();
    restartCommandCount = commands.size();
    accessBefore = Files.readAllBytes(w.root.resolve("allowed-users.json"));
    if (w.sentinel != null) sentinelBefore = Files.readAllBytes(w.sentinel);
    w.context.close();
    w.context = null;
    restarting = true;
    schedule = new Schedule();
    w.events.clear();
    w.telegram.resetRequests();
    w.start();
    assertThat(schedule.completed.get()).as("no scheduled pass during startup recovery").isZero();
    w.before.clear();
    w.before.putAll(restartBefore);
  }

  ProcessResult run(List<String> args, Duration timeout) {
    commands.add(List.copyOf(args));
    try {
      if (args.getFirst().equals("yt-dlp")) {
        if (PipelineWorld.stage(args).equals("metadata"))
          // --print omits absent fields: a single video has no playlist_index or collection _type.
          return new ProcessResult(
              0, "{\"title\":\"Earlier video\",\"duration\":60,\"is_live\":false}", "");
        Path source = Path.of(args.get(args.indexOf("-o") + 1).replace("%(ext)s", "m4a"));
        liveDir = source.getParent();
        if (holdDownload) {
          Files.writeString(source.resolveSibling("source.m4a.part"), "partial retained bytes");
          downloadReceived.countDown();
          assertThat(releaseDownload.await(45, TimeUnit.SECONDS)).as("download released").isTrue();
        }
        if (failDownload) return new ProcessResult(1, "", "download fixture failed");
        Files.copy(w.audio.source, source, StandardCopyOption.REPLACE_EXISTING);
      } else if (args.getFirst().equals("ffprobe")) {
        return new DefaultProcessRunner().run(args, timeout);
      } else if (args.getFirst().equals("ffmpeg")) {
        String output = args.getLast();
        Path target = Path.of(output.replace("%03d", "000"));
        Files.createDirectories(target.getParent());
        Files.copy(
            Path.of("src/it/resources/media/fixture.wav"),
            target,
            StandardCopyOption.REPLACE_EXISTING);
      } else throw new AssertionError("Unexpected process: " + args);
      return new ProcessResult(0, "", "");
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  void captureLive() throws Exception {
    assertThat(liveDir).isDirectory();
    liveBefore.clear();
    try (var paths = Files.walk(liveDir)) {
      for (Path p : paths.filter(Files::isRegularFile).toList())
        liveBefore.put(liveDir.relativize(p).toString(), Files.readAllBytes(p));
    }
    assertThat(liveBefore).isNotEmpty();
  }

  void liveUnchanged() throws Exception {
    for (var entry : liveBefore.entrySet())
      assertThat(Files.readAllBytes(liveDir.resolve(entry.getKey())))
          .containsExactly(entry.getValue());
  }

  void awaitUpload() {
    String kind = uploadMethod.equals("sendaudio") ? "AUDIO" : "TRANSCRIPT";
    PipelineWorld.until(() -> {
      try {
        assertThat(w.file(kind).path("state").asText())
            .isEqualTo(w.rejectUpload == 0 ? "SENT" : "AVAILABLE");
        assertThat(w.calls("editMessageReplyMarkup").getLast().json().toString())
            .contains(
                w.rejectUpload == 0
                    ? (kind.equals("AUDIO") ? "Audio sent" : "Transcript sent")
                    : "Download transcript");
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  ObjectNode file(ObjectNode job, String kind) {
    return (ObjectNode) job.path("files").path(kind);
  }

  void state(String kind, String state, String sent) throws Exception {
    w.change(j -> {
      file(j, kind).put("state", state);
      if (sent == null) file(j, kind).remove("sentAt");
      else file(j, kind).put("sentAt", sent);
    });
  }

  void assertState(String kind, String state, String sent) throws Exception {
    JsonNode file = w.file(kind);
    assertThat(file.path("state").asText()).isEqualTo(state);
    if (sent == null)
      assertThat(file.path("sentAt").isNull() || file.path("sentAt").isMissingNode()).isTrue();
    else if (sent.equals("nonnull"))
      assertThat(Instant.parse(file.path("sentAt").asText())).isEqualTo(w.clock.instant());
    else assertThat(file.path("sentAt").asText()).isEqualTo(sent);
  }

  void kept(String kind) throws Exception {
    JsonNode prior = PipelineWorld.JSON.readTree(w.before.get("job.json")).path("files").path(kind);
    assertThat(w.file(kind)).isEqualTo(prior);
    String name = prior.path("path").asText();
    assertThat(Files.readAllBytes(w.dir.resolve(name))).containsExactly(w.before.get(name));
  }

  static void erase(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Scheduling {
    @Bean(name = "taskScheduler")
    Schedule taskScheduler(
        @org.springframework.beans.factory.annotation.Qualifier("retentionSchedule")
            Schedule scheduler) {
      return scheduler;
    }
  }

  /**
   * Real scheduler thread invokes Spring's registered task; permits only control test
   * interleavings.
   */
  static final class Schedule extends ThreadPoolTaskScheduler {
    volatile boolean automatic;
    final Semaphore permits = new Semaphore(0);
    final Semaphore finished = new Semaphore(0);
    final AtomicInteger completed = new AtomicInteger();
    final AtomicInteger registrations = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    Schedule() {
      setThreadNamePrefix("retention-test-schedule-");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
      if (task.toString().endsWith(".retryAll"))
        return super.scheduleWithFixedDelay(task, start, delay);
      registrations.incrementAndGet();
      assertThat(delay).isEqualTo(Duration.ofMillis(100));
      return super.scheduleWithFixedDelay(
          () -> {
            try {
              if (!automatic) permits.acquire();
              task.run();
              completed.incrementAndGet();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Throwable e) {
              failure.set(e);
            } finally {
              finished.release();
            }
          },
          start,
          delay);
    }

    void pass() throws Exception {
      assertThat(registrations.get())
          .as("Spring registered exactly one fixed-delay task")
          .isEqualTo(1);
      permits.release();
      assertThat(finished.tryAcquire(15, TimeUnit.SECONDS))
          .as("registered scheduled pass completed")
          .isTrue();
      assertThat(failure.get()).as("scheduled task failure").isNull();
    }
  }
}
