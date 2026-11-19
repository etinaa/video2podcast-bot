package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.FileState;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class KeyboardsTest {

  private static final String JOB_ID = "123e4567-e89b-12d3-a456-426614174000";

  @Test
  void deliveryHasTheAudioAndTranscriptButtonsInOrder() {
    assertThat(Keyboards.delivery(JOB_ID))
        .containsExactly(
            new InlineButton("Download audio", "audio:" + JOB_ID),
            new InlineButton("Download transcript", "transcript:" + JOB_ID));
  }

  @Test
  void callbackDataFitsTelegramsLimit() {
    assertThat(Keyboards.delivery(JOB_ID))
        .allSatisfy(button ->
            assertThat(button.callbackData().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(64));
  }

  @Test
  void labelsFollowTheStateOfEachFile() {
    assertThat(Keyboards.delivery(JOB_ID, FileState.SENDING, FileState.SENT))
        .containsExactly(
            new InlineButton("Preparing audio…", "audio:" + JOB_ID),
            new InlineButton("Transcript sent", "transcript:" + JOB_ID));
    assertThat(Keyboards.delivery(JOB_ID, FileState.SENT, FileState.SENDING))
        .containsExactly(
            new InlineButton("Audio sent", "audio:" + JOB_ID),
            new InlineButton("Sending transcript…", "transcript:" + JOB_ID));
    assertThat(Keyboards.delivery(JOB_ID, FileState.EXPIRED, FileState.EXPIRED))
        .containsExactly(
            new InlineButton("Audio expired", "audio:" + JOB_ID),
            new InlineButton("Transcript expired", "transcript:" + JOB_ID));
  }
}
