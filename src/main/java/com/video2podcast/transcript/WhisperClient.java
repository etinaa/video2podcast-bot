package com.video2podcast.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.config.AppProperties;
import com.video2podcast.job.HttpProbe;
import com.video2podcast.job.ProbeResult;
import com.video2podcast.storage.Json;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One call to the whisper.cpp server: sends a WAV chunk and returns the recognized text. */
public class WhisperClient {

  private static final Logger LOG = LoggerFactory.getLogger(WhisperClient.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final int LOGGED_BODY_CHARS = 200;

  private final HttpClient http;
  private final URI endpoint;
  private final URI root;
  private final Duration timeout;

  public WhisperClient(AppProperties.Whisper config) {
    this(
        config,
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /** For tests: the transport is supplied. */
  WhisperClient(AppProperties.Whisper config, HttpClient http) {
    String base = config.baseUrl();
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.endpoint = URI.create(trimmed + "/inference");
    this.root = URI.create(trimmed + "/");
    this.timeout = config.timeout();
    this.http = http;
  }

  /** For tests: the transport the requests go through. */
  HttpClient httpClient() {
    return http;
  }

  /** {@code GET /}: any HTTP answer means the server is reachable. */
  public ProbeResult probe(Duration timeout) {
    return HttpProbe.get(http, root, timeout);
  }

  /** Returns the raw "text" field of the answer, not normalized. */
  public String transcribe(Path chunk) throws WhisperException {
    String boundary = "----v2p" + UUID.randomUUID().toString().replace("-", "");
    HttpResponse<String> response = send(chunk, boundary);
    int status = response.statusCode();
    if (status < 200 || status > 299) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Whisper answered {}: {}", status, abbreviate(response.body()));
      }
      throw new WhisperException("Whisper answered status " + status, false, null);
    }
    return textOf(response.body());
  }

  private HttpResponse<String> send(Path chunk, String boundary) throws WhisperException {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(body(chunk, boundary))
              .build();
      return http.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpConnectTimeoutException | ConnectException | UnresolvedAddressException e) {
      throw new WhisperException("No connection to Whisper", true, e);
    } catch (HttpTimeoutException e) {
      throw new WhisperException("Whisper did not answer in time", false, e);
    } catch (IOException e) {
      throw new WhisperException("Whisper request failed", false, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WhisperException("Whisper request interrupted", false, e);
    }
  }

  private static BodyPublisher body(Path chunk, String boundary) throws FileNotFoundException {
    String head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + chunk.getFileName()
            + "\"\r\nContent-Type: audio/wav\r\n\r\n";
    String tail =
        field(boundary, "response_format", "json")
            + field(boundary, "language", "en")
            + field(boundary, "temperature", "0.0")
            + "--"
            + boundary
            + "--\r\n";
    return BodyPublishers.concat(
        BodyPublishers.ofString(head, StandardCharsets.UTF_8),
        BodyPublishers.ofFile(chunk),
        BodyPublishers.ofString("\r\n" + tail, StandardCharsets.UTF_8));
  }

  private static String field(String boundary, String name, String value) {
    return "--"
        + boundary
        + "\r\nContent-Disposition: form-data; name=\""
        + name
        + "\"\r\n\r\n"
        + value
        + "\r\n";
  }

  private static String textOf(String body) throws WhisperException {
    if (body == null || body.isBlank()) {
      throw new WhisperException("Whisper answered with an empty body", false, null);
    }
    JsonNode text;
    try {
      text = Json.MAPPER.readTree(body).path("text");
    } catch (IOException e) {
      throw new WhisperException("Whisper answer is not JSON", false, e);
    }
    if (!text.isTextual()) {
      throw new WhisperException("Whisper answer has no text", false, null);
    }
    return text.textValue();
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= LOGGED_BODY_CHARS ? body : body.substring(0, LOGGED_BODY_CHARS);
  }
}
