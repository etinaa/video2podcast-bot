package com.video2podcast.summary;

import static com.video2podcast.job.Language.EN;
import static com.video2podcast.job.Language.RU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.video2podcast.config.AppProperties;
import com.video2podcast.job.JobFailure;
import com.video2podcast.job.Language;
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
    return valid(content, system, user, EN);
  }

  private static LlmReply valid(String content, String system, String user, Language language) {
    return new LlmReply(content, TokenEstimator.requestEstimate(system, user, language) * 4 / 5);
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

  private static int promptTokens(Language language) {
    return Math.max(
        Math.max(
            TokenEstimator.estimate(Prompts.shortPrompt(language), language),
            TokenEstimator.estimate(Prompts.mapPrompt(language), language)),
        Math.max(
            TokenEstimator.estimate(Prompts.reduceFinalPrompt(language), language),
            TokenEstimator.estimate(Prompts.reduceRoundPrompt(language), language)));
  }

  private static String words(int chars) {
    return "lorem ipsum dolor sit amet ".repeat(chars / 27 + 1).substring(0, chars).strip();
  }

  private static JobFailure failureOf(SummaryService service, String transcript) {
    try {
      service.summarize(transcript, EN);
    } catch (JobFailure e) {
      return e;
    }
    throw new AssertionError("no JobFailure");
  }

  private static void assertFits(Call call, int numCtx) {
    assertFits(call, numCtx, EN);
  }

  private static void assertFits(Call call, int numCtx, Language language) {
    assertThat(
            TokenEstimator.requestEstimate(call.system(), call.user(), language)
                + call.maxOutputTokens())
        .isLessThanOrEqualTo(numCtx);
  }

  // ---- budget ----

  @Test
  void budgetFollowsTheFormula() {
    SummaryService service = service(always("- a"));

    int budget = NUM_CTX - 1024 - promptTokens(EN) - 32;
    assertThat(service.maxChunkChars(EN)).isEqualTo(budget * 3);
    assertThat(service.partialMaxChars(EN)).isEqualTo((budget * 3 - 6) / 4);
  }

  @Test
  void theRussianBudgetUsesTwoCharactersPerToken() {
    SummaryService service = service(always("- a"));

    int budget = NUM_CTX - 1024 - promptTokens(RU) - 32;
    assertThat(service.maxChunkChars(RU)).isEqualTo(budget * 2);
    assertThat(service.partialMaxChars(RU)).isEqualTo((budget * 2 - 6) / 4);
    assertThat(service.maxChunkChars(RU)).isLessThan(service.maxChunkChars(EN));
  }

  @Test
  void everyPromptNamesTheLanguageAndKeepsTheBulletRules() {
    for (Language language : Language.values()) {
      for (String prompt :
          List.of(
              Prompts.shortPrompt(language),
              Prompts.mapPrompt(language),
              Prompts.reduceFinalPrompt(language),
              Prompts.reduceRoundPrompt(language))) {
        assertThat(prompt)
            .contains(language.displayName() + " bullet")
            .contains("\"- \"")
            .contains("bullet points only")
            .doesNotContain("even if");
      }
    }
    assertThat(Prompts.shortPrompt(RU)).doesNotContain("English");
    assertThat(Prompts.mapPrompt(RU)).doesNotContain("English");
    assertThat(Prompts.reduceFinalPrompt(RU)).doesNotContain("English");
    assertThat(Prompts.reduceRoundPrompt(RU)).doesNotContain("English");
    assertThat(Prompts.shortPrompt(EN)).doesNotContain("Russian");
  }

  @Test
  void aShortRussianTranscriptIsSentOnceWithTheRussianPrompt() {
    ScriptedLlm llm = new ScriptedLlm((i, s, u) -> valid("- Встреча во вторник.", s, u, RU));

    String summary = service(llm).summarize("Встреча состоится во вторник.", RU);

    assertThat(llm.calls).hasSize(1);
    assertThat(llm.calls.get(0).system()).isEqualTo(Prompts.shortPrompt(RU));
    assertThat(llm.calls.get(0).user()).isEqualTo("Встреча состоится во вторник.");
    assertThat(summary).isEqualTo("• Встреча во вторник.");
  }

  @Test
  void aLongRussianTranscriptUsesRussianPromptsAtEveryStage() {
    SummaryService probe = service(always("- a"));
    ScriptedLlm llm = bigAnswers(probe, RU);

    service(llm).summarize("я".repeat(probe.maxChunkChars(RU) * 16 + 1), RU);

    assertThat(llm.calls).isNotEmpty();
    assertThat(llm.calls)
        .extracting(Call::system)
        .allMatch(system ->
            system.equals(Prompts.mapPrompt(RU))
                || system.equals(Prompts.reduceRoundPrompt(RU))
                || system.equals(Prompts.reduceFinalPrompt(RU)));
    assertThat(llm.calls.get(llm.calls.size() - 1).system())
        .isEqualTo(Prompts.reduceFinalPrompt(RU));
    assertThat(llm.calls).anyMatch(c -> c.system().equals(Prompts.reduceRoundPrompt(RU)));
    for (Call call : llm.calls) {
      assertFits(call, NUM_CTX, RU);
    }
  }

  @Test
  void aNullLanguageIsRejected() {
    assertThatThrownBy(() -> service(always("- a")).summarize("text", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theLongestOfTheFourPromptsDefinesTheBudget() {
    assertThat(promptTokens(EN))
        .isEqualTo(
            Math.max(
                TokenEstimator.estimate(Prompts.shortPrompt(EN), EN),
                Math.max(
                    TokenEstimator.estimate(Prompts.mapPrompt(EN), EN),
                    Math.max(
                        TokenEstimator.estimate(Prompts.reduceFinalPrompt(EN), EN),
                        TokenEstimator.estimate(Prompts.reduceRoundPrompt(EN), EN)))));
    assertThat(Prompts.reduceFinalPrompt(EN).length())
        .isGreaterThan(Prompts.reduceRoundPrompt(EN).length());
    assertThat(Prompts.reduceFinalPrompt(EN).length())
        .isGreaterThan(Prompts.mapPrompt(EN).length());
  }

  @Test
  void aDifferentContextChangesTheBudgetByThreePerToken() {
    SummaryService base = new SummaryService(always("- a"), config(8192));
    SummaryService larger = new SummaryService(always("- a"), config(8192 + 100));

    assertThat(larger.maxChunkChars(EN) - base.maxChunkChars(EN)).isEqualTo(300);
  }

  @Test
  void aContextBelowTheMinimumBudgetIsRejected() {
    int minimum = 2 * 1024 + 1024 + Math.max(promptTokens(EN), promptTokens(RU)) + 32;

    assertThat(new SummaryService(always("- a"), config(minimum)).partialMaxChars(EN))
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

    assertThat(huge.maxChunkChars(EN)).isPositive();
  }

  @Test
  void aContextWhoseCharacterBudgetOverflowsAnIntIsRejected() {
    int largest = Integer.MAX_VALUE / 3 + 1024 + promptTokens(EN) + 32;

    assertThat(new SummaryService(always("- a"), config(largest)).maxChunkChars(EN)).isPositive();
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

    String summary = service(llm).summarize("  The budget meeting is on Tuesday.  \n", EN);

    assertThat(llm.calls).hasSize(1);
    Call call = llm.calls.get(0);
    assertThat(call.system()).isEqualTo(Prompts.shortPrompt(EN));
    assertThat(call.user()).isEqualTo("The budget meeting is on Tuesday.");
    assertThat(call.maxOutputTokens()).isEqualTo(1024);
    assertThat(summary).isEqualTo("• Tuesday meeting\n• Budget");
  }

  @Test
  void aTranscriptOfExactlyTheBudgetIsStillOneRequest() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);
    String text = "a".repeat(service.maxChunkChars(EN));

    service.summarize(text, EN);

    assertThat(llm.calls).hasSize(1);
    assertThat(llm.calls.get(0).user()).isEqualTo(text);
    assertFits(llm.calls.get(0), NUM_CTX);
  }

  @Test
  void aTranscriptOneBelowTheBudgetIsOneRequest() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);

    service.summarize("a".repeat(service.maxChunkChars(EN) - 1), EN);

    assertThat(llm.calls).hasSize(1);
  }

  @Test
  void aTranscriptOneOverTheBudgetIsSplitAndReduced() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);
    int max = service.maxChunkChars(EN);

    service.summarize("a".repeat(max + 1), EN);

    assertThat(llm.calls).hasSize(3);
    assertThat(llm.calls.get(0).system()).isEqualTo(Prompts.mapPrompt(EN));
    assertThat(llm.calls.get(0).user()).isEqualTo("a".repeat(max));
    assertThat(llm.calls.get(1).user()).isEqualTo("a");
    assertThat(llm.calls.get(2).system()).isEqualTo(Prompts.reduceFinalPrompt(EN));
  }

  @Test
  void whitespaceAroundTheTranscriptDoesNotCountTowardTheBudget() {
    ScriptedLlm llm = always("- a");
    SummaryService service = service(llm);

    service.summarize("  " + "a".repeat(service.maxChunkChars(EN)) + "\n\n", EN);

    assertThat(llm.calls).hasSize(1);
  }

  // ---- map and reduce ----

  @Test
  void everyChunkIsSummarizedInOrderThenAllPartialsAreReduced() {
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> valid(i < 3 ? "- part " + (i + 1) : "- final", s, u));
    SummaryService service = service(llm);
    String text = words(service.maxChunkChars(EN) * 5 / 2);
    List<String> chunks = TextChunker.split(text, service.maxChunkChars(EN));
    assertThat(chunks).hasSize(3);

    String summary = service.summarize(text, EN);

    assertThat(llm.calls).hasSize(4);
    for (int i = 0; i < 3; i++) {
      assertThat(llm.calls.get(i).system()).isEqualTo(Prompts.mapPrompt(EN));
      assertThat(llm.calls.get(i).user()).isEqualTo(chunks.get(i));
    }
    assertThat(llm.calls.get(3).system()).isEqualTo(Prompts.reduceFinalPrompt(EN));
    assertThat(llm.calls.get(3).user()).isEqualTo("- part 1\n\n- part 2\n\n- part 3");
    assertThat(summary).isEqualTo("• final");
    assertThat(llm.calls).allSatisfy(call -> assertThat(call.maxOutputTokens()).isEqualTo(1024));
    assertThat(llm.calls).allSatisfy(call -> assertFits(call, NUM_CTX));
  }

  @Test
  void onlyTheFinalAnswerIsFormatted() {
    ScriptedLlm llm = new ScriptedLlm((i, s, u) -> valid(i < 2 ? "* mapped" : "* final", s, u));
    SummaryService service = service(llm);

    String summary = service.summarize("a".repeat(service.maxChunkChars(EN) + 1), EN);

    assertThat(llm.calls.get(2).user()).isEqualTo("* mapped\n\n* mapped");
    assertThat(summary).isEqualTo("• final");
  }

  @Test
  void partialsThatDoNotFitAreReducedInRoundsInOrder() {
    SummaryService probe = service(always("- a"));
    int max = probe.maxChunkChars(EN);
    int partialMax = probe.partialMaxChars(EN);
    int chunks = 9;
    String text = "a".repeat(max * (chunks - 1) + 1);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> {
          if (s.equals(Prompts.mapPrompt(EN))) {
            return valid(i + "-" + "x".repeat(partialMax * 3), s, u);
          }
          return valid(s.equals(Prompts.reduceFinalPrompt(EN)) ? "- final" : "- group " + i, s, u);
        });

    String summary = service(llm).summarize(text, EN);

    // 9 maps, groups of 4, 4 and 1, then one final reduction of the 3 group answers
    assertThat(llm.calls).hasSize(9 + 3 + 1);
    assertThat(llm.calls.subList(0, 9))
        .allSatisfy(c -> assertThat(c.system()).isEqualTo(Prompts.mapPrompt(EN)));
    List<Call> round = llm.calls.subList(9, 12);
    assertThat(round)
        .allSatisfy(c -> assertThat(c.system()).isEqualTo(Prompts.reduceRoundPrompt(EN)));
    assertThat(round.get(0).user()).startsWith("0-x").contains("\n\n1-x", "\n\n2-x", "\n\n3-x");
    assertThat(round.get(1).user()).startsWith("4-x").contains("\n\n5-x", "\n\n6-x", "\n\n7-x");
    assertThat(round.get(2).user()).startsWith("8-x").doesNotContain("\n\n");
    assertThat(round.get(0).user().length()).isLessThanOrEqualTo(max);
    assertThat(round.get(0).user().length()).isEqualTo(4 * partialMax + 6);
    assertThat(llm.calls.get(12).system()).isEqualTo(Prompts.reduceFinalPrompt(EN));
    assertThat(llm.calls.get(12).user()).isEqualTo("- group 9\n\n- group 10\n\n- group 11");
    assertThat(summary).isEqualTo("• final");
    assertThat(llm.calls)
        .allSatisfy(c -> assertThat(c.user().length()).isLessThanOrEqualTo(max))
        .allSatisfy(c -> assertFits(c, NUM_CTX));
  }

  @Test
  void aGroupStartsANewOneWhenTheNextPartialWouldNotFit() {
    SummaryService probe = service(always("- a"));
    int max = probe.maxChunkChars(EN);
    int partialMax = probe.partialMaxChars(EN);
    // partials of partialMax + 1 chars cannot be clipped, but 4 of them plus separators exceed max
    String text = "a".repeat(max * 4 + 1);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) -> {
          if (s.equals(Prompts.mapPrompt(EN))) {
            return valid("y".repeat(partialMax), s, u);
          }
          return valid("- ok", s, u);
        });

    service(llm).summarize(text, EN);

    List<Call> round =
        llm.calls.stream().filter(c -> c.system().equals(Prompts.reduceRoundPrompt(EN))).toList();
    assertThat(round).hasSize(2);
    assertThat(round.get(0).user()).hasSize(4 * partialMax + 6);
    assertThat(round.get(1).user()).hasSize(partialMax);
  }

  @Test
  void anOverlongAnswerIsClippedToTheBoundBeforeItIsUsed() {
    SummaryService probe = service(always("- a"));
    int partialMax = probe.partialMaxChars(EN);
    String line = "z".repeat(partialMax * 2 / 5);
    ScriptedLlm llm =
        new ScriptedLlm((i, s, u) ->
            valid(
                s.equals(Prompts.mapPrompt(EN)) ? line + "\n" + line + "\n" + line : "- done",
                s,
                u));

    service(llm).summarize("a".repeat(probe.maxChunkChars(EN) + 1), EN);

    // three lines of two fifths of the bound: the third is dropped
    assertThat(llm.calls.get(2).user()).isEqualTo(line + "\n" + line + "\n\n" + line + "\n" + line);
  }

  /** Every map and round answer is as long as an intermediate answer may be. */
  private static ScriptedLlm bigAnswers(SummaryService probe) {
    return bigAnswers(probe, EN);
  }

  private static ScriptedLlm bigAnswers(SummaryService probe, Language language) {
    String big = "y".repeat(probe.partialMaxChars(language));
    return new ScriptedLlm((i, s, u) ->
        valid(s.equals(Prompts.reduceFinalPrompt(language)) ? "- final" : big, s, u, language));
  }

  @Test
  void seventeenPartialsNeedTwoRoundsOfReduction() {
    SummaryService probe = service(always("- a"));
    ScriptedLlm llm = bigAnswers(probe);

    String summary = service(llm).summarize("a".repeat(probe.maxChunkChars(EN) * 16 + 1), EN);

    // 17 maps, round one: 5 groups, round two: 2 groups, then the final reduction
    assertThat(llm.calls).hasSize(17 + 5 + 2 + 1);
    assertThat(llm.calls.get(llm.calls.size() - 1).system())
        .isEqualTo(Prompts.reduceFinalPrompt(EN));
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
            "a".repeat(probe.maxChunkChars(EN) * 16 + 1));

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
            .summarize("a".repeat(probe.maxChunkChars(EN) * 4 + 1), EN);

    // 5 maps, one round of 2 groups, then the final reduction
    assertThat(llm.calls).hasSize(5 + 2 + 1);
    assertThat(summary).isEqualTo("• final");
  }

  // ---- input ----

  @Test
  void aNullTranscriptIsAProgrammingError() {
    ScriptedLlm llm = always("- a");

    assertThatThrownBy(() -> service(llm).summarize(null, EN))
        .isInstanceOf(NullPointerException.class);
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
    return "a".repeat(service(always("- a")).maxChunkChars(EN) * 2 + 1);
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
    long expected = TokenEstimator.requestEstimate(system, user, EN);
    return (expected + 1) / 2;
  }

  @Test
  void theShortPathRejectsOneBelowTheThresholdAndAcceptsTheThreshold() {
    String text = "x".repeat(100);
    long threshold = threshold(Prompts.shortPrompt(EN), text);

    ScriptedLlm below = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold - 1));
    JobFailure failure = failureOf(service(below), text);
    assertThat(failure.kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(failure.getMessage()).isEqualTo(FAILED_MESSAGE);
    assertThat(failure.getCause()).isNull();

    ScriptedLlm at = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold));
    assertThat(service(at).summarize(text, EN)).isEqualTo("• a");
  }

  @Test
  void theThresholdRoundsUpForAnOddEstimate() {
    // estimate(system) + estimate(user) + 32 is odd for this text, so ceil(expected / 2) matters
    String text = "x".repeat(100);
    long expected = TokenEstimator.requestEstimate(Prompts.shortPrompt(EN), text, EN);
    String odd = expected % 2 == 1 ? text : "x".repeat(103);
    long oddExpected = TokenEstimator.requestEstimate(Prompts.shortPrompt(EN), odd, EN);
    assertThat(oddExpected % 2).isEqualTo(1);
    long threshold = (oddExpected + 1) / 2;

    ScriptedLlm at = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold));
    ScriptedLlm below = new ScriptedLlm((i, s, u) -> new LlmReply("- a", threshold - 1));

    assertThat(service(at).summarize(odd, EN)).isEqualTo("• a");
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

    assertThat(service(llm).summarize(text, EN)).isEqualTo("• a");
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
    List<String> chunks = TextChunker.split(text, service(always("- a")).maxChunkChars(EN));
    long threshold = threshold(Prompts.mapPrompt(EN), chunks.get(1));

    ScriptedLlm below =
        new ScriptedLlm(
            (i, s, u) -> i == 1 ? new LlmReply("- a", threshold - 1) : valid("- a", s, u));
    ScriptedLlm at =
        new ScriptedLlm((i, s, u) -> i == 1 ? new LlmReply("- a", threshold) : valid("- a", s, u));

    assertThat(failureOf(service(below), text).kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(service(at).summarize(text, EN)).isEqualTo("• a");
  }

  @Test
  void aReductionStageAcceptsItsThresholdAndRejectsOneBelow() {
    String joined = "- a\n\n- a\n\n- a";
    long threshold = threshold(Prompts.reduceFinalPrompt(EN), joined);
    String text = threeChunks();

    ScriptedLlm below =
        new ScriptedLlm(
            (i, s, u) -> i == 3 ? new LlmReply("- a", threshold - 1) : valid("- a", s, u));
    ScriptedLlm at =
        new ScriptedLlm((i, s, u) -> i == 3 ? new LlmReply("- a", threshold) : valid("- a", s, u));

    assertThat(failureOf(service(below), text).kind()).isEqualTo(JobFailure.Kind.FAILED);
    assertThat(service(at).summarize(text, EN)).isEqualTo("• a");
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

    assertThat(service(llm).summarize("hello", EN)).hasSize(1500).endsWith("…");
  }
}
