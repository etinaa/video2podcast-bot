package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.functional.TranscriptWorld.Call;
import io.cucumber.java.en.Then;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Checks uploaded media, Telegram requests and durable files without calling delivery services. */
public class AudioAssertionSteps {
  final TranscriptWorld w;

  public AudioAssertionSteps(TranscriptWorld w) {
    this.w = w;
  }

  @Then(
      "the Telegram mock receives in order an answerCallbackQuery with {string}, a reply-markup edit with {string}, one sendAudio and a reply-markup edit with {string}")
  public void order(String answer, String progress, String sent) {
    assertThat(w.tapCalls().stream().map(Call::method).toList())
        .containsExactly(
            "answercallbackquery", "editmessagereplymarkup", "sendaudio", "editmessagereplymarkup");
    assertThat(w.calls("answerCallbackQuery").getFirst().json().path("text").asText())
        .isEqualTo(answer);
    assertThat(w.calls("editMessageReplyMarkup").getFirst().json().toString()).contains(progress);
    assertThat(w.calls("editMessageReplyMarkup").getLast().json().toString()).contains(sent);
    assertThat(
            w.calls("sendAudio")
                .getFirst()
                .persisted()
                .path("files")
                .path("AUDIO")
                .path("state")
                .asText())
        .isEqualTo("SENDING");
    assertThat(
            w.calls("editMessageReplyMarkup")
                .getLast()
                .persisted()
                .path("files")
                .path("AUDIO")
                .path("state")
                .asText())
        .isEqualTo("SENT");
  }

  @Then(
      "the sendAudio multipart request targets chat {long} with title {string} and the unchanged retained source bytes")
  public void multipart(long chat, String title) throws Exception {
    assertThat(w.audio.part("chat_id")).isEqualTo(Long.toString(chat));
    part("title", title);
    unchangedUpload();
  }

  @Then("the sendAudio multipart request contains exactly the unchanged retained source bytes")
  public void unchangedUpload() {
    assertThat(w.audio.upload())
        .containsExactly(w.before.get(w.audio.source.getFileName().toString()));
    assertThat(w.audio.encodes).isEmpty();
  }

  void part(String name, String value) {
    assertThat(w.audio.part(name)).isEqualTo(value);
  }

  @Then("the sendAudio multipart performer is {string}")
  public void performer(String value) {
    part("performer", value);
  }

  @Then("the sendAudio multipart caption is exactly {string}")
  public void caption(String value) {
    part("caption", value);
    assertThat(w.audio.part("parse_mode")).isEqualTo("HTML");
  }

  @Then("the sendAudio multipart title is exactly {string}")
  public void title(String value) {
    part("title", value);
  }

  @Then("the sendAudio multipart title is exactly {int} copies of {string}")
  public void longTitle(int n, String value) {
    title(value.repeat(n));
  }

  String disposition() {
    var r = w.calls("sendAudio").getLast().request();
    String ref = r.getPart("audio").getBody().asString();
    return r.getPart(ref.substring(9)).getHeader("Content-Disposition").firstValue();
  }

  @Then("the uploaded audio filename ends with {string}")
  public void extension(String ext) {
    assertThat(disposition()).endsWith(ext + "\"");
  }

  @Then("the sendAudio multipart filename is {string}")
  public void filename(String name) {
    assertThat(disposition()).contains("filename=\"" + name + "\"");
  }

  @Then("the sendAudio multipart filename is {int} copies of {string} followed by {string}")
  public void longFilename(int n, String value, String suffix) {
    filename(value.repeat(n) + suffix);
  }

  @Then("job.json records AUDIO state {string} and sentAt {string}")
  public void stateAt(String state, String at) throws Exception {
    state(state, true);
    assertThat(Instant.parse(w.file("AUDIO").path("sentAt").asText())).isEqualTo(Instant.parse(at));
  }

