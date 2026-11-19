package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStore;
import com.video2podcast.media.PreparedAudio;
import com.video2podcast.media.PreparedAudio.Mode;
import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

class AudioButtonTest {

  private static final Instant T0 = Instant.parse("2026-11-19T12:30:00Z");
  private static final String ID = "job-1";
  private static final long OWNER = 101L;
  private static final long CHAT = 102L;
  private static final int SUMMARY = 701;

  /** Keeps the tasks until the test runs them, like a worker that is busy with another job. */
  private static final class HeldQueue extends JobQueue {
    final List<Runnable> tasks = new CopyOnWriteArrayList<>();
    boolean reject;

    @Override
    public CompletableFuture<Void> submit(Runnable task) {
      if (reject) {
        throw new RejectedExecutionException("closed");
      }
      tasks.add(task);
      return new CompletableFuture<>();
    }

    void runAll() {
      List<Runnable> now = new ArrayList<>(tasks);
      tasks.clear();
      now.forEach(Runnable::run);
    }
  }

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);
  private final RecordingGateway gateway = new RecordingGateway();
  private final HeldQueue queue = new HeldQueue();
  private JobStore store;
  private DeliveryService delivery;
  private Path source;

  @BeforeEach
  void setUp() throws IOException {
    store = new JobStore(dir.resolve("work"), clock);
    delivery = new DeliveryService(store, clock);
    create("Budget meeting", SUMMARY);
  }

  private DeliveryCallbackHandler handler(StubAudioPreparer preparer) {
    return new DeliveryCallbackHandler(gateway, delivery, preparer, queue);
  }

  private void create(String title, Integer summaryMessageId) throws IOException {
    Instant expires = T0.plus(Duration.ofMinutes(30));
    store.create(
        new JobRecord(
            ID,
            OWNER,
            CHAT,
            summaryMessageId,
            title,
            10_800,
            T0.minusSeconds(60),
            Map.of(
                FileKind.AUDIO, new JobFile("source.m4a", FileState.AVAILABLE, expires, null),
                FileKind.TRANSCRIPT,
                    new JobFile("transcript.txt", FileState.AVAILABLE, expires, null))));
    source = store.directory(ID).resolve("source.m4a");
    Files.writeString(source, "audio");
    Files.writeString(store.directory(ID).resolve("transcript.txt"), "text");
  }

  private static CallbackQuery tap(long userId) {
    CallbackQuery callback = new CallbackQuery();
    callback.setId("cb");
    callback.setFrom(User.builder().id(userId).firstName("T").isBot(false).build());
    callback.setData(Keyboards.AUDIO_PREFIX + ID);
    return callback;
  }

  private FileState state(FileKind kind) {
    return store.load(ID).orElseThrow().files().get(kind).state();
  }

  private List<String> answers() {
    return gateway.answers.stream().map(RecordingGateway.Answer::text).toList();
  }

  private List<String> labels() {
    return gateway.buttonEdits.stream()
        .map(edit -> edit.buttons().get(0).text() + "|" + edit.buttons().get(1).text())
        .toList();
  }

  private StubAudioPreparer encoding(Mode mode, int kbps) {
    return new StubAudioPreparer((src, seconds) -> {
      Path out = src.resolveSibling("out.m4a");
      try {
        Files.writeString(out, "encoded");
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return new PreparedAudio(out, mode, kbps);
    });
  }

  @Test
  void aTapAnswersMarksTheButtonAndQueuesWithoutPreparingYet() {
    StubAudioPreparer preparer = StubAudioPreparer.unchanged();

    handler(preparer).handle(tap(OWNER));

    assertThat(answers()).containsExactly("Preparing audio…");
    assertThat(labels()).containsExactly("Preparing audio…|Download transcript");
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENDING);
    assertThat(queue.tasks).hasSize(1);
    assertThat(preparer.calls).isEmpty();
    assertThat(gateway.audios).isEmpty();
  }

  @Test
  void theQueuedTaskSendsTheUnchangedSourceOnce() {
    StubAudioPreparer preparer = StubAudioPreparer.unchanged();
    DeliveryCallbackHandler handler = handler(preparer);
    handler.handle(tap(OWNER));

    queue.runAll();

    assertThat(preparer.calls)
        .singleElement()
        .satisfies(call -> {
          assertThat(call.source()).isEqualTo(source);
          assertThat(call.durationSeconds()).isEqualTo(10_800);
        });
    assertThat(gateway.audios)
        .singleElement()
        .satisfies(audio -> {
          assertThat(audio.chatId()).isEqualTo(CHAT);
          assertThat(audio.file()).isEqualTo(source);
          assertThat(audio.fileName()).isEqualTo("Budget meeting.m4a");
          assertThat(audio.title()).isEqualTo("Budget meeting");
          assertThat(audio.performer()).isEqualTo("Video2Podcast");
          assertThat(audio.caption()).isEqualTo("Original audio, not compressed.");
        });
    assertThat(labels())
        .containsExactly("Preparing audio…|Download transcript", "Audio sent|Download transcript");
    assertThat(gateway.buttonEdits)
        .allSatisfy(edit -> {
          assertThat(edit.chatId()).isEqualTo(CHAT);
          assertThat(edit.messageId()).isEqualTo(SUMMARY);
        });
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENT);
    assertThat(state(FileKind.TRANSCRIPT)).isEqualTo(FileState.AVAILABLE);
    assertThat(Files.exists(source)).isTrue();
    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void aConvertedFileIsSentAsM4aWithItsCaptionAndDeleted() {
    handler(encoding(Mode.CONVERTED, 128)).handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios)
        .singleElement()
        .satisfies(audio -> {
          assertThat(audio.fileName()).isEqualTo("Budget meeting.m4a");
          assertThat(audio.caption()).isEqualTo("Converted to M4A, 128 kbps.");
        });
    assertThat(Files.exists(source.resolveSibling("out.m4a"))).isFalse();
    assertThat(Files.exists(source)).isTrue();
  }

  @Test
  void aCompressedFileReportsTheBitrate() {
    handler(encoding(Mode.COMPRESSED, 37)).handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios)
        .singleElement()
        .satisfies(audio ->
            assertThat(audio.caption())
                .isEqualTo("Compressed to mono AAC, 37 kbps, to fit Telegram's 50 MB limit."));
  }

  @Test
  void theFileNameTakesTheExtensionOfTheFileSent() throws IOException {
    Path mp3 = source.resolveSibling("source.MP3");
    Files.writeString(mp3, "mp3");
    handler(new StubAudioPreparer((src, s) -> new PreparedAudio(mp3, Mode.UNCHANGED, 0)))
        .handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios.get(0).fileName()).isEqualTo("Budget meeting.mp3");
    assertThat(Files.exists(mp3)).isFalse();
  }

  @Test
  void theTitleIsCutTo256UnitsWithoutSplittingASurrogatePair() throws IOException {
    store.discard(ID);
    String title = "a".repeat(255) + "😀";
    create(title, SUMMARY);
    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios.get(0).title()).isEqualTo("a".repeat(255));
  }

  @Test
  void aTitleWithMarkupCharactersIsPassedAsText() throws IOException {
    store.discard(ID);
    create("R&D <notes>", SUMMARY);
    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios.get(0).title()).isEqualTo("R&D <notes>");
    assertThat(gateway.audios.get(0).fileName()).isEqualTo("R&D _notes_.m4a");
  }

  @Test
  void aTranscriptSentWhileTheAudioWaitsKeepsItsLabel() {
    DeliveryCallbackHandler handler = handler(StubAudioPreparer.unchanged());
    handler.handle(tap(OWNER));
    delivery.claim(ID, OWNER, FileKind.TRANSCRIPT);
    delivery.complete(ID, FileKind.TRANSCRIPT);

    queue.runAll();

    assertThat(labels()).last().isEqualTo("Audio sent|Transcript sent");
  }

  @Test
  void aSecondTapWhileQueuedIsToldItIsInProgress() {
    DeliveryCallbackHandler handler = handler(StubAudioPreparer.unchanged());
    handler.handle(tap(OWNER));
    gateway.answers.clear();

    handler.handle(tap(OWNER));

    assertThat(answers()).containsExactly("Already being sent.");
    assertThat(queue.tasks).hasSize(1);
  }

  @Test
  void aTapAfterTheSendIsToldItIsSent() {
    DeliveryCallbackHandler handler = handler(StubAudioPreparer.unchanged());
    handler.handle(tap(OWNER));
    queue.runAll();
    gateway.answers.clear();

    handler.handle(tap(OWNER));

    assertThat(answers()).containsExactly("Already sent.");
    assertThat(queue.tasks).isEmpty();
  }

  @Test
  void anotherUserIsRefusedWithOnlyAnAnswer() {
    handler(StubAudioPreparer.unchanged()).handle(tap(999L));

    assertThat(answers()).containsExactly("This button is not for you.");
    assertThat(queue.tasks).isEmpty();
    assertThat(gateway.buttonEdits).isEmpty();
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
  }

  @Test
  void anExpiredTapQueuesNothing() {
    clock.advance(Duration.ofMinutes(30));

    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));

    assertThat(answers()).containsExactly("Expired, please resend the link.");
    assertThat(queue.tasks).isEmpty();
  }

  @Test
  void aClaimGrantedBeforeExpiryFinishesAfterIt() {
    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));
    clock.advance(Duration.ofMinutes(35));

    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENT);
  }

  @Test
  void aMissingSummaryMessageSendsWithoutEditingButtons() throws IOException {
    store.discard(ID);
    create("Budget meeting", null);

    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));
    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(gateway.buttonEdits).isEmpty();
  }

  @Test
  void aFailedPreparationReleasesTheAudioAndTellsTheUser() {
    StubAudioPreparer failing =
        new StubAudioPreparer((src, seconds) -> {
          throw JobFailure.of(JobFailure.Kind.FAILED);
        });
    handler(failing).handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios).isEmpty();
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
    assertThat(labels()).last().isEqualTo("Download audio|Download transcript");
    assertThat(gateway.messages)
        .singleElement()
        .satisfies(sent -> {
          assertThat(sent.chatId()).isEqualTo(CHAT);
          assertThat(sent.html())
              .isEqualTo("Could not send the audio. Tap the button to try again.");
        });
  }

  @Test
  void aRejectedUploadReleasesTheAudioAndDeletesTheOutput() {
    gateway.failAudio = true;
    handler(encoding(Mode.CONVERTED, 128)).handle(tap(OWNER));

    queue.runAll();

    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
    assertThat(Files.exists(source.resolveSibling("out.m4a"))).isFalse();
    assertThat(Files.exists(source)).isTrue();
    assertThat(gateway.messages).hasSize(1);
  }

  @Test
  void aRejectedUploadOfTheSourceKeepsTheSource() {
    gateway.failAudio = true;
    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));

    queue.runAll();

    assertThat(Files.exists(source)).isTrue();
  }

  @Test
  void aFailedAudioCanBeRequestedAgain() {
    gateway.failAudio = true;
    DeliveryCallbackHandler handler = handler(StubAudioPreparer.unchanged());
    handler.handle(tap(OWNER));
    queue.runAll();
    gateway.failAudio = false;

    handler.handle(tap(OWNER));
    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENT);
  }

  @Test
  void aRejectedQueueSubmissionReleasesTheAudioAndTellsTheUser() {
    queue.reject = true;

    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));

    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.AVAILABLE);
    assertThat(labels()).last().isEqualTo("Download audio|Download transcript");
    assertThat(gateway.messages).hasSize(1);
  }

  @Test
  void aFailureToMarkSentKeepsSendingAndSaysNothingMore() {
    DeliveryService failing =
        new DeliveryService(store, clock) {
          @Override
          public Optional<JobRecord> complete(String jobId, FileKind kind) {
            throw new IllegalStateException("disk");
          }
        };
    new DeliveryCallbackHandler(gateway, failing, StubAudioPreparer.unchanged(), queue)
        .handle(tap(OWNER));

    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENDING);
    assertThat(gateway.messages).isEmpty();
    assertThat(labels()).hasSize(1);
  }

  @Test
  void aFailureToReleaseStillRestoresTheButtonAndTellsTheUser() {
    gateway.failAudio = true;
    DeliveryService failing =
        new DeliveryService(store, clock) {
          @Override
          public Optional<JobRecord> release(String jobId, FileKind kind) {
            throw new IllegalStateException("disk");
          }
        };
    new DeliveryCallbackHandler(gateway, failing, StubAudioPreparer.unchanged(), queue)
        .handle(tap(OWNER));

    queue.runAll();

    assertThat(labels()).last().isEqualTo("Download audio|Download transcript");
    assertThat(gateway.messages).hasSize(1);
  }

  @Test
  void failedAnswersEditsAndNoticesDoNotEscapeOrStopTheUpload() {
    gateway.failAnswers = true;
    gateway.failButtons = true;
    gateway.failMessages = true;

    handler(StubAudioPreparer.unchanged()).handle(tap(OWNER));
    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENT);
  }

  @Test
  void aStoreFailureBeforeTheClaimGivesAGenericAnswerAndNoQueueWork() {
    DeliveryService broken =
        new DeliveryService(store, clock) {
          @Override
          public Claim claim(String jobId, long userId, FileKind kind) {
            throw new com.video2podcast.job.JobStoreException("disk", null);
          }
        };

    new DeliveryCallbackHandler(gateway, broken, StubAudioPreparer.unchanged(), queue)
        .handle(tap(OWNER));

    assertThat(answers()).containsExactly("Something went wrong, please try again.");
    assertThat(queue.tasks).isEmpty();
  }

  @Test
  void aPathWithoutAFileNameGetsTheM4aExtension() {
    assertThat(DeliveryCallbackHandler.extensionOf(Path.of("/"))).isEqualTo("m4a");
    assertThat(DeliveryCallbackHandler.extensionOf(Path.of("/w/source.MP3"))).isEqualTo("mp3");
    assertThat(DeliveryCallbackHandler.extensionOf(Path.of("/w/source"))).isEqualTo("m4a");
  }

  private void setTranscript(FileState target) {
    store.transition(ID, FileKind.TRANSCRIPT, FileState.AVAILABLE, FileState.SENDING);
    if (target == FileState.SENT) {
      store.transition(ID, FileKind.TRANSCRIPT, FileState.SENDING, FileState.SENT);
    }
  }

  @Test
  void twoCompetingFirstTapsQueueOneUpload() throws Exception {
    CyclicBarrier bothAtTheClaim = new CyclicBarrier(2);
    DeliveryService racing =
        new DeliveryService(store, clock) {
          @Override
          public Claim claim(String jobId, long userId, FileKind kind) {
            try {
              bothAtTheClaim.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return super.claim(jobId, userId, kind);
          }
        };
    DeliveryCallbackHandler handler =
        new DeliveryCallbackHandler(gateway, racing, StubAudioPreparer.unchanged(), queue);
    Thread first = new Thread(() -> handler.handle(tap(OWNER)));
    Thread second = new Thread(() -> handler.handle(tap(OWNER)));

    first.start();
    second.start();
    first.join(10_000);
    second.join(10_000);
    queue.runAll();

    assertThat(gateway.audios).hasSize(1);
    assertThat(answers())
        .hasSize(2)
        .contains("Preparing audio…")
        .containsAnyOf("Already being sent.", "Already sent.");
    assertThat(state(FileKind.AUDIO)).isEqualTo(FileState.SENT);
  }

  @ParameterizedTest
  @EnumSource(
      value = FileState.class,
      names = {"SENDING", "SENT"})
  void theFirstEditShowsTheTranscriptStateOfTheClaimRecord(FileState transcript) {
    {
      DeliveryService changing =
          new DeliveryService(store, clock) {
            @Override
            public Claim claim(String jobId, long userId, FileKind kind) {
              if (state(FileKind.TRANSCRIPT) == FileState.AVAILABLE) {
                setTranscript(transcript);
              }
              return super.claim(jobId, userId, kind);
            }
          };

      new DeliveryCallbackHandler(gateway, changing, StubAudioPreparer.unchanged(), queue)
          .handle(tap(OWNER));

      String label = transcript == FileState.SENT ? "Transcript sent" : "Sending transcript…";
      assertThat(labels()).containsExactly("Preparing audio…|" + label);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = FileState.class,
      names = {"SENDING", "SENT"})
  void theFinalEditShowsTheTranscriptStateOfTheCompletionRecord(FileState transcript) {
    {
      DeliveryService changing =
          new DeliveryService(store, clock) {
            @Override
            public Optional<JobRecord> complete(String jobId, FileKind kind) {
              if (state(FileKind.TRANSCRIPT) == FileState.AVAILABLE) {
                setTranscript(transcript);
              }
              return super.complete(jobId, kind);
            }
          };
      DeliveryCallbackHandler handler =
          new DeliveryCallbackHandler(gateway, changing, StubAudioPreparer.unchanged(), queue);
      handler.handle(tap(OWNER));
      queue.runAll();

      String label = transcript == FileState.SENT ? "Transcript sent" : "Sending transcript…";
      assertThat(labels())
          .containsExactly("Preparing audio…|Download transcript", "Audio sent|" + label);
    }
  }
}
