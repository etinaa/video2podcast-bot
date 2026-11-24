package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** T18 fixtures observe files and HTTP; scheduler control releases Spring's registered tasks. */
final class DeferredSupport {
  static final String PARKED =
      "Service temporarily unavailable. We will send the result after it recovers.";
  static final String GAVE_UP = "We could not process this link. Please send it again later.";
  final PipelineWorld w;
  final FailureModeWorld h;
  final Map<Long, Integer> parkingNotices = new HashMap<>();
  Schedule schedule = new Schedule();
  JsonNode before;
  byte[] queueSnapshot;
  int telegramBefore, downloadsBefore, summariesBefore, probesBefore;
  long target;
  int whisperBefore, ollamaBefore, commandsBefore;
  boolean heldPass;

  DeferredSupport(PipelineWorld world, FailureModeWorld health) {
    w = world;
    h = health;
  }

  void install() {
    w.deferred = this;
    target = w.user;
  }

  Path file() {
    return w.root.resolve("deferred-jobs.json");
  }

  JsonNode entries() {
    return Files.exists(file())
        ? PipelineWorld.json(PipelineWorld.read(file())).path("jobs")
        : PipelineWorld.JSON.createArrayNode();
  }

  JsonNode entry(long user) {
    return entries()
        .valueStream()
        .filter(j -> j.path("ownerUserId").asLong() == user)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No deferred entry for " + user + ": " + entries()));
  }

  void parked(long user) {
    parked(user, w.lastUrl);
  }

  void parked(long user, String expectedUrl) {
    target = user;
    PipelineWorld.until(() -> {
      JsonNode j = entry(user);
      assertThat(j.path("url").asText()).isEqualTo(expectedUrl);
      assertThat(j.path("chatId").asLong()).isEqualTo(user);
      assertThat(j.path("statusMessageId").asInt()).isPositive();
      assertThat(j.path("id").asText()).isNotBlank();
      assertThat(j.properties().stream().map(Map.Entry::getKey))
          .containsExactlyInAnyOrder(
              "id", "url", "ownerUserId", "chatId", "statusMessageId", "attempts", "parkedAt");
      var queued =
          h.messages(user).stream().filter(e -> e.text().startsWith("Queued:")).toList().getLast();
      assertThat(j.path("statusMessageId").asInt()).isEqualTo(w.responseIds.get(queued));
    });
    h.queuedTerminal(user, PARKED);
    parkingNotices.putIfAbsent(user, telegram().size());
  }

  void remember() {
    before = entries().deepCopy();
    telegramBefore = telegram().size();
    downloadsBefore = downloads();
    summariesBefore = w.summaries().size();
    probesBefore = h.probes.size();
    whisperBefore = w.service("whisper").size();
    ollamaBefore = w.service("ollama").size();
    commandsBefore = w.commands.size();
  }

  List<PipelineWorld.Event> telegram() {
    return w.events.stream()
        .filter(e -> e.service().equals("telegram") && e.json().path("chat_id").asLong() == target)
        .toList();
  }

  int downloads() {
    return (int) w.commands.stream().filter(a -> PipelineWorld.stage(a).equals("download")).count();
  }

  void tick() throws Exception {
    w.start();
    remember();
    w.clock.now =
        w.clock.now.plus(
            Duration.parse(w.properties.getOrDefault("app.deferred.retry-interval", "PT2H")));
    schedule.pass(
        "retryAll",
        Duration.parse(w.properties.getOrDefault("app.deferred.retry-interval", "PT2H")));
    if (!heldPass) settled();
  }

  void settled() {
    PipelineWorld.until(() -> {
      for (JsonNode old : before) {
        long user = old.path("ownerUserId").asLong();
        var current =
            entries().valueStream().filter(j -> j.path("id").equals(old.path("id"))).findFirst();
        if (current.isPresent()) {
          assertThat(current.get().path("attempts").asInt())
              .isEqualTo(old.path("attempts").asInt() + 1);
        } else if (h.allowed(user)) {
          int status = old.path("statusMessageId").asInt();
          assertThat(w.calls("editMessageText"))
              .anyMatch(e ->
                  e.json().path("message_id").asInt() == status
                      && Set.of("Done.", "Failed.").contains(e.text()));
        }
      }
    });
  }

