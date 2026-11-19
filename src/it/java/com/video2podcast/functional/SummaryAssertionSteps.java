package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.functional.PipelineWorld.Event;
import io.cucumber.java.en.Then;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** External boundary assertions for Ollama summary requests. */
public class SummaryAssertionSteps {
  final PipelineWorld w;

  public SummaryAssertionSteps(PipelineWorld world) {
    w = world;
  }

  @Then("the Ollama mock receives exactly {int} POST request(s) to {string}")
  public void ollamaCount(int n, String path) {
    w.finishOne();
    assertThat(w.service("ollama")).hasSize(n).allSatisfy(e -> assertThat(e.url()).isEqualTo(path));
  }

  @Then(
      "every Ollama request contains model {string}, stream false, think false and options.num_ctx {int}")
  public void options(String model, int ctx) {
    w.finishOne();
    List<Event> requests = w.service("ollama");
    assertThat(requests).isNotEmpty();
    for (Event e : requests) {
      JsonNode j = e.json();
      assertThat(j.path("model").asText()).isEqualTo(model);
      assertThat(j.path("stream").asBoolean(true)).isFalse();
      assertThat(j.path("think").asBoolean(true)).isFalse();
      assertThat(j.path("options").path("num_ctx").asInt()).isEqualTo(ctx);
    }
  }

  String input(Event e) {
    return e.json().path("messages").get(1).path("content").asText();
  }

  String system(Event e) {
    return e.json().path("messages").get(0).path("content").asText();
  }

  @Then(
      "the Ollama request contains the complete transcript and instructions for English bullet points")
  public void prompt() {
    assertThat(input(w.service("ollama").getFirst())).isEqualTo(w.transcript);
    english(w.service("ollama").getFirst());
  }

  void english(Event e) {
    assertThat(system(e)).contains("English", "bullet");
  }

  @Then("the Telegram summary contains the bullet points about Tuesday and engineering spending")
  public void expectedBullets() {
    assertThat(w.body())
        .contains("• The budget meeting is on Tuesday.", "• Engineering spending increased.");
  }

  @Then("the Telegram summary contains {string}")
  public void hasBullet(String s) {
    assertThat(w.body()).contains(s);
  }

  @Then(
      "the first {int} Ollama requests contain the entire transcript in order without omissions or duplication")
  public void fullInput(int n) {
    assertThat(
            w.service("ollama").subList(0, n).stream()
                .map(this::input)
                .collect(Collectors.joining(" ")))
        .isEqualTo(w.wholeTranscript);
  }

  @Then("their transcript inputs start with {string} and end with {string}")
  public void ends(String a, String b) {
    assertThat(input(w.service("ollama").getFirst())).startsWith(a);
    assertThat(input(w.service("ollama").get(2))).endsWith(b);
  }

  @Then(
      "the fourth Ollama request contains all {int} partial summaries in order and instructions for English bullet points")
  public void reduce(int n) {
    assertThat(input(w.service("ollama").get(3)))
        .isEqualTo(String.join("\n\n", w.modelContents.subList(0, n)));
    english(w.service("ollama").get(3));
  }

  @Then(
      "each recorded Ollama request fits its context including instructions, chat overhead and reserved output")
  public void budget() {
    for (Event e : w.service("ollama")) {
      long count = (system(e).length() + 2) / 3 + (input(e).length() + 2) / 3 + 32 + 1024;
      assertThat(count).isLessThanOrEqualTo(e.json().path("options").path("num_ctx").asInt());
    }
  }

  @Then("the ninth Ollama request contains bounded prefixes for sections 1 through 4 in order")
  public void firstGroup() {
    prefixes(8, 1, 4);
  }

  @Then("the tenth Ollama request contains bounded prefixes for sections 5 through 8 in order")
  public void secondGroup() {
    prefixes(9, 5, 8);
  }

  void prefixes(int index, int start, int end) {
    String s = input(w.service("ollama").get(index));
    int last = -1;
    for (int i = start; i <= end; i++) {
      int at = s.indexOf("Section " + i + ":");
      assertThat(at).isGreaterThan(last);
      last = at;
      assertThat(s).doesNotContain(w.modelContents.get(i - 1));
    }
    assertThat(s.split("\n\n")).hasSize(end - start + 1);
  }

  @Then("the eleventh Ollama request contains both group summaries in order")
  public void lastGroup() {
    assertThat(input(w.service("ollama").get(10))).isEqualTo("- Group one.\n\n- Group two.");
  }

  @Then("the Ollama mock receives no request for the third transcript chunk or for reduction")
  public void stopMap() {
    w.finishOne();
    assertThat(w.service("ollama")).hasSize(2);
  }

  @Then("the Ollama mock receives no requests for later stages after the failure")
  public void stopped() {
    w.finishOne();
    assertThat(w.service("ollama").size()).isEqualTo(w.modelContents.size() + 1);
  }

  @Then(
      "the Telegram summary body has at most {int} characters including bullet markers and line separators")
  public void cap(int n) {
    assertThat(w.body().length()).isLessThanOrEqualTo(n);
  }

  @Then("the summary body is nonempty bullet text")
  public void nonemptyBody() {
    assertThat(w.body()).isNotBlank().startsWith("• ");
  }

  @Then("the Telegram summary body contains exactly these bullet lines:")
  public void exactBullets(String s) {
    assertThat(w.body()).isEqualTo(s);
  }
}
