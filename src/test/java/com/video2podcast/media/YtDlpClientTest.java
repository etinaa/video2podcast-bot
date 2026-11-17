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

class YtDlpClientTest {

  private static final String URL = "https://example.com/v";

  private static final AppProperties.Jobs JOBS =
      new AppProperties.Jobs(
          Path.of("w"),
          Duration.ofHours(3),
          Duration.ofHours(1),
          Duration.ofMinutes(1),
          Duration.ofMinutes(30),
          Duration.ofMinutes(20));

  @TempDir Path dir;

  private static ProcessResult ok(String stdout) {
    return new ProcessResult(0, stdout, "");
  }

  private static YtDlpClient client(FakeProcessRunner runner) {
    return new YtDlpClient(runner, new UrlValidator(), JOBS);
  }

  private static void assertKind(Runnable action, Kind kind) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(JobFailure.class, f -> assertThat(f.kind()).isEqualTo(kind));
  }

  private static void touch(Path file) {
    try {
      Files.writeString(file, "x");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void metadataCommandAndResult() {
    FakeProcessRunner runner = FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":61}"));

    VideoMetadata meta = client(runner).fetchMetadata(URL);

    assertThat(meta).isEqualTo(new VideoMetadata("T", Duration.ofSeconds(61)));
    assertThat(runner.calls.get(0))
        .containsExactly(
            "yt-dlp",
            "--no-playlist",
            "--playlist-items",
            "1",
            "--no-warnings",
            "--skip-download",
            "--dump-single-json",
            "--",
            URL);
    assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofMinutes(1));
  }

  private static final AppProperties.Jobs TWO_HOURS =
      new AppProperties.Jobs(
          Path.of("w"),
          Duration.ofHours(2),
          Duration.ofHours(1),
          Duration.ofSeconds(7),
          Duration.ofSeconds(11),
          Duration.ofSeconds(13));

  @Test
  void configuredLimitAndTimeoutsAreUsed() {
    FakeProcessRunner at = FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":7200}"));
    YtDlpClient client = new YtDlpClient(at, new UrlValidator(), TWO_HOURS);
    assertThat(client.fetchMetadata(URL).duration()).isEqualTo(Duration.ofHours(2));
    assertThat(at.timeouts).containsExactly(Duration.ofSeconds(7));

    FakeProcessRunner over = FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":7201}"));
    assertThatThrownBy(
            () -> new YtDlpClient(over, new UrlValidator(), TWO_HOURS).fetchMetadata(URL))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(Kind.TOO_LONG);
              assertThat(f).hasMessage("The video is longer than 2 hours.");
            });

    FakeProcessRunner download =
        new FakeProcessRunner((args, timeout) -> {
          touch(dir.resolve("source.m4a"));
          return ok("");
        });
    new YtDlpClient(download, new UrlValidator(), TWO_HOURS).downloadAudio(URL, dir);
    assertThat(download.timeouts).containsExactly(Duration.ofSeconds(11));
  }

  @Test
  void hostileUrlAndSpacedDirectoryStayOneArgumentEach() throws IOException {
    String url = "https://example.com/a?x=1&y=2;z=$(id)&w=%7Cq";
    Path spaced = Files.createDirectory(dir.resolve("my job dir"));
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(spaced.resolve("source.m4a"));
          return ok("{\"title\":\"T\",\"duration\":5}");
        });
    YtDlpClient client = client(runner);

    client.fetchMetadata(url);
    client.downloadAudio(url, spaced);

    for (List<String> args : runner.calls) {
      assertThat(args.get(args.size() - 1)).isEqualTo(url);
      assertThat(args.get(args.size() - 2)).isEqualTo("--");
      assertThat(args.stream().filter(a -> a.contains("$(id)"))).hasSize(1);
    }
    List<String> download = runner.calls.get(1);
    assertThat(download.get(download.indexOf("-o") + 1))
        .isEqualTo(spaced + "/source.%(ext)s")
        .contains("my job dir");
  }

  @Test
  void invalidUrlStartsNoProcess() {
    FakeProcessRunner runner = FakeProcessRunner.returning(ok("{}"));
    assertKind(() -> client(runner).fetchMetadata("http://localhost/x"), Kind.UNSUPPORTED);
    assertKind(() -> client(runner).downloadAudio("http://localhost/x", dir), Kind.UNSUPPORTED);
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void durationRoundsUpAndLimitIsInclusive() {
    assertThat(
            client(FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":10800}")))
                .fetchMetadata(URL)
                .duration())
        .isEqualTo(Duration.ofHours(3));
    assertThat(
            client(FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":59.2}")))
                .fetchMetadata(URL)
                .duration())
        .isEqualTo(Duration.ofSeconds(60));
    FakeProcessRunner tooLong =
        FakeProcessRunner.returning(ok("{\"title\":\"T\",\"duration\":10800.1}"));
    assertThatThrownBy(() -> client(tooLong).fetchMetadata(URL))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(Kind.TOO_LONG);
              assertThat(f).hasMessage("The video is longer than 3 hours.");
            });
  }

  @Test
  void badMetadata() {
    String[] unsupported = {
      "{\"_type\":\"playlist\",\"title\":\"T\",\"duration\":5}",
      "{\"_type\":\"multi_video\",\"title\":\"T\",\"duration\":5}",
      "{\"title\":\"T\"}",
      "{\"title\":\"T\",\"duration\":null}",
      "{\"title\":\"T\",\"duration\":\"5\"}",
      "{\"title\":\"T\",\"duration\":0}",
      "{\"title\":\"T\",\"duration\":-3}",
      "{\"title\":\"T\",\"duration\":5,\"is_live\":true}",
    };
    for (String json : unsupported) {
      assertKind(
          () -> client(FakeProcessRunner.returning(ok(json))).fetchMetadata(URL), Kind.UNSUPPORTED);
    }
    String[] failed = {
      "",
      "not json",
      "[1]",
      "{\"duration\":5}",
      "{\"title\":\" \",\"duration\":5}",
      "{\"title\":3,\"duration\":5}",
      "{\"title\":\"T\",\"duration\":5} trailing",
    };
    for (String json : failed) {
      assertKind(
          () -> client(FakeProcessRunner.returning(ok(json))).fetchMetadata(URL), Kind.FAILED);
    }
  }

  @Test
  void liveStreamHasItsOwnMessage() {
    FakeProcessRunner runner =
        FakeProcessRunner.returning(ok("{\"title\":\"T\",\"is_live\":true,\"duration\":5}"));
    assertThatThrownBy(() -> client(runner).fetchMetadata(URL))
        .hasMessage("Live streams are not supported.");
  }

  @Test
  void liveFalseIsFine() {
    FakeProcessRunner runner =
        FakeProcessRunner.returning(ok("{\"title\":\"T\",\"is_live\":false,\"duration\":5}"));
    assertThat(client(runner).fetchMetadata(URL).duration()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void nonZeroExitIsClassifiedAndHidesStderr() {
    String secret = "ERROR: Private video at /secret/path";
    FakeProcessRunner runner = FakeProcessRunner.returning(new ProcessResult(1, "", secret));
    assertThatThrownBy(() -> client(runner).fetchMetadata(URL))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(Kind.PRIVATE);
              assertThat(f.getMessage()).doesNotContain("secret").doesNotContain(URL);
            });
    FakeProcessRunner unsupported =
        FakeProcessRunner.returning(new ProcessResult(1, "", "Unsupported URL: x"));
    assertKind(() -> client(unsupported).fetchMetadata(URL), Kind.UNSUPPORTED);
    FakeProcessRunner other = FakeProcessRunner.returning(new ProcessResult(2, "", null));
    assertKind(() -> client(other).fetchMetadata(URL), Kind.FAILED);
  }

  @Test
  void timeoutFromRunnerPropagates() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          throw JobFailure.of(Kind.TIMEOUT);
        });
    assertKind(() -> client(runner).fetchMetadata(URL), Kind.TIMEOUT);
  }

  @Test
  void downloadTimeoutPropagatesUnchanged() {
    touch(dir.resolve("source.m4a"));
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          throw JobFailure.of(Kind.TIMEOUT);
        });

    assertKind(() -> client(runner).downloadAudio(URL, dir), Kind.TIMEOUT);

    assertThat(dir.resolve("source.m4a")).doesNotExist();
  }

  @Test
  void warningsOnStderrDoNotFailAnExitZeroRun() {
    FakeProcessRunner runner =
        FakeProcessRunner.returning(
            new ProcessResult(
                0,
                "{\"title\":\"T\",\"duration\":61}",
                "WARNING: private playlist skipped, sign in for more"));

    assertThat(client(runner).fetchMetadata(URL))
        .isEqualTo(new VideoMetadata("T", Duration.ofSeconds(61)));
  }

  @Test
  void downloadWithPrivateWordInStderrStillSucceedsOnExitZero() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(dir.resolve("source.m4a"));
          return new ProcessResult(0, "", "members-only extras are private");
        });

    assertThat(client(runner).downloadAudio(URL, dir)).isEqualTo(dir.resolve("source.m4a"));
  }

  @Test
  void downloadCommandAndResult() {
    Path source = dir.resolve("source.m4a");
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(source);
          return ok("");
        });

    Path result = client(runner).downloadAudio(URL, dir);

    assertThat(result).isEqualTo(source);
    assertThat(runner.calls.get(0))
        .containsExactly(
            "yt-dlp",
            "--no-playlist",
            "--playlist-items",
            "1",
            "--no-warnings",
            "-f",
            "bestaudio[ext=m4a]/bestaudio/best",
            "-o",
            dir + "/source.%(ext)s",
            "--",
            URL);
    assertThat(runner.timeouts.get(0)).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void percentInJobDirIsEscaped() throws IOException {
    Path odd = Files.createDirectory(dir.resolve("a%b%(x)s"));
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(odd.resolve("source.webm"));
          return ok("");
        });

    Path result = client(runner).downloadAudio(URL, odd);

    assertThat(result).isEqualTo(odd.resolve("source.webm"));
    List<String> args = runner.calls.get(0);
    assertThat(args.get(args.indexOf("-o") + 1)).isEqualTo(dir + "/a%%b%%(x)s/source.%(ext)s");
  }

  @Test
  void staleSourceFilesAreRemovedBeforeRunning() {
    touch(dir.resolve("source.m4a"));
    touch(dir.resolve("source.webm.part"));
    touch(dir.resolve("source.m4a.ytdl"));
    touch(dir.resolve("audio.wav"));
    FakeProcessRunner runner = FakeProcessRunner.returning(ok(""));

    assertKind(() -> client(runner).downloadAudio(URL, dir), Kind.FAILED);

    assertThat(dir.resolve("source.m4a")).doesNotExist();
    assertThat(dir.resolve("source.webm.part")).doesNotExist();
    assertThat(dir.resolve("source.m4a.ytdl")).doesNotExist();
    assertThat(dir.resolve("audio.wav")).exists();
  }

  @Test
  void partialFilesDoNotCountAsSuccess() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(dir.resolve("source.webm.part"));
          touch(dir.resolve("source.webm.ytdl"));
          return ok("");
        });
    assertKind(() -> client(runner).downloadAudio(URL, dir), Kind.FAILED);
  }

  @Test
  void moreThanOneSourceIsFailed() {
    FakeProcessRunner runner =
        new FakeProcessRunner((args, timeout) -> {
          touch(dir.resolve("source.m4a"));
          touch(dir.resolve("source.webm"));
          return ok("");
        });
    assertKind(() -> client(runner).downloadAudio(URL, dir), Kind.FAILED);
  }

  @Test
  void downloadNonZeroExitIsClassified() {
    FakeProcessRunner runner = FakeProcessRunner.returning(new ProcessResult(1, "", "sign in"));
    assertKind(() -> client(runner).downloadAudio(URL, dir), Kind.PRIVATE);
  }

  @Test
  void missingJobDirIsFailed() {
    FakeProcessRunner runner = FakeProcessRunner.returning(ok(""));
    assertKind(() -> client(runner).downloadAudio(URL, dir.resolve("nope")), Kind.FAILED);
    assertThat(runner.calls).isEmpty();
  }

  @Test
  void logTextIsTruncatedAndOneLine() {
    String text = YtDlpClient.forLog("a\r\nb" + "c".repeat(5000));
    assertThat(text).hasSize(2000).doesNotContain("\n").doesNotContain("\r");
  }
}
