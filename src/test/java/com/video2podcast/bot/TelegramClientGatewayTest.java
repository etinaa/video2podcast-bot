package com.video2podcast.bot;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;

class TelegramClientGatewayTest {

  private static final String TOKEN = "123:abc";
  private static final String MESSAGE_OK =
      """
      {"ok":true,"result":{"message_id":77,"date":1700000000,"chat":{"id":5,"type":"private"}}}\
      """;

  @RegisterExtension
  static WireMockExtension telegram =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tempDir;

  private TelegramClientGateway gateway;

  @BeforeEach
  void setUp() {
    TelegramUrl url =
        TelegramUrl.builder().schema("http").host("localhost").port(telegram.getPort()).build();
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

    gateway.sendAudio(5L, file, "Episode", "Channel");

    telegram.verify(
        postRequestedFor(urlPathEqualTo(path("sendAudio")))
            .withRequestBody(containing("filename=\"audio.m4a\""))
            .withRequestBody(containing("Episode"))
            .withRequestBody(containing("Channel")));
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
