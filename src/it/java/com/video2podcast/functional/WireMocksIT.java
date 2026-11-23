package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;

class WireMocksIT {

  @Test
  void occupiedLoopbackPortIsReplacedAndRequestsReachTheMock() throws Exception {
    try (ServerSocket foreign = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      WireMockServer server = WireMocks.scenarioServer(foreign.getLocalPort(), new ProbeResponse());
      try (HttpClient client = HttpClient.newHttpClient()) {
        assertThat(server.port()).isNotEqualTo(foreign.getLocalPort());
        assertThat(server.getOptions().bindAddress()).isEqualTo("127.0.0.1");
        server.stubFor(get(urlEqualTo("/probe")).willReturn(ok("untransformed")));

        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(WireMocks.url(server) + "/probe")).build(),
                BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("our extension");
        assertThat(InetAddress.getByName(response.uri().getHost()).isLoopbackAddress()).isTrue();
        assertThat(server.getAllServeEvents()).hasSize(1);
        assertThat(foreign.isClosed()).isFalse();
      } finally {
        server.stop();
      }
    }
  }

  @Test
  void sharedAndScenarioServersBindOnlyToIpv4Loopback() {
    WireMockServer scenario = WireMocks.scenarioServer();
    try {
      for (WireMockServer server :
          new WireMockServer[] {
            WireMocks.TELEGRAM, WireMocks.WHISPER, WireMocks.OLLAMA, scenario
          }) {
        assertThat(server.getOptions().bindAddress()).isEqualTo("127.0.0.1");
        assertThat(server.isRunning()).isTrue();
      }
    } finally {
      scenario.stop();
    }
  }

  private static final class ProbeResponse implements ResponseDefinitionTransformerV2 {
    @Override
    public String getName() {
      return "loopback-probe";
    }

    @Override
    public boolean applyGlobally() {
      return true;
    }

    @Override
    public ResponseDefinition transform(ServeEvent event) {
      return ok("our extension").build();
    }
  }
}
