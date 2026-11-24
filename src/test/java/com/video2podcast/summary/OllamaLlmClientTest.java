package com.video2podcast.summary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.video2podcast.config.AppProperties;
import com.video2podcast.config.SummaryConfig;
import com.video2podcast.storage.Json;
import com.video2podcast.support.LoopbackWireMock;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.ResourceAccessException;

class OllamaLlmClientTest {

  private WireMockServer server;
  private AnnotationConfigApplicationContext context;

  @BeforeEach
  void setUp() {
    server = LoopbackWireMock.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
    if (context != null) {
      context.close();
    }
  }

  private static AppProperties.Ollama config(String baseUrl, Duration timeout) {
    return new AppProperties.Ollama(baseUrl, "qwen3:32b", 8192, timeout);
  }

  /** The client as the application wires it: through {@link SummaryConfig}. */
  private LlmClient clientFor(AppProperties.Ollama ollama) {
    context = new AnnotationConfigApplicationContext();
    context.registerBean(
        AppProperties.class, () -> new AppProperties(null, null, ollama, null, null, null, null));
    context.register(SummaryConfig.class);
    context.refresh();
    return context.getBean(LlmClient.class);
  }

  private LlmClient client() {
    return clientFor(config(server.baseUrl(), Duration.ofSeconds(5)));
  }

  private void stub(int status, String body) {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
  }

