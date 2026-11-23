package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.functional.PipelineWorld.Answer;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * External configuration and boundary responses, prepared before the scenario application starts.
 */
public class PipelineSetupSteps {
  final PipelineWorld w;
  @org.springframework.beans.factory.annotation.Autowired private TranscriptWorld transcript;

  public PipelineSetupSteps(PipelineWorld world) {
    w = world;
  }

  @Given("an allowed Telegram user")
  public void allowed() {
    w.allowed = true;
  }

  @Given("real yt-dlp and ffmpeg are enabled with the local WireMock media proxy")
  public void real() {
    w.fake = false;
  }

  @Given(
      "^WireMock serves (?:an ffmpeg-generated|a media page with a separate ffmpeg-generated|a media page with only an ffmpeg-generated) (\\d+) second (?:combined )?(m4a|mp4)(?: recording| audio track| combined mp4 video with sound| video with sound) at \"([^\"]+)\"$")
  public void media(long seconds, String ext, String url) throws Exception {
    w.fixture(seconds, ext, url);
  }

  @Given(
      "WireMock serves ffmpeg-generated {int} second m4a recordings at {string}, {string} and {string}")
  public void mediaThree(int s, String a, String b, String c) throws Exception {
    for (String url : List.of(a, b, c)) w.fixture(s, "m4a", url);
  }

  @Given("that page offers no separate audio-only format")
  public void combined() {
    assertThat(w.extension).isEqualTo("mp4");
  }

  @Given("the Whisper and Ollama mocks return successful answers")
  public void success() {
    w.defaultWhisper = Answer.json(PipelineWorld.stringify(Map.of("text", w.transcript)));
    w.defaultOllama = null;
  }

  @Given("^the (Whisper|Ollama) base URL points to the (?:Whisper|Ollama) mock$")
  public void endpoint(String name) {
    w.prop(
        "app." + name.toLowerCase(Locale.ROOT) + ".base-url",
        name.equals("Whisper") ? w.whisper.baseUrl() : w.ollama.baseUrl());
  }

  @Given("the configured Ollama model is {string}")
  public void model(String s) {
    w.prop("app.ollama.model", s);
  }

  @Given("the configured Ollama context window is {int} tokens")
  public void context(int n) {
    w.prop("app.ollama.num-ctx", "" + n);
  }

  @Given("^(WHISPER_TIMEOUT|OLLAMA_TIMEOUT|MAX_VIDEO_DURATION|JOB_RETENTION) is \"([^\"]+)\"$")
  public void config(String k, String v) {
    w.prop(
        switch (k) {
          case "WHISPER_TIMEOUT" -> "app.whisper.timeout";
          case "OLLAMA_TIMEOUT" -> "app.ollama.timeout";
          case "MAX_VIDEO_DURATION" -> "app.jobs.max-video-duration";
          default -> "app.jobs.retention";
        },
        v);
  }

  @Given("the Whisper base URL points to a local port that refuses connections")
  public void refusedWhisper() throws Exception {
    // Healthy preflight; close the listener when media work starts to fail inference only.
    w.refuseWhisperDuringWork = true;
  }

  @Given("the Ollama base URL points to a local port that refuses connections")
  public void refused() throws Exception {
    w.refuseOllamaDuringWork = true;
  }

  @Given("^the Whisper mock holds (?:its first|the first job's) answer$")
  public void holdWhisper() {
    w.whisperHold = w.hold();
    w.holdFirstWhisper = true;
  }

  @Given("the Ollama mock holds its first answer")
  public void holdOllama() {
    w.ollamaHold = w.hold();
  }

  @Given("the Telegram mock holds the successful response to the summary sendMessage")
  public void holdSummary() {
    w.summaryHold = w.hold();
  }

  @Given("the Whisper mock has received the first job's chunk and holds its answer")
  public void receivedFirst() {
    w.awaitHeld(w.whisperHold);
  }

  @Given("the Ollama mock has received its first request and holds its answer")
  public void receivedOllama() {
    w.awaitHeld(w.ollamaHold);
  }

  @Given("the Telegram mock has received the summary sendMessage and holds its response")
  public void receivedSummary() {
    w.awaitHeld(w.summaryHold);
  }

  @When("the Ollama mock releases its successful answer")
  public void releaseOllama() {
    w.release(w.ollamaHold);
  }

