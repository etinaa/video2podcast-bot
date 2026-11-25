package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.functional.PipelineWorld.Answer;
import com.video2podcast.functional.PipelineWorld.Event;
import io.cucumber.java.en.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/** T19 setup and assertions through HTTP fixtures, Telegram updates and retained files. */
public class LanguageSteps {
  final PipelineWorld w;
  final FailureModeWorld h;
  final DeferredSupport d;
  int whisperBeforeTap, ollamaBeforeTap, recoveryWhispers, recoveryNotices;
  byte[] retainedTranscript, retainedAudio;
  InferenceOutageProxy outage;

  public LanguageSteps(PipelineWorld world, FailureModeWorld health, DeferredSteps deferred) {
    w = world;
    h = health;
    d = deferred.d;
  }

  @Given(
      "the Whisper mock returns text {string} through {string} for the corresponding chunks with first language {string}")
  public void eighteen(String first, String last, String language) {
    assertThat(last).isEqualTo(first.substring(0, first.length() - 2) + "17");
    for (int i = 0; i < 18; i++)
      w.whisperAnswers.add(
          Answer.json(
              PipelineWorld.stringify(
                  Map.of(
                      "text",
                      first.substring(0, first.length() - 2) + "%02d".formatted(i),
                      "language",
                      language))));
  }

  @Given(
      "^the Whisper mock returns language \"([^\"]+)\" and text \"([^\"]+)\" for the (?:first|second|next) submission$")
  public void submission(String language, String text) {
    w.defaultWhisper =
        Answer.json(PipelineWorld.stringify(Map.of("text", text, "language", language)));
  }

  @Given(
      "^the Ollama mock returns valid prompt counts and content \"([^\"]+)\" for the (?:first|second) submission$")
  public void submissionContent(String text) {
    w.defaultContent = text;
  }

  @Given("the user has sent {string} in a private chat and received its summary")
  public void completed(String url) throws Exception {
    w.send(url);
    w.finishOne();
    w.drained();
    assertThat(w.summaries()).isNotEmpty();
    assertThat(w.latestTerminal().text()).isEqualTo("Done.");
    w.noUploads();
    retainedTranscript = Files.readAllBytes(w.publishedDir().resolve("transcript.txt"));
    retainedAudio = Files.readAllBytes(w.publishedDir().resolve("source.m4a"));
    whisperBeforeTap = w.service("whisper").size();
    ollamaBeforeTap = w.service("ollama").size();
  }

  @Given(
      "the user has sent {string} in a private chat and received the unsupported-language refusal")
  public void refused(String url) {
    w.send(url);
    w.reply("Only English and Russian videos are supported. Detected language: de.");
    w.finishOne();
    w.drained();
    assertThat(w.dirs()).isEmpty();
    assertThat(w.summaries()).isEmpty();
  }

  @Then("^their multipart (language|response_format) fields are \"([^\"]+)\" in order$")
  public void fields(String field, String expected) {
    settled();
    assertThat(w.service("whisper").stream().map(e -> LanguageSupport.field(e, field)).toList())
        .containsExactly(expected.split(", "));
  }

  @Then(
      "every Whisper request has multipart fields {string} equal to {string}, {string} equal to {string} and {string} equal to {string}")
  public void options(String a, String b, String c, String e, String f, String g) {
    settled();
    assertThat(w.service("whisper")).isNotEmpty();
    for (Event request : w.service("whisper")) {
      LanguageSupport.field(request, a, b);
      LanguageSupport.field(request, c, e);
      LanguageSupport.field(request, f, g);
    }
  }

  @Then("{string} contains exactly {string} followed by one LF newline in UTF-8")
  public void transcript(String file, String text) throws Exception {
    settled();
    assertThat(Files.readAllBytes(w.publishedDir().resolve(file)))
        .isEqualTo((text + "\n").getBytes(StandardCharsets.UTF_8));
  }

  @Then(
      "the first Whisper request uses language {string} and every later request uses language {string}")
  public void pinned(String first, String later) {
    settled();
    List<Event> requests = w.service("whisper");
    assertThat(requests).hasSizeGreaterThan(1);
    LanguageSupport.field(requests.getFirst(), "language", first);
    requests.subList(1, requests.size()).forEach(e -> LanguageSupport.field(e, "language", later));
  }

  @Then("the first Whisper request for each submission uses language {string}")
  public void fresh(String language) {
    settled();
    assertThat(w.service("whisper")).hasSize(2);
    w.service("whisper").forEach(e -> LanguageSupport.field(e, "language", language));
  }

