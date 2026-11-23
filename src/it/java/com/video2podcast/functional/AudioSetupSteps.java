package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Audio-specific setup; shared retained-job and callback steps remain in TranscriptSetupSteps. */
public class AudioSetupSteps {
  final TranscriptWorld w;

  public AudioSetupSteps(TranscriptWorld w) {
    this.w = w;
  }

  @Given("the stored job duration is {long} seconds")
  public void duration(long seconds) throws Exception {
    w.change(j -> j.put("durationSeconds", seconds));
  }

  @Given(
      "the job directory contains a real ffmpeg-generated 60 second AAC audio-only {string} below 1000000 bytes")
  @Given(
      "the retained audio source is a real ffmpeg-generated 60 second {string} below 1000000 bytes")
  public void source(String name) throws Exception {
    w.audio.source(name, false, 0);
    assertThat(Files.size(w.audio.source)).isLessThan(1000000);
  }

  @Given(
      "^the retained audio source is a real ffmpeg-generated 60 second \"([^\"]+)\" below 1000000 bytes with (an Opus audio stream|video and AAC audio)$")
  public void sourceStreams(String name, String streams) throws Exception {
    source(name);
    var media = AudioSupport.probe(w.audio.source).path("streams");
    assertThat(media.toString()).contains(streams.startsWith("an Opus") ? "opus" : "video");
  }

  @Given(
      "^the retained audio source is a real ffmpeg-generated 60 second \"([^\"]+)\" below 1000000 bytes with (an E-AC-3 5.1 audio stream in an MP4 container|an Opus audio stream in an MP4 container|an AAC audio stream in an ADTS container)$")
  public void incompatibleSource(String name, String streams) throws Exception {
    boolean surround = streams.startsWith("an E-AC-3");
    boolean opus = streams.startsWith("an Opus");
    String codec = surround ? "eac3" : opus ? "opus" : "aac";
    String container = streams.contains("ADTS") ? "adts" : "mp4";
    int channels = surround ? 6 : 2;
    w.audio.incompatibleSource(name, opus ? "libopus" : codec, container, channels);
    assertThat(Files.size(w.audio.source)).isBetween(1L, 999999L);
    var media = AudioSupport.probe(w.audio.source, "-count_frames");
    assertThat(media.path("format").path("format_name").asText())
        .contains(container.equals("adts") ? "aac" : "mp4");
    assertThat(media.path("streams").size()).isEqualTo(1);
    var audio = media.path("streams").path(0);
    assertThat(audio.path("codec_type").asText()).isEqualTo("audio");
    assertThat(audio.path("codec_name").asText()).isEqualTo(codec);
    assertThat(audio.path("channels").asInt()).isEqualTo(channels);
    // Raw ADTS reports an estimated duration; count decoded AAC-LC frames (1024 samples each).
    double seconds =
        container.equals("adts")
            ? audio.path("nb_read_frames").asLong() * 1024.0 / audio.path("sample_rate").asInt()
            : media.path("format").path("duration").asDouble();
    assertThat(seconds).isBetween(59.0, 61.0);
    if (surround) assertThat(audio.path("channel_layout").asText()).startsWith("5.1");
  }

