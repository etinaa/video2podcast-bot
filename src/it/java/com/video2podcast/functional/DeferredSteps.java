package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.functional.PipelineWorld.Answer;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** T18 actions enter through Telegram, scheduler registrations, HTTP fixtures or restart. */
public class DeferredSteps {
  final PipelineWorld w;
  final FailureModeWorld h;
  final DeferredSupport d;

  public DeferredSteps(PipelineWorld world, FailureModeWorld health) {
    w = world;
    h = health;
    d = new DeferredSupport(w, h);
  }

  @Before(value = "@T18 or @T19", order = 20)
  public void begin() {
    w.health = h;
    for (String s : List.of("whisper", "ollama")) h.policy(s, "HTTP 200");
    w.properties.put("logging.level.com.video2podcast.job.HealthChecks", "INFO");
    w.properties.put("app.whisper.timeout", "PT3S");
    w.properties.put("app.ollama.timeout", "PT3S");
    d.install();
  }

  @Then(
      "deferred-jobs.json contains exactly one entry for this submission with 1 scheduled attempt")
  public void queuedOnce() {
    queued();
    attempts(1);
  }

  @Then("deferred-jobs.json contains exactly one entry for Alice's submission")
  public void aliceEntry() {
    assertThat(d.entries()).hasSize(1);
    d.parked(w.user, "http://videos.example/stalled");
  }

  @Then("deferred-jobs.json contains exactly one entry for the waiting submission")
  public void waitingEntry() {
    assertThat(d.entries()).hasSize(1);
    d.parked(w.bob, "http://videos.example/waiting");
  }

  @Given("a snapshot of allowed-users.json has been recorded")
  public void accessSnapshot() throws Exception {
    new FailureModeSteps(h).accessSnapshot();
  }

  @Then("allowed-users.json matches its snapshot")
  public void accessUnchanged() throws Exception {
    new FailureModeSteps(h).accessUnchanged();
  }

  @Then("the Ollama mock receives a POST request to {string}")
  public void chatRequest(String path) {
    assertThat(w.service("ollama")).anyMatch(e -> e.url().equals(path));
  }

  @Given(
      "^the (Whisper|Ollama) health HTTP mock refuses connections(?: for the next accepted link)?$")
  public void down(String service) {
    h.next(service, "connection refusal");
  }

  @Given("both service health HTTP mocks answer HTTP 200")
  public void healthy() {
    new FailureModeSteps(h).healthy();
  }

  @When("the Whisper health HTTP mock begins answering HTTP 200")
  public void whisperUp() {
    h.next("whisper", "HTTP 200");
  }

  @Given(
      "^the (Whisper|Ollama) base URL refuses (?:inference|chat) connections after its successful health response$")
  public void midOutage(String service) {
    if (service.equals("Ollama"))
      w.defaultWhisper = Answer.json("{\"text\":\"First transcription\",\"language\":\"english\"}");
    if (service.equals("Whisper")) w.refuseWhisperDuringWork = true;
    else w.refuseOllamaDuringWork = true;
  }

  @Given("the user has submitted {string} and its deferred entry is durably parked")
  public void submit(String url) {
    h.send(w.user, url);
    d.parked(w.user);
    d.remember();
  }

  @Given("the user has one durably parked link")
  public void one() {
    down("Whisper");
    submit("http://videos.example/video");
  }

  @Given("^user (\\d+) has one durably parked link$")
  public void parkedUser(long id) {
    w.user = id;
    w.start();
    w.allow(id);
    if (id == 202) w.allow(101);
    one();
  }

  @Given("allowed users 101 and 202 have separately parked links in that order")
  public void two() throws Exception {
    w.fixture(59, "m4a", "http://videos.example/second");
    w.start();
    w.allow(101);
    w.allow(202);
    down("Whisper");
    w.user = 101;
    submit("http://videos.example/video");
    w.user = 202;
    submit("http://videos.example/second");
    assertThat(d.entries().valueStream().map(j -> j.path("ownerUserId").asLong()).toList())
        .containsExactly(101L, 202L);
  }

