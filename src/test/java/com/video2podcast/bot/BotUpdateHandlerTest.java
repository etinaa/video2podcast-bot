package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessStore;
import com.video2podcast.support.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

class BotUpdateHandlerTest {

  private static final long OWNER = 1;
  private static final long ALLOWED = 50;
  private static final long STRANGER = 123;

  @TempDir Path dir;

  private final RecordingGateway gateway = new RecordingGateway();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
  private final UrlMessageHandler urls = mock(UrlMessageHandler.class);
  private AccessService access;
  private BotUpdateHandler handler;

  @BeforeEach
  void setUp() {
    access =
        new AccessService(
            new AccessStore(dir.resolve("allowed-users.json")), OWNER, Duration.ofHours(24), clock);
    access.allow(ALLOWED, "ann", OWNER);
    handler = new BotUpdateHandler(gateway, access, urls);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/whoami", "/WhoAmI", "  /whoami  ", "/whoami@SomeBot", "/whoami extra"})
  void whoamiRepliesWithTheSendersIdInAPrivateChat(String text) {
    handler.handle(update(message("private", 555L, 123L, text)));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(555L);
          assertThat(sent.html()).isEqualTo("Your Telegram id: <code>123</code>");
          assertThat(sent.buttons()).isEmpty();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"group", "supergroup", "channel"})
  void ignoresMessagesFromNonPrivateChats(String chatType) {
    handler.handle(update(message(chatType, -1L, 123L, "/whoami")));

    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresMessagesWithoutASender() {
    Message message = message("private", 555L, 123L, "/whoami");
    message.setFrom(null);

    handler.handle(update(message));

    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresMessagesWithoutText() {
    handler.handle(update(message("private", 555L, 123L, null)));

    assertThat(gateway.messages).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/whoamix", "/unknown", "  /unknown arg"})
  void ignoresUnknownCommandsFromAllowedUsers(String text) {
    handler.handle(update(message("private", 555L, ALLOWED, text)));

    assertThat(gateway.messages).isEmpty();
    verify(urls, never()).handle(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "whoami", "https://videos.example/v", "  https://x.example  "})
  void passesOtherTextFromAllowedUsersToTheUrlHandler(String text) {
    handler.handle(update(message("private", 555L, ALLOWED, text)));

    verify(urls).handle(new Sender(ALLOWED, 555L, null, "Test"), text);
  }

  @Test
  void passesTextFromTheOwnerToTheUrlHandler() {
    handler.handle(update(message("private", 8L, OWNER, "https://videos.example/v")));

    verify(urls).handle(any(), eq("https://videos.example/v"));
  }

  @Test
  void neverPassesTextFromUnknownUsersToTheUrlHandler() {
    handler.handle(update(message("private", 9L, STRANGER, "https://videos.example/v")));

    verify(urls, never()).handle(any(), any());
  }

  @Test
  void whoamiWorksForAllowedUsersAndTheOwnerToo() {
    handler.handle(update(message("private", 7L, ALLOWED, "/whoami")));
    handler.handle(update(message("private", 8L, OWNER, "/whoami")));

    assertThat(gateway.messages).hasSize(2);
  }

  @Test
  void unknownUserGetsARefusalWithTheirIdOnlyOncePerMinute() {
    handler.handle(update(message("private", 9L, STRANGER, "hello")));
    handler.handle(update(message("private", 9L, STRANGER, "hello again")));

    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(9L);
          assertThat(sent.html())
              .isEqualTo("No access. Send /start to request it. Your id: <code>123</code>");
        });

    clock.advance(Duration.ofMinutes(1));
    handler.handle(update(message("private", 9L, STRANGER, "hello")));

    assertThat(gateway.messages).hasSize(2);
  }

  @Test
  void startFromAnUnknownUserCreatesARequestAndConfirmsIt() {
    handler.handle(update(message("private", 9L, STRANGER, "/start")));

    assertThat(sentTo(9L))
        .singleElement()
        .extracting(RecordingGateway.Sent::html)
        .isEqualTo("Access requested. The owner will review it.");
    assertThat(access.pending())
        .singleElement()
        .satisfies(request -> {
          assertThat(request.id()).isEqualTo(STRANGER);
          assertThat(request.username()).isNull();
          assertThat(request.displayName()).isEqualTo("Test");
        });
  }

  @Test
  void repeatedStartIsAnsweredOncePerMinuteAndKeepsOneRequest() {
    handler.handle(update(message("private", 9L, STRANGER, "/start")));
    handler.handle(update(message("private", 9L, STRANGER, "/start")));
    handler.handle(update(message("private", 9L, STRANGER, "/start")));

    assertThat(sentTo(9L))
        .extracting(RecordingGateway.Sent::html)
        .containsExactly(
            "Access requested. The owner will review it.",
            "Your request is already waiting for the owner.");
    assertThat(sentTo(OWNER)).hasSize(1);
    assertThat(access.pending()).hasSize(1);
  }

  @Test
  void startDuringTheCooldownExplainsWhenToAskAgain() {
    handler.handle(update(message("private", 9L, STRANGER, "/start")));
    access.deny(STRANGER, OWNER);
    clock.advance(Duration.ofMinutes(5));

    handler.handle(update(message("private", 9L, STRANGER, "/start")));

    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::html)
        .last()
        .isEqualTo("Your request was declined. You can ask again after 2026-11-16 10:00 UTC.");
    assertThat(access.pending()).isEmpty();
  }