  @When("the Whisper mock releases the first job's successful answer")
  public void releaseWhisper() {
    w.release(w.whisperHold);
  }

  @Given("the user has sent {string} in a private chat")
  public void sent(String s) {
    w.send(s);
  }

  @Given("^the application clock (?:is|advances to) \"([^\"]+)\"$")
  public void clock(String s) {
    if (transcript != null && transcript.active) {
      transcript.clock.now = Instant.parse(s);
      return;
    }
    w.clock.now = Instant.parse(s);
  }

  @Given("the Telegram user is not allowed and has not received a refusal in the last minute")
  public void unknown() {
    w.allowed = false;
  }

  @Given("two allowed Telegram users Alice and Bob have distinct private chats")
  public void two() {
    w.start();
    w.allow(w.bob);
  }

  @When("Alice sends {string} and then Bob sends {string} in their private chats")
  public void both(String a, String b) {
    w.send(a);
    w.send(b, "private", w.bob);
  }

  @When("^the user sends \"([^\"]*)\" in a (supergroup|channel)$")
  public void outside(String s, String type) {
    w.send(s, type, w.user);
  }

  @Given("the media page title is {string}")
  public void title(String t) throws Exception {
    w.retitle(t);
  }

  @Given("the media page duration is {long} seconds")
  public void pageDuration(long seconds) throws Exception {
    for (var e : new ArrayList<>(w.media.entrySet()))
      w.fixture(e.getValue().seconds(), e.getValue().ext(), e.getKey(), seconds);
  }

  @Given("the media page title is {string} with decoded whitespace")
  public void whitespace(String t) throws Exception {
    w.retitle(t.replace("\\t", "\t").replace("\\n", "\n"));
  }

  @Given("the media page title contains {int} copies of {string}")
  public void longTitle(int n, String t) throws Exception {
    w.retitle(t.repeat(n));
  }

  @Given("the Telegram mock returns message id {int} for the summary")
  public void summaryId(int n) {
    w.summaryId = n;
  }

  @Given(
      "^a nonempty directory (?:occupies the job's \"job.json\" path|replaces the job's \"job.json\" file)$")
  public void obstacle() throws Exception {
    w.obstacle();
  }

  @Given("the Telegram mock returns status {int} for the summary withdrawal edit")
  public void withdraw(int n) {
    w.withdrawalStatus = n;
  }

  @When("the Telegram mock releases the successful summary response with message id {int}")
  public void releaseSummary(int n) {
    w.summaryId = n;
    w.release(w.summaryHold);
  }

  @Given("the Telegram mock rejects the queued sendMessage with status 500")
  public void rejectAck() {
    w.reject = n -> n.path("text").asText().startsWith("Queued:");
  }

  @Given("the Telegram mock rejects the {string} editMessageText with status 500")
  public void rejectProgress(String t) {
    w.reject = n -> n.path("text").asText().equals(t);
  }

  @Given("the Telegram mock rejects the summary sendMessage with status 500 and body {string}")
  public void rejectSummary(String body) {
    assertThat(body).isEqualTo("private /tmp/secret");
    w.rejectSummary = true;
  }

  @When("the Telegram mock accepts requests again")
  public void accept() {
    w.reject = n -> false;
    w.rejectSummary = false;
  }

  @Given(
      "^the Telegram mock rejects (the final \"Done\\.\" status edit|the failure sentence send|the final \"Failed\\.\" status edit) with status 500$")
  public void rejectTerminal(String s) {
    w.reject =
        n ->
            n.path("text")
                .asText()
                .equals(
                    s.contains("Done")
                        ? "Done."
                        : s.contains("Failed")
                            ? "Failed."
                            : "Transcription failed. Please try again later.");
  }

  @Given("the Telegram mock has received the terminal notification attempt")
  public void terminalAttempt() {
    PipelineWorld.until(() ->
        assertThat(
                w.events.stream()
                    .filter(e -> e.service().equals("telegram"))
                    .anyMatch(e -> w.reject.test(e.json())))
            .isTrue());
    w.rejectedNotification =
        w.events.stream()
            .filter(e -> e.service().equals("telegram") && w.reject.test(e.json()))
            .findFirst()
            .orElseThrow();
  }

