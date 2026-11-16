package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.video2podcast.access.AccessService;
import com.video2podcast.bot.TelegramBot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Base class for functional tests: the whole application runs in one JVM against WireMock servers
 * for Telegram, Whisper and Ollama. Updates are handed straight to the bot (long polling is
 * switched off), and the tests assert on the HTTP requests WireMock received.
 *
 * <p>The application context is shared between test classes, so state such as the allowlist
 * survives from test to test. Use {@link #newUserId()} for every user a test needs.
 */
@SpringBootTest(
    properties = {
      "app.telegram.enabled=true",
      "app.telegram.bot-token=" + FunctionalTest.TOKEN,
      "app.telegram.admin-user-id=" + FunctionalTest.OWNER_ID,
      "spring.autoconfigure.exclude="
          + "org.telegram.telegrambots.longpolling.starter.TelegramBotStarterConfiguration"
    })
abstract class FunctionalTest {

  static final String TOKEN = "123:ft-token";
  static final long OWNER_ID = 1;

  /** The id WireMock gives every message the bot sends, so the bot stores it with the request. */
  static final int BOT_MESSAGE_ID = 77;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicLong USER_IDS = new AtomicLong(1000);
  private static final Path WORK_ROOT = createWorkRoot();

  static final String MESSAGE_OK =
      """
      {"ok":true,"result":{"message_id":77,"date":1700000000,"chat":{"id":5,"type":"private"}}}\
      """;

  @Autowired TelegramBot bot;
  @Autowired AccessService access;

  @DynamicPropertySource
  static void endpoints(DynamicPropertyRegistry registry) {
    registry.add("app.telegram.api-url", () -> WireMocks.url(WireMocks.TELEGRAM));
    registry.add("app.whisper.base-url", () -> WireMocks.url(WireMocks.WHISPER));
    registry.add("app.ollama.base-url", () -> WireMocks.url(WireMocks.OLLAMA));
    registry.add("app.access.file", () -> WORK_ROOT.resolve("allowed-users.json").toString());
    registry.add("app.jobs.work-dir", () -> WORK_ROOT.resolve("work").toString());
  }

  @BeforeEach
  void resetMocks() {
    WireMocks.resetAll();
    WireMocks.TELEGRAM.stubFor(
        post(urlPathEqualTo(telegramPath("sendMessage")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));
    WireMocks.TELEGRAM.stubFor(
        post(urlPathEqualTo(telegramPath("editMessageText")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));
    WireMocks.TELEGRAM.stubFor(
        post(urlPathEqualTo(telegramPath("answerCallbackQuery")))
            .willReturn(
                aResponse()
                    .withBody("{\"ok\":true,\"result\":true}")
                    .withHeader("Content-Type", "application/json")));
  }

  static long newUserId() {
    return USER_IDS.getAndIncrement();
  }

  /** The messages the bot sent to one chat, oldest first. */
  static List<JsonNode> sentTo(long chatId) {
    return telegramCalls("sendMessage").stream()
        .filter(call -> call.get("chat_id").asLong() == chatId)
        .toList();
  }

  void receive(Update update) {
    bot.consume(update);
  }

  /** The Telegram client lower-cases method names in the URL, and Telegram accepts both. */
  static String telegramPath(String method) {
    return "/bot" + TOKEN + "/" + method.toLowerCase(Locale.ROOT);
  }

  /** The JSON bodies of every request the bot made to the given Telegram method. */
  static List<JsonNode> telegramCalls(String method) {
    List<LoggedRequest> requests =
        WireMocks.TELEGRAM.findAll(postRequestedFor(urlPathEqualTo(telegramPath(method))));
    return requests.stream().map(request -> json(request.getBodyAsString())).toList();
  }

  private static JsonNode json(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Not JSON: " + body, e);
    }
  }

  private static Path createWorkRoot() {
    try {
      Path target = Files.createDirectories(Path.of("target", "functional"));
      return Files.createTempDirectory(target, "run-");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create the functional test work directory", e);
    }
  }
}
