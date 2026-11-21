package com.video2podcast.job;

/** Best effort: shows the current file states on the summary message buttons. Never throws. */
public interface ButtonRefresher {

  void refresh(JobRecord job);
}
