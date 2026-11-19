package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Retained-job fixtures are written directly before driving the real Telegram consumer. */
public class TranscriptSetupSteps {
  final TranscriptWorld w;

  public TranscriptSetupSteps(TranscriptWorld world) {
    w = world;
  }

  @Given("an allowed Telegram user with id {long}")
  public void allowed(long id) {
    w.user = id;
    w.allowed.add(id);
  }

  @Given(
      "a published job {string} exists on disk for user {long} in private chat {long} with summary message id {int}")
  public void published(String job, long owner, long chat, int message) throws Exception {
    assertThat(job).isEqualTo(TranscriptWorld.JOB);
    w.change(j -> j.put("ownerUserId", owner).put("chatId", chat).put("summaryMessageId", message));
  }

  @Given("the stored job title is {string}")
  public void title(String title) throws Exception {
    w.originalTitle = title;
    w.change(j -> j.put("title", title));
  }

  @Given(
      "the job's AUDIO and TRANSCRIPT entries are {string} with expiresAt {string} and null sentAt")
  public void entries(String state, String expiry) throws Exception {
    w.change(j -> {
      for (String kind : List.of("AUDIO", "TRANSCRIPT")) {
        ((ObjectNode) j.path("files").path(kind))
            .put("state", state)
            .put("expiresAt", expiry)
            .remove("sentAt");
      }
    });
  }

  @Given("the job directory contains {string} and UTF-8 {string} with content {string}")
  public void content(String source, String file, String text) throws Exception {
    assertThat(source).isEqualTo("source.m4a");
    assertThat(file).isEqualTo("transcript.txt");
    // The feature's quoted \n denotes one newline in the retained UTF-8 file.
    w.transcriptBytes = text.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
    Files.write(w.dir.resolve(file), w.transcriptBytes);
  }

  @Given("the Telegram mock accepts document uploads and keyboard edits")
  public void accepts() {
    /* Default real HTTP boundary response. */
  }

  @Given("the Telegram mock holds the document upload response")
  public void hold() {
    w.hold = true;
  }

  @Given("the Telegram mock has received the document upload and holds its response")
  public void held() {
    assertThat(w.uploadReceived.getCount()).isZero();
    assertThat(w.releaseUpload.getCount()).isEqualTo(1);
    assertThat(w.pending.getFirst().isDone()).isFalse();
  }

  @When("the Telegram mock releases the successful upload response")
  public void release() throws Exception {
    w.finishUpload();
  }

  @Given("the user has successfully downloaded this job's transcript through a Telegram callback")
  public void alreadySent() throws Exception {
    tap("transcript:" + TranscriptWorld.JOB, 701, 101);
    w.state("SENT", true);
  }

  @Given("Telegram user {long} is allowed and is not the bot administrator")
  public void other(long id) {
    w.allowed.add(id);
    assertThat(id).isNotEqualTo(w.admin);
  }

  @Given("Telegram user {long} is allowed and is the bot administrator")
  public void admin(long id) {
    w.admin = id;
    w.allowed.add(id);
  }

  @Given("user {long} has been removed from the allowlist and is not the bot administrator")
  public void revoked(long id) {
    assertThat(w.context).isNull();
    assertThat(id).isNotEqualTo(w.admin);
    w.allowed.remove(id);
  }

  @Given("the TRANSCRIPT expiresAt in job.json is {string}")
  public void expires(String expiry) throws Exception {
    w.change(j -> ((ObjectNode) j.path("files").path("TRANSCRIPT")).put("expiresAt", expiry));
  }

