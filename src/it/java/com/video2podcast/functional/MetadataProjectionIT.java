package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Exercises metadata selection through Telegram, real yt-dlp and a local media page. */
class MetadataProjectionIT {
  @Test
  void playlistOnlyLinkIsRejectedBeforeProcessingItsFirstVideo() throws Exception {
    try (var world = new PipelineWorld()) {
      world.begin();
      world.fixture(59, "m4a", "http://videos.example/first");
      world.fixture(59, "m4a", "http://videos.example/second");
      String url = "http://videos.example/collection.xspf";
      world.proxy.stubFor(
          get(urlPathEqualTo("/collection.xspf"))
              .willReturn(
                  aResponse()
                      .withHeader("Content-Type", "application/xspf+xml")
                      .withBody(
                          """
                          <?xml version="1.0" encoding="UTF-8"?>
                          <playlist version="1" xmlns="http://xspf.org/ns/0/">
                            <trackList>
                              <track><title>First talk</title><duration>59000</duration>
                                <location>http://videos.example/first.m4a</location></track>
                              <track><title>Second talk</title><duration>59000</duration>
                                <location>http://videos.example/second.m4a</location></track>
                            </trackList>
                          </playlist>
                          """)));

      world.send(url);

      assertThat(world.finishOne().text()).isEqualTo("Failed.");
      world.reply("This link is not a supported video.");
      assertThat(world.summaries()).isEmpty();
      assertThat(world.service("whisper")).isEmpty();
      assertThat(world.service("ollama")).isEmpty();
      assertThat(world.commands.stream().filter(c -> PipelineWorld.stage(c).equals("download")))
          .as("collection must not start an audio download")
          .isEmpty();
      world.noUploads();
    }
  }

  @Test
  void largeUnusedDescriptionDoesNotPreventSummaryDelivery() throws Exception {
    try (var world = new PipelineWorld()) {
      world.begin();
      String url = "http://videos.example/large-description";
      world.fixture(59, "m4a", url);
      // The generic extractor retains this JSON-LD description in its full metadata JSON.
      // It exceeds the process output cap while the fields needed by the bot stay small.
      String data =
          PipelineWorld.stringify(
              Map.of(
                  "@context",
                  "https://schema.org",
                  "@type",
                  "VideoObject",
                  "name",
                  world.title,
                  "duration",
                  "PT59S",
                  "contentUrl",
                  url + ".m4a",
                  "description",
                  "x".repeat(9 * 1024 * 1024)));
      world.proxy.stubFor(
          get(urlPathEqualTo("/large-description"))
              .willReturn(
                  aResponse()
                      .withHeader("Content-Type", "text/html")
                      .withBody(
                          "<html><head><title>"
                              + world.title
                              + "</title><script type=\"application/ld+json\">"
                              + data
                              + "</script></head><body></body></html>")));

      // Guard the fixture: a large HTML page alone would not prove large extracted metadata.
      var fullMetadata = world.root.resolve("full-metadata.json");
      var diagnostics = world.root.resolve("full-metadata.stderr");
      var probe =
          new ProcessBuilder(
                  "yt-dlp",
                  "--ignore-config",
                  "--proxy",
                  world.proxy.baseUrl(),
                  "--no-playlist",
                  "--playlist-items",
                  "1",
                  "--no-warnings",
                  "--skip-download",
                  "--dump-single-json",
                  "--",
                  url)
              .redirectOutput(fullMetadata.toFile())
              .redirectError(diagnostics.toFile())
              .start();
      try {
        assertThat(probe.waitFor(30, TimeUnit.SECONDS))
            .as("fixture metadata probe completed")
            .isTrue();
        assertThat(probe.exitValue()).as(Files.readString(diagnostics)).isZero();
        assertThat(Files.size(fullMetadata))
            .as("full fixture metadata exceeds 8 MiB")
            .isGreaterThan(8L * 1024 * 1024);
      } finally {
        if (probe.isAlive()) probe.destroyForcibly();
      }

      world.send(url);

      assertThat(world.finishOne().text()).isEqualTo("Done.");
      assertThat(world.summaryText()).contains(world.title, "Duration: 59s");
      new PipelineAssertionSteps(world).buttons("Download audio", "Download transcript");
      assertThat(world.service("whisper")).hasSize(1);
      assertThat(world.service("ollama")).hasSize(1);
      world.noUploads();
    }
  }
}
