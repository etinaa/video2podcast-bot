package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.video2podcast.Video2PodcastApplication;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.job.JobFailure;
import com.video2podcast.media.DefaultProcessRunner;
import com.video2podcast.media.ProcessResult;
import com.video2podcast.media.ProcessRunner;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Scenario-owned application and external fixtures. No processing service is invoked by steps. */
public class PipelineWorld implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String TAGS = "@T8 or @T9 or @T10 or @T11 or @TD2";
  static final Duration WAIT = Duration.ofSeconds(30);
  final List<Event> events = new CopyOnWriteArrayList<>();
  final List<List<String>> commands = new CopyOnWriteArrayList<>();
  final Map<String, String> properties = new LinkedHashMap<>();
  final Map<Event, byte[]> expectedWavs = new ConcurrentHashMap<>();
  final Map<Path, byte[]> audioSnapshot = new ConcurrentHashMap<>();
  final Map<Event, Long> responseReady = new ConcurrentHashMap<>();
  final Map<Event, Integer> responseIds = new ConcurrentHashMap<>();
  final Map<String, Media> media = new ConcurrentHashMap<>();
  final Map<String, Deque<Answer>> chunkAnswers = new ConcurrentHashMap<>();
  final Deque<Answer> whisperAnswers = new ConcurrentLinkedDeque<>();
  final Deque<Answer> ollamaAnswers = new ConcurrentLinkedDeque<>();
  final List<Hold> holds = new CopyOnWriteArrayList<>();
  final AtomicInteger ids = new AtomicInteger(100);
  final AtomicInteger whispers = new AtomicInteger();
  final AtomicInteger ollamas = new AtomicInteger();
  final AtomicInteger summaryIds = new AtomicInteger();
  final AtomicInteger acceptedStatuses = new AtomicInteger();
  Event rejectedNotification;
  final MutableClock clock = new MutableClock();
  WireMockServer telegram, whisper, ollama, proxy;
  ConfigurableApplicationContext context;
  Path root;
  boolean active, allowed = true, fake, firstJobFails, holdFirstWhisper;
  long user = FunctionalTest.newUserId(), bob = FunctionalTest.newUserId();
  long lastUpdateNanos;
  int submissions;
  String lastUrl,
      title = "Example talk",
      metadata,
      failStage,
      stderr = "download failed /tmp/secret",
      missing;
  boolean timeout, splitThree;
  boolean refuseWhisperDuringWork, refuseOllamaDuringWork;
  FailureModeWorld health;
  int exitCode = 1, summaryId = 701;
  long seconds = 300;
  String extension = "m4a",
      transcript = "The budget meeting is on Tuesday. Engineering spending increased.";
  String defaultContent = "- The budget meeting is on Tuesday.\n- Engineering spending increased.";
  Answer defaultWhisper =
      Answer.json(
          "{\"text\":\"The budget meeting is on Tuesday. Engineering spending increased.\"}");
  Answer defaultOllama;
  Hold whisperHold, ollamaHold, summaryHold;
  Predicate<JsonNode> reject = n -> false;
  int withdrawalStatus = 200;
  boolean rejectSummary;
  final List<Path> seenJobs = new CopyOnWriteArrayList<>();
  Map<Path, byte[]> preserved = new HashMap<>();
  String wholeTranscript;
  final List<String> modelContents = new ArrayList<>();

  @Before(value = TAGS, order = 10)
  public void begin() throws Exception {
    active = true;
    root = Files.createTempDirectory(Path.of("target"), "pipeline-it-");
    telegram = server("telegram");
    whisper = server("whisper");
    ollama = server("ollama");
    proxy = server("media");
    telegram.stubFor(
        any(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "application/json")));
    whisper.stubFor(
        any(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "application/json")));
    ollama.stubFor(
        any(anyUrl()).willReturn(aResponse().withHeader("Content-Type", "application/json")));
  }

  WireMockServer server(String name) {
    return WireMocks.scenarioServer(new Boundary(name));
  }

  void start() {
    assertThat(active).as("pipeline scenario is active before starting its application").isTrue();
    if (context != null) return;
    FunctionalTest.applicationSettings(
            root, telegram.baseUrl(), whisper.baseUrl(), ollama.baseUrl())
        .forEach(properties::putIfAbsent);
    properties.put("spring.main.banner-mode", "off");
    properties.put("logging.level.root", "WARN");
    context =
        new SpringApplicationBuilder(Video2PodcastApplication.class, Fixtures.class)
            .initializers(c -> {
              if (health != null) health.attachLogs();
              c.getBeanFactory().registerSingleton("pipelineFixtureClock", clock);
              c.getBeanFactory()
                  .registerSingleton("pipelineFixtureProcesses", (ProcessRunner) this::run);
            })
            .run(
                properties.entrySet().stream()
                    .map(e -> "--" + e.getKey() + "=" + e.getValue())
                    .toArray(String[]::new));
    if (health != null) health.contextReady();
    else if (allowed) allow(user);
  }

  void allow(long id) {
    context.getBean(TelegramBot.class).consume(Updates.privateMessage(1, "/allow " + id));
    assertThat(calls("sendMessage"))
        .as(
            "allowlist acknowledgement reached Telegram mock %s for user %s",
            telegram.baseUrl(), id)
        .anySatisfy(e -> {
          assertThat(e.json().path("chat_id").asLong()).isEqualTo(1);
          assertThat(e.text())
              .isIn("User " + id + " allowed.", "User " + id + " already has access.");
          assertThat(responseIds).containsKey(e);
        });
    events.removeIf(e -> e.service.equals("telegram"));
  }

  void send(String text, String chatType, long sender) {
    start();
    lastUrl = text;
    lastUpdateNanos = System.nanoTime();
    if (!text.startsWith("/")) submissions++;
    context.getBean(TelegramBot.class).consume(update(sender, text, chatType));
  }

  void send(String text) {
    send(text, "private", user);
  }

  static org.telegram.telegrambots.meta.api.objects.Update update(
      long id, String text, String type) {
    var node = JSON.createObjectNode();
    node.put("update_id", 500);
    var m = node.putObject("message");
    m.put("message_id", 501);
    m.put("date", 1700000000);
    m.putObject("chat").put("id", type.equals("private") ? id : -100).put("type", type);
    m.putObject("from").put("id", id).put("is_bot", false).put("first_name", "Test");
    m.put("text", text);
    try {
      return JSON.treeToValue(node, org.telegram.telegrambots.meta.api.objects.Update.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void prop(String key, String value) {
    assertThat(context).as("configuration must precede app startup").isNull();
    properties.put(key, value);
  }

  void fixture(long duration, String ext, String url) throws Exception {
    seconds = duration;
    extension = ext;
    Path file = MediaFixtures.recording(duration, ext);
    media.put(url, new Media(file, duration, ext));
    URI uri = URI.create(url);
    String mediaPath = uri.getPath() + "." + ext;
    String html =
        "<html><head><title>"
            + escape(title)
            + "</title><meta property=\"og:title\" content=\""
            + escape(title)
            + "\">"
            + "<script type=\"application/ld+json\">"
            + stringify(
                Map.of(
                    "@context",
                    "https://schema.org",
                    "@type",
                    "VideoObject",
                    "name",
                    title,
                    "duration",
                    "PT" + duration + "S",
                    "contentUrl",
                    "http://" + uri.getHost() + mediaPath))
            + "</script></head><body></body></html>";
    proxy.stubFor(
        get(urlPathEqualTo(uri.getPath()))
            .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(html)));
    proxy.stubFor(
        get(urlPathEqualTo(mediaPath))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", ext.equals("m4a") ? "audio/mp4" : "video/mp4")
                    .withBody(Files.readAllBytes(file))));
  }

  void retitle(String t) throws Exception {
    title = t;
    for (var e : new ArrayList<>(media.entrySet()))
      fixture(e.getValue().seconds, e.getValue().ext, e.getKey());
  }

  ProcessResult run(List<String> original, Duration limit) {
    if (refuseWhisperDuringWork) {
      refuseWhisperDuringWork = false;
      whisper.stop();
    }
    if (refuseOllamaDuringWork) {
      refuseOllamaDuringWork = false;
      ollama.stop();
    }
    if (health != null) health.process(original);
    commands.add(List.copyOf(original));
    String stage = stage(original);
    try {
      if (stage.equals(failStage)) {
        if (stage.equals("download")) {
          Path dir = output(original).getParent();
          Files.createDirectories(dir);
          Files.writeString(dir.resolve("source.m4a.part"), "partial");
        }
        if (timeout) throw JobFailure.of(JobFailure.Kind.TIMEOUT);
        return new ProcessResult(exitCode, "", stderr);
      }
      if (!fake) {
        var args = new ArrayList<>(original);
        if (args.getFirst().equals("yt-dlp"))
          args.addAll(1, List.of("--proxy", proxy.baseUrl(), "--ignore-config"));
        ProcessResult result = new DefaultProcessRunner().run(args, limit);
        Files.writeString(
            root.resolve("process.log"),
            stage + "\n" + result.stdout() + "\n" + result.stderr() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        return result;
      }
      if (stage.equals("metadata"))
        return new ProcessResult(
            0,
            metadata != null ? metadata : stringify(Map.of("title", title, "duration", seconds)),
            "");
      Path out = output(original);
      Files.createDirectories(out.getParent());
      if (stage.equals("download")) {
        if (missing != null) {
          if (!missing.equals("no files"))
            Files.writeString(
                out.getParent()
                    .resolve(missing.contains(".part") ? "source.m4a.part" : "source.m4a.ytdl"),
                "partial");
        } else
          Files.copy(
              MediaFixtures.recording(1, "m4a"),
              out.getParent().resolve("source.m4a"),
              StandardCopyOption.REPLACE_EXISTING);
      } else if (stage.equals("WAV")) {
        if (!"audio.wav".equals(missing))
          Files.copy(MediaFixtures.wav(), out, StandardCopyOption.REPLACE_EXISTING);
      } else if (!"chunks".equals(missing))
        for (int i = 0; i < (splitThree ? 3 : 1); i++)
          Files.copy(
              MediaFixtures.wav(),
              out.getParent().resolve("chunk-%03d.wav".formatted(i)),
              StandardCopyOption.REPLACE_EXISTING);
      return new ProcessResult(0, "", "");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String stage(List<String> args) {
    return args.getFirst().equals("yt-dlp")
        ? (args.contains("--skip-download") ? "metadata" : "download")
        : (args.contains("-segment_time") ? "split" : "WAV");
  }

  static Path output(List<String> args) {
    return Path.of(
        args.get(args.getFirst().equals("yt-dlp") ? args.indexOf("-o") + 1 : args.size() - 1));
  }

  List<Event> calls(String method) {
    return events.stream()
        .filter(e ->
            e.service.equals("telegram")
                && e.url.toLowerCase(Locale.ROOT).endsWith("/" + method.toLowerCase(Locale.ROOT)))
        .toList();
  }

  List<Event> service(String name) {
    return events.stream().filter(e -> e.service.equals(name)).toList();
  }

  List<Event> summaries() {
    return calls("sendMessage").stream()
        .filter(e -> e.json().path("reply_markup").path("inline_keyboard").isArray())
        .toList();
  }

  Event summary() {
    until(() -> assertThat(summaries()).isNotEmpty());
    return summaries().getLast();
  }

  JsonNode record() {
    String id = PipelineAssertionSteps.callbackId(summary());
    Path file = root.resolve("work").resolve(id).resolve("job.json");
    until(() -> {
      assertThat(file).isRegularFile();
      assertThat(json(read(file)).path("summaryMessageId").isIntegralNumber()).isTrue();
    });
    return json(read(file));
  }

  List<Path> dirs() {
    Path p = root.resolve("work");
    if (!Files.exists(p)) return List.of();
    try (var s = Files.list(p)) {
      var list =
          s.filter(Files::isDirectory)
              .sorted(
                  Comparator.comparingLong(d -> {
                    try {
                      return Files.readAttributes(
                              d, java.nio.file.attribute.BasicFileAttributes.class)
                          .creationTime()
                          .toMillis();
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  }))
              .toList();
      for (Path d : list) if (!seenJobs.contains(d)) seenJobs.add(d);
      return list;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<Path> jobFiles() {
    return dirs().stream().map(d -> d.resolve("job.json")).filter(Files::isRegularFile).toList();
  }

  Path job() {
    until(() -> assertThat(dirs()).isNotEmpty());
    List<Path> current = dirs();
    return seenJobs.stream().filter(current::contains).toList().getLast();
  }

  Path publishedDir() {
    String id = record().path("jobId").asText();
    return root.resolve("work").resolve(id);
  }

  void done() {
    until(() ->
        assertThat(
                calls("editMessageText").stream()
                    .filter(e -> Set.of("Done.", "Failed.").contains(e.text()))
                    .count())
            .isGreaterThanOrEqualTo((long) submissions));
  }

  Event latestTerminal() {
    return latestStatus(Set.of("Done.", "Failed."));
  }

  Event latestStatus(Set<String> statuses) {
    List<Event> queued =
        calls("sendMessage").stream()
            .filter(e -> e.nanos >= lastUpdateNanos && e.text().startsWith("Queued:"))
            .toList();
    if (queued.isEmpty()) return null;
    Event submission = queued.getLast();
    Integer messageId = responseIds.get(submission);
    if (messageId == null) return null;
    long chat = submission.json().path("chat_id").asLong();
    return calls("editMessageText").stream()
        .filter(e ->
            e.nanos >= submission.nanos
                && e.json().path("chat_id").asLong() == chat
                && e.json().path("message_id").asInt() == messageId
                && statuses.contains(e.text()))
        .reduce((previous, current) -> current)
        .orElse(null);
  }

  Event finishOne() {
    until(() ->
        assertThat(latestTerminal())
            .as("terminal status for the latest submission %s at %s", lastUrl, telegram.baseUrl())
            .isNotNull());
    return latestTerminal();
  }

  void reply(String text) {
    until(() ->
        assertThat(calls("sendMessage").stream().map(Event::text).toList())
            .as("Telegram replies at %s for submission %s", telegram.baseUrl(), lastUrl)
            .contains(text));
  }

  void transcriptionSettled() {
    until(() ->
        assertThat(transcriptionHasSettled())
            .as(
                "transcription outcome for %s at %s; Telegram replies: %s",
                lastUrl, whisper.baseUrl(), calls("sendMessage").stream().map(Event::text).toList())
            .isTrue());
  }

  boolean transcriptionHasSettled() {
    // Summarizing is emitted only after transcription has persisted and removed temporary audio.
    return latestStatus(Set.of("Summarizing…", "Done.", "Failed.")) != null;
  }

  void noUploads() {
    assertThat(calls("sendAudio")).isEmpty();
    assertThat(calls("sendDocument")).isEmpty();
  }

  static void until(Runnable r) {
    await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).untilAsserted(r::run);
  }

  static JsonNode json(String s) {
    try {
      try {
        return JSON.readTree(s);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        return JSON.readTree(s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
      }
    } catch (Exception e) {
      throw new IllegalStateException("Invalid JSON: " + s, e);
    }
  }

  static String stringify(Object v) {
    try {
      return JSON.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] digest(Path path) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var in = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] wavDigest(Event event) {
    byte[] bytes = event.bytes;
    int at = -1;
    for (int i = 0; i < bytes.length - 12; i++)
      if (bytes[i] == 'R' && bytes[i + 1] == 'I' && bytes[i + 2] == 'F' && bytes[i + 3] == 'F') {
        at = i;
        break;
      }
    assertThat(at).as("multipart WAV header").isGreaterThanOrEqualTo(0);
    long length =
        Integer.toUnsignedLong(
                java.nio.ByteBuffer.wrap(bytes, at + 4, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .getInt())
            + 8;
    assertThat(length).isLessThanOrEqualTo(bytes.length - at);
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      digest.update(bytes, at, (int) length);
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String read(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  static String unescape(String s) {
    return s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&");
  }

  String summaryText() {
    return summary().text();
  }

  String body() {
    return unescape(summaryText().split("\n\n", 2)[1]);
  }

  static List<String> quoted(String s) {
    var m = Pattern.compile("\"([^\"]*)\"").matcher(s);
    var l = new ArrayList<String>();
    while (m.find()) l.add(m.group(1));
    return l;
  }

  Hold hold() {
    Hold h = new Hold();
    holds.add(h);
    return h;
  }

  void awaitHeld(Hold h) {
    assertThat(h).isNotNull();
    until(() -> assertThat(h.entered.getCount()).isZero());
  }

  void release(Hold h) {
    assertThat(h).isNotNull();
    h.release.countDown();
  }

  void obstacle() throws Exception {
    Path p = job().resolve("job.json");
    Files.deleteIfExists(p);
    Files.createDirectory(p);
    Files.writeString(p.resolve("obstacle"), "occupied");
  }

  void capturePreserved() throws Exception {
    preserved.clear();
    try (var s = Files.walk(root)) {
      for (Path p :
          s.filter(Files::isRegularFile)
              .filter(p ->
                  p.startsWith(root.resolve("work"))
                      || p.equals(root.resolve("allowed-users.json")))
              .toList()) preserved.put(p, digest(p));
    }
  }

  void checkPreserved() throws Exception {
    for (var e : preserved.entrySet())
      assertThat(digest(e.getKey())).as("retained file %s", e.getKey()).isEqualTo(e.getValue());
  }

  @After(value = TAGS, order = 100)
  public void end() {
    close();
  }

  @Override
  public void close() {
    active = false;
    holds.forEach(h -> h.release.countDown());
    if (context != null) {
      try {
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() ->
                calls("editMessageText").stream()
                        .filter(e -> Set.of("Done.", "Failed.").contains(e.text()))
                        .count()
                    >= acceptedStatuses.get());
      } catch (org.awaitility.core.ConditionTimeoutException e) {
        /* Preserve the original failed assertion; context.close stops any remaining worker. */
      }
      context.close();
      context = null;
    }
    List<Map<String, Object>> whisperTimings =
        whisper == null
            ? List.of()
            : whisper.getAllServeEvents().stream().map(PipelineWorld::whisperTiming).toList();
    for (var s : Arrays.asList(telegram, whisper, ollama, proxy)) if (s != null) s.stop();
    if (root != null)
      try {
        Files.writeString(
            root.resolve("events.log"),
            events.stream()
                .map(e ->
                    e.service
                        + " "
                        + e.url
                        + " "
                        + (e.service.equals("telegram")
                            ? new String(e.bytes, StandardCharsets.UTF_8)
                            : e.service.equals("whisper") ? e.chunk() : ""))
                .collect(Collectors.joining("\n")));
        Files.writeString(root.resolve("settings.json"), stringify(properties));
        Files.writeString(root.resolve("whisper-timings.json"), stringify(whisperTimings));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    active = false;
  }

  private static Map<String, Object> whisperTiming(ServeEvent serve) {
    Map<String, Object> timing = new LinkedHashMap<>();
    timing.put("receivedAt", serve.getRequest().getLoggedDateString());
    timing.put("requestBytes", serve.getRequest().getBody().length);
    timing.put("status", serve.getResponseDefinition().getStatus());
    timing.put("configuredDelayMillis", serve.getResponseDefinition().getFixedDelayMilliseconds());
    timing.put("addedDelayMillis", serve.getTiming().getAddedDelay());
    timing.put("processMillis", serve.getTiming().getProcessTime());
    timing.put("responseSendMillis", serve.getTiming().getResponseSendTime());
    timing.put("totalMillis", serve.getTiming().getTotalTime());
    return timing;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    Clock testClock(
        @org.springframework.beans.factory.annotation.Qualifier("pipelineFixtureClock")
            Clock clock) {
      return clock;
    }

    @Bean
    @Primary
    ProcessRunner testProcesses(
        @org.springframework.beans.factory.annotation.Qualifier("pipelineFixtureProcesses")
            ProcessRunner runner) {
      return runner;
    }
  }

  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-11-19T10:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  record Media(Path file, long seconds, String ext) {}

  record Event(String service, String url, byte[] bytes, long nanos, Instant time) {
    JsonNode json() {
      return PipelineWorld.json(new String(bytes, StandardCharsets.UTF_8));
    }

    String text() {
      return json().path("text").asText();
    }

    String chunk() {
      var m =
          Pattern.compile("filename=\"(chunk-\\d+\\.wav)\"")
              .matcher(new String(bytes, StandardCharsets.ISO_8859_1));
      if (!m.find()) throw new AssertionError("No chunk filename in multipart body");
      return m.group(1);
    }
  }

  static class Hold {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    void block() {
      entered.countDown();
      try {
        if (!release.await(60, TimeUnit.SECONDS))
          throw new AssertionError("Unreleased HTTP fixture");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static class Answer {
    int status = 200, delay;
    String body;
    String content;
    Hold hold;

    static Answer json(String body) {
      Answer a = new Answer();
      try {
        JSON.readTree(body);
        a.body = body;
      } catch (Exception e) {
        String escaped = body.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        try {
          JSON.readTree(escaped);
          a.body = escaped;
        } catch (Exception malformed) {
          a.body = body;
        }
      }
      return a;
    }

    static Answer failure(String kind) {
      if (kind.contains("connection"))
        throw new IllegalArgumentException(
            "Use the real closed-port fixture for connection refusal");
      Answer a = json("server diagnostic /private/jobs/secret");
      var code = Pattern.compile("status (\\d+)").matcher(kind);
      a.status = code.find() ? Integer.parseInt(code.group(1)) : 200;
      if (kind.contains("timeout")) {
        a.delay = 2000;
        a.body = "{\"text\":\"Late\"}";
      } else if (kind.contains("malformed")) a.body = "{broken";
      else if (kind.contains("missing text")) a.body = "{}";
      else if (kind.contains(" with body "))
        a.body = kind.substring(kind.indexOf(" with body ") + 11);
      else if (kind.contains("T3 fixture ")) {
        try {
          a.body =
              Files.readString(
                  Path.of(
                      "src/test/resources/fixtures",
                      kind.substring(kind.indexOf("T3 fixture ") + 11)));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return a;
    }
  }

  class Boundary
      implements ResponseDefinitionTransformerV2,
          com.github.tomakehurst.wiremock.extension.ServeEventListener {
    final String name;

    Boundary(String n) {
      name = n;
    }

    public String getName() {
      return "pipeline-" + name;
    }

    @Override
    public boolean applyGlobally() {
      return true;
    }

    @Override
    public void beforeResponseSent(
        ServeEvent serve, com.github.tomakehurst.wiremock.extension.Parameters parameters) {
      Event e =
          events.stream()
              .filter(candidate ->
                  candidate.service.equals(name)
                      && candidate.nanos == starts.getOrDefault(serve.getId(), -1L))
              .findFirst()
              .orElse(null);
      if (e != null) responseReady.put(e, System.nanoTime());
    }

    final Map<java.util.UUID, Long> starts = new ConcurrentHashMap<>();

    public ResponseDefinition transform(ServeEvent serve) {
      Request r = serve.getRequest();
      // Probes are separate from inference journals, sequences and WAV assertions.
      if (!name.equals("telegram")
          && !name.equals("media")
          && r.getMethod().getName().equals("GET")) {
        return health == null ? aResponse().withStatus(404).build() : health.probe(name, r);
      }
      Event event = new Event(name, r.getUrl(), r.getBody(), System.nanoTime(), clock.instant());
      starts.put(serve.getId(), event.nanos);
      events.add(event);
      if (name.equals("whisper")) {
        try {
          Path dir = job();
          expectedWavs.put(event, digest(dir.resolve("chunks").resolve(event.chunk())));
          if (audioSnapshot.isEmpty())
            try (var paths = Files.walk(dir)) {
              for (Path p : paths.filter(Files::isRegularFile).toList())
                audioSnapshot.put(p, digest(p));
            }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      Answer answer;
      if (name.equals("media")) return serve.getResponseDefinition();
      if (name.equals("telegram")) {
        if (health != null && r.getUrl().endsWith("/senddocument")) {
          return aResponse()
              .withHeader("Content-Type", "application/json")
              .withBody(
                  "{\"ok\":true,\"result\":{\"message_id\":900,\"date\":1700000000,\"chat\":{\"id\":101,\"type\":\"private\"}}}")
              .build();
        }
        JsonNode n = event.json();
        if (r.getUrl().endsWith("/answercallbackquery"))
          return aResponse()
              .withHeader("Content-Type", "application/json")
              .withBody("{\"ok\":true,\"result\":true}")
              .build();
        boolean send = r.getUrl().endsWith("/sendmessage");
        boolean summary = send && n.has("reply_markup");
        if (reject.test(n) || (rejectSummary && summary))
          return aResponse()
              .withStatus(500)
              .withBody("{\"ok\":false,\"description\":\"private /tmp/secret\"}")
              .build();
        if (!send && n.path("message_id").asInt() == summaryId && withdrawalStatus != 200)
          return aResponse().withStatus(withdrawalStatus).withBody("{\"ok\":false}").build();
        if (summary && summaryHold != null) summaryHold.block();
        int id = summary ? summaryId + summaryIds.getAndIncrement() : ids.incrementAndGet();
        responseIds.put(event, id);
        if (send && event.text().startsWith("Queued:")) acceptedStatuses.incrementAndGet();
        return aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(
                "{\"ok\":true,\"result\":{\"message_id\":"
                    + id
                    + ",\"date\":1700000000,\"chat\":{\"id\":"
                    + n.path("chat_id").asLong()
                    + ",\"type\":\"private\"}}}")
            .build();
      }
      if (name.equals("whisper")) {
        int number = whispers.incrementAndGet();
        if (holdFirstWhisper && whisperHold != null && number == 1) whisperHold.block();
        Deque<Answer> specific = chunkAnswers.get(event.chunk());
        answer =
            specific != null && !specific.isEmpty()
                ? specific.size() == 1 ? specific.peek() : specific.poll()
                : whisperAnswers.isEmpty() ? defaultWhisper : whisperAnswers.poll();
      } else {
        int number = ollamas.incrementAndGet();
        if (ollamaHold != null && number == 1) ollamaHold.block();
        answer = ollamaAnswers.isEmpty() ? defaultOllama : ollamaAnswers.poll();
        if (answer == null) {
          JsonNode n = event.json();
          int count = 32;
          for (var msg : n.path("messages"))
            count += (msg.path("content").asText().length() + 2) / 3;
          answer =
              Answer.json(
                  stringify(
                      Map.of(
                          "message",
                          Map.of("content", defaultContent),
                          "prompt_eval_count",
                          count,
                          "done",
                          true)));
        }
      }
      if (answer.content != null) {
        int count = 32;
        for (JsonNode msg : event.json().path("messages"))
          count += (msg.path("content").asText().length() + 2) / 3;
        answer =
            Answer.json(
                stringify(
                    Map.of(
                        "message",
                        Map.of("content", answer.content),
                        "prompt_eval_count",
                        count,
                        "done",
                        true)));
      }
      if (answer.hold != null) answer.hold.block();
      return aResponse()
          .withStatus(answer.status)
          .withFixedDelay(answer.delay)
          .withHeader("Content-Type", "application/json")
          .withBody(answer.body == null ? "" : answer.body)
          .build();
    }
  }
}
