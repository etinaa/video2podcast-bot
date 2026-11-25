package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.*;
import java.nio.file.*;
import java.util.*;

/** Acceptance assertions for health checks and pending-access application restarts. */
public class FailureModeSteps {
  final FailureModeWorld h;
  final PipelineWorld w;

  public FailureModeSteps(FailureModeWorld world) {
    h = world;
    w = h.w;
  }

  @Then(
      "^the original queued status is edited to \"([^\"]+)\" within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance$")
  public void statusDeadline(String text) {
    h.queuedTerminal(w.user, text);
    var e =
        w.calls("editMessageText").stream()
            .filter(v -> v.text().equals(text))
            .findFirst()
            .orElseThrow();
    assertThat(e.nanos() - w.lastUpdateNanos)
        .isLessThanOrEqualTo(h.timeout().plusMillis(1500).toNanos());
  }

  @Given("^the (Whisper|Ollama) health HTTP mock answers with (HTTP \\d+|connection refusal)$")
  public void initial(String service, String response) {
    h.policy(service, response);
  }

  @Given(
      "^the (Whisper|Ollama) health HTTP mock answers with (HTTP \\d+|connection refusal) for the next accepted link$")
  public void next(String service, String response) {
    h.next(service, response);
  }

  @Given("both health HTTP mocks answer HTTP 200 for the next accepted link")
  @When("both health HTTP mocks begin answering HTTP 200")
  public void healthy() {
    if (w.deferred != null) w.deferred.restoreModels();
    for (String s : List.of("whisper", "ollama")) h.next(s, "HTTP 200");
  }

  @Given("both health HTTP mocks refuse connections for the next accepted link")
  public void bothDown() {
    for (String s : List.of("whisper", "ollama")) h.next(s, "connection refusal");
  }

  @Given("both health HTTP mocks refuse connections during the next startup")
  public void nextStartupDown() {
    bothDown();
    h.expectUnavailableStartup = true;
  }

  @Given("the Whisper health HTTP mock refuses its next connection")
  public void whisperDown() {
    h.next("whisper", "connection refusal");
  }

  @When("the Ollama health HTTP mock begins refusing connections")
  public void ollamaDown() {
    h.next("ollama", "connection refusal");
  }

  @Given("^the (Whisper|Ollama|other) health HTTP mock answers HTTP 200$")
  public void oneHealthy(String service) {
    String s =
        service.equals("other")
            ? (h.heldService.equals("whisper") ? "ollama" : "whisper")
            : service.toLowerCase(Locale.ROOT);
    h.next(s, "HTTP 200");
  }

  @Given(
      "^the (?:bot has completed healthy startup checks|services answered the startup health requests successfully)$")
  public void startupDone() {
    h.startupFinished();
  }

  @Given("both services answered the startup health requests successfully")
  public void startupBoth() {
    h.startupFinished();
  }

  @Given("HEALTH_TIMEOUT is {string}")
  public void timeout(String value) {
    h.configureTimeout(value);
  }

  @Given("both health HTTP mocks hold their responses beyond the configured short health deadline")
  public void startupStall() {
    w.properties.put("app.health.timeout", "PT1S");
    h.hold("whisper", false, true);
    h.hold("ollama", false, true);
  }

  @Given("both health HTTP mocks hold their startup responses")
  public void startupHeld() {
    h.hold("whisper", false, true);
    h.hold("ollama", false, true);
  }

  @Given(
      "^the (Whisper|Ollama) health HTTP mock holds its response beyond the configured short health deadline$")
  public void stall(String service) {
    h.configureTimeout("PT1S");
    h.hold(service.toLowerCase(Locale.ROOT), false, false);
  }

  @Given(
      "^the Whisper health HTTP mock holds (?:only )?(?:Alice's|user 202's) next health response$")
  public void held() {
    h.hold("whisper", true, false);
  }

  @Given("^all other health, inference and chat HTTP responses are successful$")
  @Given("the Whisper and Ollama mocks return successful inference and chat answers")
  @When("the Whisper and Ollama mocks begin returning successful answers")
  public void successful() {
    w.defaultOllama = null;
    w.defaultWhisper =
        PipelineWorld.Answer.json("{\"text\":\"A useful transcript.\",\"language\":\"english\"}");
  }

