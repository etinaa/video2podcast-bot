package com.video2podcast.summary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.video2podcast.job.ProbeResult;
import com.video2podcast.support.LoopbackWireMock;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaProbeTest {

  private WireMockServer server;

  @BeforeEach
  void setUp() {
    server = LoopbackWireMock.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  @Test
  void getsTheVersionPathAndAnyStatusIsReachable() {
    server.stubFor(
        get(urlEqualTo("/api/version"))
            .willReturn(aResponse().withStatus(500).withBody("secret-body")));

    assertThat(new OllamaProbe(server.baseUrl()).probe(Duration.ofSeconds(2)).reachable()).isTrue();

    server.verify(getRequestedFor(urlEqualTo("/api/version")));
  }

  @Test
  void aTrailingSlashOfTheBaseUrlIsDropped() {
    server.stubFor(get(urlEqualTo("/api/version")).willReturn(aResponse().withStatus(200)));

    assertThat(new OllamaProbe(server.baseUrl() + "/").probe(Duration.ofSeconds(2)).reachable())
        .isTrue();
  }

  @Test
  void reportsAClosedPort() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    ProbeResult result = new OllamaProbe("http://localhost:" + port).probe(Duration.ofSeconds(2));

    assertThat(result.reachable()).isFalse();
    assertThat(result.reason()).doesNotContain("localhost").doesNotContain(String.valueOf(port));
  }

  @Test
  void reportsNoAnswerInTime() {
    server.stubFor(get(urlEqualTo("/api/version")).willReturn(aResponse().withFixedDelay(1500)));

    assertThat(new OllamaProbe(server.baseUrl()).probe(Duration.ofMillis(200)))
        .isEqualTo(ProbeResult.down("no answer in time"));
  }

  @Test
  void theHttpClientHasAFiveSecondConnectTimeoutAndNeverFollowsRedirects() {
    HttpClient http = new OllamaProbe("http://localhost:1").httpClient();

    assertThat(http.connectTimeout()).contains(Duration.ofSeconds(5));
    assertThat(http.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
  }
}
