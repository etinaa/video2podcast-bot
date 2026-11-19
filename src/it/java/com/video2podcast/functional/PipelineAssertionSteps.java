package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.functional.PipelineWorld.Event;
import io.cucumber.java.en.Then;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Assertions observe HTTP requests and persisted files, never processing services. */
public class PipelineAssertionSteps {
  final PipelineWorld w;

  public PipelineAssertionSteps(PipelineWorld world) {
    w = world;
  }

  @Then("the bot replies with {string}")
  public void reply(String s) {
    w.reply(s);
  }

  @Then("the status text shows position {int}")
  public void position(int n) {
    String expected = "Queued: position " + n;
    PipelineWorld.until(() ->
        assertThat(
                w.calls("sendMessage").stream()
                    .filter(e ->
                        e.text().startsWith("Queued:")
                            && e.nanos() >= w.lastUpdateNanos
                            && w.responseIds.containsKey(e))
                    .toList())
            .hasSize(1));
    Event acknowledgement =
        w.calls("sendMessage").stream()
            .filter(e -> e.text().startsWith("Queued:") && e.nanos() >= w.lastUpdateNanos)
            .findFirst()
            .orElseThrow();
    assertThat(acknowledgement.text()).isEqualTo(expected);
    assertThat(acknowledgement.json().path("chat_id").asLong()).isEqualTo(w.user);
    assertThat(w.responseIds).containsKey(acknowledgement);
  }

  @Then(
      "^the Telegram mock receives (?:the second link's |the )single queued acknowledgement \"([^\"]+)\" within 5 seconds of (?:its|the) update$")
  public void prompt(String s) {
    w.reply(s);
    Event e = w.calls("sendMessage").stream().filter(c -> c.text().equals(s)).toList().getLast();
    assertThat(e.nanos() - w.lastUpdateNanos).isBetween(0L, Duration.ofSeconds(5).toNanos());
    assertThat(w.calls("sendMessage").stream().filter(c -> c.text().equals(s)).count())
        .isEqualTo(1);
  }

  @Then("the second link's status text shows position 2 before the held Whisper answer is released")
  public void heldPosition() {
    assertThat(w.whisperHold.release.getCount()).isOne();
    position(2);
  }

  @Then("the bot replies with the user's Telegram id before the held Whisper answer is released")
  public void responsive() {
    assertThat(w.whisperHold.release.getCount()).isOne();
    w.reply("Your Telegram id: <code>" + w.user + "</code>");
  }

  @Then(
      "the Telegram mock receives exactly one initial sendMessage with {string} acknowledging the link")
  public void oneAck(String s) {
    w.reply(s);
    assertThat(w.calls("sendMessage").stream().filter(c -> c.text().equals(s)))
        .hasSize(1)
        .allSatisfy(e -> assertThat(e.json().has("reply_markup")).isFalse());
  }

  @Then("the status edits contain {string}, {string}, {string} and {string} in order")
  public void statuses(String a, String b, String c, String d) {
    w.done();
    assertThat(w.calls("editMessageText").stream().map(Event::text).toList())
        .containsSubsequence(a, b, c, d);
  }

  @Then(
      "every processing status edit targets the initial status message id in the originating chat")
  public void statusIdentity() {
    w.done();
    assertThat(w.calls("editMessageText"))
        .allSatisfy(e -> {
          assertThat(e.json().path("message_id").asInt())
              .isEqualTo(
                  w.responseIds.get(
                      w.calls("sendMessage").stream()
                          .filter(call -> call.text().startsWith("Queued:"))
                          .findFirst()
                          .orElseThrow()));
          assertThat(e.json().path("chat_id").asLong()).isEqualTo(w.user);
        });
  }

  @Then(
      "^each (?:transcription edit precedes the corresponding chunk's first Whisper request|Telegram status edit occurs before the Whisper mock receives that chunk's first request)$")
  public void beforeWhisper() {
    w.transcriptionSettled();
    var chunks = w.service("whisper");
    assertThat(chunks).isNotEmpty();
    var names = chunks.stream().map(Event::chunk).distinct().toList();
    for (int i = 0; i < names.size(); i++) {
      String text =
          "Transcribing "
              + (i + 1)
              + "/"
              + w.calls("editMessageText").stream()
                  .map(Event::text)
                  .filter(t -> t.startsWith("Transcribing "))
                  .findFirst()
                  .orElseThrow()
                  .split("/")[1];
      Event progress =
          w.calls("editMessageText").stream()
              .filter(e -> e.text().equals(text))
              .findFirst()
              .orElseThrow();
      String name = names.get(i);
      Event request = chunks.stream().filter(e -> e.chunk().equals(name)).findFirst().orElseThrow();
      assertThat(progress.nanos()).isLessThan(request.nanos());
    }
  }

