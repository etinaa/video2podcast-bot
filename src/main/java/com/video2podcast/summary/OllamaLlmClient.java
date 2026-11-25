package com.video2podcast.summary;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** {@link LlmClient} on Spring AI's Ollama chat model. */
public final class OllamaLlmClient implements LlmClient {

  private static final Logger LOG = LoggerFactory.getLogger(OllamaLlmClient.class);
  private static final int LOGGED_MESSAGE_CHARS = 200;
  private static final double TEMPERATURE = 0.2;
  private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();
  private static final String KEEP_ALIVE = "10m";

  private final ChatModel chat;
  private final String model;
  private final int numCtx;
  private final Duration timeout;

  public OllamaLlmClient(ChatModel chat, String model, int numCtx, Duration timeout) {
    this.chat = chat;
    this.model = model;
    this.numCtx = numCtx;
    this.timeout = timeout;
  }

  @Override
  public LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException {
    OllamaChatOptions options =
        OllamaChatOptions.builder()
            .model(model)
            .numCtx(numCtx)
            .numPredict(maxOutputTokens)
            .temperature(TEMPERATURE)
            .keepAlive(KEEP_ALIVE)
            .disableThinking()
            .build();
    Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options);
    return replyOf(callWithin(prompt));
  }

  /**
   * The transport's read timeout only covers the wait for the response headers, so the whole call
   * gets one deadline here; an answer that stalls in the body is cut off by the interrupt.
   */
  private ChatResponse callWithin(Prompt prompt) throws LlmException {
    Future<ChatResponse> call = CALLS.submit(() -> chat.call(prompt));
    try {
      return call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      call.cancel(true);
      throw new LlmException("Ollama did not answer in time", LlmException.Reason.TIMEOUT, e);
    } catch (ExecutionException e) {
      throw failure(e);
    } catch (InterruptedException e) {
      call.cancel(true);
      Thread.currentThread().interrupt();
      throw other("Ollama request interrupted", e);
    }
  }

  private static LlmReply replyOf(ChatResponse response) throws LlmException {
    Generation result = response == null ? null : response.getResult();
    if (result == null) {
      throw other("Ollama answered with no result", null);
    }
    String content = result.getOutput().getText();
    if (content == null || content.isBlank()) {
      throw other("Ollama answer has no content", null);
    }
    Integer promptTokens = response.getMetadata().getUsage().getPromptTokens();
    if (promptTokens == null || promptTokens < 1) {
      throw other("Ollama answer has no valid prompt_eval_count", null);
    }
    if ("length".equals(result.getMetadata().getFinishReason()) && LOG.isWarnEnabled()) {
      LOG.warn("Ollama stopped at the output limit");
    }
    return new LlmReply(content.strip(), promptTokens);
  }

  private static LlmException failure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpConnectTimeoutException
          || t instanceof ConnectException
          || t instanceof UnresolvedAddressException) {
        return new LlmException("No connection to Ollama", LlmException.Reason.CONNECTION, e);
      }
      if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
        return new LlmException("Ollama did not answer in time", LlmException.Reason.TIMEOUT, e);
      }
    }
    if (LOG.isWarnEnabled()) {
      LOG.warn("Ollama request failed: {}", abbreviate(e.getMessage()));
    }
    return other("Ollama request failed", e);
  }

  private static LlmException other(String message, Throwable cause) {
    return new LlmException(message, LlmException.Reason.OTHER, cause);
  }

  private static String abbreviate(String message) {
    if (message == null) {
      return "";
    }
    return message.length() <= LOGGED_MESSAGE_CHARS
        ? message
        : message.substring(0, LOGGED_MESSAGE_CHARS);
  }
}
