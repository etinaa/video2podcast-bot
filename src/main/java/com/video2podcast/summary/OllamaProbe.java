package com.video2podcast.summary;

import com.video2podcast.job.HttpProbe;
import com.video2podcast.job.ProbeResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** {@code GET /api/version} on Ollama: any HTTP answer means the server is reachable. */
public final class OllamaProbe {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http;
  private final URI version;

  public OllamaProbe(String baseUrl) {
    this(
        baseUrl,
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /** For tests: the transport is supplied. */
  OllamaProbe(String baseUrl, HttpClient http) {
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.version = URI.create(trimmed + "/api/version");
    this.http = http;
  }

  /** For tests: the transport the probe goes through. */
  HttpClient httpClient() {
    return http;
  }

  public ProbeResult probe(Duration timeout) {
    return HttpProbe.get(http, version, timeout);
  }
}