  @Given("the configured owner has one durably parked link and no explicit allowlist entry")
  public void owner() {
    w.user = 1;
    w.start();
    w.allow(101);
    one();
    assertThat(h.access().path("users").valueStream()).noneMatch(j -> j.path("id").asLong() == 1);
  }

  @Given("DEFERRED_MAX_ATTEMPTS is {int}")
  public void budget(int count) {
    w.prop("app.deferred.max-attempts", "" + count);
  }

  @Given("DEFERRED_MAX_ATTEMPTS is not overridden")
  public void defaultBudget() {
    assertThat(w.properties).doesNotContainKey("app.deferred.max-attempts");
  }

  @Given("DEFERRED_RETRY_INTERVAL is {string}")
  public void interval(String interval) {
    w.prop("app.deferred.retry-interval", interval);
  }

  @Given("^the parked entry and Telegram request journal (?:have|has) been recorded$")
  public void remember() throws Exception {
    d.remember();
    d.queueSnapshot = Files.readAllBytes(d.file());
  }

  @Given("a snapshot of deferred-jobs.json has been recorded")
  public void snapshot() throws Exception {
    remember();
  }

  @Given("one scheduled retry has completed with an outage")
  public void firstRetry() throws Exception {
    d.tick();
    assertThat(d.entry(w.user).path("attempts").asInt()).isEqualTo(1);
  }

  @When(
      "^the (?:application clock advances by two hours and the )?next (?:ten-minute )?deferred retry interval elapses without any Telegram update$")
  @When("another two-hour deferred retry interval elapses without any Telegram update")
  @When("another deferred retry interval elapses without any Telegram update")
  @When("the twelfth two-hour deferred retry interval elapses without any Telegram update")
  public void tick() throws Exception {
    d.tick();
  }

  @When("eleven successive two-hour deferred retry intervals elapse without any Telegram update")
  public void eleven() throws Exception {
    for (int i = 0; i < 11; i++) d.tick();
  }

  public void restart() throws Exception {
    d.restart();
  }

  @Given("deferred-jobs.json beside allowed-users.json contains exactly {string}")
  public void corrupt(String text) throws Exception {
    w.start();
    Files.writeString(d.file(), text);
  }

  @Given("deferred-jobs.json does not exist beside allowed-users.json")
  public void missing() throws Exception {
    w.start();
    assertThat(d.file()).doesNotExist();
  }

  @Then("a sibling file named {string} contains exactly {string}")
  public void quarantine(String pattern, String text) throws Exception {
    assertThat(pattern).isEqualTo("deferred-jobs.json.corrupt-<time>");
    try (var files = Files.list(w.root)) {
      var matches =
          files
              .filter(
                  p -> p.getFileName().toString().matches("deferred-jobs\\.json\\.corrupt-\\d+"))
              .toList();
      assertThat(matches)
          .singleElement()
          .satisfies(p -> assertThat(PipelineWorld.read(p)).isEqualTo(text));
    }
  }

  @Then("^the deferred queue is empty$")
  public void empty() {
    PipelineWorld.until(() -> assertThat(d.entries()).isEmpty());
  }

  @Then("startup sends no deferred-job Telegram notification")
  public void noStartupNotice() {
    assertThat(w.calls("sendMessage"))
        .noneMatch(e -> Set.of(DeferredSupport.PARKED, DeferredSupport.GAVE_UP).contains(e.text()));
  }

  @Then("deferred-jobs.json contains exactly one entry for this submission")
  public void queued() {
    PipelineWorld.until(() -> assertThat(d.entries()).hasSize(1));
    d.parked(w.user);
  }

  @Then(
      "deferred-jobs.json beside allowed-users.json contains exactly one entry for this URL and the sending user's id, chat id and queued status message id")
  public void durable() {
    queued();
  }

