package com.video2podcast.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;

class TranscriptionClientTest {

  @TempDir Path jobDir;

  private final WhisperClient whisper = mock(WhisperClient.class);
  private final TranscriptionClient client = new TranscriptionClient(whisper);
  private final List<String> events = new ArrayList<>();
  private Path c0;
  private Path c1;
  private Path c2;

  @BeforeEach
  void setUp() throws IOException {
    Files.writeString(jobDir.resolve("source.m4a"), "src");
    Files.writeString(jobDir.resolve("audio.wav"), "wav");
    Path chunks = Files.createDirectory(jobDir.resolve("chunks"));
    c0 = Files.writeString(chunks.resolve("chunk-000.wav"), "0");
    c1 = Files.writeString(chunks.resolve("chunk-001.wav"), "1");
    c2 = Files.writeString(chunks.resolve("chunk-002.wav"), "2");
  }

  private Path run(Path... chunks) {
    return client.transcribe(jobDir, List.of(chunks), (n, t) -> events.add(n + "/" + t));
  }

  private static WhisperException connection() {
    return new WhisperException("no connection", true, null);
  }

  private static WhisperException other() {
    return new WhisperException("bad", false, null);
  }

  @Test
  void writesMergedTranscriptAndRemovesTemporaryAudio() throws Exception {
    when(whisper.transcribe(c0)).thenReturn(" First\n");
    when(whisper.transcribe(c1)).thenReturn("   ");
    when(whisper.transcribe(c2)).thenReturn("Last ");

    Path transcript = run(c0, c1, c2);

    assertThat(transcript).isEqualTo(jobDir.resolve("transcript.txt"));
    assertThat(Files.readString(transcript, StandardCharsets.UTF_8)).isEqualTo("First Last\n");
    assertThat(events).containsExactly("1/3", "2/3", "3/3");
    assertThat(jobDir.resolve("audio.wav")).doesNotExist();
    assertThat(jobDir.resolve("chunks")).doesNotExist();
    assertThat(jobDir.resolve("source.m4a")).exists();
    verify(whisper, times(1)).transcribe(c1);
  }

  @Test
  void retriesOnceAndKeepsGoing() throws Exception {
    when(whisper.transcribe(c0)).thenThrow(other()).thenReturn("A");
    when(whisper.transcribe(c1)).thenReturn("B");

    run(c0, c1);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("A B\n");
    assertThat(events).containsExactly("1/2", "2/2");
    InOrder order = Mockito.inOrder(whisper);
    order.verify(whisper, times(2)).transcribe(c0);
    order.verify(whisper).transcribe(c1);
  }

  @Test
  void aConnectionErrorThenSuccessKeepsEarlierChunksAndOrderWithOneProgressEventPerChunk()
      throws Exception {
    when(whisper.transcribe(c0)).thenReturn("A");
    when(whisper.transcribe(c1)).thenThrow(connection()).thenReturn("B");
    when(whisper.transcribe(c2)).thenReturn("C");

    run(c0, c1, c2);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("A B C\n");
    assertThat(events).containsExactly("1/3", "2/3", "3/3");
    InOrder order = Mockito.inOrder(whisper);
    order.verify(whisper).transcribe(c0);
    order.verify(whisper, times(2)).transcribe(c1);
    order.verify(whisper).transcribe(c2);
    Mockito.verifyNoMoreInteractions(whisper);
  }

  @Test
  void emptyAnswerOnRetryIsValid() throws Exception {
    when(whisper.transcribe(c0)).thenThrow(other()).thenReturn(" ");
    when(whisper.transcribe(c1)).thenReturn("Speech");

    run(c0, c1);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("Speech\n");
  }

