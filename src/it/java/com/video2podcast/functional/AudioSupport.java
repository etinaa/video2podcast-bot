package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.video2podcast.job.JobFailure;
import com.video2podcast.media.DefaultProcessRunner;
import com.video2podcast.media.ProcessResult;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** T13 process/HTTP boundary fixtures. All successful encodes use real ffmpeg. */
final class AudioSupport implements AutoCloseable {
  static final long LIMIT = 50L * 1024 * 1024;
  final TranscriptWorld w;
  final List<List<String>> encodes = new CopyOnWriteArrayList<>();
  final CountDownLatch whisperReceived = new CountDownLatch(1);
  final CountDownLatch releaseWhisper = new CountDownLatch(1);
  final WireMockServer media;
  volatile boolean queued;
  int deliveryStart;
  String fault = "";
  long firstSize = LIMIT + 1;
  byte[] encodedBytes;
  Path source;

  AudioSupport(TranscriptWorld world) throws Exception {
    w = world;
    media = WireMocks.scenarioServer();
    w.whisper.stop();
    w.ollama.stop();
    w.whisper = WireMocks.scenarioServer(new Models(true));
    w.ollama = WireMocks.scenarioServer(new Models(false));
    w.whisper.stubFor(any(anyUrl()).willReturn(aResponse()));
    w.ollama.stubFor(any(anyUrl()).willReturn(aResponse()));
    source("source.m4a", false, 0);
  }

  void source(String name, boolean oversized, long padded) throws Exception {
    assertThat(w.context).as("source fixture before startup").isNull();
    source = w.dir.resolve(name);
    Path fixture = fixture(name, oversized);
    Files.copy(fixture, source, StandardCopyOption.REPLACE_EXISTING);
    if (padded != 0) resize(source, padded);
    w.change(j -> ((ObjectNode) j.path("files").path("AUDIO")).put("path", name));
    assertThat(probe(source).path("streams").size()).isGreaterThan(0);
    if (oversized) assertThat(Files.size(source)).isGreaterThan(LIMIT);
  }

