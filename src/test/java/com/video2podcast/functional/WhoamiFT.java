package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class WhoamiFT extends FunctionalTest {

  @Test
  void privateMessageGetsTheSendersIdInOneHtmlMessage() {
    long userId = newUserId();

    receive(Updates.privateMessage(userId, "/whoami"));

    assertThat(telegramCalls("sendMessage"))
        .singleElement()
        .satisfies(call -> {
          assertThat(call.get("chat_id").asLong()).isEqualTo(userId);
          assertThat(call.get("text").asText())
              .isEqualTo("Your Telegram id: <code>" + userId + "</code>");
          assertThat(call.get("parse_mode").asText()).isEqualTo("HTML");
        });
  }

  @Test
  void groupMessageGetsNoReply() {
    receive(Updates.groupMessage(-100, newUserId(), "/whoami"));

    assertThat(telegramCalls("sendMessage")).isEmpty();
  }

  @Test
  void otherTextGetsNoReply() {
    receive(Updates.privateMessage(newUserId(), "hello"));

    assertThat(telegramCalls("sendMessage")).isEmpty();
  }

  @Test
  void theHarnessReachesTheMockedTelegramApiOnly() {
    receive(Updates.privateMessage(newUserId(), "/whoami"));

    JsonNode call = telegramCalls("sendMessage").get(0);
    assertThat(call.has("chat_id")).isTrue();
    assertThat(WireMocks.WHISPER.getAllServeEvents()).isEmpty();
    assertThat(WireMocks.OLLAMA.getAllServeEvents()).isEmpty();
  }
}