  @When("a fresh bot application starts against the local HTTP mocks")
  @When("a fresh bot application starts with that access file")
  public void start() {
    h.start();
  }

  @Given("the configured health URLs contain user info {string}")
  public void credentials(String credentials) {
    for (String s : List.of("whisper", "ollama"))
      w.properties.put(
          "app." + s + ".base-url",
          (s.equals("whisper") ? w.whisper.baseUrl() : w.ollama.baseUrl())
              .replace("http://", "http://" + credentials + "@"));
  }

  @Given("both health HTTP mocks answer HTTP 503 with body {string}")
  public void diagnostic(String body) {
    for (String s : List.of("whisper", "ollama")) {
      h.policy(s, "HTTP 503");
      h.policies.get(s).body = body.replace("\\n", "\n");
    }
  }

  @Then("each responding service HTTP mock records one startup health request")
  public void startupRequests() {
    h.startupFinished();
    for (String s : List.of("whisper", "ollama")) {
      if (!h.policies.get(s).refused) assertThat(h.since(s, h.startup)).hasSize(1);
    }
  }

  @Then("the captured startup logs contain one health-result line for {word} reporting {string}")
  public void logResult(String service, String state) {
    h.startupFinished();
    assertThat(
            h.logs.stream()
                .filter(l ->
                    l.text()
                        .startsWith(
                            "Health check: " + service.toLowerCase(Locale.ROOT) + " " + state)))
        .hasSize(1);
  }

  @Then("unreachable health-result lines include a safe failure reason without a stack trace")
  @Then("each health result occupies one line without a stack trace")
  public void safeLogs() {
    h.startupFinished();
    for (var l : h.logs) {
      assertThat(l.throwable()).isFalse();
      assertThat(l.text()).doesNotContain("\n", "\r", "http://", w.root.toString());
      if (l.text().contains("unreachable"))
        assertThat(l.text()).matches("Health check: (whisper|ollama) unreachable \\([^\\r\\n]+\\)");
    }
  }

  @Then("startup sends no service-failure Telegram message and invokes no media process")
  public void startupNoWork() {
    h.startupFinished();
    assertThat(h.tools).isEmpty();
    assertThat(w.calls("sendMessage")).noneMatch(e -> e.text().contains("unreachable"));
    assertThat(w.service("whisper")).isEmpty();
    assertThat(w.service("ollama")).isEmpty();
  }

  @Then("the captured health logs identify both services as reachable")
  public void logsUp() {
    logResult("Whisper", "reachable");
    logResult("Ollama", "reachable");
  }

  @Then("the captured health logs contain none of {string}, {string}, {string} or {string}")
  public void noSecrets(String a, String b, String c, String d) {
    h.startupFinished();
    assertThat(h.logs.toString()).doesNotContain(a, b, c, d);
  }

  @Then(
      "both startup probes finish within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance")
  public void startupDeadline() {
    h.startupFinished();
    for (var l : h.logs)
      assertThat(l.nanos() - h.firstStartupProbeReceived())
          .isLessThanOrEqualTo(h.timeout().plusMillis(1500).toNanos());
    for (var p : h.policies.values()) assertThat(p.hold.release.getCount()).isEqualTo(1);
  }

  @Then("the captured startup logs report a timeout for both services without a stack trace")
  public void logTimeout() {
    h.startupFinished();
    assertThat(h.logs)
        .allSatisfy(l -> {
          assertThat(l.text()).contains("unreachable (no answer in time)");
          assertThat(l.throwable()).isFalse();
        });
  }

  @When(
      "^user (\\d+) sends \"([^\"]*)\" in a private chat(?: after startup| before the held responses are released| before the PT24H cooldown ends)?$")
  public void send(long user, String text) {
    if (!text.startsWith("/")) w.user = user;
    h.send(user, text);
  }