  @Then("job.json records AUDIO state {string} and nonnull sentAt")
  public void sent(String state) throws Exception {
    state(state, true);
  }

  @Then("job.json records AUDIO state {string} and null sentAt")
  public void unsent(String state) throws Exception {
    state(state, false);
  }

  void state(String expected, boolean sent) throws Exception {
    PipelineWorld.until(() -> {
      try {
        JsonNode f = w.file("AUDIO");
        assertThat(f.path("state").asText()).isEqualTo(expected);
        assertThat(f.path("sentAt").isNull() || f.path("sentAt").isMissingNode()).isEqualTo(!sent);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  JsonNode beforeJob() throws Exception {
    return PipelineWorld.JSON.readTree(w.before.get("job.json"));
  }

  @Then("the TRANSCRIPT entry and transcript bytes are unchanged")
  public void transcriptUnchanged() throws Exception {
    transcriptEntry();
    assertThat(Files.readAllBytes(w.dir.resolve("transcript.txt")))
        .containsExactly(w.transcriptBytes);
  }

  @Then("the TRANSCRIPT entry is unchanged")
  public void transcriptEntry() throws Exception {
    assertThat(w.file("TRANSCRIPT")).isEqualTo(beforeJob().path("files").path("TRANSCRIPT"));
  }

  @Then("both keyboard edits keep audio callback data {string}")
  public void audioCallback(String data) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup"))
      assertThat(w.keyboard(c).path(0).path(0).path("callback_data").asText()).isEqualTo(data);
  }

  @Then("the Telegram mock receives no editMessageText, sendDocument, sendVoice or sendVideo")
  public void noUnrelated() {
    for (String method : List.of("editMessageText", "sendDocument", "sendVoice", "sendVideo"))
      assertThat(w.calls(method)).isEmpty();
  }

  @Then("the source bytes and both stored expiry timestamps are unchanged")
  public void unchangedExpiry() throws Exception {
    sourceUnchanged();
    for (String kind : List.of("AUDIO", "TRANSCRIPT"))
      assertThat(w.file(kind).path("expiresAt"))
          .isEqualTo(beforeJob().path("files").path(kind).path("expiresAt"));
  }

  @Then("the source bytes are unchanged")
  public void sourceUnchanged() throws Exception {
    assertThat(Files.readAllBytes(w.audio.source))
        .containsExactly(w.before.get(w.audio.source.getFileName().toString()));
  }

  @Then("the source bytes and transcript bytes are unchanged")
  public void bytesUnchanged() throws Exception {
    sourceUnchanged();
    assertThat(Files.readAllBytes(w.dir.resolve("transcript.txt")))
        .containsExactly(w.transcriptBytes);
  }

  @Then("the stored title and source bytes are unchanged")
  public void titleAndSource() throws Exception {
    sourceUnchanged();
    assertThat(w.job().path("title").asText()).isEqualTo(w.originalTitle);
  }

  @Then("job.json retains the original title")
  public void storedTitle() throws Exception {
    assertThat(w.job().path("title").asText()).isEqualTo(w.originalTitle);
  }

  @Then("the Telegram mock receives one successful sendAudio")
  @Then("the Telegram mock receives one successful sendAudio for chat 101")
  public void success() {
    assertThat(w.calls("sendAudio")).hasSize(1);
    assertThat(w.uploadSuccess).isNotNull();
    assertThat(w.audio.part("chat_id")).isEqualTo("101");
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().endsWith("/sendaudio")))
        .singleElement()
        .satisfies(e -> assertThat(e.getResponse().getStatus()).isEqualTo(200));
  }

  @Then("the uploaded bytes form a nonempty M4A with AAC audio and no video stream")
  public void aac() throws Exception {
    media(false);
  }

  @Then("the uploaded bytes form a nonempty M4A with mono AAC audio and no video stream")
  public void mono() throws Exception {
    media(true);
  }

  void media(boolean mono) throws Exception {
    assertThat(w.audio.upload()).isNotEmpty();
    JsonNode probe = w.audio.uploadedMedia();
    assertThat(probe.path("format").path("format_name").asText()).contains("m4a");
    JsonNode streams = probe.path("streams");
    assertThat(streams.size()).isEqualTo(1);
    assertThat(streams.path(0).path("codec_type").asText()).isEqualTo("audio");
    assertThat(streams.path(0).path("codec_name").asText()).isEqualTo("aac");
    if (mono) assertThat(streams.path(0).path("channels").asInt()).isEqualTo(1);
  }

  @Then(
      "the converted file on disk contains approximately 60 seconds of audio encoded at a requested 128 kbps")
  public void duration() throws Exception {
    JsonNode probe = w.audio.uploadedMedia();
    assertThat(probe.path("format").path("duration").asDouble()).isBetween(59.0, 61.0);
    assertThat(w.audio.encodes).hasSize(1);
    assertThat(w.audio.encodes.getFirst())
        .containsSubsequence("-b:a", "128k")
        .doesNotContain("-ac");
    assertThat(probe.path("streams").path(0).path("channels").asInt())
        .isEqualTo(
            AudioSupport.probe(w.audio.source).path("streams").findValue("channels").asInt());
  }

  @Then("the uploaded audio is smaller than {long} bytes")
  public void size(long size) {
    assertThat((long) w.audio.upload().length).isLessThan(size);
  }

  @Then("{string} does not exist in the job directory after delivery")
  public void cleaned(String name) {
    PipelineWorld.until(() -> assertThat(w.dir.resolve(name)).doesNotExist());
  }

  @Then("the one sendAudio upload contains the second encoded output fixture bytes")
  @Then("the one sendAudio upload contains the mono compression output fixture bytes")
  public void secondBytes() {
    assertThat(w.calls("sendAudio")).hasSize(1);
    assertThat(w.audio.encodedBytes).isNotNull();
    assertThat(w.audio.upload()).containsExactly(w.audio.encodedBytes);
  }

  @Then(
      "the ffmpeg fault journal records exactly two mono AAC encodes at 128 and 102 kbps in order")
  public void retries() {
    assertThat(w.audio.encodes).hasSize(2);
    for (int i = 0; i < 2; i++)
      assertThat(w.audio.encodes.get(i))
          .containsSubsequence("-ac", "1")
          .containsSubsequence("-c:a", "aac")
          .containsSubsequence("-b:a", i == 0 ? "128k" : "102k");
  }

  @Then("the ffmpeg fault journal records exactly two encodes")
  public void twoEncodes() {
    assertThat(w.audio.encodes).hasSize(2);
  }

  @Then(
      "the ffmpeg fault journal records a non-mono 128 kbps conversion followed by one mono 128 kbps encode from the original source")
  public void fallback() {
    assertThat(w.audio.encodes).hasSize(2);
    assertThat(w.audio.encodes.getFirst())
        .containsSubsequence("-b:a", "128k")
        .doesNotContain("-ac");
    assertThat(w.audio.encodes.getLast())
        .containsSubsequence("-ac", "1")
        .containsSubsequence("-b:a", "128k");
    for (var args : w.audio.encodes)
      assertThat(args).containsSubsequence("-i", w.audio.source.toString());
  }

  @Then("the Telegram mock receives no sendAudio")
  public void noAudio() {
    assertThat(w.calls("sendAudio")).isEmpty();
  }

  @Then("no Telegram message contains process stderr or a filesystem path")
  public void safe() {
    new TranscriptAssertionSteps(w).safeErrors();
    for (Call c : w.events)
      if (!c.request().isMultipart())
        assertThat(c.request().getBodyAsString())
            .doesNotContain("secret stderr", w.root.toString());
  }

  @Then("the tap is answered with {string} while the Whisper response remains held")
  public void acknowledged(String text) {
    held();
    assertThat(w.calls("answerCallbackQuery").getLast().json().path("text").asText())
        .isEqualTo(text);
  }

  void held() {
    assertThat(w.audio.whisperReceived.getCount()).isZero();
    assertThat(w.audio.releaseWhisper.getCount()).isEqualTo(1);
  }

  @Then("the summary keyboard shows {string} and {string}")
  public void keyboard(String audio, String transcript) {
    w.labels(w.calls("editMessageReplyMarkup").getLast(), audio, transcript);
  }

  @Then(
      "no delivery audio output or sendAudio upload occurs while the Whisper response remains held")
  public void queued() {
    held();
    noAudio();
    assertThat(w.audio.encodes).isEmpty();
    assertThat(w.dir.resolve("out.m4a")).doesNotExist();
  }

  @Then("the Telegram mock has received exactly one sendAudio request in total")
  @Then("the Telegram mock receives one sendAudio attempt without an automatic retry")
  public void once() {
    assertThat(w.calls("sendAudio")).hasSize(1);
  }

  @Then("the Telegram mock receives one sendAudio targeting stored chat {long}")
  public void destination(long chat) {
    once();
    assertThat(w.audio.part("chat_id")).isEqualTo(Long.toString(chat));
  }

  @Then("the Telegram mock receives no audio upload or message edit targeting chat {long}")
  public void noRedirect(long chat) {
    assertThat(w.audio.part("chat_id")).isNotEqualTo(Long.toString(chat));
    for (Call c : w.events)
      if (c.method().startsWith("editmessage"))
        assertThat(c.json().path("chat_id").asLong()).isNotEqualTo(chat);
  }

  @Then("^the tap produces (one successful sendAudio and persisted AUDIO state SENT)$")
  public void outcome(String outcome) throws Exception {
    if (outcome.startsWith("one")) {
      success();
      sent("SENT");
    } else w.refusal("Expired, please resend the link.");
  }

  @Then(
      "the Telegram mock has received exactly two sendAudio attempts in total with one successful response")
  public void retryUpload() {
    assertThat(w.calls("sendAudio")).hasSize(2);
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(e ->
                    e.getRequest().getUrl().endsWith("/sendaudio")
                        && e.getResponse().getStatus() == 200)
                .count())
        .isEqualTo(1);
  }

