package com.video2podcast.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SummaryServiceTest {

  private static final int NUM_CTX = 8192;
  private static final String FAILED_MESSAGE = "Summary failed. Please try again later.";

  record Call(String system, String user, int maxOutputTokens) {}

  /** Records every request and answers through a function of the call index. */
  static final class ScriptedLlm implements LlmClient {

    interface Answer {
      LlmReply reply(int index, String system, String user) throws LlmException;
    }

    final List<Call> calls = new ArrayList<>();
    private final Answer answer;

    ScriptedLlm(Answer answer) {
      this.answer = answer;
    }

    @Override
    public LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException {
      calls.add(new Call(system, user, maxOutputTokens));
      return answer.reply(calls.size() - 1, system, user);
    }
  }

  private static LlmReply valid(String content, String system, String user) {
    return new LlmReply(content, TokenEstimator.requestEstimate(system, user) * 4 / 5);
  }

  private static ScriptedLlm always(String content) {
    return new ScriptedLlm((i, s, u) -> valid(content, s, u));
  }

  private static AppProperties.Ollama config(int numCtx) {
    return new AppProperties.Ollama("http://localhost:1", "m", numCtx, Duration.ofMinutes(1));
  }

  private static SummaryService service(LlmClient llm) {
    return new SummaryService(llm, config(NUM_CTX));
  }

  private static int promptTokens() {
    return Math.max(
        Math.max(TokenEstimator.estimate(Prompts.SHORT), TokenEstimator.estimate(Prompts.MAP)),
        Math.max(
            TokenEstimator.estimate(Prompts.REDUCE_FINAL),
            TokenEstimator.estimate(Prompts.REDUCE_ROUND)));
  }

  private static String words(int chars) {
    return "lorem ipsum dolor sit amet ".repeat(chars / 27 + 1).substring(0, chars).strip();
  }

  private static JobFailure failureOf(SummaryService service, String transcript) {
    try {
      service.summarize(transcript);
    } catch (JobFailure e) {
      return e;
    }
    throw new AssertionError("no JobFailure");
  }

  private static void assertFits(Call call, int numCtx) {
    assertThat(TokenEstimator.requestEstimate(call.system(), call.user()) + call.maxOutputTokens())
        .isLessThanOrEqualTo(numCtx);
  }

  // ---- budget ----

  @Test
  void budgetFollowsTheFormula() {
    SummaryService service = service(always("- a"));

    int budget = NUM_CTX - 1024 - promptTokens() - 32;
    assertThat(service.maxChunkChars()).isEqualTo(budget * 3);
    assertThat(service.partialMaxChars()).isEqualTo((budget * 3 - 6) / 4);
  }

  @Test
  void theLongestOfTheFourPromptsDefinesTheBudget() {
    assertThat(promptTokens())
        .isEqualTo(
            Math.max(
                TokenEstimator.estimate(Prompts.SHORT),
                Math.max(
                    TokenEstimator.estimate(Prompts.MAP),
                    Math.max(
                        TokenEstimator.estimate(Prompts.REDUCE_FINAL),
                        TokenEstimator.estimate(Prompts.REDUCE_ROUND)))));
    assertThat(Prompts.REDUCE_FINAL.length()).isGreaterThan(Prompts.REDUCE_ROUND.length());
    assertThat(Prompts.REDUCE_FINAL.length()).isGreaterThan(Prompts.MAP.length());
  }

  @Test
  void aDifferentContextChangesTheBudgetByThreePerToken() {
    SummaryService base = new SummaryService(always("- a"), config(8192));
    SummaryService larger = new SummaryService(always("- a"), config(8192 + 100));

    assertThat(larger.maxChunkChars() - base.maxChunkChars()).isEqualTo(300);
  }

  @Test
  void aContextBelowTheMinimumBudgetIsRejected() {
    int minimum = 2 * 1024 + 1024 + promptTokens() + 32;

    assertThat(new SummaryService(always("- a"), config(minimum)).partialMaxChars())
        .isGreaterThanOrEqualTo(1500);
    assertThatThrownBy(() -> new SummaryService(always("- a"), config(minimum - 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void aNonPositiveContextIsRejected(int numCtx) {
    assertThatThrownBy(() -> new SummaryService(always("- a"), config(numCtx)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("num-ctx");
  }

  @Test
  void aHugeContextDoesNotOverflow() {
    SummaryService huge = new SummaryService(always("- a"), config(Integer.MAX_VALUE / 4));

    assertThat(huge.maxChunkChars()).isPositive();
  }

  @Test
  void aContextWhoseCharacterBudgetOverflowsAnIntIsRejected() {
    int largest = Integer.MAX_VALUE / 3 + 1024 + promptTokens() + 32;

    assertThat(new SummaryService(always("- a"), config(largest)).maxChunkChars()).isPositive();
    assertThatThrownBy(() -> new SummaryService(always("- a"), config(largest + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
    assertThatThrownBy(() -> new SummaryService(always("- a"), config(Integer.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- short path ----

  @Test
  void aShortTranscriptIsOneRequest() {
    ScriptedLlm llm = always("- Tuesday meeting\n- Budget");

    String summary = service(llm).summarize("  The budget meeting is on Tuesday.  \n");

    assertThat(llm.calls).hasSize(1);
    Call call = llm.calls.get(0);
    assertThat(call.system()).isEqualTo(Prompts.SHORT);
    assertThat(call.user()).isEqualTo("The budget meeting is on Tuesday.");
    assertThat(call.maxOutputTokens()).isEqualTo(1024);
    assertThat(summary).isEqualTo("• Tuesday meeting\n• Budget");
  }

  @Test
  void aTranscriptOfExactlyTheBudgetIsStillOneRequest() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);
    String text = "a".repeat(service.maxChunkChars());

    service.summarize(text);

    assertThat(llm.calls).hasSize(1);
    assertThat(llm.calls.get(0).user()).isEqualTo(text);
    assertFits(llm.calls.get(0), NUM_CTX);
  }

  @Test
  void aTranscriptOneBelowTheBudgetIsOneRequest() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);

    service.summarize("a".repeat(service.maxChunkChars() - 1));

    assertThat(llm.calls).hasSize(1);
  }

  @Test
  void aTranscriptOneOverTheBudgetIsSplitAndReduced() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);
    int max = service.maxChunkChars();

    service.summarize("a".repeat(max + 1));

    assertThat(llm.calls).hasSize(3);
    assertThat(llm.calls.get(0).system()).isEqualTo(Prompts.MAP);
    assertThat(llm.calls.get(0).user()).isEqualTo("a".repeat(max));
    assertThat(llm.calls.get(1).user()).isEqualTo("a");
    assertThat(llm.calls.get(2).system()).isEqualTo(Prompts.REDUCE_FINAL);
  }

  @Test
  void whitespaceAroundTheTranscriptDoesNotCountTowardTheBudget() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);

    service.summarize("  " + "a".repeat(service.maxChunkChars()) + "\n\n");

    assertThat(llm.calls).hasSize(1);
  }

  // ---- map and reduce ----

  @Test
  void everyChunkIsSummarizedInOrderThenAllPartialsAreReduced() {
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> valid(i < 3 ? "- part " + (i + 1) : "- final", s, u));
    SummaryService service = service(llm);
    String text = words(service.maxChunkChars() * 5 / 2);
    List<String> chunks = TextChunker.split(text, service.maxChunkChars());
    assertThat(chunks).hasSize(3);

    String summary = service.summarize(text);

    assertThat(llm.calls).hasSize(4);
    for (int i = 0; i < 3; i++) {
      assertThat(llm.calls.get(i).system()).isEqualTo(Prompts.MAP);
      assertThat(llm.calls.get(i).user()).isEqualTo(chunks.get(i));
    }
    assertThat(llm.calls.get(3).system()).isEqualTo(Prompts.REDUCE_FINAL);
    assertThat(llm.calls.get(3).user()).isEqualTo("- part 1\n\n- part 2\n\n- part 3");
    assertThat(summary).isEqualTo("• final");
    assertThat(llm.calls).allSatisfy(call -> assertThat(call.maxOutputTokens()).isEqualTo(1024));
    assertThat(llm.calls).allSatisfy(call -> assertFits(call, NUM_CTX));
  }

  @Test
  void onlyTheFinalAnswerIsFormatted() {
    ScriptedLlm llm = new ScriptedLlm((i, s, u) -> valid(i < 2 ? "* mapped" : "* final", s, u));
    SummaryService service = service(llm);

    String summary = service.summarize("a".repeat(service.maxChunkChars() + 1));

    assertThat(llm.calls.get(2).user()).isEqualTo("* mapped\n\n* mapped");
    assertThat(summary).isEqualTo("• final");
  }

  @Test
  void partialsThatDoNotFitAreReducedInRoundsInOrder() {
    SummaryService probe = service(always("- a"));
    int max = probe.maxChunkChars();
    int partialMax = probe.partialMaxChars();
    int chunks = 9;
    String text = "a".repeat(max * (chunks - 1) + 1);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> {
          if (s.equals(Prompts.MAP)) {
            return valid(i + "-" + "x".repeat(partialMax * 3), s, u);
          }
          return valid(s.equals(Prompts.REDUCE_FINAL) ? "- final" : "- group " + i, s, u);
        });

    String summary = service(llm).summarize(text);

    // 9 maps, groups of 4, 4 and 1, then one final reduction of the 3 group answers
    assertThat(llm.calls).hasSize(9 + 3 + 1);
    assertThat(llm.calls.subList(0, 9))
        .allSatisfy(c -> assertThat(c.system()).isEqualTo(Prompts.MAP));
    List<Call> round = llm.calls.subList(9, 12);
    assertThat(round).allSatisfy(c -> assertThat(c.system()).isEqualTo(Prompts.REDUCE_ROUND));
    assertThat(round.get(0).user()).startsWith("0-x").contains("\n\n1-x", "\n\n2-x", "\n\n3-x");
    assertThat(round.get(1).user()).startsWith("4-x").contains("\n\n5-x", "\n\n6-x", "\n\n7-x");
    assertThat(round.get(2).user()).startsWith("8-x").doesNotContain("\n\n");
    assertThat(round.get(0).user().length()).isLessThanOrEqualTo(max);
    assertThat(round.get(0).user().length()).isEqualTo(4 * partialMax + 6);
    assertThat(llm.calls.get(12).system()).isEqualTo(Prompts.REDUCE_FINAL);
    assertThat(llm.calls.get(12).user()).isEqualTo("- group 9\n\n- group 10\n\n- group 11");
    assertThat(summary).isEqualTo("• final");
    assertThat(llm.calls)
        .allSatisfy(c -> assertThat(c.user().length()).isLessThanOrEqualTo(max))
        .allSatisfy(c -> assertFits(c, NUM_CTX));
  }

  @Test
  void aGroupStartsANewOneWhenTheNextPartialWouldNotFit() {
    SummaryService probe = service(always("- a"));
    int max = probe.maxChunkChars();
    int partialMax = probe.partialMaxChars();
    // partials of partialMax + 1 chars cannot be clipped, but 4 of them plus separators exceed max
    String text = "a".repeat(max * 4 + 1);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> {
          if (s.equals(Prompts.MAP)) {
            return valid("y".repeat(partialMax), s, u);
          }
          return valid("- ok", s, u);
        });

    service(llm).summarize(text);

    List<Call> round =
        llm.calls.stream().filter(c -> c.system().equals(Prompts.REDUCE_ROUND)).toList();
    assertThat(round).hasSize(2);
    assertThat(round.get(0).user()).hasSize(4 * partialMax + 6);
    assertThat(round.get(1).user()).hasSize(partialMax);
  }

  @Test
  void anOverlongAnswerIsClippedToTheBoundBeforeItIsUsed() {
    SummaryService probe = service(always("- a"));
    int partialMax = probe.partialMaxChars();
    String line = "z".repeat(partialMax * 2 / 5);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) ->
            valid(s.equals(Prompts.MAP) ? line + "\n" + line + "\n" + line : "- done", s, u));

    service(llm).summarize("a".repeat(probe.maxChunkChars() + 1));

    // three lines of two fifths of the bound: the third is dropped
    assertThat(llm.calls.get(2).user()).isEqualTo(line + "\n" + line + "\n\n" + line + "\n" + line);
  }

  /** Every map and round answer is as long as an intermediate answer may be. */
  private static ScriptedLlm bigAnswers(SummaryService probe) {
    String big = "y".repeat(probe.partialMaxChars());
    return new ScriptedLlm(
        (i, s, u) -> valid(s.equals(Prompts.REDUCE_FINAL) ? "- final" : big, s, u));
  }

  @Test
  void seventeenPartialsNeedTwoRoundsOfReduction() {
    SummaryService probe = service(always("- a"));
    ScriptedLlm llm = bigAnswers(probe);

    String summary = service(llm).summarize("a".repeat(probe.maxChunkChars() * 16 + 1));

    // 17 maps, round one: 5 groups, round two: 2 groups, then the final reduction
    assertThat(llm.calls).hasSize(17 + 5 + 2 + 1);
    assertThat(llm.calls.get(llm.calls.size() - 1).system()).isEqualTo(Prompts.REDUCE_FINAL);
    assertThat(summary).isEqualTo("• final");
    assertThat(llm.calls).allSatisfy(c -> assertFits(c, NUM_CTX));
  }

  @Test
  void furtherRoundsAreBounded() {
    SummaryService probe = service(always("- a"));
    ScriptedLlm llm = bigAnswers(probe);

    JobFailure failure =
        failureOf(
            new SummaryService(llm, config(NUM_CTX), 1),
            "a".repeat(probe.maxChunkChars() * 16 + 1));

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo(FAILED_MESSAGE);
    assertThat(failure.getCause()).isNull();
    // 17 maps and the 5 groups of round one, nothing after the limit
    assertThat(llm.calls).hasSize(17 + 5);
  }

  @Test
  void theRoundLimitIsInclusive() {
    SummaryService probe = service(always("- a"));
    ScriptedLlm llm = bigAnswers(probe);

    String summary =
        new SummaryService(llm, config(NUM_CTX), 1)
            .summarize("a".repeat(probe.maxChunkChars() * 4 + 1));

    // 5 maps, one round of 2 groups, then the final reduction
    assertThat(llm.calls).hasSize(5 + 2 + 1);
    assertThat(summary).isEqualTo("• final");
  }

  // ---- input ----

  @Test
  void aNullTranscriptIsAProgrammingError() {
    ScriptedLlm llm = always("- a");

    assertThatThrownBy(() -> service(llm).summarize(null)).isInstanceOf(NullPointerException.class);
    assertThat(llm.calls).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " \n\t "})
  void aBlankTranscriptFailsWithoutARequest(String transcript) {
    ScriptedLlm llm = always("- a");

    JobFailure failure = failureOf(service(llm), transcript);

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo(FAILED_MESSAGE);
    assertThat(llm.calls).isEmpty();
  }

  // ---- failures ----

  private static ScriptedLlm failing(int failAt, LlmException.Reason reason, Throwable cause) {
    return new ScriptedLlm((i, s, u) -> {
      if (i == failAt) {
        throw new LlmException("boom", reason, cause);
      }
      return valid("- a", s, u);
    });
  }

  private static String threeChunks() {
    return "a".repeat(service(always("- a")).maxChunkChars() * 2 + 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void aConnectionErrorAtAnyStageIsSummaryUnreachable(int stage) {
    IOException cause = new IOException("refused");
    ScriptedLlm llm = failing(stage, LlmException.Reason.CONNECTION, cause);

    JobFailure failure = failureOf(service(llm), threeChunks());

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.SUMMARY_UNREACHABLE);
    assertThat(failure.getMessage()).isEqualTo("The summary server is unreachable.");
    assertThat(failure.getCause()).isInstanceOf(LlmException.class);
    assertThat(failure.getCause().getCause()).isSameAs(cause);
    assertThat(llm.calls).hasSize(stage + 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void aTimeoutAtAnyStageIsATimeout(int stage) {
    ScriptedLlm llm = failing(stage, LlmException.Reason.TIMEOUT, null);

    JobFailure failure = failureOf(service(llm), threeChunks());

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.TIMEOUT);
    assertThat(failure.getMessage()).isEqualTo("Processing took too long and was stopped.");
    assertThat(llm.calls).hasSize(stage + 1);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void anInvalidAnswerAtAnyStageIsASummaryFailureAndStopsEverything(int stage) {
    ScriptedLlm llm = failing(stage, LlmException.Reason.OTHER, null);

    JobFailure failure = failureOf(service(llm), threeChunks());

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo(FAILED_MESSAGE);
    assertThat(failure.getCause()).isInstanceOf(LlmException.class);
    assertThat(llm.calls).hasSize(stage + 1);
  }

  @Test
  void theShortPathMapsFailuresToo() {
    ScriptedLlm llm = failing(0, LlmException.Reason.CONNECTION, null);

    assertThat(failureOf(service(llm), "hello").kind())
        .isEqualTo(JobFailure.Kind.SUMMARY_UNREACHABLE);
  }

  // ---- truncation check ----

  private static long threshold(String system, String user) {
    long expected = TokenEstimator.requestEstimate(system, user);
    return (expected + 1) / 2;
  }

  @Test
  void theShortPathRejectsOneBelowTheThresholdAndAcceptsTheThreshold() {
    String text = "x".repeat(100);
    long threshold = threshold(Prompts.SHORT, text);

    ScriptedLlm below = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold - 1));
    JobFailure failure = failureOf(service(below), text);
    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo(FAILED_MESSAGE);
    assertThat(failure.getCause()).isNull();

    ScriptedLlm at = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold));
    assertThat(service(at).summarize(text)).isEqualTo("• a");
  }

  @Test
  void theThresholdRoundsUpForAnOddEstimate() {
    // estimate(system) + estimate(user) + 32 is odd for this text, so ceil(expected / 2) matters
    String text = "x".repeat(100);
    long expected = TokenEstimator.requestEstimate(Prompts.SHORT, text);
    String odd = expected % 2 == 1 ? text : "x".repeat(103);
    long oddExpected = TokenEstimator.requestEstimate(Prompts.SHORT, odd);
    assertThat(oddExpected % 2).isEqualTo(1);
    long threshold = (oddExpected + 1) / 2;

    ScriptedLlm at = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold));
    ScriptedLlm below = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold - 1));

    assertThat(service(at).summarize(odd)).isEqualTo("• a");
    assertThat(failureOf(service(below), odd).kind()).isEqualTo(JobFailure.Kind.FAILED);
  }

  @Test
  void t3sTruncatedExampleIsRejected() {
    // a prompt of about 4,831 real tokens (the estimate is higher) that Ollama cut to 1,026
    String text = "word ".repeat(5400).strip();
    ScriptedLlm llm = new ScriptedLlm((i, s, u) -> new LlmReply("- a", 1026));

    assertThat(failureOf(service(llm), text).kind()).isEqualTo(JobFailure.Kind.FAILED);
  }

  @Test
  void aFullCountIsAccepted() {
    String text = "word ".repeat(5400).strip();
    ScriptedLlm llm = new ScriptedLlm((i, s, u) -> new LlmReply("- a", 4831));

    assertThat(service(llm).summarize(text)).isEqualTo("• a");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void everyStageChecksItsOwnPromptTokens(int stage) {
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> i == stage ? new LlmReply("- a", 1) : valid("- a", s, u));

    JobFailure failure = failureOf(service(llm), threeChunks());

    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(llm.calls).hasSize(stage + 1);
  }

  @Test
  void aMapStageAcceptsItsThresholdAndRejectsOneBelow() {
    String text = threeChunks();
    List<String> chunks = TextChunker.split(text, service(always("- a")).maxChunkChars());
    long threshold = threshold(Prompts.MAP, chunks.get(1));

    ScriptedLlm below =
        new ScriptedLlm(
            (i, s, u) -> i == 1 ? new LlmReply("- a", threshold - 1) : valid("- a", s, u));
    ScriptedLlm at =
        new ScriptedLlm((i, s, u) -> i == 1 ? new LlmReply("- a", threshold) : valid("- a", s, u));

    assertThat(failureOf(service(below), text).kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(service(at).summarize(text)).isEqualTo("• a");
  }

  @Test
  void aReductionStageAcceptsItsThresholdAndRejectsOneBelow() {
    String joined = "- a\n\n- a\n\n- a";
    long threshold = threshold(Prompts.REDUCE_FINAL, joined);
    String text = threeChunks();

    ScriptedLlm below =
        new ScriptedLlm(
            (i, s, u) -> i == 3 ? new LlmReply("- a", threshold - 1) : valid("- a", s, u));
    ScriptedLlm at =
        new ScriptedLlm((i, s, u) -> i == 3 ? new LlmReply("- a", threshold) : valid("- a", s, u));

    assertThat(failureOf(service(below), text).kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(service(at).summarize(text)).isEqualTo("• a");
  }

  // ---- output ----

  @Test
  void anAnswerWithoutABulletIsAFailure() {
    ScriptedLlm llm = always("- \n * ");

    assertThat(failureOf(service(llm), "hello").kind()).isEqualTo(JobFailure.Kind.FAILED);
  }

  @Test
  void theFinalAnswerIsCappedAt1500() {
    ScriptedLlm llm = always("x".repeat(1800));

    assertThat(service(llm).summarize("hello")).hasSize(1500).endsWith("…");
  }
}
