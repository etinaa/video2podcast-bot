package com.video2podcast.media;

/** What an external process left behind. */
public record ProcessResult(int exitCode, String stdout, String stderr) {}
