package com.video2podcast.job;

/** Stage reports from the worker thread. Implementations must not throw. */
public interface JobProgress {

  void downloading();

  /** {@code n} counts from 1; called once per chunk, before the chunk is sent to Whisper. */
  void transcribing(int n, int total);

  void summarizing();
}
