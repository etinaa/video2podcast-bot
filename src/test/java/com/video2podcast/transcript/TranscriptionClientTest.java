package com.video2podcast.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.video2podcast.job.JobFailure;
import com.video2podcast.job.Language;
import com.video2podcast.transcript.TranscriptionClient.Transcription;
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
    return client
        .transcribe(jobDir, List.of(chunks), (n, t) -> events.add(n + "/" + t))
        .transcript();
  }

  /** An answer of Whisper as the client delivers it: detected English when there is text. */
  private static WhisperResult r(String text) {
    return new WhisperResult(text, TranscriptMerger.normalize(text).isEmpty() ? null : "en");
  }

  private static WhisperException connection() {
    return new WhisperException("no connection", true, null);
  }

  private static WhisperException other() {
    return new WhisperException("bad", false, null);
  }

  @Test
  void writesMergedTranscriptAndRemovesTemporaryAudio() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r(" First\n"));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(r("   "));
    when(whisper.transcribe(eq(c2), anyString())).thenReturn(r("Last "));

    Path transcript = run(c0, c1, c2);

    assertThat(transcript).isEqualTo(jobDir.resolve("transcript.txt"));
    assertThat(Files.readString(transcript, StandardCharsets.UTF_8)).isEqualTo("First Last\n");
    assertThat(events).containsExactly("1/3", "2/3", "3/3");
    assertThat(jobDir.resolve("audio.wav")).doesNotExist();
    assertThat(jobDir.resolve("chunks")).doesNotExist();
    assertThat(jobDir.resolve("source.m4a")).exists();
    verify(whisper, times(1)).transcribe(eq(c1), anyString());
  }

  @Test
  void retriesOnceAndKeepsGoing() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenThrow(other()).thenReturn(r("A"));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(r("B"));

    run(c0, c1);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("A B\n");
    assertThat(events).containsExactly("1/2", "2/2");
    InOrder order = Mockito.inOrder(whisper);
    order.verify(whisper, times(2)).transcribe(eq(c0), anyString());
    order.verify(whisper).transcribe(eq(c1), anyString());
  }

  @Test
  void aConnectionErrorThenSuccessKeepsEarlierChunksAndOrderWithOneProgressEventPerChunk()
      throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("A"));
    when(whisper.transcribe(eq(c1), anyString())).thenThrow(connection()).thenReturn(r("B"));
    when(whisper.transcribe(eq(c2), anyString())).thenReturn(r("C"));

    run(c0, c1, c2);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("A B C\n");
    assertThat(events).containsExactly("1/3", "2/3", "3/3");
    InOrder order = Mockito.inOrder(whisper);
    order.verify(whisper).transcribe(eq(c0), anyString());
    order.verify(whisper, times(2)).transcribe(eq(c1), anyString());
    order.verify(whisper).transcribe(eq(c2), anyString());
    Mockito.verifyNoMoreInteractions(whisper);
  }

  @Test
  void emptyAnswerOnRetryIsValid() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenThrow(other()).thenReturn(r(" "));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(r("Speech"));

    run(c0, c1);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("Speech\n");
  }

  @Test
  void secondFailureDecidesTheKind() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("ok"));
    when(whisper.transcribe(eq(c1), anyString())).thenThrow(connection(), other());

    assertThatThrownBy(() -> run(c0, c1, c2))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage("Transcription failed. Please try again later.");
            });
    verify(whisper, never()).transcribe(eq(c2), anyString());
    assertThat(events).containsExactly("1/3", "2/3");
    assertNothingChanged();
  }

  @Test
  void connectionFailureOnRetryIsServiceUnreachable() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenThrow(other(), connection());

    assertThatThrownBy(() -> run(c0, c1))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.SERVICE_UNREACHABLE);
              assertThat(f).hasMessage("The transcription server is unreachable.");
            });
    verify(whisper, times(2)).transcribe(any(), anyString());
    assertNothingChanged();
  }

  @Test
  void entirelyEmptySpeechIsNoSpeech() throws Exception {
    when(whisper.transcribe(any(), anyString())).thenReturn(r(""), r(" \n"));

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
    when(whisper.transcribe(eq(c0), anyString()))
        .thenAnswer(inv -> {
          events.add("send 0");
          throw other();
        })
        .thenAnswer(inv -> {
          events.add("retry 0");
          return r("A");
        });
    when(whisper.transcribe(eq(c1), anyString()))
        .thenAnswer(inv -> {
          events.add("send 1");
          return r("B");
        });

    run(c0, c1);

    assertThat(events).containsExactly("1/2", "send 0", "retry 0", "2/2", "send 1");
  }

  @Test
  void successReplacesAnExistingTranscript() throws Exception {
    Files.writeString(jobDir.resolve("transcript.txt"), "Old text\n");
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("New text"));

    run(c0);

    assertThat(Files.readString(jobDir.resolve("transcript.txt"))).isEqualTo("New text\n");
    verify(whisper, times(1)).transcribe(eq(c0), anyString());
  }

  @Test
  void aChunkFailureLeavesAnExistingTranscriptUntouched() throws Exception {
    byte[] old = "Old text\n".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("transcript.txt"), old);
    when(whisper.transcribe(eq(c0), anyString())).thenThrow(other(), other());

    assertThatThrownBy(() -> run(c0)).isInstanceOf(JobFailure.class);

    assertThat(Files.readAllBytes(jobDir.resolve("transcript.txt"))).isEqualTo(old);
    assertThat(jobDir.resolve("audio.wav")).exists();
  }

  @Test
  void noSpeechLeavesAnExistingTranscriptUntouched() throws Exception {
    byte[] old = "Old text\n".getBytes(StandardCharsets.UTF_8);
    Files.write(jobDir.resolve("transcript.txt"), old);
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r(" "));

    assertThatThrownBy(() -> run(c0))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(JobFailure.Kind.NO_SPEECH));

    assertThat(Files.readAllBytes(jobDir.resolve("transcript.txt"))).isEqualTo(old);
    assertThat(jobDir.resolve("audio.wav")).exists();
  }

  @Test
  void failedWriteIsFailedAndDeletesNothing() throws Exception {
    when(whisper.transcribe(any(), anyString())).thenReturn(r("text"));
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
    when(whisper.transcribe(any(), anyString())).thenReturn(r("text"));
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
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("First"));
    when(whisper.transcribe(eq(c1), anyString()))
        .thenAnswer(inv -> {
          assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
          assertThat(jobDir.resolve("audio.wav")).hasContent("wav");
          assertThat(c0).hasContent("0");
          assertThat(c1).hasContent("1");
          assertThat(Files.readAllBytes(jobDir.resolve("source.m4a"))).isEqualTo(source);
          events.add("held");
          return r("Second");
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
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("New text"));

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
    when(whisper.transcribe(any(), anyString())).thenReturn(r("secret speech"));
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
    when(whisper.transcribe(any(), anyString())).thenReturn(r("text"));
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

  // ---- language ----

  private static WhisperResult ru(String text) {
    return new WhisperResult(text, TranscriptMerger.normalize(text).isEmpty() ? null : "ru");
  }

  private Transcription runFull(Path... chunks) {
    return client.transcribe(jobDir, List.of(chunks), (n, t) -> events.add(n + "/" + t));
  }

  @Test
  void theFirstChunkIsDetectedAndTheRestArePinned() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(ru("Первая"));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(new WhisperResult("Вторая", null));
    when(whisper.transcribe(eq(c2), anyString())).thenReturn(new WhisperResult("Третья", null));

    Transcription result = runFull(c0, c1, c2);

    assertThat(result.language()).isEqualTo(Language.RU);
    assertThat(result.transcript()).isEqualTo(jobDir.resolve("transcript.txt"));
    assertThat(jobDir.resolve("transcript.txt")).hasContent("Первая Вторая Третья\n");
    verify(whisper).transcribe(c0, "auto");
    verify(whisper).transcribe(c1, "ru");
    verify(whisper).transcribe(c2, "ru");
  }

  @Test
  void englishIsSelectedAndPinned() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(r("One"));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(new WhisperResult("Two", null));

    Transcription result = runFull(c0, c1);

    assertThat(result.language()).isEqualTo(Language.EN);
    verify(whisper).transcribe(c1, "en");
  }

  @Test
  void aLaterAnswerCannotChangeTheSelectedLanguage() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(ru("Первая"));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(new WhisperResult("Two", "de"));
    when(whisper.transcribe(eq(c2), anyString())).thenReturn(new WhisperResult("Три", "en"));

    Transcription result = runFull(c0, c1, c2);

    assertThat(result.language()).isEqualTo(Language.RU);
    verify(whisper).transcribe(c2, "ru");
  }

  @Test
  void aSilentStartStaysInDetectMode() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(new WhisperResult("  ", null));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(ru("Речь"));
    when(whisper.transcribe(eq(c2), anyString())).thenReturn(new WhisperResult("Конец", null));

    Transcription result = runFull(c0, c1, c2);

    assertThat(result.language()).isEqualTo(Language.RU);
    verify(whisper).transcribe(c0, "auto");
    verify(whisper).transcribe(c1, "auto");
    verify(whisper).transcribe(c2, "ru");
    assertThat(jobDir.resolve("transcript.txt")).hasContent("Речь Конец\n");
  }

  @Test
  void aLanguageOtherThanEnglishAndRussianIsRefusedAtOnce() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(new WhisperResult("Rede", "de"));

    assertThatThrownBy(() -> runFull(c0, c1, c2))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.UNSUPPORTED_LANGUAGE);
              assertThat(f.getMessage())
                  .isEqualTo(
                      "Only English and Russian videos are supported. Detected language: de.");
            });

    verify(whisper, never()).transcribe(eq(c1), anyString());
    verify(whisper, never()).transcribe(eq(c2), anyString());
    verify(whisper, times(1)).transcribe(any(), anyString());
    assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
    assertThat(events).containsExactly("1/3");
  }

  @Test
  void aRefusalAfterASilentStartComesFromTheFirstChunkWithText() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenReturn(new WhisperResult("", null));
    when(whisper.transcribe(eq(c1), anyString())).thenReturn(new WhisperResult("Rede", "es"));

    assertThatThrownBy(() -> runFull(c0, c1, c2))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.getMessage()).endsWith("Detected language: es."));
    verify(whisper, never()).transcribe(eq(c2), anyString());
  }

  @Test
  void aFailedDetectionChunkIsRetriedInDetectMode() throws Exception {
    when(whisper.transcribe(eq(c0), anyString())).thenThrow(other()).thenReturn(ru("Речь"));
    when(whisper.transcribe(eq(c1), anyString()))
        .thenThrow(other())
        .thenReturn(new WhisperResult("Две", null));

    runFull(c0, c1);

    verify(whisper, times(2)).transcribe(c0, "auto");
    verify(whisper, times(2)).transcribe(c1, "ru");
  }
}
