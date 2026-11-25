package com.video2podcast.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import com.video2podcast.config.AppProperties;
import com.video2podcast.job.HttpProbe;
import com.video2podcast.job.Language;
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
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One call to the whisper.cpp server: sends a WAV chunk, returns the text and maybe the language.
 */
public class WhisperClient {

  private static final Logger LOG = LoggerFactory.getLogger(WhisperClient.class);

  /** The value of the {@code language} field that asks Whisper to detect the language. */
  public static final String AUTO = "auto";

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

  /**
   * Sends one chunk. {@code language} is {@value #AUTO} (detect mode: asks for {@code verbose_json}
   * and reads the language) or a code such as {@code en} or {@code ru} (pinned mode).
   *
   * @throws IllegalArgumentException if {@code language} is blank or not a letters-only value
   * @throws WhisperException when the call fails, the answer is not usable, or in detect mode the
   *     answer has text but no usable language
   */
  public WhisperResult transcribe(Path chunk, String language) throws WhisperException {
    Objects.requireNonNull(language, "language");
    boolean detect = AUTO.equals(language);
    if (!detect && Language.fromCode(language).isEmpty()) {
      throw new IllegalArgumentException("unsupported language: " + language);
    }
    String boundary = "----v2p" + UUID.randomUUID().toString().replace("-", "");
    HttpResponse<String> response = send(chunk, boundary, language, detect);
    int status = response.statusCode();
    if (status < 200 || status > 299) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Whisper answered {}: {}", status, abbreviate(response.body()));
      }
      throw new WhisperException("Whisper answered status " + status, false, null);
    }
    return resultOf(response.body(), detect);
  }

  private HttpResponse<String> send(Path chunk, String boundary, String language, boolean detect)
      throws WhisperException {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(timeout)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(body(chunk, boundary, language, detect))
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

  private static BodyPublisher body(Path chunk, String boundary, String language, boolean detect)
      throws FileNotFoundException {
    String head =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + chunk.getFileName()
            + "\"\r\nContent-Type: audio/wav\r\n\r\n";
    String tail =
        field(boundary, "response_format", detect ? "verbose_json" : "json")
            + field(boundary, "language", language)
            + field(boundary, "translate", "false")
            + field(boundary, "detect_language", "false")
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

  private static WhisperResult resultOf(String body, boolean detect) throws WhisperException {
    if (body == null || body.isBlank()) {
      throw new WhisperException("Whisper answered with an empty body", false, null);
    }
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(body);
    } catch (IOException e) {
      throw new WhisperException("Whisper answer is not JSON", false, e);
    }
    JsonNode text = root.path("text");
    if (!text.isTextual()) {
      throw new WhisperException("Whisper answer has no text", false, null);
    }
    if (!detect || TranscriptMerger.normalize(text.textValue()).isEmpty()) {
      return new WhisperResult(text.textValue(), null);
    }
    JsonNode language = root.path("language");
    if (!language.isTextual() || language.textValue().isBlank()) {
      throw new WhisperException("Whisper answer has no language", false, null);
    }
    return new WhisperResult(text.textValue(), WhisperLanguages.toCode(language.textValue()));
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= LOGGED_BODY_CHARS ? body : body.substring(0, LOGGED_BODY_CHARS);
  }
}
