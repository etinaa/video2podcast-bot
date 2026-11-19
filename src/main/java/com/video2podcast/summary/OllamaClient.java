package com.video2podcast.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.video2podcast.config.AppProperties;
import com.video2podcast.storage.Json;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link LlmClient} for Ollama's {@code POST /api/chat}. */
public final class OllamaClient implements LlmClient {

  private static final Logger LOG = LoggerFactory.getLogger(OllamaClient.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final int LOGGED_BODY_CHARS = 200;
  private static final double TEMPERATURE = 0.2;

  private final HttpClient http;
  private final URI endpoint;
  private final AppProperties.Ollama config;

  public OllamaClient(AppProperties.Ollama config) {
    this(
        config,
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /** For tests: the transport is supplied. */
  OllamaClient(AppProperties.Ollama config, HttpClient http) {
    if (config.model() == null || config.model().isBlank()) {
      throw new IllegalArgumentException("app.ollama.model must not be blank");
    }
    if (config.timeout() == null || config.timeout().isZero() || config.timeout().isNegative()) {
      throw new IllegalArgumentException("app.ollama.timeout must be positive");
    }
    String base = config.baseUrl();
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    this.endpoint = URI.create(trimmed + "/api/chat");
    this.config = config;
    this.http = http;
  }

  /** For tests: the transport the requests go through. */
  HttpClient httpClient() {
    return http;
  }

  @Override
  public LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException {
    HttpResponse<String> response = send(system, user, maxOutputTokens);
    int status = response.statusCode();
    if (status < 200 || status > 299) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Ollama answered {}: {}", status, abbreviate(response.body()));
      }
      throw new LlmException("Ollama answered status " + status, LlmException.Reason.OTHER, null);
    }
    return replyOf(response.body());
  }

  private HttpResponse<String> send(String system, String user, int maxOutputTokens)
      throws LlmException {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(config.timeout())
              .header("Content-Type", "application/json")
              .POST(BodyPublishers.ofString(body(system, user, maxOutputTokens)))
              .build();
      return http.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpConnectTimeoutException | ConnectException | UnresolvedAddressException e) {
      throw new LlmException("No connection to Ollama", LlmException.Reason.CONNECTION, e);
    } catch (HttpTimeoutException e) {
      throw new LlmException("Ollama did not answer in time", LlmException.Reason.TIMEOUT, e);
    } catch (IOException e) {
      throw new LlmException("Ollama request failed", LlmException.Reason.OTHER, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmException("Ollama request interrupted", LlmException.Reason.OTHER, e);
    }
  }

  private String body(String system, String user, int maxOutputTokens) {
    ObjectNode root = Json.MAPPER.createObjectNode();
    root.put("model", config.model());
    root.put("stream", false);
    root.put("think", false);
    root.put("keep_alive", "10m");
    ObjectNode options = root.putObject("options");
    options.put("num_ctx", config.numCtx());
    options.put("num_predict", maxOutputTokens);
    options.put("temperature", TEMPERATURE);
    ArrayNode messages = root.putArray("messages");
    messages.addObject().put("role", "system").put("content", system);
    messages.addObject().put("role", "user").put("content", user);
    return root.toString();
  }

  private static LlmReply replyOf(String body) throws LlmException {
    if (body == null || body.isBlank()) {
      throw other("Ollama answered with an empty body", null);
    }
    JsonNode root;
    try {
      root = Json.MAPPER.readTree(body);
    } catch (IOException e) {
      throw other("Ollama answer is not JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw other("Ollama answer is not a JSON object", null);
    }
    JsonNode content = root.path("message").path("content");
    if (!content.isTextual() || content.textValue().isBlank()) {
      throw other("Ollama answer has no content", null);
    }
    JsonNode count = root.path("prompt_eval_count");
    if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 1) {
      throw other("Ollama answer has no valid prompt_eval_count", null);
    }
    if ("length".equals(root.path("done_reason").asText()) && LOG.isWarnEnabled()) {
      LOG.warn("Ollama stopped at the output limit");
    }
    return new LlmReply(content.textValue().strip(), count.longValue());
  }

  private static LlmException other(String message, Throwable cause) {
    return new LlmException(message, LlmException.Reason.OTHER, cause);
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= LOGGED_BODY_CHARS ? body : body.substring(0, LOGGED_BODY_CHARS);
  }
}
