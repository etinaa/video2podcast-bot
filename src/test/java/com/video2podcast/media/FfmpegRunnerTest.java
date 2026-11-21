package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobFailure.Kind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegRunnerTest {

  private static final AppProperties.Jobs JOBS =
      new AppProperties.Jobs(
          Path.of("w"),
          Duration.ofHours(3),
          Duration.ofHours(1),
          Duration.ofMinutes(1),
          Duration.ofMinutes(30),
          Duration.ofMinutes(20),
          Duration.ofMinutes(1));

  @TempDir Path dir;

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ProcessResult ok() {
    return new ProcessResult(0, "", "");
  }

  private static void assertKind(Runnable action, Kind kind) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(kind));
  }

  @Test
  void toWavCommandAndResult() {
    Path source = dir.resolve("source.m4a");
    Path wav = dir.resolve("audio.wav");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          write(wav, "RIFF");
          return ok();
        });

    Path result = new FfmpegRunner(runner, JOBS).toWav(source, dir);

    assertThat(result).isEqualTo(wav);
    assertThat(runner.calls.get(0))
        .containsExactly(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            source.toString(),
            "-vn",
            "-ar",
            "16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            wav.toString());
    assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofMinutes(20));
  }

  @Test
  void toWavDeletesStaleWavAndRequiresNewOutput() {
    write(dir.resolve("audio.wav"), "old");
    FakeProcessRunner runner = FakeProcessRunner.returning(ok());

    assertKind(
        () -> new FfmpegRunner(runner, JOBS).toWav(dir.resolve("source.m4a"), dir), Kind.FAILED);

    assertThat(dir.resolve("audio.wav")).doesNotExist();
  }

  @Test
  void toWavRejectsEmptyOutput() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          write(dir.resolve("audio.wav"), "");
          return ok();
        });
    assertKind(() -> new FfmpegRunner(runner, JOBS).toWav(dir.resolve("s.m4a"), dir), Kind.FAILED);
  }

  @Test
  void toWavNonZeroExitIsFailed() {
    FakeProcessRunner runner = FakeProcessRunner.returning(new ProcessResult(1, "", "boom /x"));
    assertThatThrownBy(() -> new FfmpegRunner(runner, JOBS).toWav(dir.resolve("s.m4a"), dir))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(Kind.FAILED);
              assertThat(f.getMessage()).doesNotContain("boom");
            });
  }

  @Test
  void toWavTimeoutPropagates() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          throw JobFailure.of(Kind.TIMEOUT);
        });
    assertKind(() -> new FfmpegRunner(runner, JOBS).toWav(dir.resolve("s.m4a"), dir), Kind.TIMEOUT);
  }

  @Test
  void splitTimeoutPropagatesUnchanged() {
    write(dir.resolve("audio.wav"), "RIFF");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          throw JobFailure.of(Kind.TIMEOUT);
        });

    assertKind(
        () -> new FfmpegRunner(runner, JOBS).split(dir.resolve("audio.wav"), dir), Kind.TIMEOUT);
  }

  @Test
  void splitCommandAndOrderedResult() {
    Path wav = dir.resolve("audio.wav");
    write(wav, "RIFF");
    Path chunks = dir.resolve("chunks");
    write(chunks.resolve("chunk-009.wav"), "stale");
    write(chunks.resolve("keep.txt"), "other");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          assertThat(chunks.resolve("chunk-009.wav")).doesNotExist();
          write(chunks.resolve("chunk-002.wav"), "c");
          write(chunks.resolve("chunk-000.wav"), "a");
          write(chunks.resolve("chunk-001.wav"), "b");
          return ok();
        });

    List<Path> result = new FfmpegRunner(runner, JOBS).split(wav, dir);

    assertThat(result)
        .containsExactly(
            chunks.resolve("chunk-000.wav"),
            chunks.resolve("chunk-001.wav"),
            chunks.resolve("chunk-002.wav"));
    assertThat(chunks.resolve("keep.txt")).exists();
    assertThat(runner.calls.get(0))
        .containsExactly(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            wav.toString(),
            "-f",
            "segment",
            "-segment_time",
            "600",
            "-c",
            "copy",
            chunks + "/chunk-%03d.wav");
    assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofMinutes(20));
  }

  @Test
  void splitWithoutWavIsFailed() {
    FakeProcessRunner runner = FakeProcessRunner.returning(ok());
    assertKind(
        () -> new FfmpegRunner(runner, JOBS).split(dir.resolve("audio.wav"), dir), Kind.FAILED);
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void splitWithNoChunksIsFailed() {
    write(dir.resolve("audio.wav"), "RIFF");
    FakeProcessRunner runner = FakeProcessRunner.returning(ok());
    assertKind(
        () -> new FfmpegRunner(runner, JOBS).split(dir.resolve("audio.wav"), dir), Kind.FAILED);
  }

  @Test
  void splitNonZeroExitIsFailed() {
    write(dir.resolve("audio.wav"), "RIFF");
    FakeProcessRunner runner = FakeProcessRunner.returning(new ProcessResult(1, "", "err"));
    assertKind(
        () -> new FfmpegRunner(runner, JOBS).split(dir.resolve("audio.wav"), dir), Kind.FAILED);
  }

  @Test
  void encodeM4aCommandAndResult() {
    Path source = dir.resolve("source.mp4");
    Path target = dir.resolve("out.m4a");
    write(target, "stale");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          assertThat(target).doesNotExist();
          write(target, "aac");
          return ok();
        });

    Path result = new FfmpegRunner(runner, JOBS).encodeM4a(source, target, 37, true);

    assertThat(result).isEqualTo(target);
    assertThat(runner.calls.get(0))
        .containsExactly(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            source.toString(),
            "-vn",
            "-ac",
            "1",
            "-c:a",
            "aac",
            "-b:a",
            "37k",
            "-movflags",
            "+faststart",
            target.toString());
    assertThat(runner.timeouts.get(0)).isEqualTo(JOBS.ffmpegTimeout());
  }

  @Test
  void encodeM4aKeepsTheChannelsWhenNotMono() {
    Path target = dir.resolve("out.m4a");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          write(target, "aac");
          return ok();
        });

    new FfmpegRunner(runner, JOBS).encodeM4a(dir.resolve("s.webm"), target, 128, false);

    assertThat(runner.calls.get(0)).doesNotContain("-ac").contains("128k");
  }

  @Test
  void encodeM4aFailsOnAnExitCodeAndLeavesNothing() {
    Path target = dir.resolve("out.m4a");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          write(target, "partial");
          return new ProcessResult(1, "", "boom");
        });

    assertKind(
        () -> new FfmpegRunner(runner, JOBS).encodeM4a(dir.resolve("s.webm"), target, 64, true),
        Kind.FAILED);
    assertThat(target).doesNotExist();
  }

  @Test
  void encodeM4aFailsWhenNoOrEmptyOutputIsWritten() {
    Path target = dir.resolve("out.m4a");

    assertKind(
        () ->
            new FfmpegRunner(FakeProcessRunner.returning(ok()), JOBS)
                .encodeM4a(dir.resolve("s.webm"), target, 64, true),
        Kind.FAILED);

    FakeProcessRunner empty =
        new FakeProcessRunner((args, timeout) -> {
          write(target, "");
          return ok();
        });
    assertKind(
        () -> new FfmpegRunner(empty, JOBS).encodeM4a(dir.resolve("s.webm"), target, 64, true),
        Kind.FAILED);
    assertThat(target).doesNotExist();
  }

  @Test
  void encodeM4aPassesATimeoutOn() {
    Path target = dir.resolve("out.m4a");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          write(target, "partial");
          throw JobFailure.of(Kind.TIMEOUT);
        });

    assertKind(
        () -> new FfmpegRunner(runner, JOBS).encodeM4a(dir.resolve("s.webm"), target, 64, true),
        Kind.TIMEOUT);
    assertThat(target).doesNotExist();
  }

  @Test
  void encodeM4aFailsWhenTheTargetCannotBeReplaced() throws IOException {
    Path target = dir.resolve("out.m4a");
    Files.createDirectories(target.resolve("inner"));

    assertKind(
        () ->
            new FfmpegRunner(FakeProcessRunner.returning(ok()), JOBS)
                .encodeM4a(dir.resolve("s.webm"), target, 64, true),
        Kind.FAILED);
  }
}
