package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.access.PendingRequest;
import org.junit.jupiter.api.Test;

class AccessGateIT extends FunctionalTest {

  @Test
  void unknownUserGetsOneRefusalNoMatterHowManyMessagesTheySend() {
    long userId = newUserId();

    receive(Updates.privateMessage(userId, "hello"));
    receive(Updates.privateMessage(userId, "https://example.com/video"));
    receive(Updates.privateMessage(userId, "anyone there?"));

    assertThat(telegramCalls("sendMessage"))
        .singleElement()
        .satisfies(call -> {
          assertThat(call.get("chat_id").asLong()).isEqualTo(userId);
          assertThat(call.get("text").asText())
              .isEqualTo(
                  "No access. Send /start to request it. Your id: <code>" + userId + "</code>");
        });
  }

  @Test
  void startFromAnUnknownUserFilesOneRequestAndRepeatsAreAnsweredOnce() {
    long userId = newUserId();

    receive(Updates.privateMessage(userId, "/start"));
    receive(Updates.privateMessage(userId, "/start"));
    receive(Updates.privateMessage(userId, "/start"));

    assertThat(sentTo(userId))
        .extracting(call -> call.get("text").asText())
        .containsExactly(
            "Access requested. The owner will review it.",
            "Your request is already waiting for the owner.");
    assertThat(sentTo(OWNER_ID))
        .filteredOn(call -> call.get("text").asText().contains("id <code>" + userId + "</code>"))
        .hasSize(1);
    assertThat(access.pending()).extracting(PendingRequest::id).containsOnlyOnce(userId);
    assertThat(access.isAllowed(userId)).isFalse();
  }

  @Test
  void allowedUserGetsHelpOnStartAndRejectsNonLinkText() {
    long userId = newUserId();
    access.allow(userId, "ann", OWNER_ID);

    receive(Updates.privateMessage(userId, "/start"));
    receive(Updates.privateMessage(userId, "hello"));

    assertThat(sentTo(userId))
        .extracting(call -> call.path("text").asText())
        .satisfiesExactly(
            help -> assertThat(help).contains("Send me a video link"),
            failure -> assertThat(failure).isEqualTo("This link is not a supported video."));
    assertThat(access.pending()).extracting(PendingRequest::id).doesNotContain(userId);
  }

  @Test
  void ownerIsAlwaysLetIn() {
    receive(Updates.privateMessage(OWNER_ID, "/start"));

    assertThat(telegramCalls("sendMessage"))
        .singleElement()
        .satisfies(call -> assertThat(call.get("text").asText()).contains("Send me a video link"));
  }

  @Test
  void whoamiWorksForEveryone() {
    long stranger = newUserId();
    long allowed = newUserId();
    access.allow(allowed, "ann", OWNER_ID);

    receive(Updates.privateMessage(stranger, "/whoami"));
    receive(Updates.privateMessage(allowed, "/whoami"));
    receive(Updates.privateMessage(OWNER_ID, "/whoami"));

    assertThat(telegramCalls("sendMessage"))
        .extracting(call -> call.get("text").asText())
        .containsExactly(
            "Your Telegram id: <code>" + stranger + "</code>",
            "Your Telegram id: <code>" + allowed + "</code>",
            "Your Telegram id: <code>" + OWNER_ID + "</code>");
  }

  @Test
  void groupMessagesGetNoReplyAndCreateNoRequest() {
    long userId = newUserId();

    receive(Updates.groupMessage(-500, userId, "/start"));
    receive(Updates.groupMessage(-500, userId, "hello"));

    assertThat(telegramCalls("sendMessage")).isEmpty();
    assertThat(access.pending()).extracting(PendingRequest::id).doesNotContain(userId);
  }

  @Test
  void buttonTapFromAnUnknownUserIsRefusedAndAnAllowedUserPasses() {
    long stranger = newUserId();
    long allowed = newUserId();
    access.allow(allowed, "ann", OWNER_ID);

    receive(Updates.callback(stranger, "cb-stranger", "audio:job1", 5));
    receive(Updates.callback(allowed, "cb-allowed", "audio:job1", 5));

    assertThat(telegramCalls("answerCallbackQuery"))
        .satisfiesExactly(
            refusal -> {
              assertThat(refusal.get("callback_query_id").asText()).isEqualTo("cb-stranger");
              assertThat(refusal.get("text").asText()).isEqualTo("No access.");
            },
            passedGate -> {
              assertThat(passedGate.get("callback_query_id").asText()).isEqualTo("cb-allowed");
              assertThat(passedGate.get("text").asText())
                  .isEqualTo("Expired, please resend the link.");
            });
    assertThat(telegramCalls("sendMessage")).isEmpty();
  }
}