  @Given("the job directory is absent")
  public void noDirectory() throws Exception {
    try (var paths = Files.walk(w.dir)) {
      for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  @Given("job.json is absent")
  public void noJob() throws Exception {
    Files.delete(w.dir.resolve("job.json"));
  }

  @Given("job.json contains invalid JSON")
  public void corrupt() throws Exception {
    Files.writeString(w.dir.resolve("job.json"), "{broken");
  }

  @Given("job.json has no TRANSCRIPT entry")
  public void noEntry() throws Exception {
    w.change(j -> ((ObjectNode) j.path("files")).remove("TRANSCRIPT"));
  }

  @Given("the TRANSCRIPT state in job.json is EXPIRED")
  public void expired() throws Exception {
    state("EXPIRED");
  }

  @Given("the TRANSCRIPT state in job.json is {string}")
  @Given("the TRANSCRIPT state in job.json is {string} before application startup")
  public void state(String state) throws Exception {
    w.change(j -> {
      ObjectNode f = (ObjectNode) j.path("files").path("TRANSCRIPT");
      f.put("state", state);
      if (state.equals("SENT")) f.put("sentAt", "2026-11-19T12:01:00Z");
      else f.remove("sentAt");
    });
  }

  @Given("a sentinel file outside the job directory contains {string}")
  public void sentinel(String text) throws Exception {
    w.sentinel = w.root.resolve("sentinel");
    Files.writeString(w.sentinel, text);
  }

  @Given(
      "the Telegram mock rejects the first sendDocument with HTTP {int} and a Bot API error response")
  public void rejectUpload(int status) {
    assertThat(status).isEqualTo(400);
    w.rejectUpload = 1;
  }

  @Given(
      "the Telegram mock accepts the upload bytes but closes the connection without a response on every upload attempt")
  public void drop() {
    w.drop = true;
  }

  @Given(
      "the Telegram mock rejects answerCallbackQuery with HTTP {int} and a Bot API error response")
  public void rejectAnswer(int status) {
    assertThat(status).isEqualTo(400);
    w.rejectAnswer = true;
  }

  @Given(
      "the Telegram mock rejects the {word} reply-markup edit with HTTP {int} and a Bot API error response")
  public void rejectEdit(String which, int status) {
    assertThat(status).isEqualTo(400);
    assertThat(which).isIn("progress", "final");
    w.rejectEdit = which.equals("progress") ? 1 : 2;
  }

  @Given("the stored job title contains {int} copies of {string}")
  public void longTitle(int count, String letter) throws Exception {
    title(letter.repeat(count));
  }

  @Given("the AUDIO state in job.json is {string}")
  public void audioState(String state) throws Exception {
    w.change(j -> {
      ObjectNode f = (ObjectNode) j.path("files").path("AUDIO");
      f.put("state", state);
      if (state.equals("SENT")) f.put("sentAt", "2026-11-19T12:01:00Z");
    });
  }

  @Given("summaryMessageId in job.json is null")
  public void noSummary() throws Exception {
    w.change(j -> j.putNull("summaryMessageId"));
  }

  @Given("the application starts with the existing job directory")
  public void startup() {
    assertThat(w.context).isNull();
    w.start();
  }

  @Given("transcript.txt is absent")
  public void noTranscript() throws Exception {
    Files.delete(w.dir.resolve("transcript.txt"));
  }

  @Given("job.json records createdAt {string}")
  public void created(String at) throws Exception {
    w.change(j -> j.put("createdAt", at));
  }

  @When("the user taps {string} on private message {int} in chat {long}")
  @When("the user taps {string} on private message {int} in chat {long} again")
  @Given("the user has tapped {string} on private message {int} in chat {long}")
  public void tap(String data, int message, long chat) throws Exception {
    w.tap(data, w.user, message, chat, "private");
  }

  @When("Telegram user {long} taps {string} on private message {int} in chat {long}")
  public void otherTap(long user, String data, int message, long chat) throws Exception {
    w.tap(data, user, message, chat, "private");
  }

  @When(
      "a concurrent Telegram update taps {string} as user {long} on private message {int} in chat {long}")
  public void concurrent(String data, long user, int message, long chat) throws Exception {
    w.tap(data, user, message, chat, "private");
  }

  @When("user {long} sends a Telegram callback with data {string} from a {word} chat")
  public void group(long user, String data, String type) throws Exception {
    w.tap(data, user, 701, -100, type);
  }
}
