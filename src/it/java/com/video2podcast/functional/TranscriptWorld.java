package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.video2podcast.Video2PodcastApplication;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.media.ProcessRunner;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.telegram.telegrambots.meta.api.objects.Update;

/** T12 fixtures use Telegram HTTP, updates and files only; no delivery service is called. */
public class TranscriptWorld implements AutoCloseable {
  static final String JOB = "11111111-1111-4111-8111-111111111111";
  static final Duration WAIT = Duration.ofSeconds(15);
  final PipelineWorld.MutableClock clock = new PipelineWorld.MutableClock();
  final List<Call> events = new CopyOnWriteArrayList<>();
  final List<Future<?>> pending = new ArrayList<>();
  final ExecutorService updates = Executors.newVirtualThreadPerTaskExecutor();
  final AtomicInteger callbackIds = new AtomicInteger();
  final AtomicInteger uploads = new AtomicInteger();
  final AtomicInteger edits = new AtomicInteger();
  final Set<Long> allowed = new HashSet<>();
  final CountDownLatch uploadReceived = new CountDownLatch(1);
  final CountDownLatch releaseUpload = new CountDownLatch(1);
  final Map<String, byte[]> before = new TreeMap<>();
  WireMockServer telegram, whisper, ollama;
  ConfigurableApplicationContext context;
  Path root, dir, sentinel;
  boolean active, hold, drop, rejectAnswer;
  int rejectUpload, rejectEdit, tapStart;
  long user = 101, admin = 999;
  String originalTitle = "Budget meeting";
  byte[] transcriptBytes;
  ObjectNode initial;
  Instant uploadSuccess;
  String lastCallback;