  @Given(
      "^the external answers cause (a successful summary|a terminal Whisper failure) for the first submission$")
  public void outcome(String s) {
    w.firstJobFails = s.contains("failure");
    if (s.contains("failure")) w.defaultWhisper = Answer.failure("status 500");
  }

  @Given("the first job has completed with Telegram status {string}")
  public void completed(String s) {
    PipelineWorld.until(() ->
        assertThat(w.calls("editMessageText").stream().map(PipelineWorld.Event::text).toList())
            .contains(s));
  }

  @Given("the first job has reported a failure in Telegram")
  public void failed() {
    w.reply("Transcription failed. Please try again later.");
  }

  @Given(
      "^the Whisper mock returns (status 200 with nonempty text|status 500) for every attempt of the first job$")
  public void firstOutcome(String s) {
    if (s.endsWith("500")) {
      w.defaultWhisper = Answer.failure(s);
    }
  }

  @Given("^process mocks are enabled for (?:tool argument checks|the rejected link)$")
  public void fake() {
    w.fake = true;
  }

  @Given("the process mock reports a deadline expiry for {string}")
  public void timeout(String stage) {
    w.fake = true;
    w.failStage = stage;
    w.timeout = true;
  }

  @Given("the yt-dlp mock returns title {string} and duration {double} seconds")
  public void metadata(String t, double n) {
    w.fake = true;
    w.metadata = PipelineWorld.stringify(Map.of("title", t, "duration", n));
  }

  @Given("^the yt-dlp mock exits with code 0 and metadata (.+)$")
  public void metadataJson(String s) {
    w.fake = true;
    w.metadata = s;
  }

  @Given("the yt-dlp mock returns metadata for a playlist rather than one video")
  public void playlist() {
    metadataJson("{\"_type\":\"playlist\",\"entries\":[]}");
  }

  @Given("^the yt-dlp mock exits with code 0 and leaves (.+) after download$")
  public void missingSource(String s) {
    w.fake = true;
    w.missing = s;
  }

  @Given("the yt-dlp mock exits with code 1 and stderr {string}")
  public void badMetadata(String s) {
    badProcess("metadata", s);
  }

  @Given("the yt-dlp mock for {string} exits with code 1 and stderr {string}")
  public void badProcess(String stage, String s) {
    w.fake = true;
    w.failStage = stage;
    w.stderr = s;
  }

  @Given("the ffmpeg mock for {string} exits with code 1 and stderr {string}")
  public void badFfmpeg(String stage, String s) {
    badProcess(stage, s);
  }

  @Given("the ffmpeg mock exits with code 0 but creates no {string}")
  public void missingWav(String s) {
    w.fake = true;
    w.missing = s;
  }

  @Given("the ffmpeg mock exits with code 0 but creates no chunk files")
  public void missingChunks() {
    w.fake = true;
    w.missing = "chunks";
  }

  @Given("the ffmpeg mock creates an output fixture at {string} with exit code 0")
  public void wav(String s) {
    assertThat(s).isEqualTo("audio.wav");
    w.fake = true;
  }

  @Given("the ffmpeg mock creates {string}, {string} and {string} fixtures with exit code 0")
  public void chunks(String a, String b, String c) {
    assertThat(List.of(a, b, c)).containsExactly("chunk-002.wav", "chunk-000.wav", "chunk-001.wav");
    w.fake = true;
    w.splitThree = true;
  }

  @Given("^the Whisper mock returns JSON (\\{.*\\})$")
  public void whisperJson(String s) {
    w.defaultWhisper = Answer.json(s);
    w.transcript = PipelineWorld.json(s).path("text").asText();
  }

  @Given("the Whisper mock returns the T3 fixture {string} with status {int}")
  public void fixture(String file, int status) throws Exception {
    w.defaultWhisper = Answer.json(Files.readString(Path.of("src/test/resources/fixtures", file)));
    w.defaultWhisper.status = status;
  }

  @Given("the Whisper mock returns these JSON responses in order:")
  public void whisperTable(DataTable table) {
    for (var row : table.asMaps()) w.whisperAnswers.add(Answer.json(row.get("response")));
  }

  @Given("the Whisper mock returns text {string}, {string} and {string} for those chunks")
  public void whisperThree(String a, String b, String c) {
    for (String s : List.of(a, b, c))
      w.whisperAnswers.add(Answer.json(PipelineWorld.stringify(Map.of("text", s))));
  }

