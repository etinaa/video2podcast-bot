package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Then;
import java.nio.file.*;
import java.util.*;

/** T14 observations use persisted state and external requests, never sweeper return values. */
public class RetentionAssertionSteps {
  final TranscriptWorld w;
  @org.springframework.beans.factory.annotation.Autowired private PipelineWorld pipeline;

  public RetentionAssertionSteps(TranscriptWorld world) {
    w = world;
  }

  RetentionSupport r() {
    return w.retention;
  }

  @Then("the job directory no longer exists")
  public void removed() {
    assertThat(w.dir).doesNotExist();
  }

  @Then("the job directory no longer exists before the first scheduled minute elapses")
  public void startupRemoved() {
    assertThat(r().schedule.completed.get()).isZero();
    removed();
  }

  @Then("the job directory still contains {string} and {string}")
  public void retained(String a, String b) {
    for (String file : List.of(a, b)) assertThat(w.dir.resolve(file)).isRegularFile();
  }

  @Then("the {word} entry and its retained file bytes are unchanged")
  public void kept(String kind) throws Exception {
    r().kept(kind);
  }

  @Then("the {word} entry remains AVAILABLE with expiresAt {string}")
  public void available(String kind, String at) throws Exception {
    assertThat(w.file(kind).path("state").asText()).isEqualTo("AVAILABLE");
    assertThat(w.file(kind).path("expiresAt").asText()).isEqualTo(at);
    r().kept(kind);
  }

  @Then("job.json records TRANSCRIPT state {string} and sentAt {string}")
  public void sentAt(String state, String at) throws Exception {
    r().assertState("TRANSCRIPT", state, at);
  }

  @Then(
      "job.json records both AUDIO and TRANSCRIPT entries as {string} with expiresAt {string} and null sentAt")
  public void both(String state, String at) throws Exception {
    for (String kind : List.of("AUDIO", "TRANSCRIPT")) {
      r().assertState(kind, state, null);
      assertThat(w.file(kind).path("expiresAt").asText()).isEqualTo(at);
    }
  }

  @Then("both expiresAt values in job.json remain {string}")
  @Then("both expiresAt values remain {string}")
  public void deadlines(String at) throws Exception {
    for (String kind : List.of("AUDIO", "TRANSCRIPT"))
      assertThat(w.file(kind).path("expiresAt").asText()).isEqualTo(at);
  }

  @Then("the retained source and transcript bytes are unchanged")
  public void bytes() throws Exception {
    for (String name : List.of("source.m4a", "transcript.txt"))
      assertThat(Files.readAllBytes(w.dir.resolve(name)))
          .containsExactly(r().restartBefore.get(name));
    JsonNode prior = PipelineWorld.JSON.readTree(r().restartBefore.get("job.json"));
    for (String key :
        List.of(
            "jobId",
            "ownerUserId",
            "chatId",
            "summaryMessageId",
            "title",
            "durationSeconds",
            "createdAt"))
      assertThat(w.job().path(key)).as("preserved %s", key).isEqualTo(prior.path(key));
  }

  @Then("no automatic upload or media process occurs")
  public void noAutomatic() {
    assertThat(w.calls("sendAudio")).isEmpty();
    assertThat(w.calls("sendDocument")).isEmpty();
    assertThat(r().commands).hasSize(r().restartCommandCount);
    w.noModels();
  }