  @Then("the entry records 0 scheduled attempts and parked time {string}")
  public void initial(String time) {
    assertThat(d.entry(w.user).path("attempts").asInt()).isZero();
    assertThat(d.entry(w.user).path("parkedAt").asText()).isEqualTo(time);
  }

  @Then(
      "^deferred-jobs.json contains (?:that same|that) entry with (\\d+) scheduled attempts?(?: and unchanged URL, owner, chat, status message id and parked time)?$")
  public void attempts(int count) {
    assertThat(d.entries()).hasSize(1);
    JsonNode current = d.entry(w.user);
    assertThat(current.path("attempts").asInt()).isEqualTo(count);
    if (d.before != null && !d.before.isEmpty()) {
      JsonNode previous = d.before.get(0);
      for (String field :
          List.of("id", "url", "ownerUserId", "chatId", "statusMessageId", "parkedAt"))
        assertThat(current.path(field)).as(field).isEqualTo(previous.path(field));
    }
  }

  @Then(
      "deferred-jobs.json retains the URL, owner, chat, status message id, parked time and count of 1")
  public void retained() {
    assertThat(d.entries())
        .isEqualTo(
            PipelineWorld.json(new String(d.queueSnapshot, java.nio.charset.StandardCharsets.UTF_8))
                .path("jobs"));
    attempts(1);
  }

  @Then("deferred-jobs.json contains no entry for this submission")
  public void removed() {
    PipelineWorld.until(() ->
        assertThat(d.entries().valueStream())
            .noneMatch(j -> j.path("ownerUserId").asLong() == d.target));
  }

  @Then("^the original queued status is edited(?: exactly once)? to \"([^\"]+)\"$")
  public void status(String text) {
    h.queuedTerminal(w.user, text);
    var queued =
        h.messages(w.user).stream().filter(e -> e.text().startsWith("Queued:")).toList().getLast();
    assertThat(
            w.calls("editMessageText").stream()
                .filter(e ->
                    e.json().path("chat_id").asLong() == w.user
                        && e.json().path("message_id").asInt() == w.responseIds.get(queued)
                        && e.text().equals(text)))
        .hasSize(1);
  }

  @Then("no separate service-failure sendMessage or {string} edit is sent for this submission")
  public void noFailure(String text) {
    noSendMessageAfterQueued(w.user);
    assertThat(w.calls("editMessageText")).noneMatch(e -> e.text().equals(text));
  }

  private void noSendMessageAfterQueued(long user) {
    var queued =
        h.messages(user).stream().filter(e -> e.text().startsWith("Queued:")).toList().getLast();
    assertThat(h.messages(user)).noneMatch(e -> e.nanos() > queued.nanos());
  }

  @Then("no new Telegram message or status edit is sent for this submission")
  public void silent() {
    int baseline = d.parkingNotices.get(d.target);
    // Eleven successive intervals must be silent over the whole history, not just the last pass.
    assertThat(d.telegram().size()).isEqualTo(baseline);
    assertThat(h.messages(d.target)).noneMatch(e -> e.text().equals(DeferredSupport.GAVE_UP));
    assertThat(
            w.calls("editMessageText").stream()
                .filter(e -> e.text().equals(DeferredSupport.PARKED)))
        .hasSize(1);
  }

  @Then("^the media proxy receives (?:a new|another) download for \"([^\"]+)\"$")
  public void download(String url) {
    assertThat(
            w.commands.stream()
                .filter(a -> PipelineWorld.stage(a).equals("download") && a.contains(url)))
        .isNotEmpty();
    assertThat(w.service("media"))
        .anyMatch(e -> e.url().equals(java.net.URI.create(url).getPath() + ".m4a"));
    assertThat(d.downloads()).isGreaterThan(d.downloadsBefore);
  }

  @Then("^the Whisper mock receives the recording's first chunk(?: again)?$")
  public void chunk() {
    assertThat(w.service("whisper").stream().map(PipelineWorld.Event::chunk))
        .contains("chunk-000.wav");
    assertThat(w.service("whisper").size()).isGreaterThan(d.whisperBefore);
  }