  void incompatibleSource(String name, String codec, String container, int channels)
      throws Exception {
    assertThat(w.context).as("source fixture before startup").isNull();
    source = w.dir.resolve(name);
    MediaFixtures.run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=48000",
            "-t",
            "60",
            "-c:a",
            codec,
            "-ac",
            Integer.toString(channels),
            "-b:a",
            "64k",
            "-f",
            container,
            source.toString()));
    w.change(j -> ((ObjectNode) j.path("files").path("AUDIO")).put("path", name));
  }

  static synchronized Path fixture(String name, boolean large) throws Exception {
    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    Path dir = Files.createDirectories(Path.of("target", "audio-fixtures"));
    Path target = dir.resolve((large ? "large" : "short") + "." + ext);
    if (Files.exists(target)) return target;
    var args =
        new ArrayList<>(
            List.of(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=" + (large ? "192000" : "44100")));
    if (ext.equals("mp4"))
      args.addAll(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "color=c=black:s=16x16:r=1",
              "-c:v",
              "libx264",
              "-pix_fmt",
              "yuv420p"));
    args.addAll(
        List.of(
            "-t",
            "60",
            "-ac",
            "2",
            "-c:a",
            ext.equals("wav")
                ? "pcm_s32le"
                : ext.equals("webm") ? "libopus" : ext.equals("mp3") ? "libmp3lame" : "aac"));
    if (!ext.equals("wav")) args.addAll(List.of("-b:a", "64k"));
    Path temporary = Files.createTempFile(dir, "generating-", "." + ext);
    args.add(temporary.toString());
    try {
      MediaFixtures.run(args);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void resize(Path file, long size) throws java.io.IOException {
    try (var f = new java.io.RandomAccessFile(file.toFile(), "rw")) {
      f.setLength(size);
    }
  }

  static JsonNode probe(Path file, String... options) {
    var args =
        new ArrayList<>(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-show_streams",
                "-show_format",
                "-of",
                "json",
                file.toString()));
    args.addAll(1, List.of(options));
    ProcessResult r = new DefaultProcessRunner().run(args, Duration.ofSeconds(15));
    assertThat(r.exitCode()).as(r.stderr()).isZero();
    return PipelineWorld.json(r.stdout());
  }

  ProcessResult run(List<String> original, Duration timeout) {
    try {
      var args = new ArrayList<>(original);
      if (args.getFirst().equals("yt-dlp")) {
        args.addAll(1, List.of("--proxy", media.baseUrl(), "--ignore-config"));
      }
      boolean delivery = args.getFirst().equals("ffmpeg") && args.getLast().endsWith("out.m4a");
      if (delivery) {
        encodes.add(List.copyOf(args));
        Path out = Path.of(args.getLast());
        if (!fault.isEmpty()) {
          if (fault.equals("timeout")) throw JobFailure.of(JobFailure.Kind.TIMEOUT);
          if (fault.equals("partial") || (fault.equals("once") && encodes.size() == 1)) {
            Files.writeString(out, "partial secret /private/jobs");
            return new ProcessResult(1, "", "secret stderr /private/jobs");
          }
          if (fault.equals("absent")) return new ProcessResult(0, "", "");
          if (fault.equals("empty")) {
            Files.write(out, new byte[0]);
            return new ProcessResult(0, "", "");
          }
          boolean oversized =
              fault.equals("always-large")
                  || (fault.equals("retry") && encodes.size() == 1)
                  || (fault.equals("conversion") && args.get(args.indexOf("-ac") + 1).equals("2"));
          if (oversized) {
            Files.writeString(out, "oversized fixture");
            resize(out, firstSize);
          } else {
            // Following a forced overflow, generate the successful second output with real ffmpeg.
            ProcessResult result = new DefaultProcessRunner().run(args, timeout);
            if (result.exitCode() == 0) encodedBytes = Files.readAllBytes(out);
            return result;
          }
          return new ProcessResult(0, "", "");
        }
      }
      return new DefaultProcessRunner().run(args, timeout);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  void afterTap(String data) {
    if (!data.startsWith("audio:")) return;
    boolean granted =
        w.tapCalls().stream()
            .anyMatch(c ->
                c.method().equals("answercallbackquery")
                    && c.json().path("text").asText().equals("Preparing audio…"));
    if (granted) deliveryStart = w.tapStart;
    if (granted && !w.hold && !queued) awaitDelivery();
  }

  void awaitDelivery() {
    PipelineWorld.until(() -> {
      boolean finished =
          w.events.stream()
              .skip(deliveryStart)
              .anyMatch(c ->
                  (c.method().equals("editmessagereplymarkup")
                          && c.json().toString().contains("Audio sent"))
                      || (c.method().equals("sendmessage")
                          && c.json()
                              .path("text")
                              .asText()
                              .equals("Could not send the audio. Tap the button to try again.")));
      if (noSummary()) finished = w.uploadSuccess != null && readState().equals("SENT");
      assertThat(finished).as("terminal audio notification").isTrue();
      assertThat(w.dir.resolve("out.m4a")).doesNotExist();
    });
  }

  boolean noSummary() {
    try {
      JsonNode id = w.job().path("summaryMessageId");
      return id.isNull() || id.isMissingNode();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  String readState() {
    try {
      return w.file("AUDIO").path("state").asText();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  byte[] upload() {
    var r = w.calls("sendAudio").getLast().request();
    assertThat(r.isMultipart()).isTrue();
    String ref = r.getPart("audio").getBody().asString();
    assertThat(ref).startsWith("attach://");
    return r.getPart(ref.substring(9)).getBody().asBytes();
  }

  String part(String name) {
    return w.calls("sendAudio").getLast().request().getPart(name).getBody().asString();
  }

  JsonNode uploadedMedia() throws Exception {
    Path captured = w.root.resolve("captured-upload.m4a");
    Files.write(captured, upload());
    return probe(captured);
  }

  void earlierJob() throws Exception {
    queued = true;
    byte[] bytes = Files.readAllBytes(fixture("source.m4a", false));
    String page =
        "<html><head><title>Earlier video</title>"
            + "<script type=\"application/ld+json\">"
            + PipelineWorld.stringify(
                Map.of(
                    "@context",
                    "https://schema.org",
                    "@type",
                    "VideoObject",
                    "name",
                    "Earlier video",
                    "duration",
                    "PT60S",
                    "contentUrl",
                    "http://videos.example/earlier.m4a"))
            + "</script></head><body></body></html>";
    media.stubFor(
        get(urlPathEqualTo("/earlier"))
            .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(page)));
    media.stubFor(
        get(urlPathEqualTo("/earlier.m4a"))
            .willReturn(aResponse().withHeader("Content-Type", "audio/mp4").withBody(bytes)));
    w.allowed.add(202L);
    w.start();
    if (w.retention != null) w.bot().consume(Updates.privateMessage(w.admin, "/allow 202"));
    w.bot().consume(Updates.privateMessage(202, "http://videos.example/earlier"));
    assertThat(whisperReceived.await(30, TimeUnit.SECONDS))
        .as("earlier job reached Whisper")
        .isTrue();
    if (w.retention != null) w.retention.captureLive();
  }

  void releaseEarlier() {
    releaseWhisper.countDown();
    PipelineWorld.until(() ->
        assertThat(
                w.calls("sendMessage").stream()
                    .anyMatch(c ->
                        c.json().path("chat_id").asLong() == 202
                            && c.json().path("text").asText().contains("<b>")))
            .isTrue());
    queued = false;
    awaitDelivery();
  }

  public void close() {
    media.stop();
  }

  final class Models implements ResponseDefinitionTransformerV2 {
    final boolean whisper;

    Models(boolean whisper) {
      this.whisper = whisper;
    }

    public String getName() {
      return "audio-models-" + whisper;
    }

    public boolean applyGlobally() {
      return true;
    }

    public ResponseDefinition transform(ServeEvent event) {
      // Reachability must not consume the held inference response or signal inference receipt.
      if (event.getRequest().getMethod().getName().equals("GET"))
        return aResponse().withStatus(404).build();
      if (whisper) {
        whisperReceived.countDown();
        try {
          if (!releaseWhisper.await(30, TimeUnit.SECONDS))
            throw new AssertionError("Whisper hold not released");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
        return aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody("{\"text\":\"Earlier speech\",\"language\":\"english\"}")
            .build();
      }
      int count = 32;
      for (JsonNode msg : PipelineWorld.json(event.getRequest().getBodyAsString()).path("messages"))
        count += (msg.path("content").asText().length() + 2) / 3;
      return aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
              PipelineWorld.stringify(
                  Map.of(
                      "message",
                      Map.of("content", "- Earlier summary"),
                      "prompt_eval_count",
                      count,
                      "done",
                      true)))
          .build();
    }
  }
}
