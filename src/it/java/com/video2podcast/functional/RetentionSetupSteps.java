package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Retention fixture changes happen while scheduler execution is gated and no pipeline owns the job.
 */
public class RetentionSetupSteps {
  final TranscriptWorld w;

  public RetentionSetupSteps(TranscriptWorld world) {
    w = world;
  }

  @Given("the Telegram mock accepts audio uploads, document uploads and keyboard edits")
  public void baseline() throws Exception {
    w.retention.baseline();
  }

  RetentionSupport support() {
    return w.retention;
  }

  @Given("the job directory also contains {string} and {string}")
  public void extras(String a, String b) throws Exception {
    for (String name : List.of(a, b)) {
      Path file = w.dir.resolve(name);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "temporary working bytes");
    }
  }

  @Given("the job createdAt in job.json is {string}")
  public void created(String at) throws Exception {
    w.change(j -> j.put("createdAt", at));
  }

  @Given("job.json is preloaded with {word} state {string} and sentAt {string}")
  public void preloaded(String kind, String state, String sent) throws Exception {
    support().state(kind, state, sent);
  }

  @Given("job.json records AUDIO state {string} and TRANSCRIPT state {string}")
  public void terminal(String a, String t) throws Exception {
    support().state("AUDIO", a, a.equals("SENT") ? "2026-11-21T11:50:00Z" : null);
    support().state("TRANSCRIPT", t, t.equals("SENT") ? "2026-11-21T11:50:00Z" : null);
  }

  @Given("files marked EXPIRED are already absent from the job directory")
  public void absentExpired() throws Exception {
    for (String kind : List.of("AUDIO", "TRANSCRIPT"))
      if (w.file(kind).path("state").asText().equals("EXPIRED"))
        Files.deleteIfExists(w.dir.resolve(w.file(kind).path("path").asText()));
  }

  @Given("the application clock advances by two hours")
  public void advance() {
    w.clock.now = w.clock.instant().plus(Duration.ofHours(2));
  }

  @When("the next scheduled minute elapses without any Telegram update")
  public void tick() throws Exception {
    support().tick();
  }

  @When("the application clock is {string} and the next scheduled minute elapses")
  public void atAndTick(String at) throws Exception {
    w.clock.now = Instant.parse(at);
    tick();
  }

  @Given("a scheduled minute has elapsed and the job directory no longer exists")
  public void expired() throws Exception {
    tick();
    assertThat(w.dir).doesNotExist();
  }

  @When("the application restarts using the same work directory and access store")
  public void restart() throws Exception {
    support().restart();
  }

  @When("the application restarts twice using the same work directory and access store")
  public void twice() throws Exception {
    restart();
    byte[] access = support().accessBefore;
    byte[] sentinel = support().sentinelBefore;
    restart();
    support().accessBefore = access;
    support().sentinelBefore = sentinel;
  }

  @Given("job.json contains null")
  public void nullRecord() throws Exception {
    Files.writeString(w.dir.resolve("job.json"), "null");
  }

  @Given("job.json contains a different jobId from its directory name")
  public void wrongId() throws Exception {
    w.change(j -> j.put("jobId", "other-job"));
  }

  @Given("job.json contains a retained filename with parent traversal")
  public void traversal() throws Exception {
    w.change(j -> support().file(j, "AUDIO").put("path", "../outside.m4a"));
  }

  @Given("the work directory is empty")
  public void empty() throws Exception {
    RetentionSupport.erase(w.root.resolve("work"));
    Files.createDirectories(w.root.resolve("work"));
  }

  @Given("the work directory is absent")
  public void noRoot() throws Exception {
    RetentionSupport.erase(w.root.resolve("work"));
  }

  @Given("the allowed-users.json file is stored outside the work directory")
  public void access() {
    assertThat(w.root.resolve("allowed-users.json")).isRegularFile();
    assertThat(w.root.resolve("allowed-users.json").startsWith(w.root.resolve("work"))).isFalse();
  }

  @Given("both expiresAt values in job.json are null")
  public void nullExpiry() throws Exception {
    w.change(j -> {
      for (String kind : List.of("AUDIO", "TRANSCRIPT"))
        support().file(j, kind).putNull("expiresAt");
    });
  }

  @Given("the AUDIO expiresAt in job.json is null")
  public void nullAudio() throws Exception {
    w.change(j -> support().file(j, "AUDIO").putNull("expiresAt"));
  }

  @Given("the job's AUDIO and TRANSCRIPT states are {string} with null sentAt")
  public void bothStates(String state) throws Exception {
    for (String kind : List.of("AUDIO", "TRANSCRIPT")) support().state(kind, state, null);
  }

  @Given(
      "a directory named {string} under the work directory contains {string} with content {string}")
  public void unrelated(String dir, String name, String text) throws Exception {
    Files.writeString(
        Files.createDirectories(w.root.resolve("work").resolve(dir)).resolve(name), text);
  }

  @Given(
      "another published expired job {string} exists on disk for user {long} with summary message id {int}")
  public void another(String id, long owner, int message) throws Exception {
    ObjectNode j = w.job();
    j.put("jobId", id).put("ownerUserId", owner).put("summaryMessageId", message);
    for (String kind : List.of("AUDIO", "TRANSCRIPT"))
      support().file(j, kind).put("expiresAt", "2026-11-21T12:00:00Z");
    Path dir = Files.createDirectories(w.root.resolve("work").resolve(id));
    Files.writeString(dir.resolve("job.json"), PipelineWorld.JSON.writeValueAsString(j));
    for (String name : List.of("source.m4a", "transcript.txt"))
      Files.copy(w.dir.resolve(name), dir.resolve(name));
  }

  @Given(
      "the Telegram mock rejects expiry edits for message {int} with HTTP {int} and a Bot API error response")
  public void reject(int message, int status) {
    assertThat(status).isEqualTo(400);
    support().rejectMessage = message;
  }

  @Given("the Telegram mock holds the document upload response and will reject it with HTTP {int}")
  public void failed(int code) {
    assertThat(code).isEqualTo(400);
    w.hold = true;
    w.rejectUpload = 1;
  }

  @When("the Telegram mock releases the rejected upload response")
  public void release() throws Exception {
    w.finishUpload();
  }

  @Given("another allowed user's video link is waiting for a held media-download response")
  public void download() throws Exception {
    support().holdDownload = true;
    w.bot().consume(Updates.privateMessage(w.admin, "/allow 202"));
    w.events.clear();
    w.bot().consume(Updates.privateMessage(202, "https://videos.example/live"));
    assertThat(support().downloadReceived.await(15, TimeUnit.SECONDS)).isTrue();
    support().captureLive();
  }

  @Given("that live job's directory contains a partial source file but no job.json")
  public void partial() {
    assertThat(support().liveDir.resolve("source.m4a.part")).isRegularFile();
    assertThat(support().liveDir.resolve("job.json")).doesNotExist();
  }

  @Given("the media-download fault fixture exits with code 1 for {string}")
  public void failure(String url) {
    assertThat(url).isEqualTo("https://videos.example/broken");
    support().failDownload = true;
  }

  @When("the user sends a private message with text {string}")
  public void message(String text) {
    w.bot().consume(Updates.privateMessage(w.user, text));
  }

  @When("the Whisper mock releases the successful response")
  public void releaseWhisper() {
    w.audio.releaseWhisper.countDown();
  }
}
