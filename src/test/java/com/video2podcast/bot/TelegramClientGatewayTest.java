package com.video2podcast.bot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.video2podcast.support.LoopbackWireMock;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;

class TelegramClientGatewayTest {

  private static final String TOKEN = "123:abc";
  private static final String MESSAGE_OK =
      """
      {"ok":true,"result":{"message_id":77,"date":1700000000,"chat":{"id":5,"type":"private"}}}\
      """;

  static WireMockServer telegram;

  @TempDir Path tempDir;

  private TelegramClientGateway gateway;

  @BeforeAll
  static void startTelegram() {
    telegram = LoopbackWireMock.start();
  }

  @AfterAll
  static void stopTelegram() {
    telegram.stop();
  }

  @BeforeEach
  void setUp() {
    telegram.resetAll();
    URI base = URI.create(telegram.baseUrl());
    TelegramUrl url =
        TelegramUrl.builder()
            .schema(base.getScheme())
            .host(base.getHost())
            .port(base.getPort())
            .build();
    gateway = new TelegramClientGateway(new OkHttpTelegramClient(TOKEN, url));
  }

  private static String path(String method) {
    // The Telegram client lower-cases the method name in the URL, and Telegram accepts both.
    return "/bot" + TOKEN + "/" + method.toLowerCase(Locale.ROOT);
  }

  @Test
  void sendMessageSendsHtmlWithoutLinkPreviewAndReturnsTheMessageId() {
    telegram.stubFor(
        post(urlPathEqualTo(path("sendMessage")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    int id = gateway.sendMessage(5L, "<b>Hi</b>", List.of());

    assertThat(id).isEqualTo(77);
    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendMessage")))
            .withRequestBody(matchingJsonPath("$.chat_id", equalTo("5")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("<b>Hi</b>")))
            .withRequestBody(matchingJsonPath("$.parse_mode", equalTo("HTML")))
            .withRequestBody(
                matchingJsonPath("$.link_preview_options.is_disabled", equalTo("true"))));
  }

  @Test
  void sendMessageAttachesButtonsAsOneInlineRow() {
    telegram.stubFor(
        post(urlPathEqualTo(path("sendMessage")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.sendMessage(
        5L,
        "x",
        List.of(
            new InlineButton("Audio", "audio:1"), new InlineButton("Transcript", "transcript:1")));

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendMessage")))
            .withRequestBody(
                matchingJsonPath(
                    "$.reply_markup",
                    equalToJson(
                        """
                        {"inline_keyboard":[[
                          {"text":"Audio","callback_data":"audio:1"},
                          {"text":"Transcript","callback_data":"transcript:1"}]]}\
                        """,
                        true,
                        true))));
  }

  @Test
  void editMessageReplacesTextAndButtons() {
    telegram.stubFor(
        post(urlPathEqualTo(path("editMessageText")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.editMessage(5L, 9, "done", List.of(new InlineButton("Sent", "noop")));

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("editMessageText")))
            .withRequestBody(matchingJsonPath("$.message_id", equalTo("9")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("done")))
            .withRequestBody(
                matchingJsonPath("$.reply_markup.inline_keyboard[0][0].text", equalTo("Sent"))));
  }

  @Test
  void editButtonsReplacesOnlyTheKeyboard() {
    telegram.stubFor(
        post(urlPathEqualTo(path("editMessageReplyMarkup")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.editButtons(5L, 9, List.of(new InlineButton("Sent", "noop")));

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("editMessageReplyMarkup")))
            .withRequestBody(matchingJsonPath("$.chat_id", equalTo("5")))
            .withRequestBody(matchingJsonPath("$.message_id", equalTo("9")))
            .withRequestBody(
                matchingJsonPath("$.reply_markup.inline_keyboard[0][0].text", equalTo("Sent"))));
  }

  @Test
  void editButtonsWithAnEmptyListRemovesTheKeyboard() {
    telegram.stubFor(
        post(urlPathEqualTo(path("editMessageReplyMarkup")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.editButtons(5L, 9, List.of());

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("editMessageReplyMarkup")))
            .withRequestBody(
                matchingJsonPath("$.reply_markup.inline_keyboard", equalToJson("[]"))));
  }

  @Test
  void editButtonsReportsATelegramRefusal() {
    telegram.stubFor(
        post(urlPathEqualTo(path("editMessageReplyMarkup")))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("{\"ok\":false,\"error_code\":400,\"description\":\"bad\"}")
                    .withHeader("Content-Type", "application/json")));

    assertThatThrownBy(() -> gateway.editButtons(5L, 9, List.of()))
        .isInstanceOf(TelegramGatewayException.class);
  }

  @Test
  void sendDocumentUploadsTheFileUnderTheGivenName() throws Exception {
    Path file = Files.writeString(tempDir.resolve("source.tmp"), "transcript text");
    telegram.stubFor(
        post(urlPathEqualTo(path("sendDocument")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.sendDocument(5L, file, "My video.txt", "caption");

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendDocument")))
            .withRequestBody(containing("filename=\"My video.txt\""))
            .withRequestBody(containing("transcript text")));
  }

  @Test
  void sendAudioUploadsTheFileWithTitleAndPerformer() throws Exception {
    Path file = Files.writeString(tempDir.resolve("audio.m4a"), "audio bytes");
    telegram.stubFor(
        post(urlPathEqualTo(path("sendAudio")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.sendAudio(5L, file, "Episode.m4a", "Episode", "Channel", "Converted &amp; done", 114);

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendAudio")))
            .withRequestBody(containing("filename=\"Episode.m4a\""))
            .withRequestBody(containing("Converted &amp; done"))
            .withRequestBody(containing("HTML"))
            .withRequestBody(containing("Episode"))
            .withRequestBody(containing("Channel"))
            .withRequestBody(matching("(?s).*name=\"duration\"[^\\n]*\\n[^\\n]*\\n\\s*114\\s.*")));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -5})
  void sendAudioLeavesOutTheDurationWhenItIsNotPositive(long seconds) throws Exception {
    Path file = Files.writeString(tempDir.resolve("audio.m4a"), "audio bytes");
    telegram.stubFor(
        post(urlPathEqualTo(path("sendAudio")))
            .willReturn(
                aResponse().withBody(MESSAGE_OK).withHeader("Content-Type", "application/json")));

    gateway.sendAudio(5L, file, "Episode.m4a", "Episode", "Channel", null, seconds);

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendAudio")))
            .withRequestBody(containing("filename=\"Episode.m4a\""))
            .withRequestBody(notMatching("(?s).*name=\"duration\".*")));
  }

  @Test
  void answerCallbackSendsTheQueryIdAndText() {
    telegram.stubFor(
        post(urlPathEqualTo(path("answerCallbackQuery")))
            .willReturn(
                aResponse()
                    .withBody("{\"ok\":true,\"result\":true}")
                    .withHeader("Content-Type", "application/json")));

    gateway.answerCallback("cb-1", "Expired");

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("answerCallbackQuery")))
            .withRequestBody(matchingJsonPath("$.callback_query_id", equalTo("cb-1")))
            .withRequestBody(matchingJsonPath("$.text", equalTo("Expired"))));
  }

  @Test
  void wrapsTelegramErrorsInAGatewayException() {
    telegram.stubFor(
        post(urlPathEqualTo(path("sendMessage")))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}")));

    assertThatThrownBy(() -> gateway.sendMessage(5L, "x", List.of()))
        .isInstanceOf(TelegramGatewayException.class)
        .hasMessageContaining("chat not found");
  }
}
