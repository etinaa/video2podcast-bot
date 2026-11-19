package com.video2podcast.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

  private static String withoutWhitespace(String text) {
    return text.replaceAll("\\s", "");
  }

  @Test
  void textWithinTheLimitIsOneUnchangedChunk() {
    assertThat(TextChunker.split(" abc def ", 9)).containsExactly(" abc def ");
    assertThat(TextChunker.split("abc", 10)).containsExactly("abc");
  }

  @Test
  void textOneOverTheLimitIsSplit() {
    assertThat(TextChunker.split("aaaaaaaaaa", 9)).containsExactly("aaaaaaaaa", "a");
  }

  @Test
  void cutsAfterTheLastWhitespaceInTheSecondHalfOfTheWindow() {
    // window "aaa bb cc " is 10 chars; the last whitespace is at index 9
    List<String> chunks = TextChunker.split("aaa bb cc dddd eeee", 10);

    assertThat(chunks).containsExactly("aaa bb cc", "dddd eeee");
  }

  @Test
  void whitespaceRightAfterTheWindowAlsoEndsTheChunk() {
    List<String> chunks = TextChunker.split("aaaa bbbb cccc", 9);

    assertThat(chunks).containsExactly("aaaa bbbb", "cccc");
  }

  @Test
  void whitespaceExactlyAtTheHalfOfTheWindowIsUsed() {
    // index 5 == maxChars / 2 for maxChars 10
    assertThat(TextChunker.split("aaaaa bbbbbbbbbbbb", 10))
        .containsExactly("aaaaa", "bbbbbbbbbb", "bb");
  }

  @Test
  void whitespaceBeforeTheHalfOfTheWindowIsIgnored() {
    // the only whitespace is at index 3 < 5, so the cut is at the limit
    assertThat(TextChunker.split("aaa bbbbbbbbbbbbbb", 10))
        .containsExactly("aaa bbbbbb", "bbbbbbbb");
  }

  @Test
  void oneLongWordIsCutIntoPiecesOfTheLimit() {
    assertThat(TextChunker.split("a".repeat(25), 10))
        .containsExactly("a".repeat(10), "a".repeat(10), "a".repeat(5));
  }

  @Test
  void aSurrogatePairIsNotSplit() {
    String text = "a".repeat(9) + "😀" + "b".repeat(5);

    List<String> chunks = TextChunker.split(text, 10);

    assertThat(chunks).containsExactly("a".repeat(9), "😀" + "b".repeat(5));
  }

  @Test
  void aPairIsKeptWhenItEndsExactlyAtTheLimit() {
    String text = "a".repeat(8) + "😀" + "b".repeat(5);

    assertThat(TextChunker.split(text, 10)).containsExactly("a".repeat(8) + "😀", "b".repeat(5));
  }

  @Test
  void aLoneHighSurrogateAtTheLimitIsNotBackedOff() {
    String text = "a".repeat(9) + "\uD83D" + "b".repeat(5);

    assertThat(TextChunker.split(text, 10))
        .containsExactly("a".repeat(9) + "\uD83D", "b".repeat(5));
  }

  @Test
  void windowOfTwoWithAPairStillMakesProgress() {
    List<String> chunks = TextChunker.split("😀😀😀", 2);

    assertThat(chunks).containsExactly("😀", "😀", "😀");
  }

  @Test
  void aWindowOfOneAlwaysMakesProgress() {
    assertThat(TextChunker.split("😀", 1)).containsExactly("\uD83D", "\uDE00");
  }

  @Test
  void emptyChunksAreDropped() {
    // after the first cut only whitespace is left in the window
    List<String> chunks = TextChunker.split("aaaa" + " ".repeat(30) + "bbbb", 8);

    assertThat(chunks).doesNotContain("");
    assertThat(String.join("", chunks)).isEqualTo("aaaabbbb");
  }

  @Test
  void everyChunkFitsAndTheTextIsKeptInOrderWithoutGaps() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      builder.append("word").append(i).append(i % 7 == 0 ? "\n" : " ");
    }
    String text = builder.toString();

    List<String> chunks = TextChunker.split(text, 100);

    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(100));
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isEqualTo(chunk.strip()).isNotEmpty());
    assertThat(withoutWhitespace(String.join("", chunks))).isEqualTo(withoutWhitespace(text));
    assertThat(chunks.size()).isGreaterThan(4);
  }

  @Test
  void theFinalRemainderIsKept() {
    List<String> chunks = TextChunker.split("aaaa bbbb cc", 9);

    assertThat(chunks).containsExactly("aaaa bbbb", "cc");
  }
}