  @Then("^the (first|second) submission's Ollama request asks for (Russian|English) bullet points$")
  public void promptByJob(String job, String language) {
    settled();
    assertThat(w.service("ollama")).hasSize(2);
    LanguageSupport.prompt(w.service("ollama").get(job.equals("first") ? 0 : 1), language);
  }

  @Then("the second Telegram summary contains {string}")
  public void secondSummary(String text) {
    settled();
    assertThat(w.summaries()).hasSize(2);
    assertThat(w.summaries().get(1).text()).contains(text);
  }

  @Then("^every Ollama request contains instructions for (Russian|English) bullet points$")
  public void allPrompts(String language) {
    settled();
    assertThat(w.service("ollama")).isNotEmpty();
    w.service("ollama").forEach(e -> LanguageSupport.prompt(e, language));
  }

  @Given(
      "^the Whisper mock returns language \"([^\"]+)\" and an ordered (Russian|English) transcript that fills exactly (\\d+) summary input chunks at context 8192$")
  public void longTranscript(String detected, String language, int chunks) {
    // Safely between seven and eight complete language-specific input budgets at 8192.
    int approximateBudget = language.equals("Russian") ? 14000 : 21000;
    String word = language.equals("Russian") ? "слово " : "word ";
    w.wholeTranscript =
        "Opening "
            + word.repeat((approximateBudget * chunks - approximateBudget / 2) / word.length())
            + "Closing";
    List<String> words = Arrays.asList(w.wholeTranscript.split(" "));
    int count = (int) ((w.seconds + 599) / 600), at = 0;
    w.whisperAnswers.clear();
    for (int i = 0; i < count; i++) {
      int end = (i + 1) * words.size() / count;
      w.whisperAnswers.add(
          Answer.json(
              PipelineWorld.stringify(
                  Map.of("text", String.join(" ", words.subList(at, end)), "language", detected))));
      at = end;
    }
  }

  @Given(
      "the Ollama mock returns valid prompt counts and one oversized Russian bullet for each of the first {int} requests")
  public void oversized(int count) {
    for (int i = 1; i <= count; i++)
      new PipelineSetupSteps(w).addContent("- Раздел " + i + ": " + "слово ".repeat(5000));
  }

  @Given("the ninth and tenth Ollama answers are valid short Russian bullet summaries")
  public void reductions() {
    new PipelineSetupSteps(w).addContent("- Первая группа.");
    new PipelineSetupSteps(w).addContent("- Вторая группа.");
  }

  @Given("the eleventh Ollama answer is a valid short final Russian bullet summary")
  public void finalReduction() {
    new PipelineSetupSteps(w).addContent("- Итоговое резюме.");
  }

  @Then("those requests include map, intermediate reduction and final reduction prompts")
  public void stages() {
    settled();
    List<Event> calls = w.service("ollama");
    assertThat(calls).hasSize(11);
    calls
        .subList(0, 8)
        .forEach(e ->
            assertThat(new SummaryAssertionSteps(w).system(e))
                .contains("one part of a longer video"));
    calls
        .subList(8, 10)
        .forEach(e ->
            assertThat(new SummaryAssertionSteps(w).system(e))
                .contains("Merge them into one shorter"));
    assertThat(new SummaryAssertionSteps(w).system(calls.get(10)))
        .contains("final summary of the whole video");
    String prefix = w.modelContents.getFirst().contains("Раздел") ? "Раздел " : "Section ";
    for (int group = 0; group < 2; group++) {
      String input = LanguageSupport.input(calls.get(8 + group));
      assertThat(input.split("\n\n")).hasSize(4);
      int previous = -1;
      for (int i = 1 + group * 4; i <= 4 + group * 4; i++) {
        int at = input.indexOf(prefix + i + ":");
        assertThat(at).isGreaterThan(previous);
        previous = at;
        assertThat(input).doesNotContain(w.modelContents.get(i - 1));
      }
    }
    assertThat(LanguageSupport.input(calls.get(10)))
        .isEqualTo(String.join("\n\n", w.modelContents.subList(8, 10)));
  }

  @Then("the map requests contain the entire transcript in order without omissions or duplication")
  public void mapInput() {
    assertThat(
            w.service("ollama").subList(0, 8).stream()
                .map(LanguageSupport::input)
                .collect(Collectors.joining(" ")))
        .isEqualTo(w.wholeTranscript);
  }

  @Then(
      "^the final Telegram summary contains the final (Russian|English) bullets returned by the Ollama mock$")
  public void finalBullets(String language) {
    assertThat(w.body()).isEqualTo(w.modelContents.getLast().replaceFirst("^- ", "• "));
  }