  @Test
  void secondFailureDecidesTheKind() throws Exception {
    when(whisper.transcribe(c0)).thenReturn("ok");
    when(whisper.transcribe(c1)).thenThrow(connection(), other());

    assertThatThrownBy(() -> run(c0, c1, c2))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage("Transcription failed. Please try again later.");
            });
    verify(whisper, never()).transcribe(c2);
    assertThat(events).containsExactly("1/3", "2/3");
    assertNothingChanged();
  }

  @Test
  void connectionFailureOnRetryIsServiceUnreachable() throws Exception {
    when(whisper.transcribe(c0)).thenThrow(other(), connection());

    assertThatThrownBy(() -> run(c0, c1))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
              assertThat(f).hasMessage("The transcription server is unreachable.");
            });
    verify(whisper, times(2)).transcribe(any());
    assertNothingChanged();
  }

  @Test
  void entirelyEmptySpeechIsNoSpeech() throws Exception {
    when(whisper.transcribe(any())).thenReturn("", " \n");

    assertThatThrownBy(() -> run(c0, c1))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.NO_SPEECH);
              assertThat(f).hasMessage("No speech was found in this video.");
            });
    assertNothingChanged();
  }

  @Test
  void rejectsBadArguments() {
    assertThatThrownBy(() -> client.transcribe(null, List.of(c0), (n, t) -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> client.transcribe(jobDir, null, (n, t) -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> client.transcribe(jobDir, List.of(c0), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> run()).isInstanceOf(JobFailure.class);
    assertThatThrownBy(() -> client.transcribe(jobDir.resolve("nope"), List.of(c0), (n, t) -> {}))
        .isInstanceOf(JobFailure.class);
    assertThat(events).isEmpty();
    Mockito.verifyNoInteractions(whisper);
  }

  @Test
  void throwingProgressPropagatesAndWritesNothing() {
    RuntimeException boom = new IllegalStateException("boom");

    assertThatThrownBy(() ->
            client.transcribe(
                jobDir,
                List.of(c0),
                (n, t) -> {
                  throw boom;
                }))
        .isSameAs(boom);
    Mockito.verifyNoInteractions(whisper);
    assertNothingChanged();
  }

  @Test
  void progressComesBeforeEachFirstSendButNotBeforeTheRetry() throws Exception {
    when(whisper.transcribe(c0))
        .thenAnswer(inv -> {
          events.add("send 0");
          throw other();
        })
        .thenAnswer(inv -> {
          events.add("retry 0");
          return "A";
        });
    when(whisper.transcribe(c1))
        .thenAnswer(inv -> {
          events.add("send 1");
          return "B";
        });

    run(c0, c1);

    assertThat(events).containsExactly("1/2", "send 0", "retry 0", "2/2", "send 1");
  }

  @Test
  void successReplacesAnExistingTranscript() throws Exception {
    Files.writeString(jobDir.resolve("transcript.txt"), "Old text\n");
    when(whisper.transcribe(c0)).thenReturn("New text");

    run(c0);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("New text\n");
    verify(whisper, times(1)).transcribe(c0);
  }

  @Test
  void aChunkFailureLeavesAnExistingTranscriptUntouched() throws Exception {
    byte[] old = "Old text\n".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("transcript.txt"), old);
    when(whisper.transcribe(c0)).thenThrow(other(), other());

    assertThatThrownBy(() -> run(c0)).isInstanceOf(JobFailure.class);

    assertThat(Files.readAllBytes(jobDir.resolve("transcript.txt"))).isEqualTo(old);
    assertThat(jobDir.resolve("audio.wav")).exists();
  }

  @Test
  void noSpeechLeavesAnExistingTranscriptUntouched() throws Exception {
    byte[] old = "Old text\n".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("transcript.txt"), old);
    when(whisper.transcribe(c0)).thenReturn(" ");

    assertThatThrownBy(() -> run(c0))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.NO_SPEECH));

    assertThat(Files.readAllBytes(jobDir.resolve("transcript.txt"))).isEqualTo(old);
    assertThat(jobDir.resolve("audio.wav")).exists();
  }

  @Test
  void failedWriteIsFailedAndDeletesNothing() throws Exception {
    when(whisper.transcribe(any())).thenReturn("text");
    Files.createDirectory(jobDir.resolve("transcript.txt"));
    Files.writeString(jobDir.resolve("transcript.txt").resolve("x"), "x");

    assertThatThrownBy(() -> run(c0))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED));
    assertThat(jobDir.resolve("audio.wav")).exists();
    assertThat(c0).exists();
  }

  @Test
  void deletionProblemsDoNotFailTheTranscription() throws Exception {
    when(whisper.transcribe(any())).thenReturn("text");
    Files.delete(c1);
    Files.delete(c2);
    Files.delete(c0);
    Files.writeString(jobDir.resolve("chunks").resolve("keep.txt"), "x");
    Files.createDirectory(jobDir.resolve("chunks").resolve("sub"));
    Files.writeString(jobDir.resolve("chunks").resolve("sub").resolve("y"), "y");

    Path transcript = run(c0);

    assertThat(Files.readString(transcript)).isEqualTo("text\n");
    assertThat(jobDir.resolve("audio.wav")).doesNotExist();
    assertThat(jobDir.resolve("chunks").resolve("keep.txt")).doesNotExist();
    assertThat(jobDir.resolve("chunks")).exists();
  }

  @Test
  void nothingIsPublishedOrChangedWhileALaterAnswerIsPending() throws Exception {
    byte[] source = Files.readAllBytes(jobDir.resolve("source.m4a"));
    when(whisper.transcribe(c0)).thenReturn("First");
    when(whisper.transcribe(c1))
        .thenAnswer(inv -> {
          assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
          assertThat(jobDir.resolve("audio.wav")).hasContent("wav");
          assertThat(c0).hasContent("0");
          assertThat(c1).hasContent("1");
          assertThat(Files.readAllBytes(jobDir.resolve("source.m4a"))).isEqualTo(source);
          events.add("held");
          return "Second";
        });

    run(c0, c1);

    assertThat(events).containsExactly("1/2", "2/2", "held");
    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("First Second\n");
  }

  @Test
  void aFailedReplacementLeavesThePriorTranscriptBytesIntact() throws Exception {
    byte[] old = "Old text\n".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("transcript.txt"), old);
    Path tmp = Files.createDirectory(jobDir.resolve("transcript.txt.tmp"));
    Files.writeString(tmp.resolve("blocker"), "x");
    when(whisper.transcribe(c0)).thenReturn("New text");

    assertThatThrownBy(() -> run(c0))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage("Transcription failed. Please try again later.");
            });

    assertThat(Files.isRegularFile(jobDir.resolve("transcript.txt"))).isTrue();
    assertThat(Files.readAllBytes(jobDir.resolve("transcript.txt"))).isEqualTo(old);
    assertThat(jobDir.resolve("audio.wav")).exists();
    assertThat(c0).exists();
    assertThat(c1).exists();
    assertThat(c2).exists();
  }

  @Test
  void anAudioDeletionFailureStillLetsEveryLaterDeletionRun() throws Exception {
    byte[] source = Files.readAllBytes(jobDir.resolve("source.m4a"));
    byte[] jobJson = "{\"id\":\"j\"}".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("job.json"), jobJson);
    Files.delete(jobDir.resolve("audio.wav"));
    Path audio = Files.createDirectory(jobDir.resolve("audio.wav"));
    Files.writeString(audio.resolve("blocker"), "x");
    when(whisper.transcribe(any())).thenReturn("secret speech");
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(TranscriptionClient.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    Path transcript;
    try {
      transcript = run(c0, c1, c2);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(Files.readString(transcript))
        .isEqualTo("secret speech secret speech secret speech\n");
    assertThat(c0).doesNotExist();
    assertThat(c1).doesNotExist();
    assertThat(c2).doesNotExist();
    assertThat(jobDir.resolve("chunks")).doesNotExist();
    assertThat(audio.resolve("blocker")).exists();
    assertThat(Files.readAllBytes(jobDir.resolve("source.m4a"))).isEqualTo(source);
    assertThat(Files.readAllBytes(jobDir.resolve("job.json"))).isEqualTo(jobJson);
    List<String> warnings =
        appender.list.stream()
            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0))
        .contains("audio.wav")
        .doesNotContain(jobDir.toString())
        .doesNotContain("secret speech");
  }

  @Test
  void missingChunksDirectoryIsOnlyLogged() throws Exception {
    when(whisper.transcribe(any())).thenReturn("text");
    Path loose = Files.writeString(jobDir.resolve("loose.wav"), "x");
    Files.delete(c0);
    Files.delete(c1);
    Files.delete(c2);
    Files.delete(jobDir.resolve("chunks"));

    run(loose);

    assertThat(jobDir.resolve("transcript.txt")).exists();
    assertThat(jobDir.resolve("audio.wav")).doesNotExist();
  }

  private void assertNothingChanged() {
    assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
    assertThat(jobDir.resolve("audio.wav")).exists();
    assertThat(c0).exists();
    assertThat(c1).exists();
    assertThat(c2).exists();
    assertThat(jobDir.resolve("source.m4a")).exists();
  }
}
