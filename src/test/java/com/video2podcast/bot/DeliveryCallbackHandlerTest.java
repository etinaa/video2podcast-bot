package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.JobStoreException;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

class DeliveryCallbackHandlerTest {

  private static final Instant T0 = Instant.parse("2026-11-19T12:30:00Z");
  private static final String ID = "job-1";
  private static final long OWNER = 101L;
  private static final long CHAT = 101L;
  private static final int SUMMARY = 701;

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final RecordingGateway gateway = new RecordingGateway();
  private JobStore store;
  private DeliveryCallbackHandler handler;

  @BeforeEach
  void setUp() throws IOException {
    store = new JobStore(dir.resolve("work"), clock);
    handler = new DeliveryCallbackHandler(gateway, new DeliveryService(store, clock));
    create(SUMMARY);
  }

  private void create(Integer summaryMessageId) throws IOException {
    Instant expires = T0.plus(Duration.ofMinutes(30));
    store.create(
        new JobRecord(
            ID,
            OWNER,
            CHAT,
            summaryMessageId,
            "Budget meeting",
            60,
            T0.minusSeconds(60),
            Map.of(
                FileKind.AUDIO, new JobFile("source.m4a", FileState.AVAILABLE, expires, null),
                FileKind.TRANSCRIPT,
                    new JobFile("transcript.txt", FileState.AVAILABLE, expires, null))));
    Files.writeString(store.directory(ID).resolve("transcript.txt"), "text");
  }

  private static CallbackQuery tap(long userId, String jobId) {
    CallbackQuery callback = new CallbackQuery();
    callback.setId("cb");
    callback.setFrom(User.builder().id(userId).firstName("T").isBot(false).build());
    callback.setData(Keyboards.TRANSCRIPT_PREFIX + jobId);
    return callback;
  }

  private FileState transcriptState() {
    return store.load(ID).orElseThrow().files().get(FileKind.TRANSCRIPT).state();
  }

  private List<String> answerTexts() {
    return gateway.answers.stream().map(RecordingGateway.Answer::text).toList();
  }

  private List<String> labels() {
    return gateway.buttonEdits.stream()
        .map(edit -> edit.buttons().get(0).text() + "|" + edit.buttons().get(1).text())
        .toList();
  }

  @Test
  void aTapFromTheOwnerSendsTheTranscriptOnceAndMarksItSent() {
    handler.handle(tap(OWNER, ID));

    assertThat(answerTexts()).containsExactly("Sending transcript…");
    assertThat(gateway.documents)
        .singleElement()
        .satisfies(doc -> {
          assertThat(doc.chatId()).isEqualTo(CHAT);
          assertThat(doc.file()).isEqualTo(store.directory(ID).resolve("transcript.txt"));
          assertThat(doc.fileName()).isEqualTo("Budget meeting.txt");
          assertThat(doc.caption()).isNull();
        });
    assertThat(labels())
        .containsExactly("Download audio|Sending transcript…", "Download audio|Transcript sent");
    assertThat(gateway.buttonEdits)
        .allSatisfy(edit -> {
          assertThat(edit.chatId()).isEqualTo(CHAT);
          assertThat(edit.messageId()).isEqualTo(SUMMARY);
        });
    assertThat(transcriptState()).isEqualTo(FileState.SENT);
    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void aSecondTapDoesNotSendAgain() {
    handler.handle(tap(OWNER, ID));
    gateway.answers.clear();

    handler.handle(tap(OWNER, ID));

    assertThat(answerTexts()).containsExactly("Already sent.");
    assertThat(gateway.documents).hasSize(1);
  }

  @Test
  void aTapWhileSendingIsToldItIsInProgress() {
    store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING);

    handler.handle(tap(OWNER, ID));

    assertThat(answerTexts()).containsExactly("Already being sent.");
    assertThat(gateway.documents).isEmpty();
  }