  @Given("the Whisper mock returns text {string} through {string} for the corresponding chunks")
  public void eighteen(String a, String b) {
    for (int i = 0; i < 18; i++)
      w.whisperAnswers.add(
          Answer.json(PipelineWorld.stringify(Map.of("text", "part-%02d".formatted(i)))));
  }

  @Given("^the Whisper mock returns JSON (.+) for \"(chunk-\\d+.wav)\"$")
  public void chunkJson(String s, String chunk) {
    w.chunkAnswers
        .computeIfAbsent(chunk, k -> new java.util.concurrent.ConcurrentLinkedDeque<>())
        .add(Answer.json(s));
  }

  @Given("^the Whisper mock returns JSON (.+) on (?:the|its) retry$")
  public void retryJson(String s) {
    String chunk =
        w.chunkAnswers.isEmpty()
            ? "chunk-000.wav"
            : w.chunkAnswers.keySet().stream().sorted().toList().getLast();
    w.chunkAnswers
        .computeIfAbsent(chunk, k -> new java.util.concurrent.ConcurrentLinkedDeque<>())
        .add(Answer.json(s));
  }

  @Given("^the Whisper mock returns status (\\d+) with body (.+) on the first attempt$")
  public void broken(int code, String s) throws Exception {
    Answer a = Answer.json(body(s));
    a.status = code;
    w.chunkAnswers
        .computeIfAbsent("chunk-000.wav", k -> new java.util.concurrent.ConcurrentLinkedDeque<>())
        .add(a);
  }

  @Given("^the Whisper mock's first attempt for \"([^\"]+)\" fails with (.+)$")
  public void firstFailure(String chunk, String why) {
    w.chunkAnswers
        .computeIfAbsent(chunk, k -> new java.util.concurrent.ConcurrentLinkedDeque<>())
        .add(Answer.failure(why));
  }

  @Given("^the Whisper mock's retry fails with (.+)$")
  public void retryFailure(String why) {
    String chunk = w.chunkAnswers.keySet().stream().sorted().toList().getLast();
    w.chunkAnswers.get(chunk).add(Answer.failure(why));
  }

  @Given("(?s)^the Whisper mock returns (.+) with status 200 for both chunks$")
  public void emptyBoth(String s) {
    w.defaultWhisper = Answer.json(s);
  }

  @Given("the Whisper mock returns nonempty text for all other chunks")
  public void otherChunks() {
    w.defaultWhisper = Answer.json("{\"text\":\"Speech\"}");
  }

  @Given("the Whisper mock returns nonempty text for both chunks")
  public void bothChunks() {
    otherChunks();
  }

  @Given("the Whisper mock returns nonempty text for {string}")
  public void nonempty(String s) {
    chunkJson("{\"text\":\"Speech\"}", s);
  }

  @Given("the Whisper mock fails once for {string} and then returns nonempty text on retry")
  public void failsOnce(String s) {
    firstFailure(s, "status 500");
    chunkJson("{\"text\":\"Recovered\"}", s);
  }

  @Given("the Whisper mock returns status {int} for both attempts for {string}")
  public void failsTwice(int n, String s) {
    firstFailure(s, "status " + n);
  }

  @Given("the Whisper mock returns status {int} for both attempts with body {string}")
  public void diagnostics(int n, String s) {
    w.defaultWhisper = Answer.json(s);
    w.defaultWhisper.status = n;
  }

  @Given("the Whisper mock holds the answer for {string} within its answer timeout")
  public void holdChunk(String s) {
    Answer a = Answer.json("{\"text\":\"Last\"}");
    a.hold = w.hold();
    w.whisperHold = a.hold;
    w.chunkAnswers.put(s, new java.util.concurrent.ConcurrentLinkedDeque<>(List.of(a)));
  }

  @Given("the Whisper mock has received {string} and holds its answer")
  public void receivedChunk(String s) {
    w.awaitHeld(w.whisperHold);
    assertThat(w.service("whisper").getLast().chunk()).isEqualTo(s);
  }

  @Given("the Whisper mock accepts each connection but delays each answer for {int} seconds")
  public void whisperDelay(int n) {
    w.defaultWhisper.delay = n * 1000;
  }

