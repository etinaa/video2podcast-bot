package com.video2podcast.summary;

import com.video2podcast.job.Language;

/** The system prompts, written in English. Every one asks for the answer in the given language. */
final class Prompts {

  private Prompts() {}

  static String shortPrompt(Language language) {
    return "You summarize a video transcript. Write the final summary as "
        + language.displayName()
        + " bullet points. Start every line with \"- \". Use at most 8 bullets and keep each"
        + " bullet short. Answer with the bullet points only, without a preamble.";
  }

  static String mapPrompt(Language language) {
    return "You summarize one part of a longer video transcript. List the key points of this part"
        + " as "
        + language.displayName()
        + " bullet points. Start every line with \"- \". Use 3 to 8 bullets. Answer with the"
        + " bullet points only, without a preamble.";
  }

  static String reduceFinalPrompt(Language language) {
    return "You are given bullet-point summaries of consecutive parts of one video transcript."
        + " Write the final summary of the whole video as "
        + language.displayName()
        + " bullet points. Start every line with \"- \". Use at most 8 bullets and keep each"
        + " bullet short. Answer with the bullet points only, without a preamble.";
  }

  static String reduceRoundPrompt(Language language) {
    return "You are given bullet-point summaries of consecutive parts of one video transcript."
        + " Merge them into one shorter "
        + language.displayName()
        + " bullet list that keeps the important points. Start every line with \"- \". Answer"
        + " with the bullet points only, without a preamble.";
  }
}
