package com.video2podcast.media;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Records every call and answers with whatever the test supplied. */
final class FakeProcessRunner implements ProcessRunner {

  final List<List<String>> calls = new ArrayList<>();
  final List<Duration> timeouts = new ArrayList<>();
  private final BiFunction<List<String>, Duration, ProcessResult> answer;

  FakeProcessRunner(BiFunction<List<String>, Duration, ProcessResult> answer) {
    this.answer = answer;
  }

  static FakeProcessRunner returning(ProcessResult result) {
    return new FakeProcessRunner((args, timeout) -> result);
  }

  @Override
  public ProcessResult run(List<String> args, Duration timeout) {
    calls.add(args);
    timeouts.add(timeout);
    return answer.apply(args, timeout);
  }
}
