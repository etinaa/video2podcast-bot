package com.video2podcast.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.video2podcast.storage.Json;
import com.video2podcast.summary.LlmClient;
import com.video2podcast.summary.LlmReply;
import com.video2podcast.support.LoopbackWireMock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The OLLAMA_* settings reach Spring AI, and the Spring AI starter adds nothing else. */
@SpringBootTest(
    properties = {
      "app.telegram.bot-token=test-token",
      "app.telegram.admin-user-id=42",
      "OLLAMA_MODEL=qwen3:14b",
      "OLLAMA_NUM_CTX=4096"
    })
class SpringAiWiringTest {

  private static final WireMockServer OLLAMA = LoopbackWireMock.start();

  @Autowired ApplicationContext context;
  @Autowired LlmClient llm;

  @DynamicPropertySource
  static void ollama(DynamicPropertyRegistry registry) {
    registry.add("OLLAMA_BASE_URL", OLLAMA::baseUrl);
  }

  @AfterAll
  static void stop() {
    OLLAMA.stop();
  }

  @Test
  void theSettingsOfTheEnvironmentReachTheRequest() throws Exception {
    OLLAMA.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},"
                            + "\"prompt_eval_count\":7}")));

    LlmReply reply = llm.complete("s", "u", 50);

    assertThat(reply.promptTokens()).isEqualTo(7);
    JsonNode body =
        Json.MAPPER.readTree(OLLAMA.getAllServeEvents().get(0).getRequest().getBodyAsString());
    assertThat(body.path("model").asText()).isEqualTo("qwen3:14b");
    assertThat(body.path("options").path("num_ctx").asInt()).isEqualTo(4096);
    assertThat(body.path("options").path("num_predict").asInt()).isEqualTo(50);
    assertThat(body.path("think").asBoolean(true)).isFalse();
  }

  @Test
  void theModelIsNeverPulled() throws Exception {
    OLLAMA.stubFor(
        post(urlEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"- a\"},"
                            + "\"prompt_eval_count\":7}")));

    llm.complete("s", "u", 50);

    assertThat(OLLAMA.getAllServeEvents())
        .extracting(e -> e.getRequest().getUrl())
        .doesNotContain("/api/pull", "/api/tags", "/api/show");
  }

  @Test
  void onlyOurOllamaApiAndChatModelExistFromSpringAi() {
    List<String> aiBeans =
        Arrays.stream(context.getBeanDefinitionNames())
            .filter(n -> {
              Class<?> type = context.getType(n);
              return type != null && type.getName().startsWith("org.springframework.ai.");
            })
            .sorted()
            .toList();
    assertThat(aiBeans).allMatch(n -> n.equals("ollamaApi") || n.equals("ollamaChatModel"));
    assertThat(aiBeans).containsExactly("ollamaApi", "ollamaChatModel");
  }

  @Test
  void theContextIsNotAWebContext() {
    assertThat(context.getClass().getName()).doesNotContain("Web");
  }

  @Test
  void noOtherSpringAiBeanExistsAndOurOwnBeansAreKept() {
    assertThat(context.getBeanNamesForType(LlmClient.class)).containsExactly("llmClient");
  }
}
