package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.functional.PipelineWorld.Event;
import io.cucumber.java.en.Then;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** External boundary assertions for media and transcription. */
public class MediaAssertionSteps {
  final PipelineWorld w;
  @org.springframework.beans.factory.annotation.Autowired private TranscriptWorld retained;

  public MediaAssertionSteps(PipelineWorld world) {
    w = world;
  }

  @Then("the yt-dlp mock records {string} as one final argument immediately after {string}")
  public void urlArgument(String url, String marker) {
    PipelineWorld.until(() -> assertThat(w.commands).isNotEmpty());
    for (var c : w.commands.stream().filter(c -> c.getFirst().equals("yt-dlp")).toList()) {
      assertThat(c.subList(c.size() - 2, c.size())).containsExactly(marker, url);
    }
  }

  @Then(
      "the yt-dlp mock records the complete URL as one final argument immediately after {string} for metadata and download")
  public void bothUrl(String marker) {
    w.done();
    assertThat(w.commands.stream().filter(c -> c.getFirst().equals("yt-dlp"))).hasSize(2);
    urlArgument(w.lastUrl, marker);
  }

  @Then("the yt-dlp mock records no invocation")
  public void noProcess() {
    assertThat(w.commands).isEmpty();
  }

  @Then("^the yt-dlp mock records (no|one|\\d+) audio download invocation(?:s)?$")
  public void downloads(String count) {
    w.done();
    long n = count.equals("no") ? 0 : count.equals("one") ? 1 : Long.parseLong(count);
    assertThat(w.commands.stream().filter(c -> PipelineWorld.stage(c).equals("download")).count())
        .isEqualTo(n);
  }

  @Then("the yt-dlp mock records format selector {string}")
  public void formatSelector(String s) {
    w.done();
    var c =
        w.commands.stream()
            .filter(a -> PipelineWorld.stage(a).equals("download"))
            .findFirst()
            .orElseThrow();
    assertThat(c.get(c.indexOf("-f") + 1)).isEqualTo(s);
  }

  @Then("the process mock records no audio delivery re-encoding invocation")
  public void noEncoding() {
    assertThat(w.commands.stream().filter(c -> c.getFirst().equals("ffmpeg")))
        .noneMatch(c -> c.contains("aac") || c.stream().anyMatch(a -> a.endsWith("out.m4a")));
  }

  @Then("the yt-dlp mock records {string} for metadata and download")
  public void playlistOptions(String s) {
    w.done();
    assertThat(w.commands.stream().filter(c -> c.getFirst().equals("yt-dlp")).toList())
        .hasSize(2)
        .allSatisfy(c -> assertThat(c).contains(s));
  }

  @Then("{string}, {string} and {string} are nonempty files in the job directory")
  public void nonemptyFiles(String a, String b, String c) throws Exception {
    w.awaitHeld(w.whisperHold);
    for (String p : List.of(a, b, c)) assertThat(Files.size(w.job().resolve(p))).isPositive();
  }

  @Then("the job directory contains exactly one completed source file named {string}")
  public void oneSource(String s) throws Exception {
    w.awaitHeld(w.whisperHold);
    try (var paths = Files.list(w.job())) {
      assertThat(
              paths
                  .map(p -> p.getFileName().toString())
                  .filter(n -> n.startsWith("source."))
                  .toList())
          .containsExactly(s);
    }
  }

  @Then("{string} has the same hash as the m4a fixture served by WireMock")
  public void m4aHash(String s) throws Exception {
    hash(s, "m4a");
  }

  @Then("{string} has the same hash as the mp4 fixture served by WireMock")
  public void mp4Hash(String s) throws Exception {
    hash(s, "mp4");
  }