  @Then("the Telegram mock receives one successful {word} targeting stored chat {long}")
  public void successful(String method, long chat) {
    assertThat(w.calls(method))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.request().isMultipart()).isTrue();
          assertThat(c.request().getPart("chat_id").getBody().asString())
              .isEqualTo(Long.toString(chat));
          String field = method.equals("sendAudio") ? "audio" : "document";
          String reference = c.request().getPart(field).getBody().asString();
          assertThat(reference).startsWith("attach://");
          assertThat(c.request().getPart(reference.substring(9)).getBody().asBytes())
              .containsExactly(
                  r().restartBefore.get(field.equals("audio") ? "source.m4a" : "transcript.txt"));
        });
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(
                    e -> e.getRequest().getUrl().endsWith("/" + method.toLowerCase(Locale.ROOT))))
        .singleElement()
        .satisfies(e -> assertThat(e.getResponse().getStatus()).isEqualTo(200));
  }

  @Then("the summary keyboard offers {string} and {string} after any recovery edit")
  public void recovered(String a, String t) throws Exception {
    JsonNode prior = PipelineWorld.JSON.readTree(r().restartBefore.get("job.json"));
    boolean reset = prior.path("files").findValuesAsText("state").contains("SENDING");
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(reset ? 1 : 0);
    for (var c : w.calls("editMessageReplyMarkup")) w.labels(c, a, t);
    // AVAILABLE startup has no edit; the original published keyboard remains unchanged.
    for (String kind : List.of("AUDIO", "TRANSCRIPT"))
      assertThat(w.file(kind).path("state").asText()).isEqualTo("AVAILABLE");
  }

  @Then(
      "the Telegram mock receives a reply-markup edit targeting chat {long} and message {int} with {string} and {string}")
  public void edit(long chat, int message, String a, String t) {
    var edits =
        w.calls("editMessageReplyMarkup").stream()
            .filter(c -> c.json().path("message_id").asInt() == message)
            .toList();
    // Fixtures with no callback have exactly one combined expiry/recovery edit.
    if (w.calls("answerCallbackQuery").isEmpty()) assertThat(edits).hasSize(1);
    assertThat(edits)
        .anySatisfy(c -> {
          assertThat(c.json().path("chat_id").asLong()).isEqualTo(chat);
          w.labels(c, a, t);
        });
  }

  @Then("the edit keeps audio first and transcript second with the original callback data")
  public void callbackData() {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(1);
    w.labels(w.calls("editMessageReplyMarkup").getFirst(), "Audio expired", "Transcript expired");
  }

  @Then("no summary keyboard edit labels either file as expired")
  public void noExpired() {
    for (var c : w.calls("editMessageReplyMarkup"))
      assertThat(c.json().toString()).doesNotContain("Audio expired", "Transcript expired");
  }

  @Then(
      "the Telegram mock receives no sendAudio, sendDocument, sendMessage, editMessageText or deleteMessage")
  public void noMessages() {
    none("sendAudio", "sendDocument", "sendMessage", "editMessageText", "deleteMessage");
  }

  @Then("the Telegram mock receives no deleteMessage, sendAudio or sendDocument")
  public void noUploadsOrDelete() {
    none("deleteMessage", "sendAudio", "sendDocument");
  }

  @Then("the Telegram mock receives no sendAudio or sendDocument")
  public void noUploads() {
    none("sendAudio", "sendDocument");
  }

  @Then("the Telegram mock receives no sendAudio request")
  public void noAudio() {
    if (pipeline.active) {
      assertThat(pipeline.calls("sendAudio")).isEmpty();
      return;
    }
    none("sendAudio");
  }

  @Then("the Telegram mock receives no deleteMessage")
  public void noDelete() {
    none("deleteMessage");
  }

  @Then(
      "the Telegram mock has received exactly one sendAudio request and one sendDocument request in total")
  public void bothOnce() {
    assertThat(w.calls("sendAudio")).hasSize(1);
    assertThat(w.calls("sendDocument")).hasSize(1);
  }

  @Then("the Telegram mock receives no upload or reply-markup edit for that job")
  @Then("the Telegram mock receives no upload or expiry keyboard edit")
  public void noUploadsOrEdits() {
    none("sendAudio", "sendDocument", "editMessageReplyMarkup");
  }

  void none(String... methods) {
    for (String m : methods) assertThat(w.calls(m)).as(m).isEmpty();
  }

  @Then("both expired job directories no longer exist")
  public void bothGone() {
    removed();
    assertThat(w.root.resolve("work/22222222-2222-4222-8222-222222222222")).doesNotExist();
  }

  @Then(
      "the Telegram mock receives a reply-markup edit for message {int} with {string} and {string}")
  public void secondEdit(int message, String a, String t) {
    assertThat(
            w.calls("editMessageReplyMarkup").stream()
                .filter(c -> c.json().path("message_id").asInt() == message))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.json().path("chat_id").asLong()).isEqualTo(101);
          JsonNode row = c.json().path("reply_markup").path("inline_keyboard");
          assertThat(row.size()).isEqualTo(1);
          assertThat(row.path(0).size()).isEqualTo(2);
          for (int i = 0; i < 2; i++) {
            assertThat(row.path(0).path(i).path("text").asText()).isEqualTo(i == 0 ? a : t);
            assertThat(row.path(0).path(i).path("callback_data").asText())
                .isEqualTo(
                    (i == 0 ? "audio:" : "transcript:") + "22222222-2222-4222-8222-222222222222");
          }
        });
  }

  @Then("no job files are created by startup cleanup")
  public void empty() throws Exception {
    if (Files.exists(w.root.resolve("work")))
      try (var entries = Files.list(w.root.resolve("work"))) {
        assertThat(entries.toList()).isEmpty();
      }
    assertThat(r().schedule.completed.get()).isZero();
  }

  @Then("the sentinel file and allowed-users.json bytes are unchanged")
  public void external() throws Exception {
    assertThat(Files.readAllBytes(w.sentinel)).containsExactly(r().sentinelBefore);
    assertThat(Files.readAllBytes(w.root.resolve("allowed-users.json")))
        .containsExactly(r().accessBefore);
  }

  @Then("{string} under the work directory still contains {string}")
  public void unrelated(String name, String text) throws Exception {
    assertThat(Files.readString(w.root.resolve("work").resolve(name))).isEqualTo(text);
  }

  @Then("the Telegram mock receives the allowed-user help text")
  public void help() {
    assertThat(w.calls("sendMessage"))
        .singleElement()
        .satisfies(c -> {
          assertThat(c.json().path("chat_id").asLong()).isEqualTo(w.user);
          assertThat(c.json().path("text").asText())
              .startsWith("Send me a video link and I'll reply with a summary.");
        });
  }

  @Then("the live job's partial source bytes remain on disk")
  public void partial() throws Exception {
    r().liveUnchanged();
    assertThat(r().liveBefore.keySet()).contains("source.m4a.part");
  }

  @Then("its directory still exists")
  public void liveDir() {
    assertThat(r().liveDir).isDirectory();
  }

  @Then("that live job's source and transcription input files remain on disk")
  public void liveFiles() throws Exception {
    r().liveUnchanged();
    assertThat(r().liveBefore.keySet()).contains("source.m4a", "audio.wav", "chunks/chunk-000.wav");
  }

  @Then("no Telegram failure message is sent for that live job")
  public void noFailure() {
    for (var c : w.calls("sendMessage"))
      if (c.json().path("chat_id").asLong() == 202)
        assertThat(c.json().path("text").asText())
            .doesNotContain("Could not", "failed", "unavailable");
  }

  @Then("that earlier job publishes its summary with both download buttons")
  public void summary() {
    PipelineWorld.until(() ->
        assertThat(
                w.calls("sendMessage").stream()
                    .filter(c ->
                        c.json().path("chat_id").asLong() == 202 && c.json().has("reply_markup")))
            .anySatisfy(c -> {
              assertThat(c.json().path("text").asText())
                  .contains("Earlier video", "Earlier summary");
              assertThat(c.json().path("reply_markup").toString())
                  .contains("Download audio", "Download transcript");
            }));
  }

  @Then("the Telegram mock receives a sendMessage with {string}")
  public void failure(String text) {
    PipelineWorld.until(() ->
        assertThat(w.calls("sendMessage"))
            .anySatisfy(c -> {
              assertThat(c.json().path("chat_id").asLong()).isEqualTo(w.user);
              assertThat(c.json().path("text").asText()).isEqualTo(text);
            }));
  }

  @Then("the failed job's directory no longer exists before another scheduled minute elapses")
  public void failedGone() {
    PipelineWorld.until(() -> assertThat(r().liveDir).isNotNull().doesNotExist());
    assertThat(r().schedule.completed.get()).isZero();
  }
}
