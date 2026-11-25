package com.video2podcast.transcript;

import com.video2podcast.job.JobFailure;
import com.video2podcast.job.Language;
import com.video2podcast.storage.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns the WAV chunks of a job into {@code transcript.txt} through whisper-server. */
public class TranscriptionClient {

  private static final Logger LOG = LoggerFactory.getLogger(TranscriptionClient.class);

  private final WhisperClient whisper;

  public TranscriptionClient(WhisperClient whisper) {
    this.whisper = whisper;
  }

  /**
   * Transcribes {@code chunks} in order and writes {@code <jobDir>/transcript.txt}. On success
   * deletes {@code <jobDir>/audio.wav} and {@code <jobDir>/chunks/}; keeps source.*.
   *
   * @param progress called as accept(n, total) before chunk n is first sent (n from 1), once per
   *     chunk, not again on the retry
   *     <p>Chunks are sent in detect mode until one has text; its language decides: en and ru are
   *     selected and every later chunk is pinned to it, anything else is refused at once.
   * @return the path of transcript.txt and the selected language
   * @throws NullPointerException if an argument is null
   * @throws JobFailure NO_SPEECH, UNSUPPORTED_LANGUAGE, SERVICE_UNREACHABLE or FAILED
   */
  public Transcription transcribe(Path jobDir, List<Path> chunks, ProgressListener progress) {
    Objects.requireNonNull(jobDir, "jobDir");
    Objects.requireNonNull(chunks, "chunks");
    Objects.requireNonNull(progress, "progress");
    if (chunks.isEmpty() || !Files.isDirectory(jobDir)) {
      throw JobFailure.transcriptionFailed(null);
    }
    List<String> texts = new ArrayList<>(chunks.size());
    int total = chunks.size();
    Language language = null;
    for (int i = 0; i < total; i++) {
      progress.accept(i + 1, total);
      String mode = language == null ? WhisperClient.AUTO : language.code();
      WhisperResult result = transcribeWithRetry(chunks.get(i), mode);
      texts.add(result.text());
      if (language == null && result.language() != null) {
        language =
            Language.fromCode(result.language())
                .orElseThrow(() -> JobFailure.unsupportedLanguage(result.language()));
      }
    }
    String merged = TranscriptMerger.merge(texts);
    if (merged.isEmpty()) {
      throw JobFailure.of(JobFailure.Kind.NO_SPEECH);
    }
    Path transcript = jobDir.resolve("transcript.txt");
    try {
      AtomicFiles.write(transcript, merged.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw JobFailure.transcriptionFailed(e);
    }
    deleteTemporaryAudio(jobDir);
    return new Transcription(transcript, language);
  }

  private WhisperResult transcribeWithRetry(Path chunk, String language) {
    try {
      return whisper.transcribe(chunk, language);
    } catch (WhisperException first) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Chunk {} failed, retrying: {}", chunk.getFileName(), first.getMessage());
      }
    }
    try {
      return whisper.transcribe(chunk, language);
    } catch (WhisperException second) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Chunk {} failed again: {}", chunk.getFileName(), second.getMessage());
      }
      if (second.isConnection()) {
        throw JobFailure.of(JobFailure.Kind.SERVICE_UNREACHABLE, second);
      }
      throw JobFailure.transcriptionFailed(second);
    }
  }

  private static void deleteTemporaryAudio(Path jobDir) {
    delete(jobDir.resolve("audio.wav"));
    Path chunkDir = jobDir.resolve("chunks");
    try (Stream<Path> files = Files.list(chunkDir)) {
      files.forEach(TranscriptionClient::delete);
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not list {}: {}", chunkDir.getFileName(), e.getClass().getSimpleName());
      }
    }
    delete(chunkDir);
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not delete {}: {}", file.getFileName(), e.getClass().getSimpleName());
      }
    }
  }

  /** The transcript file and the language that decided its chunks. */
  public record Transcription(Path transcript, Language language) {}

  @FunctionalInterface
  public interface ProgressListener {
    void accept(int n, int total);
  }
}