  @Given(
      "the Ollama mock returns valid prompt counts and Russian bullet content longer than 1500 characters beginning with {string}")
  public void cappedContent(String prefix) {
    w.defaultContent = prefix + " Русское содержание.".repeat(150);
  }

  @Then(
      "the displayed summary body has at most {int} characters under the existing summary counting rule")
  public void cap(int length) {
    assertThat(w.body()).isNotBlank().hasSizeLessThanOrEqualTo(length);
  }

  @Then("the summary body displays literal {string}")
  public void literal(String text) {
    assertThat(w.body()).contains(text);
  }

  @Then(
      "the summary sendMessage request has valid HTML and at most {int} characters after HTML entity parsing")
  public void html(int length) throws Exception {
    var check = new PipelineAssertionSteps(w);
    check.validHtml();
    assertThat(check.parsedText()).hasSizeLessThanOrEqualTo(length);
  }

  @Then("the status edits contain {string}, {string} and {string} in order")
  public void statuses(String a, String b, String c) {
    settled();
    assertThat(w.calls("editMessageText").stream().map(Event::text).toList())
        .containsSubsequence(a, b, c);
  }

  @Then("{string} is sent exactly once")
  public void once(String chunk) {
    settled();
    assertThat(w.service("whisper").stream().filter(e -> e.chunk().equals(chunk))).hasSize(1);
  }

  @Then("the Ollama mock receives no POST request to {string}")
  public void noChat(String path) {
    settled();
    assertThat(path).isEqualTo("/api/chat");
    assertThat(w.service("ollama")).isEmpty();
  }

  @Then("no recovery-promise message is sent for this submission")
  public void noPromise() {
    settled();
    assertThat(
            w.events.stream()
                .filter(e ->
                    e.service().equals("telegram") && !e.url().endsWith("/answercallbackquery")))
        .noneMatch(e -> e.text().equals(DeferredSupport.PARKED));
  }

  @Then("both requests use language {string} and response_format {string}")
  public void both(String language, String format) {
    settled();
    assertThat(w.service("whisper")).hasSize(2);
    for (Event e : w.service("whisper")) {
      LanguageSupport.field(e, "language", language);
      LanguageSupport.field(e, "response_format", format);
    }
  }

  @Then("the first two Whisper requests both use language {string}")
  public void firstTwo(String language) {
    settled();
    assertThat(w.service("whisper")).hasSize(2);
    w.service("whisper").forEach(e -> LanguageSupport.field(e, "language", language));
  }

  @Then("no unsupported-language refusal is sent")
  public void noRefusal() {
    settled();
    assertThat(w.calls("sendMessage"))
        .noneMatch(e -> e.text().startsWith("Only English and Russian"));
  }

  @Then("the second Whisper request uses language {string}")
  public void secondLanguage(String language) {
    settled();
    LanguageSupport.field(w.service("whisper").get(1), "language", language);
  }

  @Then("the next submission's first Whisper request uses language {string}")
  public void nextLanguage(String language) {
    settled();
    LanguageSupport.field(
        w.service("whisper").stream()
            .filter(e -> e.nanos() >= w.lastUpdateNanos)
            .findFirst()
            .orElseThrow(),
        "language",
        language);
  }

  @Given("the Whisper endpoint refuses connections for both attempts of {string}")
  public void laterOutage(String chunk) {
    assertThat(chunk).isEqualTo("chunk-001.wav");
    try {
      outage = new InferenceOutageProxy(w.whisper.baseUrl());
      w.prop("app.whisper.base-url", outage.url());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Given(
      "a previously parked submission for {string} exists in deferred-jobs.json with its original status message")
  public void parked(String url) {
    h.next("whisper", "connection refusal");
    h.send(w.user, url);
    d.parked(w.user);
    w.drained();
  }

  @Given("both service health endpoints now answer HTTP 200")
  public void healthy() {
    h.policy("whisper", "HTTP 200");
    h.policy("ollama", "HTTP 200");
  }

  @When("the deferred retry interval elapses")
  @When("another deferred retry interval elapses")
  public void tick() throws Exception {
    recoveryWhispers = w.service("whisper").size();
    recoveryNotices = w.calls("sendMessage").size();
    d.tick();
    w.drained();
  }

  @Then(
      "no further Whisper inference or unsupported-language notification occurs for this submission")
  public void noRepeat() {
    assertThat(w.service("whisper")).hasSize(recoveryWhispers);
    assertThat(w.calls("sendMessage")).hasSize(recoveryNotices);
  }

  @io.cucumber.java.After(value = "@T19", order = 120)
  public void closeOutage() throws Exception {
    if (outage != null) outage.close();
  }

  void settled() {
    w.finishOne();
    w.drained();
  }
}