  @Then("the Ollama mock receives no request before both Whisper chunks have succeeded")
  public void afterWhisper() {
    assertThat(w.service("whisper")).hasSize(2);
    assertThat(w.service("ollama").getFirst().nanos())
        .isGreaterThan(w.responseReady.get(w.service("whisper").getLast()));
  }

  @Then("the bot sends one successful Telegram summary with a different message id from the status")
  public void differentIds() {
    w.done();
    assertThat(w.summaries()).hasSize(1);
    assertThat(w.record().path("summaryMessageId").asInt())
        .isNotEqualTo(
            w.responseIds.get(
                w.calls("sendMessage").stream()
                    .filter(call -> call.text().startsWith("Queued:"))
                    .findFirst()
                    .orElseThrow()));
  }

  @Then("the status message is finally edited to {string}")
  public void finalStatus(String s) {
    PipelineWorld.until(() -> assertThat(w.calls("editMessageText").getLast().text()).isEqualTo(s));
  }

  @Then("^the bot sends one successful Telegram summary(?: after the final Ollama answer)?$")
  public void summary() {
    w.done();
    assertThat(w.summaries()).hasSize(1);
    assertThat(w.body()).isNotBlank();
    if (!w.service("ollama").isEmpty())
      assertThat(w.summary().nanos()).isGreaterThan(w.service("ollama").getLast().nanos());
  }

  @Then(
      "the summary sendMessage request targets the originating private chat with HTML parse mode and disabled link previews")
  public void format() {
    JsonNode j = w.summary().json();
    assertThat(j.path("chat_id").asLong()).isEqualTo(w.user);
    assertThat(j.path("parse_mode").asText()).isEqualTo("HTML");
    assertThat(
            j.path("link_preview_options")
                .path("is_disabled")
                .asBoolean(j.path("disable_web_page_preview").asBoolean()))
        .isTrue();
  }

  @Then(
      "the summary contains the bold title {string}, the duration line {string} and bullet {string}")
  public void content(String title, String duration, String bullet) {
    assertThat(w.summaryText()).contains("<b>" + title + "</b>", duration, "• " + bullet);
  }

  @Then("the summary keyboard contains exactly {string} and {string}")
  public void buttons(String a, String b) {
    assertThat(buttons().stream().map(n -> n.path("text").asText()).toList()).containsExactly(a, b);
  }

  List<JsonNode> buttons() {
    var list = new ArrayList<JsonNode>();
    w.summary()
        .json()
        .path("reply_markup")
        .path("inline_keyboard")
        .forEach(row -> row.forEach(list::add));
    return list;
  }

  @Then("the callback data is {string} and {string} using the published job's id")
  public void callbacks(String a, String b) {
    String id = w.record().path("jobId").asText();
    assertThat(buttons().stream().map(n -> n.path("callback_data").asText()).toList())
        .containsExactly(a.replace("<jobId>", id), b.replace("<jobId>", id));
  }

