package com.video2podcast.transcript;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TranscriptionClient with the real WhisperClient against a WireMock server. */
class TranscriptionAssembledTest {

  private static final Pattern FILENAME = Pattern.compile("filename=\"([^\"]+)\"");
  private static final String FAILED_MESSAGE = "Transcription failed. Please try again later.";

  @TempDir Path jobDir;

  private WireMockServer server;
  private final List<String> progress = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    Files.writeString(jobDir.resolve("source.m4a"), "source");
    Files.writeString(jobDir.resolve("audio.wav"), "wav");
    Files.createDirectory(jobDir.resolve("chunks"));
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private TranscriptionClient client(Duration timeout) {
    return new TranscriptionClient(
        new WhisperClient(new AppProperties.Whisper("http://localhost:" + server.port(), timeout)));
  }

  private Path chunk(String name, int size) throws IOException {
    return Files.writeString(
        jobDir.resolve("chunks").resolve(name), "x".repeat(size), StandardCharsets.US_ASCII);
  }

  private void answer(String chunkName, String text, int delayMillis) {
    server.stubFor(
        post(urlEqualTo("/inference"))
            .withRequestBody(containing("filename=\"" + chunkName + "\""))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"text\":\"" + text + "\"}")
                    .withFixedDelay(delayMillis)));
  }

  private Path run(TranscriptionClient client, List<Path> chunks) {
    return client.transcribe(jobDir, chunks, (n, t) -> progress.add(n + "/" + t));
  }

  /** The chunk file names in the order the server received them. */
  private List<String> received() {
    List<ServeEvent> events = new ArrayList<>(server.getAllServeEvents());
    Collections.reverse(events);
    List<String> names = new ArrayList<>();
    for (ServeEvent event : events) {
      Matcher matcher =
          FILENAME.matcher(new String(event.getRequest().getBody(), StandardCharsets.ISO_8859_1));
      assertThat(matcher.find()).isTrue();
      names.add(matcher.group(1));
    }
    return names;
  }

  @Test
  void twoDelayedAnswersEndInFailedAfterExactlyTwoAttempts() throws IOException {
    Path c0 = chunk("chunk-000.wav", 10);
    Path c1 = chunk("chunk-001.wav", 10);
    answer("chunk-000.wav", "late", 1500);

    assertThatThrownBy(() -> run(client(Duration.ofMillis(300)), List.of(c0, c1)))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage(FAILED_MESSAGE);
            });

    assertThat(received()).containsExactly("chunk-000.wav", "chunk-000.wav");
    assertThat(progress).containsExactly("1/2");
    assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
    assertThat(jobDir.resolve("audio.wav")).exists();
  }

  @Test
  void theRetryAndTheNextChunkEachGetAFreshAnswerBudget() throws IOException {
    Path c0 = chunk("chunk-000.wav", 10);
    Path c1 = chunk("chunk-001.wav", 10);
    server.stubFor(
        post(urlEqualTo("/inference"))
            .inScenario("c0")
            .whenScenarioStateIs(Scenario.STARTED)
            .withRequestBody(containing("filename=\"chunk-000.wav\""))
            .willSetStateTo("retry")
            .willReturn(
                aResponse().withStatus(200).withBody("{\"text\":\"late\"}").withFixedDelay(2500)));
    server.stubFor(
        post(urlEqualTo("/inference"))
            .inScenario("c0")
            .whenScenarioStateIs("retry")
            .withRequestBody(containing("filename=\"chunk-000.wav\""))
            .willReturn(
                aResponse().withStatus(200).withBody("{\"text\":\"First\"}").withFixedDelay(700)));
    answer("chunk-001.wav", "Last", 700);
    Duration timeout = Duration.ofSeconds(1);

    long start = System.nanoTime();
    Path transcript = run(client(timeout), List.of(c0, c1));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(Files.readString(transcript)).isEqualTo("First Last\n");
    assertThat(received()).containsExactly("chunk-000.wav", "chunk-000.wav", "chunk-001.wav");
    assertThat(elapsed).isGreaterThan(timeout.plusMillis(1400));
    assertThat(progress).containsExactly("1/2", "2/2");
  }

  @Test
  void theSuppliedOrderWinsOverTheFilenameOrder() throws IOException {
    Path c0 = chunk("chunk-000.wav", 10);
    Path c1 = chunk("chunk-001.wav", 10);
    answer("chunk-000.wav", "Second supplied", 0);
    answer("chunk-001.wav", "First supplied", 0);

    Path transcript = run(client(Duration.ofSeconds(5)), List.of(c1, c0));

    assertThat(received()).containsExactly("chunk-001.wav", "chunk-000.wav");
    assertThat(Files.readString(transcript)).isEqualTo("First supplied Second supplied\n");
  }

  @Test
  void everyChunkOfAThreeHourVideoIsSentOnceInOrder() throws IOException {
    List<Path> chunks = new ArrayList<>();
    StringBuilder expected = new StringBuilder();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < 18; i++) {
      String name = String.format("chunk-%03d.wav", i);
      String text = String.format("part-%02d", i);
      chunks.add(chunk(name, 10));
      answer(name, text, 0);
      names.add(name);
      expected.append(i == 0 ? "" : " ").append(text);
    }

    Path transcript = run(client(Duration.ofSeconds(5)), chunks);

    assertThat(received()).isEqualTo(names);
    assertThat(Files.readString(transcript)).isEqualTo(expected + "\n");
    assertThat(progress).hasSize(18).startsWith("1/18").endsWith("18/18");
  }

  @Test
  void aShortFinalChunkIsIncluded() throws IOException {
    Path c0 = chunk("chunk-000.wav", 6000);
    Path c1 = chunk("chunk-001.wav", 6000);
    Path c2 = chunk("chunk-002.wav", 50);
    answer("chunk-000.wav", "First", 0);
    answer("chunk-001.wav", "Second", 0);
    answer("chunk-002.wav", "Remainder", 0);

    Path transcript = run(client(Duration.ofSeconds(5)), List.of(c0, c1, c2));

    assertThat(Files.readString(transcript)).isEqualTo("First Second Remainder\n");
  }

  @Test
  void aJobDirThatIsAFileFailsWithoutARequest() throws IOException {
    Path c0 = chunk("chunk-000.wav", 10);
    answer("chunk-000.wav", "text", 0);
    Path file = Files.writeString(jobDir.resolve("not-a-dir"), "x");

    assertThatThrownBy(
            () -> client(Duration.ofSeconds(5)).transcribe(file, List.of(c0), (n, t) -> {}))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage(FAILED_MESSAGE);
            });

    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void anUnavailableChunkIsRetriedThenFailedAndLaterChunksAreNotSent() throws IOException {
    Path missing = jobDir.resolve("chunks").resolve("chunk-000.wav");
    Path c1 = chunk("chunk-001.wav", 10);
    answer("chunk-001.wav", "text", 0);

    assertThatThrownBy(() -> run(client(Duration.ofSeconds(5)), List.of(missing, c1)))
        .isInstanceOfSatisfying(
            JobFailure.class,
            f -> {
              assertThat(f.kind()).isEqualTo(JobFailure.Kind.FAILED);
              assertThat(f).hasMessage(FAILED_MESSAGE);
            });

    assertThat(server.getAllServeEvents()).isEmpty();
    assertThat(progress).containsExactly("1/2");
    assertThat(jobDir.resolve("transcript.txt")).doesNotExist();
    assertThat(c1).exists();
  }
}