  private static LlmException failure(LlmClient client) {
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

  private static LlmException failureWith(RuntimeException thrown) {
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenThrow(thrown);
    return failure(new OllamaLlmClient(chat, "m", 8192, Duration.ofMinutes(10)));
  }

  private static ChatResponse answer(String text, Integer promptTokens, String finishReason) {
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, 3)).build();
    Generation generation =
        new Generation(
            new AssistantMessage(text),
            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build());
    return new ChatResponse(java.util.List.of(generation), metadata);
  }

  private static LlmException failureOfAnswer(ChatResponse response) {
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(response);
    return failure(new OllamaLlmClient(chat, "m", 8192, Duration.ofMinutes(10)));
  }

  @Test
  void sendsEveryFieldOfTheContract() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));

    client().complete("the system", "the user text", 777);

    server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/chat"))
            .withHeader("Content-Type", equalTo("application/json")));
    JsonNode body =
        Json.MAPPER.readTree(server.getAllServeEvents().get(0).getRequest().getBodyAsString());
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
    LlmClient other =
        clientFor(
            new AppProperties.Ollama(server.baseUrl(), "qwen3:14b", 4096, Duration.ofSeconds(5)));

    other.complete("s", "u", 10);

    JsonNode body =
        Json.MAPPER.readTree(server.getAllServeEvents().get(0).getRequest().getBodyAsString());
    assertThat(body.path("model").asText()).isEqualTo("qwen3:14b");
    assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(4096);
  }

  @Test
  void aTrailingSlashOfTheBaseUrlIsAccepted() throws Exception {
    stub(200, fixture("ollama-chat-ok.json"));

    clientFor(config(server.baseUrl() + "/", Duration.ofSeconds(5))).complete("s", "u", 10);

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
    stub(
        200,
        "{\"message\":{\"role\":\"assistant\",\"content\":\" \\n- a \\n\"},\"prompt_eval_count\":5}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- a");
  }

  @Test
  void theCachedCountIsIgnored() throws Exception {
    stub(
        200,
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":1,"
            + "\"prompt_eval_cached_count\":100000}");

    assertThat(client().complete("s", "u", 10).promptTokens()).isEqualTo(1);
  }

  @Test
  void thinkingIsNotUsed() throws Exception {
    stub(
        200,
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- real\",\"thinking\":\"secret\"},"
            + "\"prompt_eval_count\":9}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- real");
  }

  @Test
  void anAnswerStoppedAtTheLengthLimitIsAccepted() throws Exception {
    stub(
        200,
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- cut\"},\"done_reason\":\"length\","
            + "\"prompt_eval_count\":9,\"eval_count\":4}");

    assertThat(client().complete("s", "u", 10).content()).isEqualTo("- cut");
  }

  @Test
  void anAnswerStoppedAtTheLengthLimitIsAcceptedFromTheModel() throws Exception {
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(answer("- cut", 9, "length"));

    assertThat(
            new OllamaLlmClient(chat, "m", 8192, Duration.ofMinutes(10))
                .complete("s", "u", 10)
                .content())
        .isEqualTo("- cut");
  }

  @Test
  void theThinkingOnlyAnswerIsAFailure() throws Exception {
    assertThat(failureOf(200, fixture("ollama-chat-thinking-default.json")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aMissingModelIsAFailure() throws Exception {
    assertThat(failureOf(404, fixture("ollama-model-not-found.json")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 500, 503})
  void anErrorStatusIsAFailureAndIsNotRetried(int status) {
    stub(status, "{\"error\":\"boom\"}");

    assertThat(failure(client()).reason()).isEqualTo(LlmException.Reason.OTHER);

    server.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
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
        "{\"message\":{\"role\":\"assistant\",\"content\":null},\"prompt_eval_count\":9}",
        "{\"message\":{\"role\":\"assistant\",\"content\":42},\"prompt_eval_count\":9}",
        "{\"message\":{\"role\":\"assistant\",\"content\":[\"a\"]},\"prompt_eval_count\":9}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":71.5}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":\"71\"}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":true}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"prompt_eval_count\":9}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\" \\t\\n \"},\"prompt_eval_count\":9}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"}}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":null}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":0}",
        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},\"prompt_eval_count\":-5}"
      })
  void anInvalidSuccessfulAnswerIsAFailure(String body) {
    assertThat(failureOf(200, body).reason()).isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aMissingOrZeroPromptCountFromTheModelIsAFailure() {
    assertThat(failureOfAnswer(answer("- a", 0, "stop")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
    assertThat(failureOfAnswer(answer("- a", null, "stop")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aBlankOrNullTextFromTheModelIsAFailure() {
    assertThat(failureOfAnswer(answer(" ", 5, "stop")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
    assertThat(failureOfAnswer(answer(null, 5, "stop")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void noResultOrNoMetadataFromTheModelIsAFailure() {
    assertThat(failureOfAnswer(null).reason()).isEqualTo(LlmException.Reason.OTHER);
    assertThat(failureOfAnswer(new ChatResponse(java.util.List.of())).reason())
        .isEqualTo(LlmException.Reason.OTHER);
    Generation generation = new Generation(new AssistantMessage("- a"));
    assertThat(failureOfAnswer(new ChatResponse(java.util.List.of(generation))).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aRefusedConnectionIsAConnectionError() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    LlmException e = failure(clientFor(config("http://localhost:" + port, Duration.ofSeconds(5))));

    assertThat(e.reason()).isEqualTo(LlmException.Reason.CONNECTION);
  }

  @Test
  void connectionFailuresInTheCauseChainAreConnectionErrors() {
    for (Throwable cause :
        new Throwable[] {
          new HttpConnectTimeoutException("connect timed out"),
          new UnresolvedAddressException(),
          new ConnectException("refused")
        }) {
      assertThat(
              failureWith(new ResourceAccessException("I/O error", new IOException(cause)))
                  .reason())
          .isEqualTo(LlmException.Reason.CONNECTION);
    }
  }

  @Test
  void aRequestTimeoutInTheCauseChainIsATimeout() {
    for (Throwable cause :
        new Throwable[] {
          new HttpTimeoutException("request timed out"), new SocketTimeoutException("read")
        }) {
      assertThat(
              failureWith(new ResourceAccessException("I/O error", new IOException(cause)))
                  .reason())
          .isEqualTo(LlmException.Reason.TIMEOUT);
    }
  }

  @Test
  void aSlowAnswerTimesOutAfterTheConfiguredTimeout() {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(3000)));
    LlmClient slow = clientFor(config(server.baseUrl(), Duration.ofMillis(300)));

    long start = System.nanoTime();
    LlmException e = failure(slow);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(e.reason()).isEqualTo(LlmException.Reason.TIMEOUT);
    assertThat(took).isLessThan(Duration.ofSeconds(2));
    server.verify(1, postRequestedFor(urlEqualTo("/api/chat")));
  }

  @Test
  void aStallAfterTheHeadersAndPartialBodyTimesOut() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor()) {
      executor.submit(() -> {
        try (java.net.Socket peer = socket.accept()) {
          java.io.InputStream in = peer.getInputStream();
          byte[] buffer = new byte[8192];
          in.read(buffer);
          java.io.OutputStream out = peer.getOutputStream();
          out.write(
              ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                      + "Content-Length: 1000\r\n\r\n{\"message\":{\"role\":\"assi")
                  .getBytes(StandardCharsets.UTF_8));
          out.flush();
          Thread.sleep(2500);
        } catch (IOException | InterruptedException e) {
          // the client gave up first
        }
        return null;
      });
      LlmClient stalled =
          clientFor(config("http://localhost:" + socket.getLocalPort(), Duration.ofMillis(500)));

      long start = System.nanoTime();
      LlmException e = failure(stalled);

      assertThat(e.reason()).isEqualTo(LlmException.Reason.TIMEOUT);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
  }

  @Test
  void aStreamClosedBeforeTheTimeoutIsNotATimeout() {
    assertThat(failureWith(new ResourceAccessException("I/O", new IOException("closed"))).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aCallThatNeverReturnsIsCutOffAndInterrupted() {
    java.util.concurrent.atomic.AtomicBoolean interrupted =
        new java.util.concurrent.atomic.AtomicBoolean();
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class)))
        .thenAnswer(call -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException e) {
            interrupted.set(true);
          }
          return null;
        });

    LlmException e = failure(new OllamaLlmClient(chat, "m", 8192, Duration.ofMillis(100)));

    assertThat(e.reason()).isEqualTo(LlmException.Reason.TIMEOUT);
    org.awaitility.Awaitility.await().untilTrue(interrupted);
  }

  @Test
  void aCallerInterruptedWhileWaitingIsAnOtherFailureAndKeepsTheFlag() {
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class)))
        .thenAnswer(call -> {
          Thread.sleep(5_000);
          return null;
        });
    Thread.currentThread().interrupt();
    try {
      LlmException e = failure(new OllamaLlmClient(chat, "m", 8192, Duration.ofMinutes(1)));

      assertThat(e.reason()).isEqualTo(LlmException.Reason.OTHER);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void theRetryWrapperIsNotRetriedAndItsCauseIsClassified() {
    assertThat(failureWith(new TransientAiException("503 - down")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
    assertThat(failureWith(new NonTransientAiException("400 - bad")).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void otherFailuresAreOtherFailuresAndTheCauseIsKept() {
    IOException io = new IOException("broken pipe");
    ResourceAccessException thrown = new ResourceAccessException("I/O error", io);

    LlmException e = failureWith(thrown);

    assertThat(e.reason()).isEqualTo(LlmException.Reason.OTHER);
    assertThat(e.getCause().getCause()).isSameAs(thrown);
  }

  @Test
  void aFailureWithoutAMessageIsAnOtherFailure() {
    assertThat(failureWith(new IllegalStateException()).reason())
        .isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void aLongFailureMessageDoesNotBreakTheFailure() {
    assertThat(failureWith(new IllegalStateException("x".repeat(5000))).getMessage())
        .doesNotContain("xxx");
  }

  @Test
  void aConnectionResetIsAnOtherFailure() {
    server.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(failure(client()).reason()).isEqualTo(LlmException.Reason.OTHER);
  }

  @Test
  void anInterruptionIsAnOtherFailureAndKeepsTheFlag() {
    try {
      LlmException e =
          failureWith(
              new ResourceAccessException("I/O", new IOException(new InterruptedException())));

      assertThat(e.reason()).isEqualTo(LlmException.Reason.OTHER);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
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
  void theRequestOptionsAreSetOnEveryCall() throws Exception {
    ChatModel chat = mock(ChatModel.class);
    when(chat.call(any(Prompt.class))).thenReturn(answer("- a", 5, "stop"));
    OllamaLlmClient client = new OllamaLlmClient(chat, "m1", 2048, Duration.ofMinutes(10));

    client.complete("s", "u", 11);
    client.complete("s", "u", 22);

    org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    org.mockito.Mockito.verify(chat, org.mockito.Mockito.times(2)).call(captor.capture());
    OllamaChatOptions first = (OllamaChatOptions) captor.getAllValues().get(0).getOptions();
    OllamaChatOptions second = (OllamaChatOptions) captor.getAllValues().get(1).getOptions();
    assertThat(first.getModel()).isEqualTo("m1");
    assertThat(first.getNumCtx()).isEqualTo(2048);
    assertThat(first.getNumPredict()).isEqualTo(11);
    assertThat(second.getNumPredict()).isEqualTo(22);
    assertThat(first.getTemperature()).isEqualTo(0.2);
    assertThat(first.getKeepAlive()).isEqualTo("10m");
    assertThat(first.getThinkOption())
        .isEqualTo(org.springframework.ai.ollama.api.ThinkOption.ThinkBoolean.DISABLED);
  }
}
