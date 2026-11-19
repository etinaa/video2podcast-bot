package com.video2podcast.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobFailure.Kind;
import com.video2podcast.media.PreparedAudio.Mode;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AudioPreparerTest {

  private static final long LIMIT = 50L * 1024 * 1024;
  private static final AppProperties.Jobs JOBS =
      new AppProperties.Jobs(
          Path.of("w"),
          Duration.ofHours(3),
          Duration.ofHours(1),
          Duration.ofMinutes(1),
          Duration.ofMinutes(30),
          Duration.ofMinutes(20));

  @TempDir Path dir;

  private static void sized(Path file, long size) {
    try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
      f.setLength(size);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path source(String name, long size) {
    Path file = dir.resolve(name);
    sized(file, size);
    return file;
  }

  /** An ffmpeg that writes the target with a size chosen from the bitrate in the command. */
  private FakeProcessRunner ffmpeg(ToLongFunction<Integer> sizeForKbps) {
    return new FakeProcessRunner((args, timeout) -> {
      String bitrate = args.get(args.indexOf("-b:a") + 1);
      int kbps = Integer.parseInt(bitrate.replace("k", ""));
      sized(Path.of(args.get(args.size() - 1)), sizeForKbps.applyAsLong(kbps));
      return new ProcessResult(0, "", "");
    });
  }

  private AudioPreparer preparer(FakeProcessRunner runner) {
    return new AudioPreparer(new FfmpegRunner(runner, JOBS));
  }

  private static List<String> kbpsOf(FakeProcessRunner runner) {
    return runner.calls.stream().map(args -> args.get(args.indexOf("-b:a") + 1)).toList();
  }

  private static boolean mono(List<String> args) {
    return args.contains("-ac");
  }

  @ParameterizedTest
  @CsvSource({"source.mp3", "source.m4a", "SOURCE.MP3", "Source.M4A"})
  void aSmallMp3OrM4aIsSentUnchanged(String name) {
    Path source = source(name, 1000);
    FakeProcessRunner runner = ffmpeg(kbps -> 10);

    PreparedAudio result = preparer(runner).prepare(source, 60);

    assertThat(result.mode()).isEqualTo(Mode.UNCHANGED);
    assertThat(result.file()).isSameAs(source);
    assertThat(result.kbps()).isZero();
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void anMp3ExactlyAtTheLimitIsUnchanged() {
    Path source = source("source.mp3", LIMIT);
    FakeProcessRunner runner = ffmpeg(kbps -> 10);

    assertThat(preparer(runner).prepare(source, 60).mode()).isEqualTo(Mode.UNCHANGED);
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void anMp3OneByteOverTheLimitIsCompressed() {
    Path source = source("source.mp3", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> 1000);

    PreparedAudio result = preparer(runner).prepare(source, 60);

    assertThat(result.mode()).isEqualTo(Mode.COMPRESSED);
    assertThat(result.kbps()).isEqualTo(128);
    assertThat(mono(runner.calls.get(0))).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"source.mp4", "source.webm", "source.opus", "source.mp3x", "source"})
  void aSmallSourceInAnotherFormatIsConvertedAt128Kbps(String name) {
    Path source = source(name, 1000);
    FakeProcessRunner runner = ffmpeg(kbps -> 5000);

    PreparedAudio result = preparer(runner).prepare(source, 60);

    assertThat(result.mode()).isEqualTo(Mode.CONVERTED);
    assertThat(result.kbps()).isEqualTo(128);
    assertThat(result.file()).isEqualTo(dir.resolve("out.m4a"));
    assertThat(kbpsOf(runner)).containsExactly("128k");
    List<String> args = runner.calls.get(0);
    assertThat(mono(args)).isFalse();
    assertThat(args).contains("-vn", "aac", "+faststart").contains(source.toString());
    assertThat(source).exists();
  }

  @Test
  void aConvertedOutputExactlyAtTheLimitFallsBackToCompression() {
    Path source = source("source.webm", 1000);
    // the first call (128k, stereo) is oversized; the compression call (mono) is small
    FakeProcessRunner sequence =
        new FakeProcessRunner((args, timeout) -> {
          boolean stereo = !mono(args);
          sized(Path.of(args.get(args.size() - 1)), stereo ? LIMIT : 5000);
          return new ProcessResult(0, "", "");
        });

    PreparedAudio result = preparer(sequence).prepare(source, 3600);

    assertThat(result.mode()).isEqualTo(Mode.COMPRESSED);
    assertThat(result.kbps()).isEqualTo(111);
    assertThat(kbpsOf(sequence)).containsExactly("128k", "111k");
  }

  @ParameterizedTest
  @CsvSource({
    "10800, 37",
    "3600, 111",
    "60, 128",
    "1, 128",
    "0, 128",
    "-5, 128",
    "393216, 8",
    "5000000, 8",
    "33554, 12"
  })
  void theBitrateFitsTheDurationIntoTheTarget(long seconds, int kbps) {
    assertThat(AudioPreparer.targetKbps(seconds)).isEqualTo(kbps);
  }

  @Test
  void anOversizedSourceIsCompressedToMonoAtTheComputedBitrate() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> 40_000_000L);

    PreparedAudio result = preparer(runner).prepare(source, 10_800);

    assertThat(result.mode()).isEqualTo(Mode.COMPRESSED);
    assertThat(result.kbps()).isEqualTo(37);
    assertThat(result.file()).isEqualTo(dir.resolve("out.m4a"));
    assertThat(kbpsOf(runner)).containsExactly("37k");
    assertThat(mono(runner.calls.get(0))).isTrue();
  }

  @Test
  void anOutputOverTheLimitIsRetriedOnceAtEightyPercent() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> kbps == 37 ? LIMIT : LIMIT - 1);

    PreparedAudio result = preparer(runner).prepare(source, 10_800);

    assertThat(result.mode()).isEqualTo(Mode.COMPRESSED);
    assertThat(result.kbps()).isEqualTo(29);
    assertThat(kbpsOf(runner)).containsExactly("37k", "29k");
  }

  @Test
  void aRetryAt128GoesTo102() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> kbps == 128 ? LIMIT + 1 : 1000);

    PreparedAudio result = preparer(runner).prepare(source, 60);

    assertThat(result.kbps()).isEqualTo(102);
  }

  @Test
  void aSecondOversizedOutputFailsAndLeavesNoOutput() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> LIMIT + 5);

    assertThatThrownBy(() -> preparer(runner).prepare(source, 10_800))
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED));

    assertThat(kbpsOf(runner)).containsExactly("37k", "29k");
    assertThat(dir.resolve("out.m4a")).doesNotExist();
    assertThat(source).exists();
  }

  @Test
  void atTheFloorThereIsNoLowerRetry() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> LIMIT);

    assertThatThrownBy(() -> preparer(runner).prepare(source, 5_000_000))
        .isInstanceOf(JobFailure.class);

    assertThat(kbpsOf(runner)).containsExactly("8k");
    assertThat(dir.resolve("out.m4a")).doesNotExist();
  }

  @Test
  void aNonPositiveDurationIsTreatedAsOneSecond() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> 1000);

    assertThat(preparer(runner).prepare(source, -3).kbps()).isEqualTo(128);
  }

  @Test
  void anEncodingErrorIsAFailureWithoutOutput() {
    Path source = source("source.webm", 1000);
    FakeProcessRunner runner =
        FakeProcessRunner.returning(new ProcessResult(1, "", "Invalid data found"));

    assertThatThrownBy(() -> preparer(runner).prepare(source, 60))
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED))
        .hasMessageNotContaining("Invalid data");

    assertThat(dir.resolve("out.m4a")).doesNotExist();
  }

  @Test
  void aTimeoutIsPassedOnAndLeavesNoOutput() {
    Path source = source("source.webm", 1000);
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          sized(Path.of(args.get(args.size() - 1)), 10);
          throw JobFailure.of(Kind.TIMEOUT);
        });

    assertThatThrownBy(() -> preparer(runner).prepare(source, 60))
        .isInstanceOfSatisfying(
            JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.TIMEOUT));

    assertThat(dir.resolve("out.m4a")).doesNotExist();
  }

  @Test
  void aMissingSourceFails() {
    FakeProcessRunner runner = ffmpeg(kbps -> 10);

    assertThatThrownBy(() -> preparer(runner).prepare(dir.resolve("source.m4a"), 60))
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(Kind.FAILED));
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void aSecondPreparationReplacesAnEarlierOutput() throws IOException {
    Path source = source("source.webm", 1000);
    Files.writeString(dir.resolve("out.m4a"), "old");
    FakeProcessRunner runner = ffmpeg(kbps -> 2000);

    PreparedAudio result = preparer(runner).prepare(source, 60);

    assertThat(Files.size(result.file())).isEqualTo(2000);
  }

  @Test
  void aPathWithoutAFileNameIsNotTakenForAnMp3() {
    FakeProcessRunner runner = FakeProcessRunner.returning(new ProcessResult(1, "", ""));

    assertThatThrownBy(() -> preparer(runner).prepare(Path.of("/"), 60))
        .isInstanceOf(JobFailure.class);

    assertThat(runner.calls).hasSize(1);
  }

  @Test
  void theRetryNeverGoesBelowTheFloor() {
    Path source = source("source.m4a", LIMIT + 1);
    FakeProcessRunner runner = ffmpeg(kbps -> kbps == 9 ? LIMIT : 1000);

    PreparedAudio result = preparer(runner).prepare(source, 44_739);

    assertThat(kbpsOf(runner)).containsExactly("9k", "8k");
    assertThat(result.kbps()).isEqualTo(8);
  }
}
