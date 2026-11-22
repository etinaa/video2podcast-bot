package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.*;

/** Full Spring wiring and real media tools, driven solely by Telegram updates and job files. */
class AudioButtonIT extends FunctionalTest {
  TranscriptWorld w;

  @BeforeEach
  void fixtures() throws Exception {
    w = new TranscriptWorld();
    w.beginAudio();
  }

  @AfterEach
  void cleanup() {
    w.close();
  }

  @Test
  void unchangedUploadIsSentOnlyOnce() throws Exception {
    w.tap("audio:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    assertThat(w.audio.upload()).containsExactly(w.before.get("source.m4a"));
    assertThat(w.audio.part("caption")).isEqualTo("Original audio, not compressed.");
    assertThat(w.file("AUDIO").path("state").asText()).isEqualTo("SENT");
    w.tap("audio:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Already sent.");
    assertThat(w.calls("sendAudio")).hasSize(1);
    w.unchanged();
    w.noModels();
  }

  @Test
  void realOversizedAudioIsCompressedAndTranscriptPreserved() throws Exception {
    w.audio.source("source.wav", true, 0);
    w.tap("audio:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    assertThat(w.audio.upload().length).isBetween(1, (int) AudioSupport.LIMIT - 1);
    var streams = w.audio.uploadedMedia().path("streams");
    assertThat(streams.size()).isEqualTo(1);
    assertThat(streams.get(0).path("codec_name").asText()).isEqualTo("aac");
    assertThat(streams.get(0).path("channels").asInt()).isEqualTo(1);
    assertThat(w.file("TRANSCRIPT").path("state").asText()).isEqualTo("AVAILABLE");
    assertThat(w.dir.resolve("out.m4a")).doesNotExist();
  }

  @Test
  void ownershipAndExpiryPreventAudioDelivery() throws Exception {
    w.allowed.add(202L);
    w.tap("audio:" + TranscriptWorld.JOB, 202, 701, 101, "private");
    w.refusal("This button is not for you.");
    w.unchanged();
    w.change(j ->
        ((com.fasterxml.jackson.databind.node.ObjectNode) j.path("files").path("AUDIO"))
            .put("expiresAt", w.clock.instant().toString()));
    w.tap("audio:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Files already downloaded or expired, please resend the link.");
    w.unchanged();
    assertThat(w.calls("sendAudio")).isEmpty();
  }
}