  @Test
  void anotherUserIsRefusedWithOnlyAnAnswer() {
    handler.handle(tap(202L, ID));

    assertThat(answerTexts()).containsExactly("This button is not for you.");
    assertThat(gateway.documents).isEmpty();
    assertThat(gateway.buttonEdits).isEmpty();
    assertThat(transcriptState()).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void anExpiredTapIsRefused() {
    clock.advance(Duration.ofMinutes(30));

    handler.handle(tap(OWNER, ID));

    assertThat(answerTexts()).containsExactly("Expired, please resend the link.");
    assertThat(gateway.documents).isEmpty();
    assertThat(gateway.buttonEdits).isEmpty();
  }

  @Test
  void anInvalidOrUnknownJobIdIsExpired() {
    handler.handle(tap(OWNER, "../x"));
    handler.handle(tap(OWNER, ""));
    handler.handle(tap(OWNER, "other"));

    assertThat(answerTexts())
        .containsExactly(
            "Expired, please resend the link.",
            "Expired, please resend the link.",
            "Expired, please resend the link.");
  }

  @Test
  void theDocumentGoesToTheStoredChatNotTheCallbacksChat() {
    CallbackQuery callback = tap(OWNER, ID);

    handler.handle(callback);

    assertThat(gateway.documents)
        .singleElement()
        .satisfies(d -> assertThat(d.chatId()).isEqualTo(CHAT));
  }

  @Test
  void withoutASummaryMessageNoButtonsAreEditedButTheFileIsSent() throws IOException {
    store.discard(ID);
    create(null);

    handler.handle(tap(OWNER, ID));

    assertThat(gateway.documents).hasSize(1);
    assertThat(gateway.buttonEdits).isEmpty();
    assertThat(transcriptState()).isEqualTo(FileState.SENT);
  }

  @Test
  void theAudioLabelFollowsTheAudioState() {
    store.transition(ID, FileKind.AUDIO, FileState.AVAILABLE, FileState.SENDING);

    handler.handle(tap(OWNER, ID));

    assertThat(labels())
        .containsExactly(
            "Preparing audio…|Sending transcript…", "Preparing audio…|Transcript sent");
  }

  @Test
  void aFailedSendReleasesTheFileRestoresTheButtonAndTellsTheUser() {
    gateway.failDocuments = true;

    handler.handle(tap(OWNER, ID));

    assertThat(transcriptState()).isEqualTo(FileState.AVAILABLE);
    assertThat(labels())
        .containsExactly(
            "Download audio|Sending transcript…", "Download audio|Download transcript");
    assertThat(gateway.messages)
        .singleElement()
        .satisfies(m -> {
          assertThat(m.chatId()).isEqualTo(CHAT);
          assertThat(m.html())
              .isEqualTo("Could not send the transcript. Tap the button to try again.");
        });
    assertThat(store.load(ID).orElseThrow().files().get(FileKind.TRANSCRIPT).sentAt()).isNull();
  }

  @Test
  void afterAFailedSendTheOwnerCanTryAgain() {
    gateway.failDocuments = true;
    handler.handle(tap(OWNER, ID));
    gateway.failDocuments = false;

    handler.handle(tap(OWNER, ID));

    assertThat(gateway.documents).hasSize(1);
    assertThat(transcriptState()).isEqualTo(FileState.SENT);
  }

  @Test
  void aFailedNotificationAfterAFailedSendDoesNotEscape() {
    gateway.failDocuments = true;
    gateway.failMessages = true;
    gateway.failButtons = true;

    handler.handle(tap(OWNER, ID));

    assertThat(transcriptState()).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void aFailedAnswerAndFailedButtonEditsDoNotStopTheUpload() {
    gateway.failAnswers = true;
    gateway.failButtons = true;

    handler.handle(tap(OWNER, ID));

    assertThat(gateway.documents).hasSize(1);
    assertThat(transcriptState()).isEqualTo(FileState.SENT);
  }

  @Test
  void aFailedAnswerToARefusalDoesNotEscape() {
    gateway.failAnswers = true;

    handler.handle(tap(202L, ID));

    assertThat(gateway.documents).isEmpty();
  }

  @Test
  void aStoreFailureBeforeTheClaimGivesAGenericAnswerAndNoUpload() {
    DeliveryService broken =
        new DeliveryService(store, clock) {
          @Override
          public Claim claim(String jobId, long userId, FileKind kind) {
            throw new JobStoreException("disk", new IOException("disk"));
          }
        };

    new DeliveryCallbackHandler(gateway, broken).handle(tap(OWNER, ID));

    assertThat(answerTexts()).containsExactly("Something went wrong, please try again.");
    assertThat(gateway.documents).isEmpty();
  }

  @Test
  void ifMarkingSentFailsTheFileStaysSendingAndNoErrorIsShown() {
    DeliveryService failing =
        new DeliveryService(store, clock) {
          @Override
          public Optional<JobRecord> complete(String jobId, FileKind kind) {
            throw new JobStoreException("disk", new IOException("disk"));
          }
        };

    new DeliveryCallbackHandler(gateway, failing).handle(tap(OWNER, ID));

    assertThat(gateway.documents).hasSize(1);
    assertThat(transcriptState()).isEqualTo(FileState.SENDING);
    assertThat(gateway.messages).isEmpty();
    assertThat(labels()).containsExactly("Download audio|Sending transcript…");
  }

  @Test
  void ifReleasingFailsAfterAFailedSendTheUserIsStillTold() {
    gateway.failDocuments = true;
    DeliveryService failing =
        new DeliveryService(store, clock) {
          @Override
          public Optional<JobRecord> release(String jobId, FileKind kind) {
            throw new JobStoreException("disk", new IOException("disk"));
          }
        };

    new DeliveryCallbackHandler(gateway, failing).handle(tap(OWNER, ID));

    assertThat(gateway.messages).hasSize(1);
  }

  @Test
  void aJobWithoutAnAudioEntryShowsAnAvailableAudioButton() throws IOException {
    store.discard(ID);
    store.create(
        new JobRecord(
            ID,
            OWNER,
            CHAT,
            SUMMARY,
            "T",
            1,
            T0,
            Map.of(FileKind.TRANSCRIPT, JobFile.available("transcript.txt"))));
    Files.writeString(store.directory(ID).resolve("transcript.txt"), "x");

    handler.handle(tap(OWNER, ID));

    assertThat(labels()).last().isEqualTo("Download audio|Transcript sent");
  }
}