  @When("^Alice sends \"([^\"]*)\" in her private chat$")
  public void alice(String text) {
    h.send(w.user, text);
  }

  @When("^Bob sends \"([^\"]*)\" in his private chat$")
  public void bob(String text) {
    h.send(w.bob, text);
  }

  @Given("^the Whisper health HTTP mock has received (?:Alice's|user 202's) request$")
  public void received() {
    h.awaitProbe();
  }

  @Given("both health HTTP mocks have received the startup requests")
  public void receivedBothStartup() {
    for (String service : List.of("whisper", "ollama")) w.awaitHeld(h.policies.get(service).hold);
  }

  @Then(
      "^the Telegram mock receives a reply containing Telegram id (\\d+)(?: before the held responses are released)?$")
  public void id(long user) {
    h.replyTime = h.message(user, "Your Telegram id: <code>" + user + "</code>").nanos();
  }

  @Then("Bob's Telegram id reply arrives before Alice's held health response is released")
  public void bobReply() {
    id(w.bob);
    h.pendingAt(h.replyTime, false);
  }

  @Then(
      "^when that (?:reply|sendDocument) arrives (?:Alice's|user 202's) health check is still pending before its configured deadline with no terminal failure notification$")
  public void pending() {
    h.pendingAt(h.replyTime, false);
  }

  @Then(
      "when that reply arrives both startup health checks are still pending before their configured deadlines with no health-result log lines")
  public void startupPending() {
    for (String s : List.of("whisper", "ollama")) {
      h.heldService = s;
      h.held = h.policies.get(s).hold;
      h.pendingAt(h.replyTime, true);
    }
  }

  @Then("^the (Whisper|Ollama) mock records GET \"([^\"]+)\" with no request body$")
  public void get(String service, String path) {
    w.finishOne();
    var ps = h.since(service.toLowerCase(Locale.ROOT), w.lastUpdateNanos);
    assertThat(ps).hasSize(1);
    assertThat(ps.getFirst().path).isEqualTo(path);
    assertThat(ps.getFirst().body).isEmpty();
  }

  @Then("both service health HTTP requests finish before this submission's first yt-dlp invocation")
  public void beforeTools() {
    w.finishOne();
    long first =
        h.tools.stream()
            .filter(t -> t.nanos() >= w.lastUpdateNanos)
            .findFirst()
            .orElseThrow()
            .nanos();
    for (String s : List.of("whisper", "ollama")) {
      var ps = h.since(s, w.lastUpdateNanos);
      assertThat(ps).hasSize(1);
      assertThat(ps.getFirst().ready).isBetween(w.lastUpdateNanos, first);
    }
  }

  @Then(
      "this submission produces a queued acknowledgement and a summary with both download buttons")
  public void summary() {
    h.summary(w.user);
    new PipelineAssertionSteps(w).buttons("Download audio", "Download transcript");
    assertThat(h.messages(w.user)).anyMatch(e -> e.text().startsWith("Queued:"));
  }

  @Then("this submission sends no service-unreachable message")
  public void noFailure() {
    assertThat(h.failures(w.user)).isEmpty();
  }

  @Then("the responding peer service HTTP mock records a new health request for this submission")
  public void peer() {
    w.finishOne();
    String s = h.policies.get("whisper").refused ? "ollama" : "whisper";
    assertThat(h.since(s, w.lastUpdateNanos)).hasSize(1);
  }

  @Then(
      "this submission receives a queued acknowledgement before its failure message and a {string} status edit afterwards")
  public void order(String status) {
    h.rejected();
    var f = h.failures(w.user).getFirst();
    var q =
        h.messages(w.user).stream()
            .filter(e -> e.text().startsWith("Queued:"))
            .findFirst()
            .orElseThrow();
    var end = w.finishOne();
    assertThat(q.nanos()).isLessThan(f.nanos());
    assertThat(end.nanos()).isGreaterThan(f.nanos());
    assertThat(end.text()).isEqualTo(status);
    assertThat(end.json().path("message_id").asInt()).isEqualTo(w.responseIds.get(q));
  }