  @Test
  void startFromAnAllowedUserOrTheOwnerShowsHelp() {
    handler.handle(update(message("private", 7L, ALLOWED, "/start")));
    handler.handle(update(message("private", 8L, OWNER, "/start@SomeBot")));

    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::html)
        .containsExactly(BotUpdateHandler.HELP, BotUpdateHandler.HELP);
    assertThat(access.pending()).isEmpty();
  }

  @Test
  void startRacingWithAnApprovalShowsHelp() {
    AccessService racing = mock(AccessService.class);
    when(racing.requestAccess(eq(STRANGER), any(), any()))
        .thenReturn(new AccessService.RequestOutcome.AlreadyAllowed());
    new BotUpdateHandler(gateway, racing, urls)
        .handle(update(message("private", 9L, STRANGER, "/start")));

    assertThat(gateway.messages)
        .extracting(RecordingGateway.Sent::html)
        .containsExactly(BotUpdateHandler.HELP);
  }

  @Test
  void usernameAndFullNameAreStoredWithTheRequest() {
    Message message = message("private", 9L, STRANGER, "/start");
    message.getFrom().setUserName("stranger");
    message.getFrom().setLastName("Danger");

    handler.handle(update(message));

    assertThat(access.pending())
        .singleElement()
        .satisfies(request -> {
          assertThat(request.username()).isEqualTo("stranger");
          assertThat(request.displayName()).isEqualTo("Test Danger");
        });
  }

  @Test
  void groupMessagesFromUnknownUsersAreIgnoredCompletely() {
    handler.handle(update(message("group", -1L, STRANGER, "/start")));

    assertThat(gateway.messages).isEmpty();
    assertThat(access.pending()).isEmpty();
  }

  @Test
  void callbackFromAnUnknownUserIsRefused() {
    handler.handle(callbackUpdate("cb1", STRANGER, "private"));

    assertThat(gateway.answers).containsExactly(new RecordingGateway.Answer("cb1", "No access."));
  }

  @Test
  void callbackFromAnAllowedUserPassesTheGate() {
    handler.handle(callbackUpdate("cb2", ALLOWED, "private"));

    assertThat(gateway.answers).isEmpty();
  }

  @Test
  void callbacksOutsidePrivateChatsAreIgnored() {
    handler.handle(callbackUpdate("cb3", STRANGER, "group"));

    assertThat(gateway.answers).isEmpty();
  }

  @Test
  void callbackWithoutAMessageIsIgnored() {
    Update update = new Update();
    update.setCallbackQuery(new CallbackQuery());

    handler.handle(update);

    assertThat(gateway.answers).isEmpty();
    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void ignoresUpdatesOfOtherKinds() {
    handler.handle(new Update());

    assertThat(gateway.messages).isEmpty();
  }

  private static Update callbackUpdate(String id, long userId, String chatType) {
    CallbackQuery callback = new CallbackQuery();
    callback.setId(id);
    callback.setFrom(User.builder().id(userId).firstName("Test").isBot(false).build());
    callback.setMessage(message(chatType, 9L, userId, "button message"));
    callback.setData("x");
    Update update = new Update();
    update.setCallbackQuery(callback);
    return update;
  }

  private List<RecordingGateway.Sent> sentTo(long chatId) {
    return gateway.messages.stream().filter(sent -> sent.chatId() == chatId).toList();
  }

  private static Update update(Message message) {
    Update update = new Update();
    update.setMessage(message);
    return update;
  }

  private static Message message(String chatType, long chatId, long userId, String text) {
    return Message.builder()
        .messageId(1)
        .chat(Chat.builder().id(chatId).type(chatType).build())
        .from(User.builder().id(userId).firstName("Test").isBot(false).build())
        .text(text)
        .build();
  }
}