  @Given("the job directory contains UTF-8 {string} with content {string}")
  public void transcript(String name, String text) throws Exception {
    assertThat(name).isEqualTo("transcript.txt");
    w.transcriptBytes = text.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8);
    Files.write(w.dir.resolve(name), w.transcriptBytes);
  }

  @Given("the Telegram mock accepts audio uploads, document uploads, messages and keyboard edits")
  public void accepts() {
    /* HTTP responses supplied by retained boundary. */
  }

  @Given(
      "the retained audio source is a real decodable 60 second stereo WAV larger than 52428800 bytes")
  public void largeWav() throws Exception {
    w.audio.source("source.wav", true, 0);
  }

  @Given("the retained audio source is larger than 52428800 bytes")
  public void large() throws Exception {
    w.audio.source("source.m4a", false, AudioSupport.LIMIT + 1);
  }

  @Given("the retained audio source is a valid {string} with padding to exactly {long} bytes")
  @Given(
      "the retained audio source is a valid 60 second {string} with padding to exactly {long} bytes")
  public void padded(String name, long size) throws Exception {
    w.audio.source(name, false, size);
    assertThat(Files.size(w.audio.source)).isEqualTo(size);
  }

  @Given(
      "the ffmpeg fault fixture writes an oversized M4A for the first encode and a nonempty below-limit M4A for the second encode")
  public void retry() {
    w.audio.fault = "retry";
  }

  @Given("the ffmpeg fault fixture writes an oversized M4A for every encode")
  public void alwaysLarge() {
    w.audio.fault = "always-large";
  }

  @Given(
      "^the ffmpeg fault fixture (exits with code 1 and writes a partial file|reports a process timeout|exits with code 0 but writes no output|exits with code 0 but writes an empty output)$")
  public void fault(String failure) {
    w.audio.fault =
        failure.contains("partial")
            ? "partial"
            : failure.contains("timeout")
                ? "timeout"
                : failure.contains("no output") ? "absent" : "empty";
  }

  @Given(
      "the ffmpeg fault fixture writes a {long} byte output for the non-mono 128 kbps conversion and a valid below-limit M4A for mono compression")
  public void conversion(long size) {
    w.audio.fault = "conversion";
    w.audio.firstSize = size;
  }

  @Given(
      "the ffmpeg fault fixture writes exactly 52428800 bytes for the first mono encode and a valid below-limit M4A for the second")
  public void equalLimit() {
    retry();
    w.audio.firstSize = AudioSupport.LIMIT;
  }

  @Given("the ffmpeg fault fixture exits with code 1 for the first conversion only")
  public void once() {
    w.audio.fault = "once";
  }

  @Given("the ffmpeg fault fixture now allows real ffmpeg to run")
  public void real() {
    w.audio.fault = "";
  }

  @Given("another allowed user's earlier video link is waiting for a held Whisper HTTP response")
  public void earlier() throws Exception {
    w.audio.earlierJob();
  }

  @When(
      "the Whisper mock releases the successful response and the earlier job publishes its summary")
  public void releaseEarlier() {
    w.audio.releaseEarlier();
  }

  @Given("the Telegram mock holds the audio upload response")
  public void hold() {
    w.hold = true;
  }

  @Given("the Telegram mock has received the audio upload and holds its response")
  public void held() throws Exception {
    assertThat(w.uploadReceived.await(15, TimeUnit.SECONDS)).isTrue();
    assertThat(w.releaseUpload.getCount()).isEqualTo(1);
    assertThat(w.file("AUDIO").path("state").asText()).isEqualTo("SENDING");
  }

  @Given("the user has successfully downloaded this job's audio through a Telegram callback")
  public void delivered() throws Exception {
    w.tap("audio:" + TranscriptWorld.JOB, w.user, 701, 101, "private");
    assertThat(w.audio.readState()).isEqualTo("SENT");
  }

  @Given("the AUDIO expiresAt in job.json is {string}")
  public void expiry(String at) throws Exception {
    w.change(j -> ((ObjectNode) j.path("files").path("AUDIO")).put("expiresAt", at));
  }

  @Given("job.json has no AUDIO entry")
  public void noAudio() throws Exception {
    w.change(j -> ((ObjectNode) j.path("files")).remove("AUDIO"));
  }

  @Given("the AUDIO state in job.json is EXPIRED")
  public void expired() throws Exception {
    w.change(j -> ((ObjectNode) j.path("files").path("AUDIO")).put("state", "EXPIRED"));
  }

  @Given("the retained audio source file is absent")
  public void absent() throws Exception {
    Files.delete(w.audio.source);
  }

  @Given(
      "the Telegram mock rejects the first sendAudio with HTTP {int} and a Bot API error response")
  public void rejects(int status) {
    assertThat(status).isEqualTo(400);
    w.rejectUpload = 1;
  }

  @Given(
      "the Telegram mock accepts the audio upload bytes but closes the connection without a response on every audio upload attempt")
  public void drops() {
    w.drop = true;
  }
}
