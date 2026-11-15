package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessApprovalFT extends FunctionalTest {

  /** The one message to the owner that mentions the user (the owner chat is shared by tests). */
  private static JsonNode requestFor(long userId) {
    List<JsonNode> matching =
        sentTo(OWNER_ID).stream()
            .filter(call -> call.get("text").asText().contains("id <code>" + userId + "</code>"))
            .toList();
    assertThat(matching).hasSize(1);
    return matching.get(0);
  }

  @Test
  void ownerGetsTheRequestWithButtonsAndApprovalLetsTheUserIn() {
    long userId = newUserId();
    receive(Updates.privateMessage(userId, "/start"));

    JsonNode request = requestFor(userId);
    assertThat(request.get("text").asText()).startsWith("Access request from <b>Test</b> (@user");
    JsonNode buttons = request.get("reply_markup").get("inline_keyboard").get(0);
    assertThat(buttons.get(0).get("text").asText()).isEqualTo("Approve");
    assertThat(buttons.get(0).get("callback_data").asText()).isEqualTo("access:approve:" + userId);
    assertThat(buttons.get(1).get("callback_data").asText()).isEqualTo("access:deny:" + userId);

    receive(
        Updates.callback(
            OWNER_ID, "cb-approve-" + userId, "access:approve:" + userId, BOT_MESSAGE_ID));

    assertThat(sentTo(userId))
        .extracting(call -> call.get("text").asText())
        .containsExactly(
            "Access requested. The owner will review it.",
            "Access granted. Send me a video link to get started.");
    assertThat(telegramCalls("editMessageText"))
        .filteredOn(call -> call.get("text").asText().contains("id <code>" + userId + "</code>"))
        .singleElement()
        .satisfies(edit -> {
          assertThat(edit.get("text").asText()).endsWith("Approved.");
          assertThat(edit.get("chat_id").asLong()).isEqualTo(OWNER_ID);
          assertThat(edit.has("reply_markup")).isFalse();
        });
    assertThat(telegramCalls("answerCallbackQuery"))
        .filteredOn(call -> call.get("callback_query_id").asText().equals("cb-approve-" + userId))
        .singleElement()
        .satisfies(call ->
            assertThat(call.get("text").asText()).isEqualTo("User " + userId + " allowed."));

    receive(Updates.privateMessage(userId, "/start"));
    assertThat(sentTo(userId).get(2).get("text").asText()).contains("Send me a video link");
  }

  @Test
  void denyRefusesTheUserAndStartsTheCooldown() {
    long userId = newUserId();
    receive(Updates.privateMessage(userId, "/start"));

    receive(
        Updates.callback(OWNER_ID, "cb-deny-" + userId, "access:deny:" + userId, BOT_MESSAGE_ID));

    assertThat(access.isAllowed(userId)).isFalse();
    assertThat(sentTo(userId))
        .extracting(call -> call.get("text").asText())
        .containsExactly(
            "Access requested. The owner will review it.", "Your access request was declined.");

    receive(Updates.privateMessage(userId, "/start"));
    assertThat(sentTo(userId).get(2).get("text").asText())
        .startsWith("Your request was declined. You can ask again after ");
    assertThat(sentTo(OWNER_ID))
        .filteredOn(call -> call.get("text").asText().contains("id <code>" + userId + "</code>"))
        .hasSize(1);
  }

  @Test
  void aButtonTapFromSomeoneWhoIsNotTheOwnerChangesNothing() {
    long applicant = newUserId();
    long other = newUserId();
    access.allow(other, "other", OWNER_ID);
    receive(Updates.privateMessage(applicant, "/start"));

    receive(Updates.callback(other, "cb-other", "access:approve:" + applicant, BOT_MESSAGE_ID));

    assertThat(access.isAllowed(applicant)).isFalse();
    assertThat(telegramCalls("answerCallbackQuery"))
        .filteredOn(call -> call.get("callback_query_id").asText().equals("cb-other"))
        .singleElement()
        .satisfies(call -> assertThat(call.get("text").asText()).isEqualTo("Owner only."));
  }

  @Test
  void ownerCommandsManageTheAllowlist() {
    long userId = newUserId();

    receive(Updates.privateMessage(OWNER_ID, "/allow " + userId));
    assertThat(access.isAllowed(userId)).isTrue();
    assertThat(sentTo(userId))
        .extracting(call -> call.get("text").asText())
        .containsExactly("Access granted. Send me a video link to get started.");

    receive(Updates.privateMessage(OWNER_ID, "/users"));
    assertThat(sentTo(OWNER_ID).get(sentTo(OWNER_ID).size() - 1).get("text").asText())
        .contains("<b>Allowed users</b>")
        .contains(String.valueOf(userId));

    receive(Updates.privateMessage(OWNER_ID, "/deny " + userId));
    assertThat(access.isAllowed(userId)).isFalse();
    assertThat(sentTo(userId).get(1).get("text").asText()).isEqualTo("Your access was removed.");
  }

  @Test
  void anOldApproveButtonCannotRestoreAccessTheOwnerRevoked() {
    long userId = newUserId();
    receive(Updates.privateMessage(userId, "/start"));
    receive(
        Updates.callback(
            OWNER_ID, "cb-first-" + userId, "access:approve:" + userId, BOT_MESSAGE_ID));
    receive(Updates.privateMessage(OWNER_ID, "/deny " + userId));

    receive(
        Updates.callback(
            OWNER_ID, "cb-stale-" + userId, "access:approve:" + userId, BOT_MESSAGE_ID));

    assertThat(access.isAllowed(userId)).isFalse();
    assertThat(telegramCalls("answerCallbackQuery"))
        .filteredOn(call -> call.get("callback_query_id").asText().equals("cb-stale-" + userId))
        .singleElement()
        .satisfies(call ->
            assertThat(call.get("text").asText()).isEqualTo("This request is no longer active."));
  }

  @Test
  void anAllowedUserCannotUseOwnerCommands() {
    long userId = newUserId();
    long target = newUserId();
    access.allow(userId, "ann", OWNER_ID);

    receive(Updates.privateMessage(userId, "/allow " + target));

    assertThat(access.isAllowed(target)).isFalse();
    assertThat(sentTo(userId))
        .extracting(call -> call.get("text").asText())
        .containsExactly("Owner only.");
  }
}