  @Then("after this preflight finishes no working directory for this submission remains")
  @Then("no working directory for the rejected submission remains")
  public void noDirectory() {
    h.noRejectedDirectory();
  }

  @Then("^the (yt-dlp|ffmpeg) mock records no invocation for this submission$")
  public void noTool(String name) {
    w.finishOne();
    assertThat(h.tools).noneMatch(t -> t.args().getFirst().equals(name));
  }

  @Then("this submission produces no Whisper inference or Ollama chat request")
  public void noInference() {
    w.finishOne();
    assertThat(w.service("whisper")).isEmpty();
    assertThat(w.service("ollama")).isEmpty();
  }

  @Then("this submission produces no yt-dlp, ffmpeg, Whisper inference or Ollama chat request")
  public void noWork() {
    noInference();
    assertThat(h.tools).isEmpty();
  }

  @Then("this submission produces no summary keyboard, sendAudio or sendDocument request")
  public void noOutput() {
    w.finishOne();
    assertThat(w.summaries()).isEmpty();
    w.noUploads();
  }

  @Then("this submission's Telegram failure notification is {string}")
  public void combined(String text) {
    h.rejected();
    assertThat(h.failures(w.user))
        .singleElement()
        .satisfies(e -> assertThat(e.text()).isEqualTo(text));
  }

  @Then(
      "this submission's Telegram failure notifications contain each existing unreachable message exactly once")
  public void combinedOnce() {
    combined(FailureModeWorld.WHISPER + " " + FailureModeWorld.OLLAMA);
  }

  @Then(
      "^the bot replies with \"([^\"]+)\" within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance$")
  public void deadline(String text) {
    var e = h.message(w.user, text);
    assertThat(e.nanos() - w.lastUpdateNanos)
        .isLessThanOrEqualTo(h.timeout().plusMillis(1500).toNanos());
    assertThat(h.held.release.getCount()).isEqualTo(1);
  }

  @Then(
      "Alice receives {string} within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance")
  public void aliceDeadline(String text) {
    var e = h.message(w.user, text);
    var p = h.since("whisper", h.observation).getFirst();
    assertThat(e.nanos() - p.received).isLessThanOrEqualTo(h.timeout().plusMillis(1500).toNanos());
  }

  @Then("this submission sends no message {string}")
  public void notMessage(String text) {
    w.finishOne();
    assertThat(h.messages(w.user)).noneMatch(e -> e.text().equals(text));
  }

  @Then(
      "^before the next deferred retry interval only \"([^\"]+)\" is downloaded and receives a successful summary$")
  public void recovered(String url) {
    h.summary(w.user);
    assertThat(h.tools.stream().filter(t -> PipelineWorld.stage(t.args()).equals("download")))
        .singleElement()
        .satisfies(t -> assertThat(t.args()).contains(url));
    assertThat(h.tools.stream().filter(t -> t.args().getFirst().equals("yt-dlp")))
        .allSatisfy(t -> assertThat(t.args()).contains(url));
  }

  @Then("both service HTTP mocks record a fresh health request for the recovered submission")
  public void fresh() {
    w.finishOne();
    for (String s : List.of("whisper", "ollama"))
      assertThat(h.since(s, w.lastUpdateNanos)).hasSize(1);
  }

  @Then("no audio download is recorded for {string}")
  public void noDownload(String url) {
    w.finishOne();
    h.noWork(url);
  }

  @Given("the Telegram mock rejects the next service-failure notification with HTTP 500")
  public void rejectFailure() {
    w.reject = n -> n.path("text").asText().contains("server is unreachable.");
  }

  @Then("Alice's submission has not downloaded any audio")
  public void aliceState() {
    h.noWork("http://videos.example/video");
  }

  @Then("user 202's queued status is edited to {string}")
  public void user202Status(String text) {
    h.queuedTerminal(202, text);
  }

  @Then("user 101 receives no outage notification")
  public void no101Outage() {
    assertThat(h.failures(101)).isEmpty();
  }