  void hash(String s, String ext) throws Exception {
    Path p = w.job().resolve(s);
    assertThat(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)))
        .isEqualTo(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(MediaFixtures.recording(w.seconds, ext))));
  }

  @Then("{string} and {string} contain 16000 Hz mono PCM signed 16-bit little-endian audio")
  public void wavFormat(String a, String b) throws Exception {
    w.awaitHeld(w.whisperHold);
    for (String p : List.of(a, b)) {
      try (var audio =
          javax.sound.sampled.AudioSystem.getAudioInputStream(w.job().resolve(p).toFile())) {
        var f = audio.getFormat();
        assertThat(f.getSampleRate()).isEqualTo(16000);
        assertThat(f.getChannels()).isEqualTo(1);
        assertThat(f.getSampleSizeInBits()).isEqualTo(16);
        assertThat(f.isBigEndian()).isFalse();
        assertThat(f.getEncoding()).isEqualTo(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED);
      }
    }
  }

  @Then("{string} contains the full recording")
  public void fullRecording(String s) throws Exception {
    try (var audio =
        javax.sound.sampled.AudioSystem.getAudioInputStream(w.job().resolve(s).toFile())) {
      assertThat(audio.getFrameLength() / audio.getFormat().getFrameRate())
          .isCloseTo((float) w.seconds, org.assertj.core.data.Offset.offset(0.2f));
    }
  }

  @Then("the Whisper mock receives the bytes of {string}")
  public void receivedBytes(String s) throws Exception {
    w.awaitHeld(w.whisperHold);
    Event e = w.service("whisper").getLast();
    assertMultipartBytes(e, Files.readAllBytes(w.job().resolve(s)));
  }

  @Then("the ffmpeg mock records separate source and output path arguments")
  public void separateArgs() {
    w.awaitHeld(w.whisperHold);
    var c =
        w.commands.stream()
            .filter(a -> PipelineWorld.stage(a).equals("WAV"))
            .findFirst()
            .orElseThrow();
    assertThat(c.get(c.indexOf("-i") + 1)).isEqualTo(w.job().resolve("source.m4a").toString());
    assertThat(c.getLast()).isEqualTo(w.job().resolve("audio.wav").toString());
  }

  @Then("the ffmpeg mock records {string}, {string}, {string} and {string} as options and values")
  public void wavOptions(String a, String b, String c, String d) {
    var command =
        w.commands.stream()
            .filter(x -> PipelineWorld.stage(x).equals("WAV"))
            .findFirst()
            .orElseThrow();
    for (String s : List.of(a, b, c, d)) assertThat(command).containsSubsequence(s.split(" "));
  }

  @Then("{string} in the job directory contains the fixture bytes")
  public void fixtureBytes(String s) throws Exception {
    assertThat(Files.readAllBytes(w.job().resolve(s)))
        .isEqualTo(Files.readAllBytes(MediaFixtures.wav()));
  }

  @Then("the ffmpeg mock records a segment duration of {int} seconds and audio stream copy")
  public void segment(int n) {
    w.done();
    var c =
        w.commands.stream()
            .filter(a -> PipelineWorld.stage(a).equals("split"))
            .findFirst()
            .orElseThrow();
    assertThat(c).containsSubsequence("-segment_time", "" + n);
    assertThat(c).containsSubsequence("-c", "copy");
  }

  @Then("^the Whisper mock receives (\\d+) POST requests? to \"([^\"]+)\"$")
  public void whisperCount(int n, String path) {
    w.transcriptionSettled();
    PipelineWorld.until(() -> assertThat(w.service("whisper")).hasSize(n));
    assertThat(w.service("whisper")).allSatisfy(e -> assertThat(e.url()).isEqualTo(path));
  }

  @Then("the request is multipart with {string} containing the bytes of {string}")
  public void multipart(String field, String file) throws Exception {
    Event e = w.service("whisper").getFirst();
    assertThat(new String(e.bytes(), StandardCharsets.ISO_8859_1))
        .contains("name=\"" + field + "\"", "filename=\"" + file + "\"");
    assertMultipartWav(e);
    assertThat(PipelineWorld.wavDigest(e)).isEqualTo(w.expectedWavs.get(e));
  }

  static void assertMultipartBytes(Event e, byte[] bytes) {
    assertThat(new String(e.bytes(), StandardCharsets.ISO_8859_1))
        .contains(new String(bytes, StandardCharsets.ISO_8859_1));
  }

  static void assertMultipartWav(Event e) throws Exception {
    String s = new String(e.bytes(), StandardCharsets.ISO_8859_1);
    int at = s.indexOf("RIFF");
    assertThat(at).isGreaterThanOrEqualTo(0);
    byte[] data = Arrays.copyOfRange(e.bytes(), at, e.bytes().length);
    try (var audio =
        javax.sound.sampled.AudioSystem.getAudioInputStream(
            new java.io.ByteArrayInputStream(data))) {
      assertThat(audio.getFormat().getSampleRate()).isEqualTo(16000);
      assertThat(audio.getFormat().getChannels()).isEqualTo(1);
    }
  }

  @Then(
      "the request has multipart fields {string} equal to {string} and {string} equal to {string}")
  public void fields(String a, String b, String c, String d) {
    for (Event e : w.service("whisper")) {
      String s = new String(e.bytes(), StandardCharsets.ISO_8859_1);
      assertThat(s)
          .containsPattern(
              "name=\"" + a + "\"[^\\r]*\\r\\n(?:[^\\r]*\\r\\n)*\\r\\n" + b + "\\r\\n");
      assertThat(s).contains("name=\"" + c + "\"").contains("\r\n\r\n" + d + "\r\n");
    }
  }

  @Then("{string} contains exactly this UTF-8 text followed by one LF newline:")
  public void transcript(String file, String text) {
    PipelineWorld.until(() -> assertThat(w.job().resolve(file)).isRegularFile());
    assertThat(PipelineWorld.read(w.job().resolve(file))).isEqualTo(text + "\n");
  }

  @Then("^the Whisper mock receives (?:chunks|chunk attempts) in order \"([^\"]+)\"$")
  public void chunkOrder(String expected) {
    w.transcriptionSettled();
    PipelineWorld.until(() ->
        assertThat(w.service("whisper").stream().map(Event::chunk).toList())
            .containsExactly(expected.split(", ")));
  }

  @Then("at most {int} chunk request is in flight at a time")
  public void sequential(int n) {
    w.transcriptionSettled();
    assertThat(n).isOne();
    var calls = w.service("whisper");
    assertThat(calls).hasSize(3);
    for (int i = 1; i < calls.size(); i++)
      assertThat(calls.get(i).nanos()).isGreaterThan(w.responseReady.get(calls.get(i - 1)));
  }

  @Then("the Whisper mock receives all {int} chunks once in playback order")
  public void allChunks(int n) {
    String expected =
        IntStream.range(0, n)
            .mapToObj(i -> "chunk-%03d.wav".formatted(i))
            .collect(Collectors.joining(", "));
    chunkOrder(expected);
  }

  @Then(
      "{string} contains {string} through {string} in order separated by single spaces and followed by one LF newline")
  public void allParts(String file, String a, String b) {
    assertThat(b).isEqualTo(a.substring(0, a.length() - 2) + "17");
    String expected =
        IntStream.range(0, 18)
            .mapToObj(i -> a.substring(0, a.length() - 2) + "%02d".formatted(i))
            .collect(Collectors.joining(" "));
    transcript(file, expected);
  }

  @Then("no {string} exists in the job directory")
  public void absent(String s) {
    assertThat(w.job().resolve(s)).doesNotExist();
  }

  @Then("{string}, {string} and every chunk remain unchanged")
  public void temporaryRetained(String a, String b) throws Exception {
    assertThat(w.whisperHold.release.getCount()).isOne();
    for (String p : List.of(a, b, "chunks/chunk-000.wav", "chunks/chunk-001.wav"))
      assertThat(w.job().resolve(p)).isRegularFile();
    hash(a, "m4a");
    for (var snapshot : w.audioSnapshot.entrySet())
      assertThat(PipelineWorld.digest(snapshot.getKey())).isEqualTo(snapshot.getValue());
  }

  @Then("the Whisper mock receives each chunk exactly once")
  public void once() {
    w.transcriptionSettled();
    PipelineWorld.until(
        () -> assertThat(w.service("whisper")).hasSize((int) ((w.seconds + 599) / 600)));
    assertThat(w.service("whisper").stream().map(Event::chunk).toList()).doesNotHaveDuplicates();
  }

  @Then("the Telegram status edits report chunk progress {string} in that order")
  public void progress(String expected) {
    w.transcriptionSettled();
    PipelineWorld.until(() ->
        assertThat(
                w.calls("editMessageText").stream()
                    .map(Event::text)
                    .filter(t -> t.startsWith("Transcribing "))
                    .map(t -> t.substring(13, t.length() - 1))
                    .toList())
            .containsExactly(expected.split(", ")));
  }

  @Then("no additional Telegram status edit is sent for a retry")
  public void noRetryProgress() {
    assertThat(
            w.calls("editMessageText").stream()
                .map(Event::text)
                .filter(t -> t.startsWith("Transcribing "))
                .toList())
        .doesNotHaveDuplicates();
  }

  @Then("no Telegram status edit reports chunk {int}")
  public void noProgress(int n) {
    w.transcriptionSettled();
    assertThat(w.calls("editMessageText").stream().map(Event::text).toList())
        .noneMatch(t -> t.startsWith("Transcribing " + n + "/"));
  }

  @Then("^\"([^\"]+)\" is (?:sent|attempted) exactly twice$")
  public void twice(String s) {
    w.transcriptionSettled();
    assertThat(w.service("whisper").stream().filter(e -> e.chunk().equals(s)).toList())
        .as(
            "Whisper attempts for %s at %s after transcription settled; Telegram replies: %s",
            s, w.whisper.baseUrl(), w.calls("sendMessage").stream().map(Event::text).toList())
        .hasSize(2);
  }

  @Then("{string} is never sent")
  public void neverChunk(String s) {
    w.transcriptionSettled();
    assertThat(w.service("whisper")).noneMatch(e -> e.chunk().equals(s));
  }

  @Then(
      "every retry uses the same multipart language and response_format fields as that chunk's first attempt and the corresponding chunk bytes")
  public void everyMultipart() throws Exception {
    for (Event e : w.service("whisper")) {
      assertMultipartWav(e);
      assertThat(PipelineWorld.wavDigest(e)).isEqualTo(w.expectedWavs.get(e));
    }
    var calls = w.service("whisper");
    for (Event e : calls) {
      boolean detect = e.chunk().equals("chunk-000.wav");
      LanguageSupport.field(e, "language", detect ? "auto" : "en");
      LanguageSupport.field(e, "response_format", detect ? "verbose_json" : "json");
    }
    var byChunk = w.service("whisper").stream().collect(Collectors.groupingBy(Event::chunk));
    for (var list : byChunk.values())
      if (list.size() > 1) {
        String first = new String(list.getFirst().bytes(), StandardCharsets.ISO_8859_1);
        int begin = first.indexOf("RIFF");
        int end = first.indexOf("\r\n--", begin);
        String bytes = first.substring(begin, end);
        for (Event e : list)
          assertThat(new String(e.bytes(), StandardCharsets.ISO_8859_1)).contains(bytes);
      }
  }

  @Then("{string} exists in the job directory despite total elapsed time exceeding 1 second")
  public void freshWait(String s) {
    w.transcriptionSettled();
    assertThat(w.job().resolve(s))
        .as(
            "persisted transcript after transcription settled at %s; Telegram replies: %s",
            w.whisper.baseUrl(), w.calls("sendMessage").stream().map(Event::text).toList())
        .isRegularFile();
    assertThat(System.nanoTime() - w.lastUpdateNanos)
        .isGreaterThan(Duration.ofSeconds(1).toNanos());
  }

  @Then("{string} is readable and contains the text returned for both chunks")
  public void transcriptBoth(String file) {
    transcript(file, "Speech Speech");
  }

  @Then("{string} is readable and contains the text returned by the Whisper mock")
  public void transcriptSingle(String file) {
    transcript(file, w.transcript);
  }

  @Then("{string} does not exist in the job directory")
  public void missingFile(String file) {
    if (retained != null && retained.active) {
      assertThat(retained.dir.resolve(file)).doesNotExist();
      return;
    }
    PipelineWorld.until(() -> assertThat(w.job().resolve(file)).doesNotExist());
  }

  @Then("the {string} directory does not exist in the job directory")
  public void missingDir(String file) {
    missingFile(file);
  }

  @Then("{string} and the {string} directory do not exist in the job directory")
  public void missingTwo(String a, String b) {
    missingFile(a);
    missingFile(b);
  }

  @Then("{string} remains unchanged")
  public void sourceUnchanged(String file) throws Exception {
    hash(file, "m4a");
  }
}
