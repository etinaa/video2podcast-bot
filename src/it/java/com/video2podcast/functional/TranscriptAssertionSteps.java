package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.video2podcast.functional.TranscriptWorld.Call;
import io.cucumber.java.en.Then;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

/** Assertions inspect HTTP multipart/JSON and durable job files, including in-flight snapshots. */
public class TranscriptAssertionSteps {
  final TranscriptWorld w;

  public TranscriptAssertionSteps(TranscriptWorld world) {
    w = world;
  }

  @Then(
      "the Telegram mock receives in order an answerCallbackQuery with {string}, a reply-markup edit with {string}, one sendDocument and a reply-markup edit with {string}")
  public void order(String answer, String progress, String sent) {
    assertThat(w.tapCalls().stream().map(Call::method).toList())
        .containsExactly(
            "answercallbackquery",
            "editmessagereplymarkup",
            "senddocument",
            "editmessagereplymarkup");
    assertThat(w.calls("answerCallbackQuery").getFirst().json().path("text").asText())
        .isEqualTo(answer);
    assertThat(w.calls("editMessageReplyMarkup").getFirst().json().toString()).contains(progress);
    Call finalEdit = w.calls("editMessageReplyMarkup").getLast();
    assertThat(finalEdit.json().toString()).contains(sent);
    assertThat(finalEdit.persisted().path("files").path("TRANSCRIPT").path("state").asText())
        .isEqualTo("SENT");
    assertThat(
            w.calls("sendDocument")
                .getFirst()
                .persisted()
                .path("files")
                .path("TRANSCRIPT")
                .path("state")
                .asText())
        .isEqualTo("SENDING");
  }

  @Then(
      "the sendDocument multipart request targets chat {long} with filename {string}, the unchanged transcript bytes and no caption")
  public void multipart(long chat, String filename) {
    w.multipart(filename, chat);
  }