  void restoreModels() {
    if (!w.whisper.isRunning()) {
      w.whisper = WireMocks.scenarioServer(w.whisperPort, w.new Boundary("whisper"));
      w.whisper.stubFor(
          com.github.tomakehurst.wiremock.client.WireMock.any(
                  com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
              .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()));
      assertThat(w.whisper.port()).isEqualTo(w.whisperPort);
    }
    if (!w.ollama.isRunning()) {
      w.ollama = WireMocks.scenarioServer(w.ollamaPort, w.new Boundary("ollama"));
      w.ollama.stubFor(
          com.github.tomakehurst.wiremock.client.WireMock.any(
                  com.github.tomakehurst.wiremock.client.WireMock.anyUrl())
              .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()));
      assertThat(w.ollama.port()).isEqualTo(w.ollamaPort);
    }
  }

  void restart() throws Exception {
    h.settleUpdates();
    if (w.context != null) {
      w.context.close();
      w.context = null;
    }
    schedule.shutdown();
    schedule = new Schedule();
    h.logs.clear();
    h.startup = System.nanoTime();
    w.start();
    h.startupFinished();
    schedule.registration(
        "retryAll",
        Duration.parse(w.properties.getOrDefault("app.deferred.retry-interval", "PT2H")));
  }

  void summary(long user) {
    h.summary(user);
    var e =
        w.summaries().stream()
            .filter(s -> s.json().path("chat_id").asLong() == user)
            .toList()
            .getLast();
    assertThat(e.json().path("parse_mode").asText()).isEqualToIgnoringCase("HTML");
    JsonNode keys = e.json().path("reply_markup").path("inline_keyboard").get(0);
    assertThat(keys.size()).isEqualTo(2);
    assertThat(keys.get(0).path("text").asText()).isEqualTo("Download audio");
    assertThat(keys.get(1).path("text").asText()).isEqualTo("Download transcript");
    String id = PipelineAssertionSteps.callbackId(e);
    assertThat(keys.get(1).path("callback_data").asText()).isEqualTo("transcript:" + id);
    Path record = w.root.resolve("work").resolve(id).resolve("job.json");
    PipelineWorld.until(() -> assertThat(record).isRegularFile());
    var j = PipelineWorld.json(PipelineWorld.read(record));
    assertThat(j.path("ownerUserId").asLong()).isEqualTo(user);
    assertThat(j.path("chatId").asLong()).isEqualTo(user);
  }

  static final class Schedule extends ThreadPoolTaskScheduler {
    final Map<String, Registration> tasks = new ConcurrentHashMap<>();

    Schedule() {
      setThreadNamePrefix("deferred-test-scheduler-");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
      String name = task.toString().substring(task.toString().lastIndexOf('.') + 1);
      assertThat(name).isIn("retryAll", "sweep");
      assertThat(tasks.putIfAbsent(name, new Registration(task, start, delay, Instant.now())))
          .as("one Spring registration for " + name)
          .isNull();
      // Keep registration/cancellation semantics while the test owns elapsed scheduler time.
      return super.scheduleWithFixedDelay(
          () -> {}, Instant.now().plus(Duration.ofDays(365)), delay);
    }

    Registration registration(String name, Duration expected) {
      assertThat(tasks).containsKey(name);
      Registration r = tasks.get(name);
      assertThat(r.delay).isEqualTo(expected);
      assertThat(Duration.between(r.registered, r.start).minus(expected).abs())
          .isLessThan(Duration.ofSeconds(1));
      return r;
    }

    void pass(String name, Duration expected) throws Exception {
      Registration r = registration(name, expected);
      getScheduledExecutor().submit(r.task).get(15, TimeUnit.SECONDS);
    }

    record Registration(Runnable task, Instant start, Duration delay, Instant registered) {}
  }
}