  @Then(
      "each callback_data value is at most 64 UTF-8 bytes and contains no filesystem path or source URL")
  public void callbackLimit() {
    for (JsonNode b : buttons()) {
      String s = b.path("callback_data").asText();
      assertThat(s.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
      assertThat(s).matches("(?:audio|transcript):[0-9a-f-]{36}");
    }
  }

  @Then("the Telegram mock receives no sendAudio or sendDocument request")
  public void noUploads() {
    w.finishOne();
    w.noUploads();
  }

  @Then("no delivery audio file is created before a button tap")
  public void noDelivery() throws Exception {
    w.done();
    try (var s = Files.walk(w.publishedDir())) {
      assertThat(s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
          .containsExactlyInAnyOrder("source." + w.extension, "transcript.txt", "job.json");
    }
  }

  @Then("the HTML summary title is {string}")
  public void htmlTitle(String s) {
    assertThat(w.summaryText().split("\n")[0]).isEqualTo(s);
  }

  @Then(
      "the summary body contains escaped literal text for the model's link tags, ampersand and greater-than sign")
  public void escapedBody() {
    assertThat(w.summaryText())
        .contains("&lt;a href=", "&lt;/a&gt;", "&amp; compare &gt; 2 plans.");
  }

  @Then("no user-controlled title or summary fragment becomes an active HTML tag")
  public void noMarkup() {
    String s = w.summaryText().replaceFirst("^<b>.*?</b>", "");
    assertThat(s).doesNotContain("<", ">");
  }

  @Then(
      "the displayed title and bullet text contain the original characters without double escaping")
  public void originalText() {
    assertThat(PipelineWorld.unescape(w.summaryText().split("\n")[0]))
        .isEqualTo("<b>" + w.title + "</b>");
    assertThat(w.body()).isEqualTo("• " + w.defaultContent.substring(2));
  }

  @Then(
      "the bot sends one successful Telegram summary with at most {int} characters after HTML entity parsing")
  public void length(int n) {
    summary();
    assertThat(parsedText().length()).isLessThanOrEqualTo(n);
  }

  String parsedText() {
    return PipelineWorld.unescape(w.summaryText().replace("<b>", "").replace("</b>", ""));
  }

  @Then("the summary has valid HTML, a nonempty bold title, a duration and both download buttons")
  public void validHtml() throws Exception {
    String s = w.summaryText();
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var doc =
        factory
            .newDocumentBuilder()
            .parse(
                new java.io.ByteArrayInputStream(
                    ("<root>" + s + "</root>").getBytes(StandardCharsets.UTF_8)));
    assertThat(doc.getElementsByTagName("b").getLength()).isEqualTo(1);
    assertThat(doc.getElementsByTagName("b").item(0).getTextContent()).isNotBlank();
    assertThat(s).contains("Duration: ");
    assertThat(buttons()).hasSize(2);
  }

  @Then("the displayed bold title is exactly 199 ampersands followed by {string}")
  public void clipped(String ellipsis) {
    assertThat(PipelineWorld.unescape(w.summaryText().split("\n")[0]))
        .isEqualTo("<b>" + "&".repeat(199) + ellipsis + "</b>");
  }

  @Then("job.json retains the full original title of {int} ampersands")
  public void fullTitle(int n) {
    assertThat(w.record().path("title").asText()).isEqualTo("&".repeat(n));
  }

  @Then("job.json retains the original title including its whitespace")
  public void titleStored() {
    assertThat(w.record().path("title").asText()).isEqualTo(w.title);
  }

  @Then("the summary body retains the full {int} characters independently of title bounding")
  public void bodyLength(int n) {
    assertThat(w.body()).hasSize(n);
  }

  @Then(
      "the published job's {string} records its jobId, the sender's user id and originating chat id")
  public void jobOwner(String file) {
    JsonNode r = w.record();
    assertThat(r.path("jobId").asText()).isEqualTo(w.publishedDir().getFileName().toString());
    assertThat(r.path("ownerUserId").asLong()).isEqualTo(w.user);
    assertThat(r.path("chatId").asLong()).isEqualTo(w.user);
  }

  @Then("{string} records summaryMessageId {int}, the metadata title and durationSeconds {int}")
  public void metadata(String file, int id, int seconds) {
    JsonNode r = w.record();
    assertThat(r.path("summaryMessageId").asInt()).isEqualTo(id);
    assertThat(r.path("title").asText()).isEqualTo(w.title);
    assertThat(r.path("durationSeconds").asLong()).isEqualTo(seconds);
  }

  @Then("{string} records createdAt no later than summary publication")
  public void created(String file) {
    assertThat(Instant.parse(w.record().path("createdAt").asText()))
        .isBeforeOrEqualTo(w.summary().time());
  }

  @Then(
      "^the (AUDIO|TRANSCRIPT) file entry points to \"([^\"]+)\" with state \"AVAILABLE\" and null sentAt$")
  public void entry(String kind, String path) {
    JsonNode j = w.record().path("files").path(kind);
    assertThat(j.path("path").asText()).isEqualTo(path);
    assertThat(j.path("state").asText()).isEqualTo("AVAILABLE");
    assertThat(j.hasNonNull("sentAt")).isFalse();
  }

  @Then(
      "^both file entries have expiresAt equal to the successful summary-send time plus (\\d+) (hour|minutes)$")
  public void retention(int n, String unit) {
    expiry(
        w.summary().time().plus(unit.equals("hour") ? Duration.ofHours(n) : Duration.ofMinutes(n)));
  }

  @Then("both stored file entries expire at {string}")
  public void exactExpiry(String s) {
    expiry(Instant.parse(s));
  }

  void expiry(Instant time) {
    JsonNode files = w.record().path("files");
    assertThat(files).hasSize(2);
    for (JsonNode j : files)
      assertThat(Instant.parse(j.path("expiresAt").asText())).isEqualTo(time);
  }

  @Then("{string} and {string} remain readable in the published job directory")
  public void retained(String a, String b) {
    for (String p : List.of(a, b))
      assertThat(w.publishedDir().resolve(p)).isReadable().isRegularFile();
  }

  @Then("the bot replies with the existing no-access refusal containing the sender's Telegram id")
  public void refusal() {
    w.reply("No access. Send /start to request it. Your id: <code>" + w.user + "</code>");
  }

  @Then("the Whisper mock receives no POST request to {string}")
  public void noWhisper(String path) {
    assertThat(path).isEqualTo("/inference");
    assertThat(w.service("whisper")).isEmpty();
  }

  @Then("the Ollama mock receives no request")
  public void noOllama() {
    assertThat(w.service("ollama")).isEmpty();
  }

  @Then("the Whisper and Ollama mocks receive no requests")
  public void noModels() {
    assertThat(w.service("whisper")).isEmpty();
    assertThat(w.service("ollama")).isEmpty();
  }

  @Then("the Whisper mock receives {string}")
  public void receivesChunk(String s) {
    PipelineWorld.until(
        () -> assertThat(w.service("whisper").stream().map(Event::chunk).toList()).contains(s));
  }

  @Then("the media, Whisper and Ollama mocks receive no requests for this update")
  public void noServices() {
    for (String s : List.of("media", "whisper", "ollama")) assertThat(w.service(s)).isEmpty();
    assertThat(w.commands).isEmpty();
  }

  @Then("^no new job director(?:y|y or summary message) is created$")
  public void noDirectory() {
    assertThat(w.dirs()).isEmpty();
    assertThat(w.summaries()).isEmpty();
  }

  @Then("no working directory for this submission remains")
  public void noWork() {
    if (!w.calls("editMessageText").isEmpty()) w.finishOne();
    assertThat(w.dirs()).isEmpty();
  }

  @Then("after this submission finishes no working directory for it remains")
  public void cleaned() {
    w.finishOne();
    assertThat(w.dirs()).isEmpty();
  }

  @Then("no successful Telegram summary or download keyboard is sent for it")
  public void noSummaryKeyboard() {
    w.finishOne();
    assertThat(w.summaries()).isEmpty();
  }

  @Then("no successful Telegram summary is sent")
  public void noSummary() {
    w.finishOne();
    assertThat(w.summaries()).isEmpty();
  }

  @Then("no queued status message is sent for this submission")
  public void noQueued() {
    assertThat(w.calls("sendMessage").stream().map(Event::text).toList())
        .noneMatch(t -> t.startsWith("Queued:"));
  }

  @Then("no Telegram message contains raw stderr, server diagnostic bodies or job filesystem paths")
  public void safe() {
    for (Event e : w.events.stream().filter(v -> v.service().equals("telegram")).toList())
      assertThat(e.text())
          .doesNotContain(w.root.toString(), w.stderr, "/tmp/secret", "server diagnostic");
  }

  @Then("no Telegram message contains {string}")
  public void safeText(String s) {
    for (Event e : w.calls("sendMessage")) assertThat(e.text()).doesNotContain(s);
  }

  @Then("no Telegram message contains {string} or {string} or {string}")
  public void safeThree(String a, String b, String c) {
    safeText(a);
    safeText(b);
    safeText(c);
  }

  @Then(
      "no media HTTP request for the second or third job is received while the first answer is held")
  public void noLaterMedia() {
    w.awaitHeld(w.whisperHold);
    assertThat(w.service("media"))
        .noneMatch(e -> e.url().contains("second") || e.url().contains("third"));
  }

  @Then("successful Telegram summaries arrive for first, second and third in that order")
  public void orderedSummaries() {
    w.done();
    assertThat(w.summaries()).hasSize(3);
    for (int i = 0; i < 3; i++) {
      String id = callbackId(w.summaries().get(i));
      List<Event> requests = w.service("media");
      String url = List.of("first", "second", "third").get(i);
      assertThat(
              requests.stream()
                  .filter(e -> e.url().contains("/" + url))
                  .findFirst()
                  .orElseThrow()
                  .nanos())
          .isLessThan(w.summaries().get(i).nanos());
      assertThat(w.root.resolve("work").resolve(id).resolve("job.json")).isRegularFile();
    }
  }

  static String callbackId(Event e) {
    return e.json()
        .path("reply_markup")
        .path("inline_keyboard")
        .get(0)
        .get(0)
        .path("callback_data")
        .asText()
        .substring(6);
  }

  @Then("no media request for a later job precedes the preceding job's final summary send")
  public void noOverlap() {
    for (int i = 1; i < 3; i++) {
      String s = List.of("first", "second", "third").get(i);
      assertThat(
              w.service("media").stream()
                  .filter(e -> e.url().contains("/" + s))
                  .findFirst()
                  .orElseThrow()
                  .nanos())
          .isGreaterThan(w.summaries().get(i - 1).nanos());
    }
  }

  @Then("the 3 summaries use distinct job ids and matching job.json files")
  public void distinct() {
    assertThat(w.summaries().stream().map(PipelineAssertionSteps::callbackId).toList())
        .doesNotHaveDuplicates()
        .hasSize(3);
  }

  @Then("the bot replies with {string} for the first job")
  public void firstReply(String s) {
    w.reply(s);
  }

  @Then("after the first submission finishes its working directory is absent")
  public void firstClean() {
    w.done();
    assertThat(w.dirs()).hasSize(2);
  }

  @Then(
      "the second job produces its summary in Telegram and retains its source, transcript and job.json")
  public void secondRetained() {
    w.done();
    assertThat(w.summaries()).hasSize(1);
    Path d = w.root.resolve("work").resolve(callbackId(w.summary()));
    for (String f : List.of("source.m4a", "transcript.txt", "job.json"))
      assertThat(d.resolve(f)).isReadable();
  }

  @Then("the previously completed job's files and the access file remain unchanged")
  public void unchanged() throws Exception {
    w.checkPreserved();
  }

  @Then(
      "each chat receives exactly its own single queued acknowledgement\\/status and its own summary")
  public void ownChat() {
    w.done();
    for (long id : List.of(w.user, w.bob)) {
      var list =
          w.calls("sendMessage").stream()
              .filter(e -> e.json().path("chat_id").asLong() == id)
              .toList();
      assertThat(list).hasSize(2);
      assertThat(list.stream().filter(e -> e.text().startsWith("Queued:"))).hasSize(1);
      assertThat(list.stream().filter(e -> e.json().has("reply_markup"))).hasSize(1);
    }
  }

  @Then("the summary buttons in each chat refer to that user's distinct stored job id")
  public void ownButtons() {
    assertThat(w.summaries().stream().map(PipelineAssertionSteps::callbackId).toList())
        .doesNotHaveDuplicates()
        .hasSize(2);
    for (Event e : w.summaries()) {
      var j =
          PipelineWorld.json(
              PipelineWorld.read(
                  w.root.resolve("work").resolve(callbackId(e)).resolve("job.json")));
      assertThat(j.path("chatId").asLong()).isEqualTo(e.json().path("chat_id").asLong());
    }
  }

  @Then("each job.json records its own sender's user id and originating chat id")
  public void eachOwner() {
    List<Path> jobs = w.jobFiles();
    assertThat(jobs).hasSize(2);
    for (Path p : jobs) {
      var j = PipelineWorld.json(PipelineWorld.read(p));
      assertThat(j.path("ownerUserId").asLong())
          .isEqualTo(j.path("chatId").asLong())
          .isIn(w.user, w.bob);
    }
  }

  @Then("neither summary references the other user's job id")
  public void noOther() {
    for (Event e : w.summaries())
      for (Event other : w.summaries())
        if (e != other) assertThat(e.json().toString()).doesNotContain(callbackId(other));
  }

  @Then("the Telegram mock receives exactly one queued sendMessage attempt for this submission")
  public void ackAttempt() {
    assertThat(w.calls("sendMessage").stream().filter(e -> e.text().startsWith("Queued:")))
        .hasSize(1);
  }

  @Then("no retry or error-message send is attempted for this submission")
  public void noAckRetry() {
    assertThat(w.calls("sendMessage")).hasSize(1);
  }

  @Then("the next submission shows {string} and produces its summary in Telegram")
  public void next(String s) {
    PipelineWorld.until(() ->
        assertThat(
                w.calls("sendMessage").stream()
                    .filter(e ->
                        e.text().startsWith("Queued:")
                            && e.nanos() >= w.lastUpdateNanos
                            && w.responseIds.containsKey(e))
                    .toList())
            .hasSize(1));
    Event acknowledgement =
        w.calls("sendMessage").stream()
            .filter(e -> e.text().startsWith("Queued:") && e.nanos() >= w.lastUpdateNanos)
            .findFirst()
            .orElseThrow();
    assertThat(acknowledgement.text()).isEqualTo(s);
    int statusId = w.responseIds.get(acknowledgement);
    PipelineWorld.until(() ->
        assertThat(
                w.summaries().stream()
                    .filter(e ->
                        e.nanos() >= w.lastUpdateNanos
                            && e.json().path("chat_id").asLong() == w.user))
            .hasSize(1));
    Event summary =
        w.summaries().stream()
            .filter(
                e -> e.nanos() >= w.lastUpdateNanos && e.json().path("chat_id").asLong() == w.user)
            .findFirst()
            .orElseThrow();
    PipelineWorld.until(() ->
        assertThat(
                w.calls("editMessageText").stream()
                    .filter(e ->
                        e.nanos() >= w.lastUpdateNanos
                            && e.json().path("message_id").asInt() == statusId
                            && e.text().equals("Done.")))
            .hasSize(1));
    String jobId = callbackId(summary);
    Path jobDir = w.root.resolve("work").resolve(jobId);
    PipelineWorld.until(() -> assertThat(jobDir.resolve("job.json")).isRegularFile());
    JsonNode published = PipelineWorld.json(PipelineWorld.read(jobDir.resolve("job.json")));
    assertThat(published.path("summaryMessageId").asInt()).isEqualTo(w.responseIds.get(summary));
    assertThat(published.path("jobId").asText()).isEqualTo(jobId);
    assertThat(
            w.calls("sendMessage").stream()
                .filter(e -> e.text().equals(s) && e.nanos() >= w.lastUpdateNanos))
        .hasSize(1);
  }

  @Then("the Telegram mock receives exactly one {string} edit attempt")
  public void oneEdit(String s) {
    w.done();
    assertThat(w.calls("editMessageText").stream().filter(e -> e.text().equals(s))).hasSize(1);
  }

  @Then("the Telegram mock receives subsequent {string} and {string} edits")
  public void subsequent(String a, String b) {
    w.done();
    assertThat(w.calls("editMessageText").stream().map(Event::text).toList())
        .containsSubsequence(a, b);
  }

  @Then("the published job retains its source, transcript and job.json")
  public void retainedAll() {
    Path p = w.publishedDir();
    for (String f : List.of("source.m4a", "transcript.txt", "job.json"))
      assertThat(p.resolve(f)).isRegularFile();
  }

  @Then("the Telegram mock receives exactly one summary send attempt")
  public void summaryAttempt() {
    w.finishOne();
    assertThat(w.summaries()).hasSize(1);
  }

  @Then("no summary sendMessage is attempted")
  public void noAttempt() {
    w.finishOne();
    assertThat(w.summaries()).isEmpty();
  }

  @Then(
      "the Telegram mock receives an editMessageText for message {int} with the same summary HTML and no buttons")
  public void withdrawn(int id) {
    PipelineWorld.until(() ->
        assertThat(
                w.calls("editMessageText").stream()
                    .filter(e -> e.json().path("message_id").asInt() == id))
            .hasSize(1));
    Event edit =
        w.calls("editMessageText").stream()
            .filter(e -> e.json().path("message_id").asInt() == id)
            .findFirst()
            .orElseThrow();
    assertThat(edit.text()).isEqualTo(w.summaryText());
    assertThat(edit.json().path("reply_markup").path("inline_keyboard").size()).isZero();
  }

  @Then("the first submission's rejected notification is not retried")
  public void noRetry() {
    w.done();
    Event first = w.rejectedNotification;
    assertThat(
            w.events.stream()
                .filter(e ->
                    e.service().equals("telegram")
                        && e.url().equals(first.url())
                        && e.text().equals(first.text())
                        && e.json().path("message_id").equals(first.json().path("message_id"))))
        .hasSize(1);
  }
}
