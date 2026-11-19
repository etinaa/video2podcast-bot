package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dedicated full-context acceptance smoke checks; detailed matrices run through CucumberIT. */
class SummaryFlowIT extends FunctionalTest {
  PipelineWorld world;

  @BeforeEach
  void fixtures() throws Exception {
    world = new PipelineWorld();
    world.begin();
    world.fixture(59, "m4a", "http://videos.example/video");
  }

  @AfterEach
  void cleanup() {
    world.close();
  }

  @Test
  void linkProducesProgressSummaryButtonsAndPublishedFilesWithoutUploading() throws Exception {
    world.send("http://videos.example/video");
    world.done();
    var check = new PipelineAssertionSteps(world);
    check.oneAck("Queued: position 1");
    assertThat(world.calls("editMessageText").stream().map(PipelineWorld.Event::text).toList())
        .containsExactly("Downloading…", "Transcribing 1/1…", "Summarizing…", "Done.");
    check.statusIdentity();
    check.differentIds();
    check.format();
    check.buttons("Download audio", "Download transcript");
    check.callbacks("audio:<jobId>", "transcript:<jobId>");
    check.jobOwner("job.json");
    check.metadata("job.json", 701, 59);
    check.entry("AUDIO", "source.m4a");
    check.entry("TRANSCRIPT", "transcript.txt");
    check.retention(1, "hour");
    check.retainedAll();
    var media = new MediaAssertionSteps(world);
    media.m4aHash("source.m4a");
    media.transcriptSingle("transcript.txt");
    media.missingTwo("audio.wav", "chunks");
    check.noDelivery();
    world.noUploads();
  }

  @Test
  void failedDownloadRemovesPartialFilesAndReportsOnlySafeError() throws Exception {
    new PipelineSetupSteps(world).badProcess("download", "download failed /tmp/secret");
    world.send("http://videos.example/video");
    world.reply("Could not process this video. Please try again later.");
    world.done();
    var check = new PipelineAssertionSteps(world);
    check.cleaned();
    check.noSummaryKeyboard();
    check.safeText("download failed /tmp/secret");
    check.finalStatus("Failed.");
    world.noUploads();
  }

  @Test
  void anAllowedChatDoesNotAuthorizeAnUnknownSender() {
    world.start();
    var update = PipelineWorld.update(world.user, "http://videos.example/video", "private");
    update.getMessage().getFrom().setId(world.bob);
    world.context.getBean(com.video2podcast.bot.TelegramBot.class).consume(update);
    world.reply("No access. Send /start to request it. Your id: <code>" + world.bob + "</code>");
    new PipelineAssertionSteps(world).noServices();
    assertThat(world.dirs()).isEmpty();
    world.noUploads();
  }
}
