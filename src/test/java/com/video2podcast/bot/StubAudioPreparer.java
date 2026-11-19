package com.video2podcast.bot;

import com.video2podcast.media.AudioPreparer;
import com.video2podcast.media.PreparedAudio;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** An {@link AudioPreparer} that answers with whatever the test supplied. */
class StubAudioPreparer extends AudioPreparer {

  record Call(Path source, long durationSeconds) {}

  final List<Call> calls = new ArrayList<>();
  private final BiFunction<Path, Long, PreparedAudio> answer;

  StubAudioPreparer(BiFunction<Path, Long, PreparedAudio> answer) {
    super(null);
    this.answer = answer;
  }

  /** Returns the source unchanged. */
  static StubAudioPreparer unchanged() {
    return new StubAudioPreparer(
        (source, seconds) -> new PreparedAudio(source, PreparedAudio.Mode.UNCHANGED, 0));
  }

  @Override
  public PreparedAudio prepare(Path source, long durationSeconds) {
    calls.add(new Call(source, durationSeconds));
    return answer.apply(source, durationSeconds);
  }
}
