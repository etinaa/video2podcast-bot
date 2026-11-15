package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessStore;
import com.video2podcast.support.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

class AdminCommandsTest {

  private static final long OWNER = 1;
  private static final long ANN = 50;
  private static final long BOB = 60;

  @TempDir Path dir;

  private final RecordingGateway gateway = new RecordingGateway();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
  private Path file;
  private AccessService access;
  private BotUpdateHandler handler;

  @BeforeEach
  void setUp() {
    file = dir.resolve("allowed-users.json");
    access = newAccess();
    handler = new BotUpdateHandler(gateway, access);
  }

  private AccessService newAccess() {
    return new AccessService(new AccessStore(file), OWNER, Duration.ofHours(24), clock);
  }

  // --- request notification ---

  @Test
  void ownerGetsTheRequestWithApproveAndDenyButtons() {
    handler.handle(text(BOB, "stranger", "Bob <b>Hacker</b> & Co", "/start"));

    assertThat(gateway.messages).hasSize(2);
    RecordingGateway.Sent toOwner = gateway.messages.get(1);
    assertThat(toOwner.chatId()).isEqualTo(OWNER);
    assertThat(toOwner.html())
        .isEqualTo(
            "Access request from <b>Bob &lt;b&gt;Hacker&lt;/b&gt; &amp; Co</b> "
                + "(@stranger, id <code>60</code>)");
    assertThat(toOwner.buttons())
        .containsExactly(
            new InlineButton("Approve", "access:approve:60"),
            new InlineButton("Deny", "access:deny:60"));
    assertThat(access.pendingRequest(BOB).orElseThrow().requestMessageId()).isEqualTo(2);
  }

  @Test
  void requestWithoutAUsernameOmitsTheHandle() {
    handler.handle(text(BOB, null, "Bob", "/start"));

    assertThat(gateway.messages.get(1).html())
        .isEqualTo("Access request from <b>Bob</b> (id <code>60</code>)");
  }

