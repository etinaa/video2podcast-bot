package com.video2podcast.summary;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Summarizes a transcript: one request when it fits the context, otherwise map and reduce. */
public final class SummaryService {

  private static final Logger LOG = LoggerFactory.getLogger(SummaryService.class);

  static final int OUTPUT_RESERVE = 1024;
  static final double MIN_RATIO = 0.5;
  static final int MAX_ROUNDS = 8;

  private static final String SEPARATOR = "\n\n";

  private final LlmClient llm;
  private final int maxChunkChars;
  private final int partialMaxChars;
  private final int maxRounds;

  public SummaryService(LlmClient llm, AppProperties.Ollama config) {
    this(llm, config, MAX_ROUNDS);
  }

  /** For tests: the round limit is supplied. */
  SummaryService(LlmClient llm, AppProperties.Ollama config, int maxRounds) {
    if (config.numCtx() <= 0) {
      throw new IllegalArgumentException("app.ollama.num-ctx must be positive");
    }
    int promptTokens =
        Math.max(
            Math.max(TokenEstimator.estimate(Prompts.SHORT), TokenEstimator.estimate(Prompts.MAP)),
            Math.max(
                TokenEstimator.estimate(Prompts.REDUCE_FINAL),
                TokenEstimator.estimate(Prompts.REDUCE_ROUND)));
    long textBudget =
        (long) config.numCtx() - OUTPUT_RESERVE - promptTokens - TokenEstimator.TEMPLATE_OVERHEAD;
    if (textBudget < 2L * OUTPUT_RESERVE) {
      throw new IllegalArgumentException("app.ollama.num-ctx is too small: " + config.numCtx());
    }
    if (textBudget > Integer.MAX_VALUE / TokenEstimator.CHARS_PER_TOKEN) {
      throw new IllegalArgumentException("app.ollama.num-ctx is too large: " + config.numCtx());
    }
    this.llm = llm;
    this.maxRounds = maxRounds;
    this.maxChunkChars = (int) (textBudget * TokenEstimator.CHARS_PER_TOKEN);
    this.partialMaxChars = (maxChunkChars - 3 * SEPARATOR.length()) / 4;
  }

  /** Characters of user text that fit one request. */
  int maxChunkChars() {
    return maxChunkChars;
  }

  /** The bound for every intermediate answer. */
  int partialMaxChars() {
    return partialMaxChars;
  }

  /**
   * Returns English bullet text, {@code "• "} per line, at most 1,500 UTF-16 chars, never blank.
   *
   * @throws NullPointerException if {@code transcript} is null
   * @throws JobFailure when the summary server is unreachable, too slow, or its answer is unusable
   */
  public String summarize(String transcript) {
    String text = Objects.requireNonNull(transcript, "transcript").strip();
    if (text.isEmpty()) {
      throw JobFailure.summaryFailed(null);
    }
    if (text.length() <= maxChunkChars) {
      return SummaryFormatter.format(call(Prompts.SHORT, text));
    }
    List<String> partials = new ArrayList<>();
    for (String chunk : TextChunker.split(text, maxChunkChars)) {
      partials.add(clipped(call(Prompts.MAP, chunk)));
    }
    int round = 0;
    while (true) {
      String joined = String.join(SEPARATOR, partials);
      if (joined.length() <= maxChunkChars) {
        return SummaryFormatter.format(call(Prompts.REDUCE_FINAL, joined));
      }
      round++;
      if (round > maxRounds) {
        throw JobFailure.summaryFailed(null);
      }
      List<String> next = new ArrayList<>();
      for (String group : groups(partials)) {
        next.add(clipped(call(Prompts.REDUCE_ROUND, group)));
      }
      partials = next;
    }
  }

  /** Greedy, in order; each group is the partials joined by blank lines, within one request. */
  private List<String> groups(List<String> partials) {
    List<String> groups = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String partial : partials) {
      if (!current.isEmpty()
          && current.length() + SEPARATOR.length() + partial.length() > maxChunkChars) {
        groups.add(current.toString());
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append(SEPARATOR);
      }
      current.append(partial);
    }
    groups.add(current.toString());
    return groups;
  }

  private String clipped(String content) {
    String clipped = SummaryFormatter.clip(content, partialMaxChars);
    if (clipped.length() < content.length() && LOG.isWarnEnabled()) {
      LOG.warn(
          "Clipped an intermediate answer from {} to {} chars", content.length(), clipped.length());
    }
    return clipped;
  }

  private static JobFailure failureOf(LlmException e) {
    return switch (e.reason()) {
      case CONNECTION -> JobFailure.of(JobFailure.Kind.SUMMARY_UNREACHABLE, e);
      case TIMEOUT -> JobFailure.of(JobFailure.Kind.TIMEOUT, e);
      case OTHER -> JobFailure.summaryFailed(e);
    };
  }

  private String call(String system, String user) {
    LlmReply reply;
    try {
      reply = llm.complete(system, user, OUTPUT_RESERVE);
    } catch (LlmException e) {
      throw failureOf(e);
    }
    long expected = TokenEstimator.requestEstimate(system, user);
    long threshold = (long) Math.ceil(expected * MIN_RATIO);
    if (reply.promptTokens() < threshold) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "Prompt looks truncated: {} tokens evaluated, at least {} expected",
            reply.promptTokens(),
            threshold);
      }
      throw JobFailure.summaryFailed(null);
    }
    return reply.content();
  }
}