  @Then(
      "^the Telegram mock receives the usual (?:HTML summary in the stored private chat with \"Download audio\" then \"Download transcript\"|summary(?: in the original stored chat)? with both download buttons)$")
  public void summary() {
    d.summary(d.target);
  }

  @Then("^user (\\d+) receives the usual summary with both download buttons$")
  public void userSummary(long user) {
    d.summary(user);
  }

  @Then("the owner receives the usual summary with both download buttons")
  public void ownerSummary() {
    d.summary(1);
  }

  @Then("the successful job directory contains {string}, {string} and {string}")
  public void files(String a, String b, String c) {
    for (String name : List.of(a, b, c))
      assertThat(w.publishedDir().resolve(name)).isReadable().isRegularFile();
  }

  @Then("both retained files expire one hour after successful summary publication")
  public void expiry() {
    new PipelineAssertionSteps(w).retention(1, "hour");
  }

  @Then("no second summary or media download is produced for this submission")
  public void noReplay() {
    assertThat(w.summaries()).hasSize(d.summariesBefore);
    assertThat(d.downloads()).isEqualTo(d.downloadsBefore);
  }

  @Given("the Whisper mock has already returned text {string}")
  public void firstText(String text) {
    // Configure before initial processing via the mid-job outage setup, then observe its result.
    assertThat(w.service("whisper")).isNotEmpty();
    assertThat(w.defaultWhisper.body).contains(text);
  }

  @When("the Whisper mock begins returning text {string}")
  public void freshText(String text) {
    w.defaultWhisper =
        Answer.json(PipelineWorld.stringify(Map.of("text", text, "language", "english")));
  }

  @When("the Ollama mock begins returning successful answers")
  public void ollamaUp() {
    d.restoreModels();
    if (!w.ollama.isRunning()) w.ollama.start();
    w.defaultOllama = null;
  }

  @Then("the Ollama mock receives a chat request containing {string}")
  public void contains(String text) {
    assertThat(w.service("ollama")).anyMatch(e -> e.json().toString().contains(text));
  }

  @Then("the stored chat receives exactly one terminal notification {string}")
  public void exhausted(String text) {
    h.message(d.target, text);
    assertThat(h.messages(d.target).stream().filter(e -> e.text().equals(text))).hasSize(1);
    h.queuedTerminal(d.target, "Failed.");
  }

  @Then(
      "^no further (?:health request or terminal notification|notification or health request) is produced for this submission$")
  public void noFurther() {
    assertThat(h.probes).hasSize(d.probesBefore);
    assertThat(d.telegram()).hasSize(d.telegramBefore);
  }

  @Then("no terminal notification says {string}")
  public void noExhaustion(String text) {
    assertThat(h.messages(d.target)).noneMatch(e -> e.text().equals(text));
  }

  @When("^the next attempt encounters (.+)$")
  public void failure(String kind) throws Exception {
    var setup = new PipelineSetupSteps(w);
    if (kind.contains("Whisper answer timeout")) {
      w.defaultWhisper = Answer.failure("answer timeout");
      w.defaultWhisper.delay = 4000;
    } else if (kind.contains("Ollama answer timeout")) {
      w.defaultOllama = Answer.json("{}");
      w.defaultOllama.delay = 4000;
    } else if (kind.contains("Ollama HTTP")) w.defaultOllama = Answer.failure("status 500");
    else setup.failure(kind);
  }

  @Then("the stored chat receives the normal error {string}")
  public void normal(String text) {
    h.message(d.target, text);
    h.queuedTerminal(d.target, "Failed.");
  }

  @Then("^user (101) receives \"([^\"]+)\"$")
  public void error(long user, String text) {
    h.message(user, text);
  }

  @Given("user 101's next download fails with private metadata")
  public void privateFirst() {
    w.privateMetadataUrl = "http://videos.example/video";
  }

