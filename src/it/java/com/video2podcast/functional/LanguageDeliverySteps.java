package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.functional.TelegramUploadRecorder.Upload;
import io.cucumber.java.en.*;
import java.nio.charset.*;
import java.util.*;

/** Language delivery checks inspect actual Telegram multipart parts from a real published job. */
public class LanguageDeliverySteps {
  final LanguageSteps language;
  final PipelineWorld w;

  public LanguageDeliverySteps(LanguageSteps language) {
    this.language = language;
    w = language.w;
  }

  @io.cucumber.java.Before(value = "@T19", order = 30)
  public void captureUploads() throws Exception {
    w.telegramUploads = new TelegramUploadRecorder(w);
    w.prop("app.telegram.api-url", w.telegramUploads.url());
  }

  @io.cucumber.java.After(value = "@T19", order = 90)
  public void closeRecorder() {
    if (w.telegramUploads != null) w.telegramUploads.close();
  }

  @When("^the user taps this summary's Download (transcript|audio) button$")
  public void tap(String kind) throws Exception {
    var summary = w.summary();
    tapPublished(kind, summary);
  }

  private void tapPublished(String kind, PipelineWorld.Event summary) throws Exception {
    language.h.callback(
        w.user,
        kind + ":" + PipelineAssertionSteps.callbackId(summary),
        w.responseIds.get(summary));
    w.drained();
    PipelineWorld.until(
        () -> assertThat(w.calls(kind.equals("audio") ? "sendAudio" : "sendDocument")).hasSize(1));
  }

  Upload upload(String kind) {
    Upload request = w.telegramUploads.one(kind.equals("audio") ? "sendAudio" : "sendDocument");
    assertThat(request.part("chat_id").text()).isEqualTo("" + w.user);
    return request;
  }

  String filename(String kind) {
    Upload request = upload(kind);
    String reference = request.part(kind.equals("audio") ? "audio" : "document").text();
    assertThat(reference).startsWith("attach://");
    String disposition = request.part(reference.substring(9)).headers();
    var matcher = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"").matcher(disposition);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  byte[] bytes(String kind) {
    Upload request = upload(kind);
    String reference = request.part(kind.equals("audio") ? "audio" : "document").text();
    return request.part(reference.substring(9)).bytes();
  }

  @Then(
      "the Telegram mock receives one sendDocument with filename {string}, unchanged retained transcript bytes and no caption")
  public void document(String name) {
    assertThat(filename("transcript")).isEqualTo(name);
    assertThat(bytes("transcript")).isEqualTo(language.retainedTranscript);
    assertThat(upload("transcript").part("caption")).isNull();
  }

  @Then("the transcript bytes decode as UTF-8 {string} followed by one LF newline")
  public void utf8(String text) throws Exception {
    String decoded =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes("transcript")))
            .toString();
    assertThat(decoded).isEqualTo(text + "\n");
  }

  @Then("the transcript button changes through {string} to {string}")
  public void labels(String first, String last) {
    PipelineWorld.until(() -> {
      assertThat(w.calls("editMessageReplyMarkup")).hasSize(2);
      assertThat(
              w.calls("editMessageReplyMarkup")
                  .getLast()
                  .json()
                  .path("reply_markup")
                  .path("inline_keyboard")
                  .get(0)
                  .get(1)
                  .path("text")
                  .asText())
          .isEqualTo(last);
    });
    var edits = w.calls("editMessageReplyMarkup");
    assertThat(edits).hasSize(2);
    for (int i = 0; i < 2; i++) {
      var edit = edits.get(i).json();
      assertThat(edit.path("chat_id").asLong()).isEqualTo(w.user);
      assertThat(edit.path("message_id").asInt()).isEqualTo(w.responseIds.get(w.summary()));
      var buttons = edit.path("reply_markup").path("inline_keyboard").get(0);
      assertThat(buttons.get(0).path("text").asText()).isEqualTo("Download audio");
      assertThat(buttons.get(1).path("text").asText()).isEqualTo(i == 0 ? first : last);
    }
  }

  @Then(
      "the Telegram mock receives one sendAudio with filename {string} and the unchanged retained source bytes")
  public void audio(String name) {
    assertThat(filename("audio")).isEqualTo(name);
    assertThat(bytes("audio")).isEqualTo(language.retainedAudio);
  }

  @Then("neither button tap sends a new Whisper or Ollama request")
  public void noModels() {
    w.drained();
    assertThat(w.service("whisper")).hasSize(language.whisperBeforeTap);
    assertThat(w.service("ollama")).hasSize(language.ollamaBeforeTap);
  }

  @Then(
      "^the (sendDocument|sendAudio) multipart filename is exactly (\\d+) copies of \"([^\"]+)\" followed by \"([^\"]+)\"$")
  public void bounded(String method, int count, String letter, String extension) {
    assertThat(filename(method.equals("sendAudio") ? "audio" : "transcript"))
        .isEqualTo(letter.repeat(count) + extension);
  }

  @Then("both upload filenames are valid Unicode and each is {int} UTF-8 bytes")
  public void validNames(int size) throws Exception {
    for (String kind : List.of("audio", "transcript")) {
      String name = filename(kind);
      var encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(name));
      assertThat(encoded.remaining()).isEqualTo(size);
    }
  }
}
