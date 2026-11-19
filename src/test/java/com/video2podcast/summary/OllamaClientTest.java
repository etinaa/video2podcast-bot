package com.video2podcast.summary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.video2podcast.config.AppProperties;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaClientTest {

  private WireMockServer server;

  @BeforeEach
  void setUp() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private static AppProperties.Ollama config(String baseUrl, Duration timeout) {
    return new AppProperties.Ollama(baseUrl, "qwen3:32b", 8192, timeout);
  }

  private OllamaClient client() {
    return new OllamaClient(config("http://localhost:" + server.port(), Duration.ofSeconds(5)));
  }

  private void stub(int status, String body) {
    server.stubFor(
        post(urlEqualTo("/api/chat")).willReturn(aResponse().withStatus(status).withBody(body)));
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
  }

  private static LlmException failure(OllamaClient client) {
    try {
      client.complete("system", "user", 1024);
    } catch (LlmException e) {
      return e;
    }
    throw new AssertionError("no LlmException");
  }

  private LlmException failureOf(int status, String body) {
    stub(status, body);
    return failure(client());
  }

  private static LlmException failureWith(Throwable thrown) throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(), any())).thenThrow(thrown);
    return failure(new OllamaClient(config("http://localhost:1", Duration.ofSeconds(5)), http));
  }

  @Test
  void sendsEveryFieldOfTheContract() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));

    client().complete("the system", "the user text", 777);

    server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/chat"))
            .withHeader(
                "Content-Type",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json")));
    JsonNode body =
        Json.MAPPER.readTree(server.getAllServeEvents().get(0).getRequest().getBodyAsString());
    assertThat(body.size()).isEqualTo(6);
    assertThat(body.path("model").asText()).isEqualTo("qwen3:32b");
    assertThat(body.path("stream").isBoolean()).isTrue();
    assertThat(body.path("stream").asBoolean()).isFalse();
    assertThat(body.path("think").isBoolean()).isTrue();
    assertThat(body.path("think").asBoolean()).isFalse();
    assertThat(body.path("keep_alive").asText()).isEqualTo("10m");
    JsonNode options = body.path("options");
    assertThat(options.size()).isEqualTo(3);
    assertThat(options.path("num_ctx").asInt()).isEqualTo(8192);
    assertThat(options.path("num_predict").asInt()).isEqualTo(777);
    assertThat(options.path("temperature").asDouble()).isEqualTo(0.2);
    JsonNode messages = body.path("messages");
    assertThat(messages.size()).isEqualTo(2);
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(0).path("content").asText()).isEqualTo("the system");
    assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    assertThat(messages.get(1).path("content").asText()).isEqualTo("the user text");
  }

  @Test
  void theConfiguredModelAndContextAreSent() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));
    OllamaClient other =
        new OllamaClient(
            new AppProperties.Ollama(
                "http://localhost:" + server.port(), "qwen3:14b", 4096, Duration.ofSeconds(5)));

    other.complete("s", "u", 10);

    JsonNode body =
        Json.MAPPER.readTree(server.getAllServeEvents().get(0).getRequest().getBodyAsString());
    assertThat(body.path("model").asText()).isEqualTo("qwen3:14b");
    assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(4096);
  }

  @Test
  void aTrailingSlashOfTheBaseUrlIsDropped() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));
    OllamaClient slash =
        new OllamaClient(config("http://localhost:" + server.port() + "/", Duration.ofSeconds(5)));

    slash.complete("s", "u", 10);

    server.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
  }

  @Test
  void returnsTheStrippedContentAndThePromptTokens() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));

    LlmReply reply = client().complete("s", "u", 10);

    assertThat(reply.content())
        .isEqualTo(
            "- The user is testing the video to podcast bot.  \n"
                + "- A meeting is scheduled for Tuesday at 10 o'clock to discuss the budget.");
    assertThat(reply.promptTokens()).isEqualTo(71);
  }

  @Test
  void contentIsStripped() throws Exception {
    stub(200, "{\"message\":{\"content\":\" \\n- a \\n\"},\"prompt_eval_count\":5}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- a");
  }

  @Test
  void theCachedCountIsIgnored() throws Exception {
    stub(
        200,
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":1,\"prompt_eval_cached_count\":100000}");

    assertThat(client().complete("s", "u", 10).promptTokens()).isEqualTo(1);
  }

  @Test
  void thinkingAndOtherFieldsAreNotUsed() throws Exception {
    stub(
        200,
        "{\"message\":{\"content\":\"- real\",\"thinking\":\"secret\"},\"response\":\"x\","
            + "\"prompt_eval_count\":9}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- real");
  }

  @Test
  void anAnswerStoppedAtTheLengthLimitIsAccepted() throws Exception {
    stub(
        200,
        "{\"message\":{\"content\":\"- cut\"},\"done_reason\":\"length\",\"prompt_eval_count\":9}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- cut");
  }

  @Test
  void theThinkingOnlyAnswerIsAFailure() throws Exception {
    assertThat(failureOf(200, fixture("ollama-chat-thinking-default.json")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aMissingModelIsAFailure() throws Exception {
    LlmException e = failureOf(404, fixture("ollama-model-not-found.json"));

    assertThat(e.reason()).isEqualTo(LlmException.Reason.OTHER);
    assertThat(e.getMessage()).contains("404");
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 302, 400, 500, 503})
  void aStatusOutsideTwoHundredIsAFailure(int status) {
    assertThat(
            failureOf(status, "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":9}")
                .reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 299})
  void anyTwoHundredStatusWithAValidBodyIsAccepted(int status) throws Exception {
    stub(status, "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":9}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- a");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "{broken",
        "[]",
        "42",
        "null",
        "\"text\"",
        "{}",
        "{\"prompt_eval_count\":9}",
        "{\"message\":null,\"prompt_eval_count\":9}",
        "{\"message\":{},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":null},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":42},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":[\"a\"]},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":\"\"},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":\" \\t\\n \"},\"prompt_eval_count\":9}",
        "{\"message\":{\"content\":\"- a\"}}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":null}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":0}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":-5}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":71.5}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":71.0}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":\"71\"}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":true}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":99999999999999999999}",
        "{\"message\":{\"content\":\"- a\"},\"prompt_eval_count\":9} trailing"
      })
  void anInvalidSuccessfulAnswerIsAFailure(String body) {
    assertThat(failureOf(200, body).reason()).isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aRefusedConnectionIsAConnectionError() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    OllamaClient refused =
        new OllamaClient(config("http://localhost:" + port, Duration.ofSeconds(5)));

    assertThat(failure(refused).reason()).isEqualTo(LlmException.Reason.CONNECTION);
  }

  @Test
  void aConnectTimeoutIsAConnectionError() throws Exception {
    assertThat(failureWith(new HttpConnectTimeoutException("connect timed out")).reason())
        .isEqualTo(LlmException.Reason.CONNECTION);
  }

  @Test
  void anUnresolvedAddressIsAConnectionError() throws Exception {
    assertThat(failureWith(new UnresolvedAddressException()).reason())
        .isEqualTo(LlmException.Reason.CONNECTION);
  }

  @Test
  void aConnectExceptionIsAConnectionError() throws Exception {
    assertThat(failureWith(new java.net.ConnectException("refused")).reason())
        .isEqualTo(LlmException.Reason.CONNECTION);
  }

  @Test
  void aRequestTimeoutIsATimeout() throws Exception {
    assertThat(failureWith(new HttpTimeoutException("request timed out")).reason())
        .isEqualTo(LlmException.Reason.TIMEOUT);
  }

  @Test
  void aSlowAnswerTimesOut() {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(2000)));
    OllamaClient slow =
        new OllamaClient(config("http://localhost:" + server.port(), Duration.ofMillis(200)));

    LlmException e = failure(slow);

    assertThat(e.reason()).isEqualTo(LlmException.Reason.TIMEOUT);
    assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class);
  }

  @Test
  void otherIoErrorsAreOtherFailures() throws Exception {
    assertThat(failureWith(new IOException("broken pipe")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aConnectionResetIsAnOtherFailure() {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(failure(client()).reason()).isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void anInterruptionIsAnOtherFailureAndKeepsTheFlag() throws Exception {
    try {
      LlmException e = failureWith(new InterruptedException("stop"));

      assertThat(e.reason()).isEqualTo(LlmException.Reason.OTHER);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void theCauseIsKept() throws Exception {
    IOException cause = new IOException("broken pipe");

    assertThat(failureWith(cause).getCause()).isSameAs(cause);
  }

  @Test
  void theHttpClientHasAFiveSecondConnectTimeoutAndNeverFollowsRedirects() {
    HttpClient http = client().httpClient();

    assertThat(http.connectTimeout()).contains(Duration.ofSeconds(5));
    assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
  }

  @Test
  void aRedirectIsNotFollowed() {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));

    assertThat(failure(client()).reason()).isEqualTo(LlmException.Reason.OTHER);
    server.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
  }

  @Test
  void aLongErrorBodyDoesNotBreakTheFailure() {
    assertThat(failureOf(500, "x".repeat(5000)).getMessage()).doesNotContain("xxx");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " \t\n"})
  void aBlankModelIsRejectedAtConstruction(String model) {
    AppProperties.Ollama config =
        new AppProperties.Ollama("http://localhost:1", model, 8192, Duration.ofSeconds(5));

    assertThatThrownBy(() -> new OllamaClient(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
  }

  @Test
  void aNullModelIsRejectedAtConstruction() {
    AppProperties.Ollama config =
        new AppProperties.Ollama("http://localhost:1", null, 8192, Duration.ofSeconds(5));

    assertThatThrownBy(() -> new OllamaClient(config)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNonPositiveOrMissingTimeoutIsRejectedAtConstruction() {
    for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofMillis(-1), null}) {
      AppProperties.Ollama config =
          new AppProperties.Ollama("http://localhost:1", "m", 8192, timeout);

      assertThatThrownBy(() -> new OllamaClient(config))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("timeout");
    }
  }

  @Test
  void theSmallestPositiveTimeoutIsAccepted() {
    new OllamaClient(
        new AppProperties.Ollama("http://localhost:1", "m", 8192, Duration.ofNanos(1)));
  }
}