  @Then(
      "^Alice's queued status is edited to \"([^\"]+)\" within HEALTH_TIMEOUT plus 1 second and a 500 millisecond test scheduling tolerance$")
  public void parkedDeadline(String text) {
    h.queuedTerminal(w.user, text);
    var e =
        w.calls("editMessageText").stream()
            .filter(v -> v.text().equals(text) && v.json().path("chat_id").asLong() == w.user)
            .findFirst()
            .orElseThrow();
    var p = h.since("whisper", h.observation).getFirst();
    assertThat(e.nanos() - p.received).isLessThanOrEqualTo(h.timeout().plusMillis(1500).toNanos());
  }

  @Then("Alice's queued status is edited to {string}")
  public void aliceStatus(String text) {
    h.queuedTerminal(w.user, text);
  }

  @Then("user 202's submission has not downloaded any audio")
  public void noAudio202() {
    h.noWork("http://videos.example/video");
  }

  @Then(
      "Bob's first yt-dlp invocation occurs after Alice's preflight ends and before the held HTTP response is released")
  public void workerReleased() {
    h.summary(w.bob);
    long failure =
        w.calls("editMessageText").stream()
            .filter(e ->
                e.text().equals(DeferredSupport.PARKED)
                    && e.json().path("chat_id").asLong() == w.user)
            .findFirst()
            .orElseThrow()
            .nanos();
    var first = h.tools.getFirst();
    assertThat(first.nanos()).isGreaterThan(failure);
    assertThat(first.args()).contains("http://videos.example/following");
    assertThat(h.held.release.getCount()).isEqualTo(1);
  }

  @Then("Bob receives a successful summary")
  public void bobSummary() {
    h.summary(w.bob);
  }

  @Then("no yt-dlp invocation or working directory exists for Alice's submission")
  public void noAlice() {
    h.noWork("http://videos.example/stalled");
    assertThat(w.dirs()).hasSize(1);
  }

  @Given("user 101 has a retained unexpired published job with audio and transcript files")
  public void retained() throws Exception {
    h.retained();
  }

  @Given("user 202 is allowed and has a distinct private chat")
  public void allow202() {
    h.start();
  }

  @Given("snapshots of user 101's job files and the access file have been recorded")
  public void snapshot() throws Exception {
    try (var paths = Files.walk(h.retained)) {
      for (Path p : paths.filter(Files::isRegularFile).toList())
        h.snapshots.put(p, Files.readAllBytes(p));
    }
    accessSnapshot();
  }

  @Then("user 101's retained files and the access file match their snapshots")
  public void unchanged() throws Exception {
    for (var e : h.snapshots.entrySet())
      assertThat(Files.readAllBytes(e.getKey())).containsExactly(e.getValue());
    accessUnchanged();
  }

  @Then("user 202 receives both existing unreachable messages and user 101 receives neither")
  public void ownFailure() {
    combinedOnce();
    assertThat(h.failures(101)).isEmpty();
  }

  @When("user 101 taps the retained job's transcript button in its private chat")
  public void transcript() throws Exception {
    h.callback(101, "transcript:" + TranscriptWorld.JOB, 701);
  }