  @Then("deferred-jobs.json records one scheduled attempt for each entry in order 101 then 202")
  public void ordered() {
    assertThat(d.entries().valueStream().map(j -> j.path("ownerUserId").asLong()).toList())
        .containsExactly(101L, 202L);
    assertThat(d.entries().valueStream()).allMatch(j -> j.path("attempts").asInt() == 1);
  }

  @Then("user 101's summary is sent before user 202's summary")
  public void summaryOrder() {
    d.summary(101);
    d.summary(202);
    assertThat(w.summaries().stream().map(e -> e.json().path("chat_id").asLong()).toList())
        .containsExactly(101L, 202L);
  }

  @Then("no two model requests or media processes are in flight concurrently")
  public void sequential() {
    var windows = new ArrayList<>(w.processWindows);
    for (var e : w.events)
      if (Set.of("whisper", "ollama").contains(e.service())) {
        assertThat(w.responseReady).containsKey(e);
        windows.add(new PipelineWorld.ProcessWindow(e.nanos(), w.responseReady.get(e)));
      }
    windows.sort(Comparator.comparingLong(PipelineWorld.ProcessWindow::start));
    for (int i = 1; i < windows.size(); i++)
      assertThat(windows.get(i).start()).isGreaterThanOrEqualTo(windows.get(i - 1).end());
  }

  @Given("the Whisper mock holds its first answer within its configured timeout")
  public void hold() {
    w.whisperHold = w.hold();
    w.holdFirstWhisper = true;
    d.heldPass = true;
  }

  @When("the Whisper mock has received the retry's first chunk and holds its answer")
  public void held() {
    w.awaitHeld(w.whisperHold);
  }

  @When("another deferred retry interval elapses while that answer is held")
  public void overlap() throws Exception {
    d.tick();
  }

  @Then("the Whisper mock has received that chunk exactly once for the deferred attempt")
  public void singleChunk() {
    assertThat(w.service("whisper")).hasSize(1);
  }

  @Then("no second download for that deferred attempt is recorded")
  public void singleDownload() {
    assertThat(d.downloads()).isEqualTo(1);
  }

  @When("the Whisper mock releases the successful response for user 101")
  public void release() {
    w.release(w.whisperHold);
    d.heldPass = false;
  }