  @Before(value = "@T12", order = 10)
  public void begin() throws Exception {
    active = true;
    root = Files.createTempDirectory(Path.of("target"), "transcript-it-");
    dir = Files.createDirectories(root.resolve("work").resolve(JOB));
    telegram = WireMocks.scenarioServer(new Boundary());
    whisper = WireMocks.scenarioServer();
    ollama = WireMocks.scenarioServer();
    telegram.stubFor(
        any(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "application/json")));
    clock.now = Instant.parse("2026-11-19T12:30:00Z");
    initial = PipelineWorld.JSON.createObjectNode();
    initial
        .put("jobId", JOB)
        .put("ownerUserId", 101)
        .put("chatId", 101)
        .put("summaryMessageId", 701)
        .put("title", originalTitle)
        .put("durationSeconds", 300)
        .put("createdAt", "2026-11-19T12:00:00Z");
    ObjectNode files = initial.putObject("files");
    for (String kind : List.of("AUDIO", "TRANSCRIPT")) {
      files
          .putObject(kind)
          .put("path", kind.equals("AUDIO") ? "source.m4a" : "transcript.txt")
          .put("state", "AVAILABLE")
          .put("expiresAt", "2026-11-19T13:00:00Z")
          .remove("sentAt");
    }
    allowed.add(user);
    write(initial);
    Files.write(dir.resolve("source.m4a"), new byte[] {1, 2, 3, 4});
    transcriptBytes = "The budget meeting is on Tuesday.\n".getBytes(StandardCharsets.UTF_8);
    Files.write(dir.resolve("transcript.txt"), transcriptBytes);
  }

  void start() {
    if (context != null) return;
    Map<String, String> props =
        new LinkedHashMap<>(
            FunctionalTest.applicationSettings(
                root, telegram.baseUrl(), whisper.baseUrl(), ollama.baseUrl()));
    props.put("app.telegram.admin-user-id", Long.toString(admin));
    props.put("spring.main.banner-mode", "off");
    props.put("logging.level.root", "WARN");
    // Initializer registers only boundary fixtures, leaving all delivery wiring real.
    context =
        new SpringApplicationBuilder(Video2PodcastApplication.class, Fixtures.class)
            .initializers(
                c -> c.getBeanFactory().registerSingleton("transcriptFixtureClock", clock))
            .run(
                props.entrySet().stream()
                    .map(e -> "--" + e.getKey() + "=" + e.getValue())
                    .toArray(String[]::new));
    for (long id : allowed) bot().consume(Updates.privateMessage(admin, "/allow " + id));
    events.clear();
    telegram.resetRequests();
  }

  TelegramBot bot() {
    return context.getBean(TelegramBot.class);
  }

  void write(ObjectNode job) throws Exception {
    Files.writeString(dir.resolve("job.json"), PipelineWorld.JSON.writeValueAsString(job));
  }

  ObjectNode job() throws Exception {
    return (ObjectNode) PipelineWorld.JSON.readTree(Files.readAllBytes(dir.resolve("job.json")));
  }

  ObjectNode file(String kind) throws Exception {
    return (ObjectNode) job().path("files").path(kind);
  }

  void change(java.util.function.Consumer<ObjectNode> edit) throws Exception {
    ObjectNode j = job();
    edit.accept(j);
    write(j);
  }

  Map<String, byte[]> snapshot() throws Exception {
    Map<String, byte[]> result = new TreeMap<>();
    if (Files.exists(dir))
      try (var paths = Files.walk(dir)) {
        for (Path p : paths.filter(Files::isRegularFile).toList())
          result.put(dir.relativize(p).toString(), Files.readAllBytes(p));
      }
    return result;
  }

  void unchanged() throws Exception {
    sameFiles(before, snapshot());
  }

  static void sameFiles(Map<String, byte[]> expected, Map<String, byte[]> actual) {
    assertThat(actual.keySet()).isEqualTo(expected.keySet());
    expected.forEach((name, bytes) -> assertThat(actual.get(name)).as(name).containsExactly(bytes));
  }

  void tap(String data, long sender, int message, long chat, String type) throws Exception {
    start();
    before.clear();
    before.putAll(snapshot());
    tapStart = events.size();
    lastCallback = "transcript-tap-" + callbackIds.incrementAndGet();
    ObjectNode node = PipelineWorld.JSON.createObjectNode();
    node.put("update_id", callbackIds.get());
    ObjectNode callback = node.putObject("callback_query");
    callback.put("id", lastCallback).put("chat_instance", "1").put("data", data);
    callback.putObject("from").put("id", sender).put("is_bot", false).put("first_name", "Tester");
    callback
        .putObject("message")
        .put("message_id", message)
        .put("date", 1700000000)
        .putObject("chat")
        .put("id", chat)
        .put("type", type);
    Update update = PipelineWorld.JSON.treeToValue(node, Update.class);
    boolean awaitFirstUpload = hold && releaseUpload.getCount() != 0 && pending.isEmpty();
    Future<?> task = updates.submit(() -> bot().consume(update));
    pending.add(task);
    if (awaitFirstUpload) {
      assertThat(uploadReceived.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
          .as("upload reached held HTTP response")
          .isTrue();
    } else task.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  void finishUpload() throws Exception {
    releaseUpload.countDown();
    for (Future<?> task : pending) task.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  List<Call> calls(String method) {
    return events.stream().filter(c -> c.method.equals(method.toLowerCase(Locale.ROOT))).toList();
  }

  List<Call> tapCalls() {
    return List.copyOf(events.subList(tapStart, events.size()));
  }

  void refusal(String text) {
    assertThat(tapCalls())
        .singleElement()
        .satisfies(c -> {
          assertThat(c.method).isEqualTo("answercallbackquery");
          assertThat(c.json().path("text").asText()).isEqualTo(text);
          assertThat(c.json().path("callback_query_id").asText()).isEqualTo(lastCallback);
        });
  }

  void state(String expected, boolean sent) throws Exception {
    JsonNode f = file("TRANSCRIPT");
    assertThat(f.path("state").asText()).isEqualTo(expected);
    assertThat(f.path("sentAt").isMissingNode() || f.path("sentAt").isNull()).isEqualTo(!sent);
    if (sent) {
      Instant at = Instant.parse(f.path("sentAt").asText());
      assertThat(at).isEqualTo(clock.instant());
      if (uploadSuccess != null) assertThat(at).isAfterOrEqualTo(uploadSuccess);
    }
  }

  void noModels() {
    assertThat(whisper.getAllServeEvents()).isEmpty();
    assertThat(ollama.getAllServeEvents()).isEmpty();
  }

  JsonNode keyboard(Call call) {
    return call.json().path("reply_markup").path("inline_keyboard");
  }

  void labels(Call call, String audio, String transcript) {
    JsonNode rows = keyboard(call);
    assertThat(rows.size()).isEqualTo(1);
    assertThat(rows.get(0).size()).isEqualTo(2);
    assertThat(rows.get(0).get(0).path("text").asText()).isEqualTo(audio);
    assertThat(rows.get(0).get(1).path("text").asText()).isEqualTo(transcript);
    for (int i = 0; i < 2; i++) {
      String data = rows.get(0).get(i).path("callback_data").asText();
      assertThat(data).isEqualTo((i == 0 ? "audio:" : "transcript:") + JOB);
      assertThat(data.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
    }
  }

  void multipart(String filename, long chat) {
    Request r = calls("sendDocument").getLast().request;
    assertThat(r.isMultipart()).isTrue();
    assertThat(r.getPart("chat_id").getBody().asString()).isEqualTo(Long.toString(chat));
    // Telegram's Java client uses a document=attach://<part-name> reference.
    String reference = r.getPart("document").getBody().asString();
    assertThat(reference).startsWith("attach://");
    var document = r.getPart(reference.substring("attach://".length()));
    assertThat(document).as("document attachment %s", reference).isNotNull();
    assertThat(document.getHeader("Content-Disposition").firstValue())
        .contains("filename=\"" + filename + "\"");
    assertThat(document.getBody().asBytes()).containsExactly(transcriptBytes);
    assertThat(r.getPart("caption")).isNull();
  }

  @After(value = "@T12", order = 100)
  public void close() {
    releaseUpload.countDown();
    updates.shutdown();
    try {
      if (!updates.awaitTermination(20, TimeUnit.SECONDS)) updates.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      updates.shutdownNow();
    }
    if (context != null) context.close();
    for (WireMockServer server : Arrays.asList(telegram, whisper, ollama))
      if (server != null) server.stop();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    Clock transcriptClock(
        @org.springframework.beans.factory.annotation.Qualifier("transcriptFixtureClock")
            Clock transcriptFixtureClock) {
      return transcriptFixtureClock;
    }

    @Bean
    @Primary
    ProcessRunner noMediaForTranscript() {
      return (command, timeout) -> {
        throw new AssertionError("Transcript tap invoked a media process: " + command);
      };
    }
  }

  record Call(String method, Request request, JsonNode persisted) {
    JsonNode json() {
      return PipelineWorld.json(request.getBodyAsString());
    }
  }

  class Boundary implements ResponseDefinitionTransformerV2 {
    public String getName() {
      return "transcript-boundary";
    }

    public boolean applyGlobally() {
      return true;
    }

    public ResponseDefinition transform(ServeEvent serve) {
      Request request = serve.getRequest();
      String method =
          request
              .getUrl()
              .substring(request.getUrl().lastIndexOf('/') + 1)
              .toLowerCase(Locale.ROOT);
      JsonNode persisted;
      try {
        persisted = job();
      } catch (Exception e) {
        persisted = null;
      }
      events.add(new Call(method, request, persisted));
      if (method.equals("senddocument")) {
        int attempt = uploads.incrementAndGet();
        uploadReceived.countDown();
        if (hold)
          try {
            if (!releaseUpload.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
              throw new AssertionError("Held upload was never released");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        if (drop) return aResponse().withFault(Fault.EMPTY_RESPONSE).build();
        if (attempt == rejectUpload) return rejected();
        // Mark the success boundary with application time, distinct from claim time.
        clock.now = clock.instant().plusSeconds(1);
        uploadSuccess = clock.instant();
      }
      if (method.equals("answercallbackquery") && rejectAnswer) return rejected();
      if (method.equals("editmessagereplymarkup") && edits.incrementAndGet() == rejectEdit)
        return rejected();
      if (method.equals("answercallbackquery"))
        return aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("{\"ok\":true,\"result\":true}")
            .build();
      return aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(FunctionalTest.MESSAGE_OK)
          .build();
    }

    ResponseDefinition rejected() {
      return aResponse()
          .withStatus(400)
          .withHeader("Content-Type", "application/json")
          .withBody(
              "{\"ok\":false,\"error_code\":400,\"description\":\"transport diagnostic /private/jobs/secret\"}")
          .build();
    }
  }
}
