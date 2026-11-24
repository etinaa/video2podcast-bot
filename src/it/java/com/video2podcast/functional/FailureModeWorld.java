package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.video2podcast.bot.TelegramBot;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** T16 fixtures control HTTP, process and durable-file boundaries, never processing services. */
public class FailureModeWorld {
  static final String WHISPER = "The transcription server is unreachable.";
  static final String OLLAMA = "The summary server is unreachable.";
  final PipelineWorld w;
  final Map<String, Policy> policies = new ConcurrentHashMap<>();
  final List<Probe> probes = new CopyOnWriteArrayList<>();
  final List<Tool> tools = new CopyOnWriteArrayList<>();
  final List<Log> logs = new CopyOnWriteArrayList<>();
  final Map<Path, byte[]> snapshots = new LinkedHashMap<>();
  // Match Telegram's single polling consumer: a blocked update must also delay later taps/messages.
  final ExecutorService updates = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
  final List<Future<?>> pendingUpdates = new CopyOnWriteArrayList<>();
  final Logger logger = (Logger) LoggerFactory.getLogger("com.video2podcast.job.HealthChecks");
  final AppenderBase<ILoggingEvent> appender =
      new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent e) {
          logs.add(
              new Log(e.getFormattedMessage(), e.getThrowableProxy() != null, System.nanoTime()));
        }
      };
  long startup, observation, callbackStart;
  long replyTime;
  String callbackId;
  boolean expectUnavailableStartup;
  Path retained;
  PipelineWorld.Hold held;
  String heldService;
  byte[] accessSnapshot;

  public FailureModeWorld(PipelineWorld world) {
    w = world;
  }

  @Before(value = "@T16", order = 10)
  public void begin() throws Exception {
    w.begin();
    w.health = this;
    w.fake = true;
    w.user = 202;
    w.bob = 203;
    for (String service : List.of("whisper", "ollama")) policies.put(service, new Policy());
    // Spring initializes logging again for each fresh application. Attach after that reset.
    w.properties.put("logging.level.com.video2podcast.job.HealthChecks", "INFO");
    startup = System.nanoTime();
  }

  void attachLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @After(value = "@T16 or @T18", order = 110)
  public void close() throws Exception {
    try {
      w.holds.forEach(h -> h.release.countDown());
      for (Future<?> f : pendingUpdates) f.get(15, TimeUnit.SECONDS);
      if (w.context != null) PipelineWorld.until(() -> assertThat(logs).hasSize(2));
    } finally {
      updates.shutdownNow();
      w.close();
      logger.detachAppender(appender);
      appender.stop();
      Files.writeString(w.root.resolve("health-events.txt"), probes + "\n" + logs);
    }
  }

  ResponseDefinition probe(String name, Request request) {
    Policy policy = policies.get(name);
    Probe event = new Probe(name, request.getUrl(), request.getBody(), System.nanoTime());
    probes.add(event);
    PipelineWorld.Hold hold = policy.hold;
    if (hold != null && (!policy.once || policy.claim.compareAndSet(false, true))) hold.block();
    event.ready = System.nanoTime();
    if (policy.refused) return aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER).build();
    return aResponse().withStatus(policy.status).withBody(policy.body).build();
  }

  void process(List<String> args) {
    tools.add(new Tool(List.copyOf(args), System.nanoTime()));
  }

  Duration timeout() {
    return Duration.parse(w.properties.getOrDefault("app.health.timeout", "PT3S"));
  }

  void configureTimeout(String timeout) {
    if (w.context != null) {
      w.context.close();
      w.context = null;
    }
    w.properties.put("app.health.timeout", timeout);
    logs.clear();
    startup = System.nanoTime();
    w.start();
    startupFinished();
  }

  void contextReady() {
    if (w.allowed && (!Files.exists(w.root.resolve("allowed-users.json")) || !allowed(w.user)))
      w.context.getBean(TelegramBot.class).consume(Updates.privateMessage(1, "/allow " + w.user));
  }

  void start() {
    if (w.context == null) startup = System.nanoTime();
    w.start();
  }

  void startupFinished() {
    start();
    PipelineWorld.until(() -> assertThat(logs).hasSize(2));
  }

  void next(String service, String response) {
    startupFinished();
    policy(service, response);
    observation = System.nanoTime();
  }

  void policy(String service, String response) {
    Policy p = new Policy();
    p.refused = response.equals("connection refusal");
    if (response.startsWith("HTTP ")) p.status = Integer.parseInt(response.substring(5));
    policies.put(service.toLowerCase(Locale.ROOT), p);
  }

  void hold(String service, boolean once, boolean startupCheck) {
    if (!startupCheck) startupFinished();
    Policy p = new Policy();
    p.hold = w.hold();
    p.once = once;
    policies.put(service, p);
    held = p.hold;
    heldService = service;
    observation = System.nanoTime();
  }

  void send(long user, String text) {
    start();
    if (!text.startsWith("/")) {
      w.lastUrl = text;
      w.lastUpdateNanos = System.nanoTime();
      w.submissions++;
    }
    pendingUpdates.add(
        updates.submit(() ->
            w.context.getBean(TelegramBot.class).consume(Updates.privateMessage(user, text))));
  }

  void settleUpdates() throws Exception {
    for (Future<?> f : pendingUpdates) f.get(15, TimeUnit.SECONDS);
  }

  List<PipelineWorld.Event> messages(long user) {
    return w.calls("sendMessage").stream()
        .filter(e -> e.json().path("chat_id").asLong() == user)
        .toList();
  }

  PipelineWorld.Event message(long user, String text) {
    PipelineWorld.until(
        () -> assertThat(messages(user).stream().filter(e -> e.text().equals(text))).isNotEmpty());
    return messages(user).stream()
        .filter(e -> e.text().equals(text))
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  List<PipelineWorld.Event> failures(long user) {
    return messages(user).stream()
        .filter(e -> e.text().contains("server is unreachable."))
        .toList();
  }

  List<Probe> since(String name, long time) {
    return probes.stream().filter(p -> p.service.equals(name) && p.received >= time).toList();
  }

  void awaitProbe() {
    w.awaitHeld(held);
  }

  long firstStartupProbeReceived() {
    return probes.stream()
        .filter(p -> p.received >= startup)
        .mapToLong(p -> p.received)
        .min()
        .orElseThrow();
  }

  void queuedTerminal(long user, String text) {
    PipelineWorld.until(() -> {
      var queued =
          messages(user).stream()
              .filter(e -> e.text().startsWith("Queued:"))
              .reduce((a, b) -> b)
              .orElseThrow();
      Integer message = w.responseIds.get(queued);
      assertThat(message).isNotNull();
      assertThat(w.calls("editMessageText"))
          .anySatisfy(e -> {
            assertThat(e.json().path("chat_id").asLong()).isEqualTo(user);
            assertThat(e.json().path("message_id").asInt()).isEqualTo(message);
            assertThat(e.text()).isEqualTo(text);
            assertThat(e.nanos()).isGreaterThan(queued.nanos());
          });
    });
  }

  void pendingAt(long time, boolean startupCheck) {
    assertThat(held.release.getCount()).isEqualTo(1);
    Probe p = since(heldService, observation).getFirst();
    assertThat(p.ready).as("held HTTP response has not completed").isZero();
    assertThat(time).isGreaterThanOrEqualTo(p.received);
    // Startup probe deadlines exclude Spring boot. Per-job checks retain the update boundary.
    long began = startupCheck ? firstStartupProbeReceived() : w.lastUpdateNanos;
    assertThat(time - began).isLessThan(timeout().toNanos());
    if (startupCheck) assertThat(logs).isEmpty();
    else assertThat(failures(w.user)).isEmpty();
  }

  void terminal(long user, String text) {
    PipelineWorld.until(() ->
        assertThat(
                w.calls("editMessageText").stream()
                    .filter(
                        e -> e.json().path("chat_id").asLong() == user && e.text().equals(text)))
            .isNotEmpty());
  }

  void rejected() {
    queuedTerminal(w.user, DeferredSupport.PARKED);
    assertThat(failures(w.user)).isEmpty();
  }

  void noWork(String url) {
    assertThat(tools).noneMatch(t -> t.args.contains(url));
  }

  void noRejectedDirectory() {
    PipelineWorld.until(() -> {
      Path queue = w.root.resolve("deferred-jobs.json");
      assertThat(queue).isRegularFile();
      var entries =
          PipelineWorld.json(PipelineWorld.read(queue))
              .path("jobs")
              .valueStream()
              .filter(j -> j.path("ownerUserId").asLong() == w.user)
              .toList();
      assertThat(entries).isNotEmpty();
      var entry = entries.getFirst();
      assertThat(w.calls("editMessageText"))
          .anyMatch(e ->
              e.json().path("chat_id").asLong() == entry.path("chatId").asLong()
                  && e.json().path("message_id").asInt() == entry.path("statusMessageId").asInt()
                  && e.text().equals(DeferredSupport.PARKED));
    });
    Set<Path> legitimate = new HashSet<>();
    if (retained != null) legitimate.add(retained);
    for (var summary : w.summaries()) {
      String data =
          summary
              .json()
              .path("reply_markup")
              .path("inline_keyboard")
              .get(0)
              .get(0)
              .path("callback_data")
              .asText();
      if (data.startsWith("audio:"))
        legitimate.add(w.root.resolve("work").resolve(data.substring(6)));
    }
    assertThat(w.dirs()).containsExactlyInAnyOrderElementsOf(legitimate);
  }

  void summary(long user) {
    terminal(user, "Done.");
    assertThat(messages(user).stream().filter(e -> e.json().has("reply_markup"))).hasSize(1);
  }

  JsonNode access() {
    return PipelineWorld.json(PipelineWorld.read(w.root.resolve("allowed-users.json")));
  }

  JsonNode request() {
    return access()
        .path("pending")
        .valueStream()
        .filter(n -> n.path("id").asLong() == 303)
        .findFirst()
        .orElseThrow();
  }

  boolean allowed(long id) {
    return access().path("users").valueStream().anyMatch(n -> n.path("id").asLong() == id);
  }

  List<PipelineWorld.Event> notifications() {
    return messages(1).stream().filter(e -> e.text().startsWith("Access request from ")).toList();
  }

  void requestAccess(boolean reject) throws Exception {
    start();
    if (reject)
      w.reject =
          n ->
              n.path("chat_id").asLong() == 1
                  && n.path("text").asText().startsWith("Access request from ");
    // Arrange the externally assigned message id before /start is submitted.
    w.ids.set(700);
    send(303, "/start");
    settleUpdates();
    assertThat(request().path("id").asLong()).isEqualTo(303);
  }

  void callback(long sender, String data, int message) throws Exception {
    start();
    callbackStart = System.nanoTime();
    callbackId = "t16-" + callbackStart;
    Future<?> update =
        updates.submit(() ->
            w.context
                .getBean(TelegramBot.class)
                .consume(Updates.callback(sender, callbackId, data, message)));
    pendingUpdates.add(update);
    update.get(15, TimeUnit.SECONDS);
  }

  void restart() {
    if (w.context != null) {
      startupFinished();
      w.context.close();
    }
    w.context = null;
    w.events.clear();
    logs.clear();
    probes.clear();
    startup = System.nanoTime();
    start();
  }

  void seedRequest() throws Exception {
    Files.writeString(
        w.root.resolve("allowed-users.json"),
        """
        {"users":[],"pending":[{"id":303,"username":"user303","displayName":"Test","requestedAt":"2026-11-19T10:00:00Z","requestMessageId":null}],"denials":[]}
        """);
  }

  void retained() throws Exception {
    start();
    w.allow(101);
    retained = Files.createDirectories(w.root.resolve("work").resolve(TranscriptWorld.JOB));
    Files.writeString(retained.resolve("source.m4a"), "retained audio");
    Files.writeString(retained.resolve("transcript.txt"), "retained transcript\n");
    Files.writeString(
        retained.resolve("job.json"),
        PipelineWorld.stringify(
            Map.of(
                "jobId",
                TranscriptWorld.JOB,
                "ownerUserId",
                101,
                "chatId",
                101,
                "summaryMessageId",
                701,
                "title",
                "Retained",
                "durationSeconds",
                60,
                "createdAt",
                w.clock.instant().toString(),
                "files",
                Map.of("AUDIO", file("source.m4a"), "TRANSCRIPT", file("transcript.txt")))));
  }

  Map<String, Object> file(String name) {
    return Map.of(
        "path",
        name,
        "state",
        "AVAILABLE",
        "expiresAt",
        w.clock.instant().plusSeconds(3600).toString());
  }

  static final class Policy {
    int status = 200;
    String body = "";
    boolean refused, once;
    PipelineWorld.Hold hold;
    final java.util.concurrent.atomic.AtomicBoolean claim =
        new java.util.concurrent.atomic.AtomicBoolean();
  }

  static final class Probe {
    final String service, path;
    final byte[] body;
    final long received;
    volatile long ready;

    Probe(String service, String path, byte[] body, long received) {
      this.service = service;
      this.path = path;
      this.body = body;
      this.received = received;
    }

    @Override
    public String toString() {
      return service + " " + path + " " + received + " " + ready;
    }
  }

  record Tool(List<String> args, long nanos) {}

  record Log(String text, boolean throwable, long nanos) {}
}