  @Given("the Whisper mock's first attempt for {string} exceeds the answer timeout")
  public void firstSlow(String s) {
    firstFailure(s, "answer timeout");
  }

  @Given("^the Whisper mock returns JSON (.+) after (\\d+) milliseconds$")
  public void retrySlow(String s, int n) {
    retryJson(s);
    w.chunkAnswers.get("chunk-000.wav").getLast().delay = n;
  }

  @Given("^the Whisper mock returns for \"([^\"]+)\" JSON (.+) after (\\d+) milliseconds$")
  public void chunkSlow(String c, String s, int n) {
    chunkJson(s, c);
    w.chunkAnswers.get(c).getLast().delay = n;
  }

  @When(
      "the Whisper mock releases status 500 for both attempts of the first job and successful text for the second job")
  public void releaseFailed() {
    w.chunkAnswers.clear();
    w.whisperAnswers.clear();
    w.whisperAnswers.add(Answer.failure("status 500"));
    w.whisperAnswers.add(Answer.failure("status 500"));
    w.defaultWhisper = Answer.json("{\"text\":\"Next job succeeded\"}");
    w.release(w.whisperHold);
  }

  @Given("the Ollama mock returns valid prompt counts and this content:")
  public void contentDoc(String s) {
    w.defaultContent = s;
  }

  @Given("the Ollama mock returns valid prompt counts and content {string}")
  public void content(String s) {
    w.defaultContent = s;
  }

  @Given("(?s)^the Ollama mock returns valid prompt counts and JSON message.content (.+)$")
  public void contentJson(String s) {
    w.defaultContent = PipelineWorld.json(s).asText();
  }

  @Given(
      "the Ollama mock returns valid prompt counts and English bullet content of {int} characters")
  public void contentLength(int n) {
    w.defaultContent = "- " + "A".repeat(n - 2);
  }

  @Given("^the Ollama mock returns JSON (.+)$")
  public void ollamaJson(String s) {
    w.defaultOllama = Answer.json(s);
  }

  @Given("(?s)^the Ollama mock returns status (\\d+) with body (.+?)(?: for every request)?$")
  public void ollamaBody(int n, String s) throws Exception {
    w.defaultOllama = Answer.json(body(s));
    w.defaultOllama.status = n;
  }

  @Given("the Ollama mock accepts connections but delays every answer for {int} seconds")
  public void ollamaDelay(int n) {
    w.defaultOllama = Answer.json("{}");
    w.defaultOllama.delay = n * 1000;
  }

  @Given("the Ollama mock returns valid prompt counts and these contents in order:")
  public void contents(DataTable t) {
    for (var row : t.asMaps()) addContent(row.get("content"));
  }

  void addContent(String s) {
    w.modelContents.add(s);
    Answer a = Answer.json("");
    a.content = s;
    w.ollamaAnswers.add(a);
  }

  @Given("the Ollama mock first returns valid prompt counts and content {string}")
  public void firstContent(String s) {
    addContent(s);
  }

  @Given("every subsequent Ollama answer has empty content")
  public void laterEmpty() {
    w.defaultOllama = Answer.json("{\"message\":{\"content\":\"\"},\"prompt_eval_count\":8192}");
  }

  @Given(
      "the Ollama mock returns valid short English bullet summaries for the first {int} requests")
  public void firstContents(int n) {
    for (int i = 0; i < n; i++) addContent("- Section " + (i + 1));
  }

  @Given("every reduction answer has empty content")
  public void reductionEmpty() {
    laterEmpty();
  }

  @Given(
      "the Whisper mock returns an ordered English transcript that fills exactly {int} summary input chunks at context 8192")
  public void longTranscript(int n) {
    // T10 contract: largest system prompt is 322 UTF-16 units (108 tokens).
    int budget = (8192 - 1024 - 108 - 32) * 3;
    w.wholeTranscript = "Opening " + "word ".repeat((budget * (n - 1) + 100) / 5) + "Closing";
    w.whisperAnswers.clear();
    List<String> words = Arrays.asList(w.wholeTranscript.split(" "));
    int count = (int) ((w.seconds + 599) / 600), at = 0;
    for (int i = 0; i < count; i++) {
      int end = (i + 1) * words.size() / count;
      w.whisperAnswers.add(
          Answer.json(
              PipelineWorld.stringify(Map.of("text", String.join(" ", words.subList(at, end))))));
      at = end;
    }
  }