  @Then(
      "both reply-markup edits retain the transcript button label {string} and its original callback data")
  public void labels(String label) {
    assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
    for (Call c : w.calls("editMessageReplyMarkup")) {
      assertThat(w.keyboard(c).path(0).path(1).path("text").asText()).isEqualTo(label);
      assertThat(w.keyboard(c).path(0).path(1).path("callback_data").asText())
          .isEqualTo("transcript:" + TranscriptWorld.JOB);
    }
  }

  @Then(
      "the Telegram mock receives one successful sendDocument while the Whisper response remains held")
  public void transcriptWhileQueued() {
    held();
    assertThat(w.calls("sendDocument")).hasSize(1);
    assertThat(
            w.telegram.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().endsWith("/senddocument")))
        .singleElement()
        .satisfies(e -> assertThat(e.getResponse().getStatus()).isEqualTo(200));
  }

  @Then("the final summary keyboard shows {string} and {string}")
  public void finalKeyboard(String a, String t) {
    keyboard(a, t);
  }

  @Then("the source file, transcript.txt and job.json remain on disk")
  public void files() {
    for (Path p :
        List.of(w.audio.source, w.dir.resolve("transcript.txt"), w.dir.resolve("job.json")))
      assertThat(p).isRegularFile();
  }

  @Then("the Telegram mock receives no editMessageReplyMarkup")
  public void noEdits() {
    assertThat(w.calls("editMessageReplyMarkup")).isEmpty();
  }

  @Then("the AUDIO expiresAt in job.json is still {string}")
  public void expiry(String value) throws Exception {
    assertThat(w.file("AUDIO").path("expiresAt").asText()).isEqualTo(value);
  }
}
