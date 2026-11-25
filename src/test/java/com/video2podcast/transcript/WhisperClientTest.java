package com.video2podcast.transcript;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.ProbeResult;
import com.video2podcast.support.LoopbackWireMock;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperClientTest {

  private static final String OK = "{\"text\":\" Hello\\n world \"}";

  @TempDir Path dir;

  private WireMockServer server;
  private Path chunk;

  @BeforeEach
  void setUp() throws IOException {
    server = LoopbackWireMock.start();
    chunk = Files.write(dir.resolve("chunk-000.wav"), new byte[] {1, 2, 3, (byte) 0xFF});
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private WhisperClient client(String baseUrl, Duration timeout) {
    return new WhisperClient(new AppProperties.Whisper(baseUrl, timeout));
  }

  private WhisperClient client() {
    return client(server.baseUrl(), Duration.ofSeconds(5));
  }

  private void stub(int status, String body) {
    server.stubFor(
        post(urlEqualTo("/inference")).willReturn(aResponse().withStatus(status).withBody(body)));
  }

  private WhisperException failure() {
    return catchWhisper(() -> client().transcribe(chunk, "en"));
  }

  private static WhisperException catchWhisper(ThrowingRun run) {
    try {
      run.run();
    } catch (WhisperException e) {
      return e;
    }
    throw new AssertionError("no WhisperException");
  }

  private interface ThrowingRun {
    void run() throws WhisperException;
  }

  private static String fixture(String name) throws IOException {
    return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
  }

  @Test
  void theHttpClientHasAFiveSecondConnectTimeoutAndNeverFollowsRedirects() {
    HttpClient http = client().httpClient();

    assertThat(http.connectTimeout()).contains(Duration.ofSeconds(5));
    assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
  }

  @Test
  void aConnectTimeoutIsAConnectionError() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(), any())).thenThrow(new HttpConnectTimeoutException("connect timed out"));
    WhisperClient whisper =
        new WhisperClient(
            new AppProperties.Whisper("http://localhost:1", Duration.ofSeconds(5)), http);

    WhisperException e = catchWhisper(() -> whisper.transcribe(chunk, "en"));

    assertThat(e.isConnection()).isTrue();
  }

  @Test
  void twoConnectTimeoutsGiveServiceUnreachable() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(), any())).thenThrow(new HttpConnectTimeoutException("connect timed out"));
    WhisperClient whisper =
        new WhisperClient(
            new AppProperties.Whisper("http://localhost:1", Duration.ofSeconds(5)), http);

    assertThatThrownBy(
            () -> new TranscriptionClient(whisper).transcribe(dir, List.of(chunk), (n, t) -> {}))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE));
    verify(http, times(2)).send(any(), any());
  }

  @Test
  void sendsTheFourPartsAndReturnsRawText() throws Exception {
    stub(200, fixture("whisper-inference-ok.json"));

    String text = client().transcribe(chunk, "en").text();

    assertThat(text)
        .isEqualTo(
            " Hello, this is a short test of the video to podcast but the meeting is on Tuesday"
                + " at 10\n o'clock, and we will discuss the budget.\n");
    server.verify(
        1,
        postRequestedFor(urlEqualTo("/inference"))
            .withHeader(
                "Content-Type",
                com.github.tomakehurst.wiremock.client.WireMock.containing(
                    "multipart/form-data; boundary=")));
    LoggedRequest request = server.getAllServeEvents().get(0).getRequest();
    String body = new String(request.getBody(), StandardCharsets.ISO_8859_1);
    int file = body.indexOf("name=\"file\"; filename=\"chunk-000.wav\"");
    int format = body.indexOf("name=\"response_format\"");
    int language = body.indexOf("name=\"language\"");
    int temperature = body.indexOf("name=\"temperature\"");
    assertThat(file).isPositive();
    assertThat(file).isLessThan(format);
    assertThat(format).isLessThan(language);
    assertThat(language).isLessThan(temperature);
    assertThat(body).contains("Content-Type: audio/wav\r\n\r\n\u0001\u0002\u0003ÿ\r\n");
    assertThat(body)
        .contains("\r\n\r\njson\r\n")
        .contains("\r\n\r\nen\r\n")
        .contains("\r\n\r\n0.0\r\n");
    assertThat(field(body, "response_format")).isEqualTo("json");
    assertThat(field(body, "language")).isEqualTo("en");
    assertThat(field(body, "translate")).isEqualTo("false");
    assertThat(field(body, "detect_language")).isEqualTo("false");
    assertThat(field(body, "temperature")).isEqualTo("0.0");
  }

  private String sentBody() {
    LoggedRequest request = server.getAllServeEvents().get(0).getRequest();
    return new String(request.getBody(), StandardCharsets.ISO_8859_1);
  }

  private static String field(String body, String name) {
    String head = "name=\"" + name + "\"\r\n\r\n";
    int start = body.indexOf(head) + head.length();
    return body.substring(start, body.indexOf("\r\n", start));
  }

  @Test
  void detectModeAsksForVerboseJsonAndReadsTheLanguage() throws Exception {
    stub(200, "{\"text\":\" Привет \",\"language\":\"russian\",\"segments\":[]}");

    WhisperResult result = client().transcribe(chunk, "auto");

    assertThat(result.text()).isEqualTo(" Привет ");
    assertThat(result.language()).isEqualTo("ru");
    String body = sentBody();
    assertThat(field(body, "response_format")).isEqualTo("verbose_json");
    assertThat(field(body, "language")).isEqualTo("auto");
    assertThat(field(body, "translate")).isEqualTo("false");
    assertThat(field(body, "detect_language")).isEqualTo("false");
    assertThat(field(body, "temperature")).isEqualTo("0.0");
  }

  @Test
  void pinnedModeIgnoresTheLanguageOfTheAnswer() throws Exception {
    stub(200, "{\"text\":\"Привет\",\"language\":\"german\"}");

    WhisperResult result = client().transcribe(chunk, "ru");

    assertThat(result).isEqualTo(new WhisperResult("Привет", null));
    assertThat(field(sentBody(), "language")).isEqualTo("ru");
    assertThat(field(sentBody(), "response_format")).isEqualTo("json");
  }

  @Test
  void detectModeNormalizesNamesAndCodes() throws Exception {
    stub(200, "{\"text\":\"x\",\"language\":\" \\tRuSsIaN\\n \"}");
    assertThat(client().transcribe(chunk, "auto").language()).isEqualTo("ru");
    server.resetAll();
    stub(200, "{\"text\":\"x\",\"language\":\"de\"}");
    assertThat(client().transcribe(chunk, "auto").language()).isEqualTo("de");
    server.resetAll();
    stub(200, "{\"text\":\"x\",\"language\":\"klingon\"}");
    assertThat(client().transcribe(chunk, "auto").language()).isEqualTo("unknown");
  }

  @Test
  void detectModeWithoutTextDoesNotNeedALanguage() throws Exception {
    for (String body :
        List.of(
            "{\"text\":\"\"}",
            "{\"text\":\" \\t \",\"language\":null}",
            "{\"text\":\"\\u00a0\",\"language\":42}")) {
      server.resetAll();
      stub(200, body);

      WhisperResult result = client().transcribe(chunk, "auto");

      assertThat(result.language()).isNull();
    }
  }

  @Test
  void detectModeWithTextButUnusableLanguageIsABrokenAnswer() {
    for (String language : List.of("", "null", "42", "true", "[]", "{}", "\"\"", "\" \\t\"")) {
      server.resetAll();
      String json =
          language.isEmpty()
              ? "{\"text\":\"Речь.\"}"
              : "{\"text\":\"Речь.\",\"language\":" + language + "}";
      stub(200, json);

      assertThatThrownBy(() -> client().transcribe(chunk, "auto"))
          .isInstanceOfSatisfying(
              WhisperException.class,
              e -> {
                assertThat(e.isConnection()).isFalse();
                assertThat(e.getMessage()).isEqualTo("Whisper answer has no language");
              });
    }
  }

  @Test
  void aNonBreakingSpaceLanguageIsAnUnknownLanguageNotABrokenAnswer() throws Exception {
    stub(200, "{\"text\":\"Речь.\",\"language\":\"\\u00a0\"}");

    assertThat(client().transcribe(chunk, "auto").language()).isEqualTo("unknown");
  }

  @Test
  void onlyAutoAndSupportedCodesAreAccepted() {
    assertThatThrownBy(() -> client().transcribe(chunk, "de"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client().transcribe(chunk, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client().transcribe(chunk, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void trailingSlashInBaseUrlIsDropped() throws Exception {
    stub(200, "{\"text\":\"\"}");

    assertThat(client(server.baseUrl() + "/", Duration.ofSeconds(5)).transcribe(chunk, "en").text())
        .isEmpty();
    server.verify(1, postRequestedFor(urlEqualTo("/inference")));
  }

  @Test
  void nonSuccessStatusIsNotAConnectionError() throws IOException {
    String errorBody = fixture("whisper-inference-bad-request.txt");
    stub(400, errorBody);

    WhisperException e = failure();

    assertThat(e.isConnection()).isFalse();
    assertThat(e.getMessage()).doesNotContain(errorBody.strip());
  }

  @Test
  void longErrorBodyIsAccepted() {
    stub(500, "x".repeat(1000));

    assertThat(failure().isConnection()).isFalse();
  }

  @Test
  void redirectIsNotFollowed() {
    server.stubFor(
        post(urlEqualTo("/inference"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/other")));

    assertThat(failure().isConnection()).isFalse();
    server.verify(1, postRequestedFor(urlEqualTo("/inference")));
  }

  @Test
  void brokenAnswersAreFailures() {
    String[] bodies = {
      "", "{broken", "{}", "{\"text\":null}", "{\"text\":42}", "[]", "{\"text\":[]}"
    };
    for (String body : bodies) {
      stub(200, body);
      assertThat(failure().isConnection()).as(body).isFalse();
    }
    stub(204, "");
    assertThat(failure().isConnection()).isFalse();
  }

  @Test
  void answerTimeoutIsNotAConnectionError() {
    server.stubFor(
        post(urlEqualTo("/inference"))
            .willReturn(aResponse().withStatus(200).withBody(OK).withFixedDelay(2000)));

    WhisperException e =
        catchWhisper(
            () -> client(server.baseUrl(), Duration.ofMillis(200)).transcribe(chunk, "en"));

    assertThat(e.isConnection()).isFalse();
  }

  @Test
  void connectionResetIsNotAConnectionError() {
    server.stubFor(
        post(urlEqualTo("/inference"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThat(failure().isConnection()).isFalse();
  }

  @Test
  void refusedConnectionIsAConnectionError() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    WhisperException e =
        catchWhisper(() ->
            client("http://localhost:" + port, Duration.ofSeconds(5)).transcribe(chunk, "en"));

    assertThat(e.isConnection()).isTrue();
  }

  @Test
  void unresolvedHostIsAConnectionError() {
    WhisperException e =
        catchWhisper(() ->
            client("http://no-such-host.invalid", Duration.ofSeconds(5)).transcribe(chunk, "en"));

    assertThat(e.isConnection()).isTrue();
  }

  @Test
  void missingChunkIsAFailureWithoutARequest() {
    stub(200, OK);

    WhisperException e = catchWhisper(() -> client().transcribe(dir.resolve("missing.wav"), "en"));

    assertThat(e.isConnection()).isFalse();
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void interruptIsRestored() {
    server.stubFor(
        post(urlEqualTo("/inference"))
            .willReturn(aResponse().withStatus(200).withBody(OK).withFixedDelay(2000)));
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> client().transcribe(chunk, "en"))
          .isInstanceOf(WhisperException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void probeGetsTheRootAndAnyStatusIsReachable() {
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(503).withBody("secret-body")));

    assertThat(client().probe(Duration.ofSeconds(2)).reachable()).isTrue();

    server.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/")));
  }

  @Test
  void probeReportsAClosedPort() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    ProbeResult result =
        client("http://localhost:" + port, Duration.ofSeconds(5)).probe(Duration.ofSeconds(2));

    assertThat(result.reachable()).isFalse();
    assertThat(result.reason()).doesNotContain("localhost").doesNotContain(String.valueOf(port));
  }

  @Test
  void probeReportsNoAnswerInTime() {
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/"))
            .willReturn(aResponse().withFixedDelay(1500)));

    ProbeResult result = client().probe(Duration.ofMillis(200));

    assertThat(result).isEqualTo(ProbeResult.down("no answer in time"));
  }

  @Test
  void probeKeepsTheRootWhenTheBaseUrlEndsWithASlash() {
    server.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/"))
            .willReturn(aResponse().withStatus(200)));

    assertThat(
            client(server.baseUrl() + "/", Duration.ofSeconds(5))
                .probe(Duration.ofSeconds(2))
                .reachable())
        .isTrue();
  }
}