  @Given("the first and last transcript words are {string} and {string}")
  public void endpoints(String a, String b) {
    assertThat(w.wholeTranscript.split(" ", 2)[0]).isEqualTo(a);
    assertThat(w.wholeTranscript).startsWith(a).endsWith(b);
  }

  @Given(
      "the Ollama mock returns valid prompt counts and one oversized English bullet for each of the first {int} requests")
  public void oversized(int n) {
    for (int i = 1; i <= n; i++) addContent("- Section " + i + ": " + "word ".repeat(5000));
  }

  @Given(
      "each oversized bullet begins with its section number and exceeds the entire reduction input budget")
  public void oversizedCheck() {
    assertThat(w.modelContents).allSatisfy(s -> assertThat(s.length()).isGreaterThan(22000));
  }

  @Given("the ninth and tenth Ollama answers are valid short English bullet summaries")
  public void groups() {
    addContent("- Group one.");
    addContent("- Group two.");
  }

  @Given("the eleventh Ollama answer is a valid short final English bullet summary")
  public void finalGroup() {
    addContent("- Final summary.");
  }

  @Given(
      "the Whisper mock returns an English transcript whose complete prompt has an expected input size of approximately 4831 tokens")
  public void count4831() {
    w.defaultWhisper = Answer.json(PipelineWorld.stringify(Map.of("text", "word ".repeat(2818))));
  }

  @Given(
      "^the Ollama mock returns valid prompt counts and short English bullet content before the (second map|final reduction) request$")
  public void beforeStage(String stage) {
    firstContents(stage.equals("second map") ? 1 : 3);
  }

  @Given(
      "^every answer for the (second map|final reduction) request contains nonempty bullet content but prompt_eval_count 1$")
  public void truncatedStage(String stage) {
    w.defaultOllama =
        Answer.json("{\"message\":{\"content\":\"- Incomplete\"},\"prompt_eval_count\":1}");
  }

  static String body(String s) throws Exception {
    if (s.equals("an empty body")) return "";
    if (s.startsWith("the T3 fixture "))
      return Files.readString(Path.of("src/test/resources/fixtures", s.substring(15)));
    return s;
  }

  @Given("a completed job has readable source, transcript and job.json files")
  public void completedFixture() throws Exception {
    w.send("http://videos.example/video");
    w.done();
    w.submissions = 0;
    w.events.clear();
    w.acceptedStatuses.set(0);
    w.whispers.set(0);
    w.ollamas.set(0);
  }

  @Given("the access file contains the allowed user's persisted entry")
  public void accessFixture() throws Exception {
    assertThat(PipelineWorld.read(w.root.resolve("allowed-users.json"))).contains("" + w.user);
    w.capturePreserved();
  }

  @Given("^the external fixture for this submission causes (.+)$")
  public void failure(String s) throws Exception {
    if (s.contains("private metadata")) badMetadata("PRIVATE content");
    else if (s.contains("unsupported metadata")) badMetadata("Unsupported URL");
    else if (s.contains("duration")) metadata("Too long", 10801);
    else if (s.contains("live stream"))
      metadataJson("{\"title\":\"Live\",\"duration\":300,\"is_live\":true}");
    else if (s.contains("yt-dlp download")) badProcess("download", w.stderr);
    else if (s.contains("ffmpeg WAV")) badProcess("WAV", w.stderr);
    else if (s.contains("ffmpeg split")) badProcess("split", w.stderr);
    else if (s.contains("download timeout")) timeout("download");
    else if (s.contains("empty speech")) w.defaultWhisper = Answer.json("{\"text\":\"\"}");
    else if (s.contains("Whisper")) {
      if (s.contains("connection refusal")) refusedWhisper();
      else w.defaultWhisper = Answer.failure("status 500");
    } else if (s.contains("Ollama connection")) refused();
    else if (s.contains("Ollama empty")) laterEmpty();
    else if (s.contains("Ollama silently")) truncatedStage("second map");
    else if (s.contains("Ollama answer timeout")) {
      w.prop("app.ollama.timeout", "PT0.1S");
      ollamaDelay(2);
    } else throw new AssertionError("Unknown external failure: " + s);
  }
}