  @Test
  void aFailedOwnerNotificationLeavesTheRequestPending() {
    gateway.failingChats.add(OWNER);

    handler.handle(text(BOB, "bob", "Bob", "/start"));

    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::html)
        .containsExactly("Access requested. The owner will review it.");
    assertThat(access.pendingRequest(BOB)).isPresent();
    assertThat(access.pendingRequest(BOB).orElseThrow().requestMessageId()).isNull();
  }

  // --- buttons ---

  @Test
  void approveButtonLetsTheUserInNotifiesThemAndClosesTheRequestMessage() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.messages.clear();

    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(access.pending()).isEmpty();
    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(BOB);
          assertThat(sent.html()).startsWith("Access granted.");
        });
    assertThat(gateway.edits)
        .singleElement()
        .satisfies(edit -> {
          assertThat(edit.chatId()).isEqualTo(OWNER);
          assertThat(edit.messageId()).isEqualTo(2);
          assertThat(edit.html()).endsWith("\n\nApproved.");
          assertThat(edit.buttons()).isEmpty();
        });
    assertThat(gateway.answers)
        .containsExactly(new RecordingGateway.Answer("cb1", "User 60 allowed."));
  }

  @Test
  void aSecondTapOnTheSameButtonDoesNothingMore() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));
    gateway.messages.clear();
    gateway.edits.clear();

    handler.handle(tap(OWNER, "cb2", "access:approve:60", 2));

    assertThat(gateway.messages).isEmpty();
    assertThat(gateway.edits).isEmpty();
    assertThat(gateway.answers)
        .last()
        .isEqualTo(new RecordingGateway.Answer("cb2", "This request is no longer active."));
  }

  @Test
  void denyButtonRejectsNotifiesAndStartsTheCooldown() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.messages.clear();

    handler.handle(tap(OWNER, "cb1", "access:deny:60", 2));

    assertThat(access.isAllowed(BOB)).isFalse();
    assertThat(access.pending()).isEmpty();
    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(BOB);
          assertThat(sent.html()).isEqualTo("Your access request was declined.");
        });
    assertThat(gateway.edits)
        .singleElement()
        .satisfies(e -> assertThat(e.html()).endsWith("Denied."));

    handler.handle(text(BOB, "bob", "Bob", "/start"));
    assertThat(gateway.messages)
        .last()
        .satisfies(sent -> assertThat(sent.html()).contains("You can ask again after"));
  }

  @Test
  void denyingAnAlreadyHandledRequestReportsIt() {
    handler.handle(tap(OWNER, "cb1", "access:deny:60", 2));

    assertThat(gateway.answers)
        .containsExactly(new RecordingGateway.Answer("cb1", "This request is no longer active."));
    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void anAllowedUserWhoIsNotTheOwnerCannotUseTheButtons() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    access.allow(ANN, "ann", OWNER);
    gateway.messages.clear();

    handler.handle(tap(ANN, "cb1", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isFalse();
    assertThat(gateway.answers).containsExactly(new RecordingGateway.Answer("cb1", "Owner only."));
    assertThat(gateway.messages).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "access:approve",
        "access:approve:abc",
        "access:approve:-5",
        "access:grant:60",
        "access:approve:60:x"
      })
  void malformedButtonDataIsRejected(String data) {
    handler.handle(tap(OWNER, "cb1", data, 2));

    assertThat(gateway.answers)
        .containsExactly(new RecordingGateway.Answer("cb1", "Unknown button."));
    assertThat(access.isAllowed(60)).isFalse();
  }

  @Test
  void buttonsKeepWorkingAfterARestart() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.messages.clear();

    AccessService restarted = newAccess();
    BotUpdateHandler afterRestart = new BotUpdateHandler(gateway, restarted);
    afterRestart.handle(tap(OWNER, "cb1", "access:approve:60", 2));

    assertThat(restarted.isAllowed(BOB)).isTrue();
    assertThat(gateway.messages).extracting(RecordingGateway.Sent::chatId).containsExactly(BOB);
    assertThat(gateway.edits).hasSize(1);
  }

  @Test
  void aUserWhoBlockedTheBotDoesNotBreakApproval() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failingChats.add(BOB);

    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(gateway.answers).hasSize(1);
  }

  @Test
  void aFailedEditOfTheRequestMessageDoesNotBreakApproval() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failEdits = true;

    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(gateway.answers).hasSize(1);
  }

  // --- stale buttons ---

  @Test
  void anOldApproveButtonCannotRestoreRevokedAccess() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failEdits = true;
    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));
    handler.handle(text(OWNER, "owner", "Owner", "/deny 60"));
    gateway.answers.clear();

    handler.handle(tap(OWNER, "cb2", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isFalse();
    assertThat(gateway.answers)
        .containsExactly(new RecordingGateway.Answer("cb2", "This request is no longer active."));
  }

  @Test
  void anOldDenyButtonCannotRemoveAUserWhoWasAllowedAgain() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    handler.handle(text(OWNER, "owner", "Owner", "/allow 60"));

    handler.handle(tap(OWNER, "cb1", "access:deny:60", 2));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(gateway.answers)
        .containsExactly(new RecordingGateway.Answer("cb1", "This request is no longer active."));
  }

  @Test
  void aButtonFromAnotherMessageDoesNotMatchTheRequest() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));

    handler.handle(tap(OWNER, "cb1", "access:approve:60", 99));

    assertThat(access.isAllowed(BOB)).isFalse();
    assertThat(access.pendingRequest(BOB)).isPresent();
  }

  @Test
  void aButtonCannotApproveARequestTheOwnerWasNeverNotifiedAbout() {
    gateway.failingChats.add(OWNER);
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failingChats.clear();

    handler.handle(tap(OWNER, "cb1", "access:approve:60", 2));

    assertThat(access.isAllowed(BOB)).isFalse();
  }

  // --- delivery failures ---

  @Test
  void aFailedConfirmationDoesNotStopTheOwnerNotification() {
    gateway.failingChats.add(BOB);

    handler.handle(text(BOB, "bob", "Bob", "/start"));

    assertThat(gateway.messages).extracting(RecordingGateway.Sent::chatId).containsExactly(OWNER);
    assertThat(access.pendingRequest(BOB).orElseThrow().requestMessageId()).isNotNull();
  }

  @Test
  void aRequestTheOwnerWasNotToldAboutIsSentAgainOnTheNextStart() {
    gateway.failingChats.add(OWNER);
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failingChats.clear();
    gateway.messages.clear();

    handler.handle(text(BOB, "bob", "Bob", "/start"));
    handler.handle(text(BOB, "bob", "Bob", "/start"));

    assertThat(gateway.messages.stream().filter(m -> m.chatId() == OWNER)).hasSize(1);
    assertThat(access.pendingRequest(BOB).orElseThrow().requestMessageId()).isNotNull();
  }

  @Test
  void pendingNotificationsAreSentAgainAtStartupOnlyWhenMissing() {
    gateway.failingChats.add(OWNER);
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failingChats.clear();
    gateway.messages.clear();

    handler.resendPendingNotifications();
    handler.resendPendingNotifications();

    assertThat(gateway.messages).extracting(RecordingGateway.Sent::chatId).containsExactly(OWNER);
  }

  @Test
  void aLongUsersListIsSplitIntoMessagesBelowTheTelegramLimit() {
    String name = "<b>" + "x".repeat(100) + "</b> & ";
    for (long id = 1000; id < 1040; id++) {
      access.requestAccess(id, "user" + id, name);
    }

    handler.handle(text(OWNER, "owner", "Owner", "/users"));

    assertThat(gateway.messages).hasSizeGreaterThan(1);
    assertThat(gateway.messages).allSatisfy(m -> assertThat(m.html().length()).isLessThan(4096));
    String all =
        String.join("\n", gateway.messages.stream().map(RecordingGateway.Sent::html).toList());
    for (long id = 1000; id < 1040; id++) {
      assertThat(all).contains(id + " @user" + id + " - ");
    }
  }

  // --- commands ---

  @Test
  void allowCommandAddsTheUserNotifiesThemAndClosesAPendingRequestMessage() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.messages.clear();

    handler.handle(text(OWNER, "owner", "Owner", "/allow 60"));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::chatId, RecordingGateway.Sent::html)
        .containsExactly(
            tuple(BOB, "Access granted. Send me a video link to get started."),
            tuple(OWNER, "User 60 allowed."));
    assertThat(gateway.edits)
        .singleElement()
        .satisfies(edit -> {
          assertThat(edit.chatId()).isEqualTo(OWNER);
          assertThat(edit.messageId()).isEqualTo(2);
          assertThat(edit.html()).endsWith("Approved.");
        });
  }

  @Test
  void allowCommandWorksForAnIdWithoutARequest() {
    handler.handle(text(OWNER, "owner", "Owner", "/allow 777"));

    assertThat(access.isAllowed(777)).isTrue();
    assertThat(gateway.edits).isEmpty();
  }

  @Test
  void allowingSomeoneWhoAlreadyHasAccessSaysSo() {
    access.allow(ANN, "ann", OWNER);

    handler.handle(text(OWNER, "owner", "Owner", "/allow 50"));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> assertThat(sent.html()).isEqualTo("User 50 already has access."));
  }

  @Test
  void allowingAPendingRequestWithoutAStoredMessageSkipsTheEdit() {
    gateway.failingChats.add(OWNER);
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.failingChats.clear();

    handler.handle(text(OWNER, "owner", "Owner", "/allow 60"));

    assertThat(access.isAllowed(BOB)).isTrue();
    assertThat(gateway.edits).isEmpty();
  }

  @Test
  void denyCommandRemovesAnAllowedUserAndTellsThem() {
    access.allow(ANN, "ann", OWNER);

    handler.handle(text(OWNER, "owner", "Owner", "/deny 50"));

    assertThat(access.isAllowed(ANN)).isFalse();
    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::chatId, RecordingGateway.Sent::html)
        .containsExactly(tuple(ANN, "Your access was removed."), tuple(OWNER, "User 50 removed."));
  }

  @Test
  void denyCommandRejectsAPendingRequest() {
    handler.handle(text(BOB, "bob", "Bob", "/start"));
    gateway.messages.clear();

    handler.handle(text(OWNER, "owner", "Owner", "/deny 60"));

    assertThat(access.pending()).isEmpty();
    assertThat(gateway.messages.get(0).html()).isEqualTo("Your access request was declined.");
    assertThat(gateway.edits)
        .singleElement()
        .satisfies(e -> assertThat(e.html()).endsWith("Denied."));
  }

  @Test
  void denyCommandHandlesUnknownIdsAndProtectsTheOwner() {
    handler.handle(text(OWNER, "owner", "Owner", "/deny 999"));
    handler.handle(text(OWNER, "owner", "Owner", "/deny 1"));

    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::html)
        .containsExactly("User 999 has no request and no access.", "The owner can't be removed.");
    assertThat(access.isAllowed(OWNER)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/allow", "/allow abc", "/allow 0", "/allow -3", "/deny", "/deny 1 2"})
  void badArgumentsGetTheUsageHint(String command) {
    handler.handle(text(OWNER, "owner", "Owner", command));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> assertThat(sent.html()).startsWith("Usage: /"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/allow 60", "/deny 50", "/users"})
  void aUserWhoIsNotTheOwnerCannotUseOwnerCommands(String command) {
    access.allow(ANN, "ann", OWNER);

    handler.handle(text(ANN, "ann", "Ann", command));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> assertThat(sent.html()).isEqualTo("Owner only."));
    assertThat(access.isAllowed(60)).isFalse();
    assertThat(access.isAllowed(ANN)).isTrue();
  }

  @Test
  void anUnknownUserTryingOwnerCommandsJustGetsTheRefusal() {
    handler.handle(text(BOB, "bob", "Bob", "/allow 60"));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> assertThat(sent.html()).startsWith("No access."));
    assertThat(access.isAllowed(BOB)).isFalse();
  }

  @Test
  void usersListsAllowedUsersAndPendingRequestsEscaped() {
    access.allow(ANN, "ann_<x>", OWNER);
    access.allow(777, null, OWNER);
    handler.handle(text(BOB, "bob", "Bob & <i>Co</i>", "/start"));
    gateway.messages.clear();

    handler.handle(text(OWNER, "owner", "Owner", "/users"));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent ->
            assertThat(sent.html())
                .isEqualTo(
                    """
                    <b>Allowed users</b>
                    50 @ann_&lt;x&gt;
                    777

                    <b>Pending requests</b>
                    60 @bob - Bob &amp; &lt;i&gt;Co&lt;/i&gt;\
                    """));
  }

  @Test
  void usersWithNothingToShowSaysNone() {
    handler.handle(text(OWNER, "owner", "Owner", "/users"));

    assertThat(gateway.messages.get(0).html())
        .isEqualTo("<b>Allowed users</b>\nnone\n\n<b>Pending requests</b>\nnone");
  }

  @Test
  void pendingRequestWithoutAUsernameIsListedByIdAndName() {
    handler.handle(text(BOB, null, "Bob", "/start"));
    gateway.messages.clear();

    handler.handle(text(OWNER, "owner", "Owner", "/users"));

    assertThat(gateway.messages.get(0).html()).endsWith("60 - Bob");
  }

  // --- helpers ---

  private static Update text(long userId, String username, String firstName, String text) {
    Update update = new Update();
    update.setMessage(
        Message.builder()
            .messageId(1)
            .chat(Chat.builder().id(userId).type("private").build())
            .from(
                User.builder()
                    .id(userId)
                    .firstName(firstName)
                    .userName(username)
                    .isBot(false)
                    .build())
            .text(text)
            .build());
    return update;
  }

  private static Update tap(long userId, String callbackId, String data, int messageId) {
    CallbackQuery callback = new CallbackQuery();
    callback.setId(callbackId);
    callback.setData(data);
    callback.setFrom(User.builder().id(userId).firstName("Test").isBot(false).build());
    callback.setMessage(
        Message.builder()
            .messageId(messageId)
            .chat(Chat.builder().id(OWNER).type("private").build())
            .build());
    Update update = new Update();
    update.setCallbackQuery(callback);
    return update;
  }
}
