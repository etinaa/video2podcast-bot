package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessStore;
import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStore;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/** The transcript button from a Telegram update to the stored job state. */
class TranscriptButtonTest {

  private static final Instant T0 = Instant.parse("2026-11-19T12:30:00Z");
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static final long OWNER = 1;
  private static final long USER = 101;

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch uploading = new CountDownLatch(1);
  private boolean hold;
  private final RecordingGateway gateway =
      new RecordingGateway() {
        @Override
        public void sendDocument(long chatId, Path file, String fileName, String caption) {
          super.sendDocument(chatId, file, fileName, caption);
          if (hold) {
            uploading.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }
      };
  private JobStore store;
  private BotUpdateHandler handler;

  @BeforeEach
  void setUp() throws IOException {
    AccessService access =
        new AccessService(
            new AccessStore(dir.resolve("allowed-users.json")), OWNER, Duration.ofHours(24), clock);
    access.allow(USER, "ann", OWNER);
    access.allow(202, "bob", OWNER);
    store = new JobStore(dir.resolve("work"), clock);
    handler =
        new BotUpdateHandler(
            gateway,
            access,
            mock(UrlMessageHandler.class),
            new DeliveryCallbackHandler(gateway, new DeliveryService(store, clock)));
    Instant expires = T0.plus(Duration.ofMinutes(30));
    store.create(
        new JobRecord(
            ID,
            USER,
            USER,
            701,
            "Budget meeting",
            60,
            T0,
            Map.of(
                FileKind.AUDIO, new JobFile("source.m4a", FileState.AVAILABLE, expires, null),
                FileKind.TRANSCRIPT,
                    new JobFile("transcript.txt", FileState.AVAILABLE, expires, null))));
    Files.writeString(store.directory(ID).resolve("transcript.txt"), "The budget meeting.\n");
  }

  private static Update tap(long userId) {
    CallbackQuery callback = new CallbackQuery();
    callback.setId("cb");
    callback.setFrom(User.builder().id(userId).firstName("T").isBot(false).build());
    Message message = new Message();
    message.setChat(Chat.builder().id(userId).type("private").build());
    message.setMessageId(701);
    callback.setMessage(message);
    callback.setData("transcript:" + ID);
    Update update = new Update();
    update.setCallbackQuery(callback);
    return update;
  }

  private FileState state() {
    return store.load(ID).orElseThrow().files().get(FileKind.TRANSCRIPT).state();
  }

  private List<String> answers() {
    return gateway.answers.stream().map(RecordingGateway.Answer::text).toList();
  }

  @Test
  void theOwnersTapSendsTheTranscriptAndMarksItSent() {
    handler.handle(tap(USER));

    assertThat(gateway.documents)
        .singleElement()
        .satisfies(doc -> assertThat(doc.fileName()).isEqualTo("Budget meeting.txt"));
    assertThat(state()).isEqualTo(FileState.SENT);
    assertThat(gateway.buttonEdits)
        .last()
        .satisfies(edit -> assertThat(edit.buttons().get(1).text()).isEqualTo("Transcript sent"));
    assertThat(gateway.buttonEdits.get(0).buttons().get(0).text()).isEqualTo("Download audio");
  }

  @Test
  void aSecondTapSendsNothingMore() {
    handler.handle(tap(USER));
    handler.handle(tap(USER));

    assertThat(gateway.documents).hasSize(1);
    assertThat(answers()).containsExactly("Sending transcript…", "Already sent.");
  }

  @Test
  void aTapAtTheSameMomentSendsOnce() throws Exception {
    hold = true;
    CompletableFuture<Void> first = CompletableFuture.runAsync(() -> handler.handle(tap(USER)));
    assertThat(uploading.await(10, TimeUnit.SECONDS)).isTrue();

    handler.handle(tap(USER));

    assertThat(answers()).contains("Already being sent.");
    assertThat(state()).isEqualTo(FileState.SENDING);
    release.countDown();
    first.get(10, TimeUnit.SECONDS);
    assertThat(gateway.documents).hasSize(1);
    assertThat(state()).isEqualTo(FileState.SENT);
  }

  @Test
  void anotherUsersTapIsRejected() {
    handler.handle(tap(202));

    assertThat(answers()).containsExactly("This button is not for you.");
    assertThat(gateway.documents).isEmpty();
    assertThat(state()).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void anExpiredJobGivesTheResendNotice() {
    clock.advance(Duration.ofMinutes(31));

    handler.handle(tap(USER));

    assertThat(answers()).containsExactly("Expired, please resend the link.");
    assertThat(gateway.documents).isEmpty();
  }
}
