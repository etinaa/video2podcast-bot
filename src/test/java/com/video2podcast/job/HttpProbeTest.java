package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpProbeTest {

  private static final URI URI_ = URI.create("http://svc.example:1/x");
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient http = mock(HttpClient.class);

  @AfterEach
  void clearFlag() {
    Thread.interrupted();
  }

  private ProbeResult probeThrowing(Throwable t) throws Exception {
    org.mockito.Mockito.doThrow(t).when(http).send(any(), any());
    return HttpProbe.get(http, URI_, TIMEOUT);
  }

  @Test
  void anyStatusMeansReachableAndTheRequestIsABodylessGetWithTheTimeout() throws Exception {
    for (int status : new int[] {200, 404, 503}) {
      HttpResponse<Object> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(status);
      when(http.send(any(), any())).thenReturn(response);

      assertThat(HttpProbe.get(http, URI_, TIMEOUT)).isEqualTo(ProbeResult.up());
    }
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(http, org.mockito.Mockito.atLeastOnce())
        .send(request.capture(), any());
    HttpRequest sent = request.getValue();
    assertThat(sent.method()).isEqualTo("GET");
    assertThat(sent.uri()).isEqualTo(URI_);
    assertThat(sent.timeout()).contains(TIMEOUT);
    assertThat(sent.bodyPublisher()).isEmpty();
  }

  @Test
  void reasonsAreFixedPhrases() throws Exception {
    assertThat(probeThrowing(new ConnectException("http://secret")).reason())
        .isEqualTo("connection refused");
    assertThat(probeThrowing(new UnresolvedAddressException()).reason())
        .isEqualTo("host not found");
    ConnectException dns = new ConnectException();
    dns.initCause(new UnresolvedAddressException());
    assertThat(probeThrowing(dns).reason()).isEqualTo("host not found");
    assertThat(probeThrowing(new HttpTimeoutException("x")).reason())
        .isEqualTo("no answer in time");
    assertThat(probeThrowing(new HttpConnectTimeoutException("x")).reason())
        .isEqualTo("no answer in time");
    ProbeResult other = probeThrowing(new IOException("/secret/path"));
    assertThat(other.reachable()).isFalse();
    assertThat(other.reason()).isEqualTo("i/o error (IOException)");
  }

  @Test
  void anInterruptionRestoresTheFlag() throws Exception {
    ProbeResult result = probeThrowing(new InterruptedException());

    assertThat(result).isEqualTo(ProbeResult.down("interrupted"));
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }
}