  @Then(
      "the Telegram mock receives one sendDocument targeting stored chat 101 before the held health response is released")
  public void uploaded() throws Exception {
    PipelineWorld.until(() -> assertThat(w.calls("sendDocument")).hasSize(1));
    var e = w.calls("sendDocument").getFirst();
    var query =
        com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                FunctionalTest.telegramPath("sendDocument")));
    PipelineWorld.until(() -> assertThat(w.telegram.findAll(query)).hasSize(1));
    var request = w.telegram.findAll(query).getFirst();
    assertThat(request.getPart("chat_id").getBody().asString()).isEqualTo("101");
    String reference = request.getPart("document").getBody().asString();
    assertThat(reference).startsWith("attach://");
    assertThat(request.getPart(reference.substring("attach://".length())).getBody().asBytes())
        .containsExactly(Files.readAllBytes(h.retained.resolve("transcript.txt")));
    h.replyTime = e.nanos();
    h.pendingAt(e.nanos(), false);
  }

  @Given("an allowed Telegram user has submitted {string} with healthy preflight")
  public void first(String url) {
    h.startupFinished();
    w.whisperHold = w.hold();
    w.holdFirstWhisper = true;
    h.send(w.user, url);
    w.awaitHeld(w.whisperHold);
  }

  @Given("the Whisper inference HTTP mock holds the first job's answer")
  public void inferenceHeld() {
    w.awaitHeld(w.whisperHold);
  }

  @Given("a second allowed user has a distinct private chat")
  public void second() {
    w.allow(w.bob);
  }

  @When("the second user sends {string} in a private chat")
  public void secondSend(String url) {
    h.send(w.bob, url);
  }

  @Then("the second user receives a queued acknowledgement")
  public void queued() {
    PipelineWorld.until(
        () -> assertThat(h.messages(w.bob)).anyMatch(e -> e.text().startsWith("Queued:")));
  }

  @Then("neither service HTTP mock records a health request for the waiting submission yet")
  public void waitingNotChecked() {
    for (String s : List.of("whisper", "ollama"))
      assertThat(h.since(s, w.lastUpdateNanos)).isEmpty();
  }

  @When("the Whisper inference HTTP mock releases a successful answer for the first job")
  public void release() {
    w.release(w.whisperHold);
  }

  @Then("the first job receives a successful summary")
  public void firstSummary() {
    h.summary(w.user);
  }

  @Then("the waiting submission receives {string}")
  public void waitingFailure(String text) {
    h.message(w.bob, text);
  }

  @Then("the waiting submission's queued status is edited to {string}")
  public void waitingStatus(String text) {
    h.queuedTerminal(w.bob, text);
  }

  @Then("no yt-dlp invocation or working directory exists for the waiting submission")
  public void noWaiting() {
    h.noWork("http://videos.example/waiting");
    assertThat(w.dirs()).hasSize(1);
  }

  @Given("user 303 has requested access through a private {string} message")
  public void request(String command) throws Exception {
    assertThat(command).isEqualTo("/start");
    h.requestAccess(false);
  }

  @Given("the Telegram mock delivered the owner's request notification as message {int}")
  public void delivered(int id) {
    assertThat(h.notifications()).hasSize(1);
    assertThat(h.request().path("requestMessageId").asInt()).isEqualTo(id);
  }

  @Given("user 202 is not allowlisted")
  public void unknown() throws Exception {
    h.send(1, "/deny 202");
    h.settleUpdates();
    assertThat(h.allowed(202)).isFalse();
  }

  @Given("a snapshot of the access file has been recorded")
  public void accessSnapshot() throws Exception {
    h.accessSnapshot = Files.readAllBytes(w.root.resolve("allowed-users.json"));
  }

  @When("user 202 taps {string} on private message {int}")
  public void unknownTap(String data, int id) throws Exception {
    h.callback(202, data, id);
  }

  @Then("that callback produces only answerCallbackQuery with {string}")
  public void callbackOnly(String text) {
    var calls =
        w.events.stream()
            .filter(e -> e.service().equals("telegram") && e.nanos() >= h.callbackStart)
            .toList();
    assertThat(calls)
        .singleElement()
        .satisfies(e -> {
          assertThat(e.url()).endsWith("/answercallbackquery");
          assertThat(e.json().path("callback_query_id").asText()).isEqualTo(h.callbackId);
          assertThat(e.text()).isEqualTo(text);
        });
  }

  @Then("the access file matches its snapshot")
  public void accessUnchanged() throws Exception {
    assertThat(Files.readAllBytes(w.root.resolve("allowed-users.json")))
        .containsExactly(h.accessSnapshot);
  }

  @Then(
      "no approval or denial notification, request-message edit, upload or model request is sent for that callback")
  public void noCallbackEffects() {
    callbackOnly("No access.");
    assertThat(w.service("whisper")).isEmpty();
    assertThat(w.service("ollama")).isEmpty();
    assertThat(h.tools).isEmpty();
  }

  @When("the bot application is closed and a fresh application starts with the same access file")
  public void restart() {
    h.restart();
  }

  @Then("^startup sends no duplicate owner notification for user 303$")
  @Then("the second startup sends no duplicate owner notification for user 303")
  @Then("no new access request notification is sent to the owner for user 303")
  public void noNotification() {
    assertThat(h.notifications()).isEmpty();
  }

  @Then("the access file still records user 303's pending request with message id {int}")
  public void pendingId(int id) {
    assertThat(h.request().path("requestMessageId").asInt()).isEqualTo(id);
  }

  @When(
      "^the owner taps \"([^\"]*)\"(?: again)? on (?:the original private|private) message (\\d+)$")
  public void ownerTap(String data, int id) throws Exception {
    h.callback(1, data, id);
  }

  @Then("user 303 receives {string}")
  public void applicantMessage(String text) {
    h.message(303, text);
  }

  @Then(
      "the access file has no pending request for user 303 and records allowed membership as {word}")
  public void resolved(String allowed) {
    noPending();
    assertThat(h.allowed(303)).isEqualTo(Boolean.parseBoolean(allowed));
  }

  @Then("the access file has no pending request for user 303")
  public void noPending() {
    assertThat(h.access().path("pending").valueStream())
        .noneMatch(n -> n.path("id").asLong() == 303);
  }

  @Then("the access file does not allow user 303")
  public void notAllowed() {
    assertThat(h.allowed(303)).isFalse();
  }

  @Then("the access file allows user 303")
  public void allowed() {
    assertThat(h.allowed(303)).isTrue();
  }

  @Given("user 303 requested access and the owner denied it through the original callback")
  public void denied() throws Exception {
    h.requestAccess(false);
    h.callback(1, "access:deny:303", h.request().path("requestMessageId").asInt());
  }

  @Then("user 303 receives a reply starting with {string}")
  public void cooldown(String text) {
    PipelineWorld.until(() -> assertThat(h.messages(303)).anyMatch(e -> e.text().startsWith(text)));
  }

  @Given(
      "the Telegram mock rejected the owner notification for user 303's private {string} request")
  public void rejectedRequest(String command) throws Exception {
    assertThat(command).isEqualTo("/start");
    h.requestAccess(true);
    w.reject = n -> false;
  }

  @Given("the access file records user 303's pending request without a notification message id")
  public void missingId() throws Exception {
    if (w.context == null) h.seedRequest();
    else assertThat(h.request().hasNonNull("requestMessageId")).isFalse();
  }

  @Then(
      "the access file still records user 303's pending request without a notification message id")
  public void stillMissing() {
    assertThat(h.request().hasNonNull("requestMessageId")).isFalse();
  }

  @Then(
      "^the Telegram mock receives (?:one retried|a successful) owner notification for user 303 with Approve and Deny buttons$")
  public void notified() throws Exception {
    h.settleUpdates();
    if (h.expectUnavailableStartup) {
      logResult("Whisper", "unreachable");
      logResult("Ollama", "unreachable");
    }
    assertThat(h.notifications()).hasSize(1);
    assertThat(h.notifications().getFirst().json().path("reply_markup").toString())
        .contains("access:approve:303", "access:deny:303");
  }

  @Then("its successful message id is persisted in the access file")
  public void notificationId() {
    assertThat(h.request().path("requestMessageId").asInt())
        .isEqualTo(w.responseIds.get(h.notifications().getLast()));
  }

  @When("the owner taps the retried notification's {string} button")
  public void retriedTap(String data) throws Exception {
    h.callback(1, data, h.request().path("requestMessageId").asInt());
  }

  @When(
      "a fresh bot application starts with that access file and successfully notifies the owner as message {int}")
  public void startNotified(int id) {
    h.start();
    pendingId(id);
  }

  @Given("the Telegram mock rejects the next owner notification with HTTP 500")
  public void rejectOwner() {
    w.reject =
        n ->
            n.path("chat_id").asLong() == 1
                && n.path("text").asText().startsWith("Access request from ");
  }

  @When("the Telegram mock begins accepting owner notifications")
  public void acceptOwner() {
    w.events.clear();
    w.reject = n -> false;
  }
}