  @Then(
      "both keyboard edits target chat {long} and message {int} with audio first and transcript second")
  public void targets(long chat, int message) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup")) {
      assertThat(c.json().path("chat_id").asLong()).isEqualTo(chat);
      assertThat(c.json().path("message_id").asInt()).isEqualTo(message);
      boolean first = c == w.calls("editMessageReplyMarkup").getFirst();
      String audioLabel = "Download audio";
      String transcriptLabel = first ? "Sending transcript…" : "Transcript sent";
      if (w.audio != null) {
        audioLabel = first ? "Preparing audio…" : "Audio sent";
        transcriptLabel = "Download transcript";
      }
      w.labels(c, audioLabel, transcriptLabel);
    }
  }

  @Then("both keyboard edits keep {string} with callback data {string}")
  public void keepAudio(String label, String data) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup")) {
      JsonNode b = w.keyboard(c).path(0).path(w.audio == null ? 0 : 1);
      assertThat(b.path("text").asText()).isEqualTo(label);
      assertThat(b.path("callback_data").asText()).isEqualTo(data);
    }
  }

  @Then("both keyboard edits keep transcript callback data {string}")
  public void keepTranscript(String data) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup"))
      assertThat(w.keyboard(c).path(0).path(1).path("callback_data").asText()).isEqualTo(data);
  }

  @Then("the Telegram mock receives no editMessageText or sendAudio request")
  public void noOtherCalls() {
    assertThat(w.calls("editMessageText")).isEmpty();
    assertThat(w.calls("sendAudio")).isEmpty();
  }

  @Then(
      "job.json records TRANSCRIPT state {string} and sentAt no earlier than the successful upload response")
  public void successfulTime(String state) throws Exception {
    assertThat(w.uploadSuccess).isNotNull();
    w.state(state, true);
    assertThat(Instant.parse(w.file("TRANSCRIPT").path("sentAt").asText()))
        .isAfterOrEqualTo(w.uploadSuccess);
  }

  @Then("job.json records TRANSCRIPT state {string} and null sentAt")
  @Then(
      "before the upload response is released job.json records TRANSCRIPT state {string} and null sentAt")
  public void nullTime(String state) throws Exception {
    w.state(state, false);
  }

  @Then("job.json records TRANSCRIPT state {string} and nonnull sentAt")
  public void sentTime(String state) throws Exception {
    w.state(state, true);
  }

  JsonNode beforeJob() throws Exception {
    return PipelineWorld.JSON.readTree(w.before.get("job.json"));
  }

  @Then("the AUDIO entry and both expiresAt values are unchanged")
  public void metadataUnchanged() throws Exception {
    ObjectNode expected = (ObjectNode) beforeJob();
    ObjectNode actual = w.job();
    for (ObjectNode j : List.of(expected, actual)) {
      ObjectNode f = (ObjectNode) j.path("files").path("TRANSCRIPT");
      f.remove(List.of("state", "sentAt"));
    }
    assertThat(actual).isEqualTo(expected);
  }

  @Then("the job directory still contains {string}, {string} and {string}")
  public void retained(String a, String b, String c) throws Exception {
    for (String file : List.of(a, b, c)) assertThat(w.dir.resolve(file)).isRegularFile();
    assertThat(Files.readAllBytes(w.dir.resolve("source.m4a")))
        .containsExactly(w.before.get("source.m4a"));
    assertThat(Files.readAllBytes(w.dir.resolve("transcript.txt")))
        .containsExactly(w.transcriptBytes);
  }

  @Then("the Telegram mock receives one sendDocument request")
  public void uploadAttempt() {
    assertThat(w.calls("sendDocument")).hasSize(1);
  }

  @Then("before the upload response is released the summary keyboard shows {string} and {string}")
  public void progress(String transcript, String audio) {
    assertThat(w.releaseUpload.getCount()).isEqualTo(1);
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(1);
    w.labels(w.calls("editMessageReplyMarkup").getFirst(), audio, transcript);
  }

  @Then("the tap produces only answerCallbackQuery with {string}")
  @Then("the second tap produces only answerCallbackQuery with {string}")
  @Then("the concurrent tap produces only answerCallbackQuery with {string}")
  public void refusal(String text) {
    w.refusal(text);
  }

  @Then("the Telegram mock has received exactly one sendDocument request in total")
  public void once() {
    uploadAttempt();
  }

  @Then("the second tap leaves job.json unchanged")
  public void jobUnchanged() throws Exception {
    assertThat(Files.readAllBytes(w.dir.resolve("job.json")))
        .containsExactly(w.before.get("job.json"));
  }

  @Then("the job files are unchanged")
  @Then("the tap leaves all remaining job files unchanged")
  public void unchanged() throws Exception {
    w.unchanged();
  }

  @Then("the Telegram mock receives one sendDocument targeting stored chat {long}")
  public void destination(long chat) {
    uploadAttempt();
    w.multipart("Budget meeting.txt", chat);
  }

  @Then("the reply-markup edits target stored chat {long} and message {int}")
  public void storedEdits(long chat, int message) {
    targets(chat, message);
  }

  @Then("the Telegram mock receives no document upload or message edit targeting chat {long}")
  public void noRedirect(long chat) {
    for (Call c : w.events) {
      if (c.method().equals("senddocument"))
        assertThat(c.request().getPart("chat_id").getBody().asString())
            .isNotEqualTo(Long.toString(chat));
      else if (c.method().startsWith("editmessage"))
        assertThat(c.json().path("chat_id").asLong()).isNotEqualTo(chat);
    }
  }

  @Then(
      "^the tap produces (one successful sendDocument and persisted TRANSCRIPT state SENT|only answerCallbackQuery with text .+)$")
  public void outcome(String outcome) throws Exception {
    if (outcome.startsWith("one successful")) {
      successfulUpload();
      w.state("SENT", true);
    } else w.refusal(outcome.substring("only answerCallbackQuery with text ".length()));
  }

  @Then("the job files and sentinel file are unchanged")
  public void sentinel() throws Exception {
    w.unchanged();
    assertThat(Files.readString(w.sentinel)).isEqualTo("keep me");
  }

  @Then("the Telegram mock receives one sendDocument attempt without an automatic retry")
  public void noRetry() {
    uploadAttempt();
  }

  @Then("the summary keyboard is restored to {string} with {string} unchanged")
  public void restored(String transcript, String audio) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    w.labels(
        w.calls("editMessageReplyMarkup").getLast(),
        w.audio == null ? audio : transcript,
        w.audio == null ? transcript : audio);
  }

  @Then("the Telegram mock receives a sendMessage to chat {long} with {string}")
  public void failureMessage(long chat, String text) {
    assertThat(w.calls("sendMessage"))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.json().path("chat_id").asLong()).isEqualTo(chat);
          assertThat(c.json().path("text").asText()).isEqualTo(text);
        });
  }

  @Then(
      "the Telegram mock has received exactly two sendDocument attempts in total with one successful response")
  public void retry() {
    assertThat(w.calls("sendDocument")).hasSize(2);
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(e ->
                    e.getRequest().getUrl().endsWith("/senddocument")
                        && e.getResponse().getStatus() == 200)
                .count())
        .isEqualTo(1);
  }

  @Then("no Telegram message contains the transport diagnostic or a filesystem path")
  public void safeErrors() {
    for (Call c : w.events)
      if (!c.method().equals("senddocument"))
        assertThat(c.request().getBodyAsString())
            .doesNotContain(
                "transport diagnostic", "/private/jobs", w.root.toString(), "Exception");
  }

  @Then("the Telegram mock receives one answerCallbackQuery attempt without retry")
  public void answerOnce() {
    assertThat(w.calls("answerCallbackQuery")).hasSize(1);
  }

  @Then("the Telegram mock receives one successful sendDocument")
  public void successfulUpload() {
    uploadAttempt();
    assertThat(w.uploadSuccess).isNotNull();
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().endsWith("/senddocument")))
        .singleElement()
        .satisfies(e -> assertThat(e.getResponse().getStatus()).isEqualTo(200));
  }

  @Then("the sendDocument multipart filename is {string}")
  public void filename(String filename) {
    uploadAttempt();
    w.multipart(filename, 101);
  }

  @Then("the stored title and transcript bytes are unchanged")
  public void titleAndBytes() throws Exception {
    assertThat(w.job().path("title").asText()).isEqualTo(w.originalTitle);
    assertThat(Files.readAllBytes(w.dir.resolve("transcript.txt")))
        .containsExactly(w.transcriptBytes);
  }

  @Then("the sendDocument multipart filename is {int} copies of {string} followed by {string}")
  public void longFilename(int n, String letter, String suffix) {
    filename(letter.repeat(n) + suffix);
  }

  @Then("job.json retains the original {int}-character title")
  public void longTitle(int n) throws Exception {
    titleAndBytes();
    assertThat(w.originalTitle.length()).isEqualTo(n);
  }

  @Then(
      "both reply-markup edits retain the audio button label {string} and its original callback data")
  public void audioLabels(String label) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup"))
      w.labels(
          c,
          label,
          c == w.calls("editMessageReplyMarkup").getFirst()
              ? "Sending transcript…"
              : "Transcript sent");
  }

  @Then("the AUDIO entry is unchanged")
  public void audioUnchanged() throws Exception {
    assertThat(w.job().path("files").path("AUDIO"))
        .isEqualTo(beforeJob().path("files").path("AUDIO"));
  }

  @Then("the Telegram mock receives no reply-markup edit")
  public void noEdits() {
    assertThat(w.calls("editMessageReplyMarkup")).isEmpty();
  }

  @Then("the AUDIO entry and remaining source file are unchanged")
  public void audioAndSource() throws Exception {
    audioUnchanged();
    assertThat(Files.readAllBytes(w.dir.resolve("source.m4a")))
        .containsExactly(w.before.get("source.m4a"));
    assertThat(Files.exists(w.dir.resolve("transcript.txt"))).isFalse();
  }

  @Then("the TRANSCRIPT expiresAt in job.json remains {string}")
  public void retainedExpiry(String expiry) throws Exception {
    assertThat(w.file("TRANSCRIPT").path("expiresAt").asText()).isEqualTo(expiry);
  }
}