  @Then("exactly one usual summary is sent for this deferred entry")
  public void singleSummary() {
    summary();
    PipelineWorld.until(() -> assertThat(d.entries()).isEmpty());
    var queued =
        h.messages(d.target).stream()
            .filter(e -> e.text().startsWith("Queued:"))
            .toList()
            .getLast();
    int statusId = w.responseIds.get(queued);
    org.awaitility.Awaitility.await()
        .during(Duration.ofSeconds(3))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          assertThat(d.downloads()).isEqualTo(1);
          assertThat(w.service("whisper")).hasSize(1);
          assertThat(w.service("ollama")).hasSize(1);
          assertThat(w.summaries()).hasSize(1);
          assertThat(
                  w.calls("editMessageText").stream()
                      .filter(e ->
                          e.json().path("chat_id").asLong() == d.target
                              && e.json().path("message_id").asInt() == statusId
                              && e.text().equals("Done.")))
              .hasSize(1);
        });
  }

  @Given("user 101 has a fresh link waiting for a held Whisper answer")
  public void freshHeld() throws Exception {
    healthy();
    if (!h.allowed(101)) w.allow(101);
    w.fixture(59, "m4a", "http://videos.example/fresh");
    hold();
    h.send(101, "http://videos.example/fresh");
    w.awaitHeld(w.whisperHold);
  }

  @When("the owner sends {string} in a private chat")
  public void ownerCommand(String command) {
    h.send(1, command);
  }

  @Given("the owner has removed user 202 with {string}")
  public void revoked(String command) throws Exception {
    ownerCommand(command);
    denied();
  }

  @When("the access-removal acknowledgement has been received")
  public void denied() throws Exception {
    h.settleUpdates();
    assertThat(h.allowed(202)).isFalse();
    d.remember();
  }

  @Then("user 202's deferred entry is removed")
  public void revokedRemoved() {
    d.target = 202;
    removed();
  }

  @Then(
      "no health, media download, inference or chat request is made for user 202's deferred retry")
  public void noRevokedWork() {
    assertThat(h.probes).hasSize(d.probesBefore);
    assertThat(d.downloads()).isEqualTo(d.downloadsBefore);
    assertThat(w.service("whisper")).hasSize(d.whisperBefore);
    assertThat(w.service("ollama")).hasSize(d.ollamaBefore + (d.whisperBefore > 0 ? 1 : 0));
    assertThat(w.commands.subList(d.commandsBefore, w.commands.size())).isEmpty();
  }

  @Then("the removal produces no Telegram notification to user 202")
  public void silentRemoval() {
    d.target = 202;
    assertThat(d.telegram()).hasSize(d.telegramBefore);
  }

  @Given("the Telegram mock rejects the next temporarily-unavailable status edit with HTTP {int}")
  public void rejectPark(int code) {
    w.rejectionStatus = code;
    w.reject = n -> n.path("text").asText().equals(DeferredSupport.PARKED);
  }

  @Then("no fallback outage sendMessage is attempted")
  public void noFallback() {
    noSendMessageAfterQueued(w.user);
    assertThat(h.messages(w.user))
        .noneMatch(e -> Set.of(DeferredSupport.PARKED, DeferredSupport.GAVE_UP).contains(e.text()));
  }

  @Given("the Telegram mock rejects the exhaustion notification with HTTP {int}")
  public void rejectExhaustion(int code) {
    w.rejectionStatus = code;
    w.reject = n -> n.path("text").asText().equals(DeferredSupport.GAVE_UP);
  }

  @Then("the Telegram mock records exactly one exhaustion notification attempt")
  public void oneGiveUp() {
    assertThat(h.messages(d.target).stream().filter(e -> e.text().equals(DeferredSupport.GAVE_UP)))
        .hasSize(1);
  }

  @Given("the Whisper mock returns status 500 for both attempts")
  public void httpFailure() {
    w.defaultWhisper = Answer.failure("status 500");
  }

  @Then("no health, media download, inference or chat request is made for this submission")
  public void noWork() {
    assertThat(w.commands).isEmpty();
    assertThat(w.service("whisper")).isEmpty();
    assertThat(w.service("ollama")).isEmpty();
    assertThat(h.probes.stream().filter(p -> p.received >= w.lastUpdateNanos)).isEmpty();
  }

  @Then("user 202 receives only the existing access-refusal reply")
  public void refused() {
    h.message(202, "No access. Send /start to request it. Your id: <code>202</code>");
    assertThat(h.messages(202)).hasSize(1);
  }

  @Then("the original parked status has been left unchanged")
  public void unchanged() {
    silent();
  }

  @When("the user taps the successful summary's transcript button in the stored private chat")
  public void tap() throws Exception {
    var e = w.summary();
    h.callback(
        d.target, "transcript:" + PipelineAssertionSteps.callbackId(e), w.responseIds.get(e));
  }

  @Then(
      "the Telegram mock receives one sendDocument containing the fresh transcript and targeting the stored chat")
  public void document() {
    PipelineWorld.until(() -> assertThat(w.calls("sendDocument")).hasSize(1));
    var request =
        w.telegram
            .findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                        FunctionalTest.telegramPath("sendDocument"))))
            .getFirst();
    assertThat(request.getPart("chat_id").getBody().asString()).isEqualTo("" + d.target);
    String ref = request.getPart("document").getBody().asString().substring("attach://".length());
    assertThat(request.getPart(ref).getBody().asString())
        .isEqualTo(PipelineWorld.read(w.publishedDir().resolve("transcript.txt")));
  }
}
