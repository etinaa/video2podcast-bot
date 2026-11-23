package com.video2podcast.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.support.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredJobsTest {

  private static final Instant T0 = Instant.parse("2026-11-23T10:15:30Z");

  @TempDir Path dir;

  private final MutableClock clock = new MutableClock(T0);

  private Path file() {
    return dir.resolve("deferred-jobs.json");
  }

  private DeferredJobs open() {
    return new DeferredJobs(file(), clock);
  }

  private static JobRequest request(String id, String url) {
    return new JobRequest(id, 7, 555, url, T0);
  }

  private List<Path> corruptFiles() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().contains(".corrupt-")).toList();
    }
  }

  @Test
  void aMissingFileIsAnEmptyQueue() {
    assertThat(open().entries()).isEmpty();
    assertThat(file()).doesNotExist();
  }

  @Test
  void parkAppendsWithZeroAttemptsAndTheCurrentTime() {
    DeferredJobs jobs = open();
    clock.advance(Duration.ofMinutes(5));

    DeferredJob parked = jobs.park(request("a", "https://v/1"), 42);

    assertThat(parked)
        .isEqualTo(new DeferredJob("a", "https://v/1", 7, 555, 42, 0, T0.plusSeconds(300)));
    assertThat(jobs.entries()).containsExactly(parked);
    assertThat(jobs.find("a")).contains(parked);
    assertThat(jobs.find("zzz")).isEmpty();
  }

  @Test
  void entriesKeepParkingOrderAndSurviveARestart() {
    DeferredJobs jobs = open();
    jobs.park(request("a", "https://v/1"), 1);
    jobs.park(request("b", "https://v/2"), 2);
    jobs.park(request("c", "https://v/3"), 3);
    jobs.recordFailedAttempt("b");
    jobs.recordFailedAttempt("b");

    DeferredJobs reopened = open();

    assertThat(reopened.entries()).extracting(DeferredJob::id).containsExactly("a", "b", "c");
    assertThat(reopened.find("b").orElseThrow().attempts()).isEqualTo(2);
    assertThat(reopened.find("a").orElseThrow().attempts()).isZero();
    assertThat(reopened.entries()).isEqualTo(jobs.entries());
  }

  @Test
  void recordFailedAttemptKeepsThePlaceAndTheOtherFields() {
    DeferredJobs jobs = open();
    jobs.park(request("a", "https://v/1"), 1);
    jobs.park(request("b", "https://v/2"), 2);

    DeferredJob updated = jobs.recordFailedAttempt("a").orElseThrow();

    assertThat(updated.attempts()).isEqualTo(1);
    assertThat(updated.url()).isEqualTo("https://v/1");
    assertThat(updated.parkedAt()).isEqualTo(T0);
    assertThat(jobs.entries()).extracting(DeferredJob::id).containsExactly("a", "b");
    assertThat(jobs.recordFailedAttempt("gone")).isEmpty();
  }

  @Test
  void removeDeletesOneEntryAndReportsAMissingOne() {
    DeferredJobs jobs = open();
    jobs.park(request("a", "https://v/1"), 1);
    jobs.park(request("b", "https://v/2"), 2);

    assertThat(jobs.remove("a")).isTrue();
    assertThat(jobs.remove("a")).isFalse();

    assertThat(open().entries()).extracting(DeferredJob::id).containsExactly("b");
  }

  @Test
  void theFileIsPrettyJsonWithAJobsArray() throws IOException {
    open().park(request("a", "https://v/1"), 42);

    String json = Files.readString(file());

    assertThat(json)
        .contains("\"jobs\"", "\"id\" : \"a\"", "\"ownerUserId\" : 7", "\"statusMessageId\" : 42")
        .contains("\"parkedAt\" : \"2026-11-23T10:15:30Z\"");
  }

  @Test
  void anEmptyJobsListOrNoJobsFieldIsValid() throws IOException {
    Files.writeString(file(), "{}");
    assertThat(open().entries()).isEmpty();
    Files.writeString(file(), "{\"jobs\": []}");
    assertThat(open().entries()).isEmpty();
    Files.writeString(file(), "{\"jobs\": null}");
    assertThat(open().entries()).isEmpty();
    assertThat(corruptFiles()).isEmpty();
  }

  @Test
  void aCorruptFileIsRenamedAndTheQueueStartsEmpty() throws IOException {
    for (String bad :
        List.of(
            "not json",
            "{\"jobs\": []} trailing",
            "null",
            "",
            entry("\"\"", "\"u\"", 1, 1, 1, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "\" \"", 1, 1, 1, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "\"u\"", 0, 1, 1, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "\"u\"", 1, 0, 1, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "\"u\"", 1, 1, 0, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "\"u\"", 1, 1, 1, "null", 0),
            entry("\"a\"", "\"u\"", 1, 1, 1, "\"2026-11-23T10:15:30Z\"", -1),
            entry("null", "\"u\"", 1, 1, 1, "\"2026-11-23T10:15:30Z\"", 0),
            entry("\"a\"", "null", 1, 1, 1, "\"2026-11-23T10:15:30Z\"", 0),
            "{\"jobs\": [null]}")) {
      Files.writeString(file(), bad);

      DeferredJobs jobs = open();

      assertThat(jobs.entries()).as(bad).isEmpty();
      assertThat(file()).doesNotExist();
      List<Path> aside = corruptFiles();
      assertThat(aside).hasSize(1);
      assertThat(aside.get(0).getFileName().toString())
          .isEqualTo("deferred-jobs.json.corrupt-" + T0.getEpochSecond());
      assertThat(Files.readString(aside.get(0))).isEqualTo(bad);
      Files.delete(aside.get(0));
    }
  }

  private static String entry(
      String id, String url, long owner, long chat, int status, String parkedAt, int attempts) {
    return "{\"jobs\": [{\"id\": %s, \"url\": %s, \"ownerUserId\": %d, \"chatId\": %d,"
            .formatted(id, url, owner, chat)
        + " \"statusMessageId\": %d, \"attempts\": %d, \"parkedAt\": %s}]}"
            .formatted(status, attempts, parkedAt);
  }

  @Test
  void aCorruptFileDoesNotStopTheNextWrite() throws IOException {
    Files.writeString(file(), "garbage");
    DeferredJobs jobs = open();

    jobs.park(request("a", "https://v/1"), 1);

    assertThat(open().entries()).extracting(DeferredJob::id).containsExactly("a");
  }

  @Test
  void aFileThatCannotBeReadFailsTheStart() throws IOException {
    Files.createDirectory(file());

    assertThatThrownBy(this::open)
        .isInstanceOf(DeferredJobsException.class)
        .hasMessageContaining("Cannot read")
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  void aFailedWriteLeavesTheQueueAndTheFileAsTheyWere() throws IOException {
    DeferredJobs jobs = open();
    jobs.park(request("a", "https://v/1"), 1);
    String before = Files.readString(file());
    // a directory where the temporary file must go makes every write fail
    Files.createDirectory(dir.resolve("deferred-jobs.json.tmp"));

    assertThatThrownBy(() -> jobs.park(request("b", "https://v/2"), 2))
        .isInstanceOf(DeferredJobsException.class)
        .hasMessageContaining("Cannot write");
    assertThatThrownBy(() -> jobs.recordFailedAttempt("a"))
        .isInstanceOf(DeferredJobsException.class);
    assertThatThrownBy(() -> jobs.remove("a")).isInstanceOf(DeferredJobsException.class);

    assertThat(jobs.entries()).extracting(DeferredJob::id).containsExactly("a");
    assertThat(jobs.find("a").orElseThrow().attempts()).isZero();
    assertThat(Files.readString(file())).isEqualTo(before);
  }

  @Test
  void entriesIsAnImmutableCopy() {
    DeferredJobs jobs = open();
    jobs.park(request("a", "https://v/1"), 1);
    List<DeferredJob> snapshot = jobs.entries();

    jobs.park(request("b", "https://v/2"), 2);

    assertThat(snapshot).hasSize(1);
    assertThatThrownBy(() -> snapshot.add(snapshot.get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void parallelWritersLoseNothing() throws Exception {
    DeferredJobs jobs = open();
    int threads = 8;
    int each = 10;
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int base = t * each;
        futures.add(
            pool.submit(() -> {
              for (int i = 0; i < each; i++) {
                jobs.park(request("j" + (base + i), "https://v/" + (base + i)), base + i + 1);
              }
            }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(jobs.entries()).hasSize(threads * each);
    assertThat(open().entries()).isEqualTo(jobs.entries());
  }
}
