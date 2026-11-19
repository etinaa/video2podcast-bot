package com.video2podcast.summary;

/** The system prompts. Every one asks for English, whatever the language of the input. */
final class Prompts {

  static final String SHORT =
      "You summarize a video transcript. Write the final summary as English bullet points, even if"
          + " the transcript is not in English. Start every line with \"- \". Use at most 8"
          + " bullets and keep each bullet short. Answer with the bullet points only, without a"
          + " preamble.";

  static final String MAP =
      "You summarize one part of a longer video transcript. List the key points of this part as"
          + " English bullet points, even if the text is not in English. Start every line with"
          + " \"- \". Use 3 to 8 bullets. Answer with the bullet points only, without a"
          + " preamble.";

  static final String REDUCE_FINAL =
      "You are given bullet-point summaries of consecutive parts of one video transcript. Write"
          + " the final summary of the whole video as English bullet points, even if the input is"
          + " not in English. Start every line with \"- \". Use at most 8 bullets and keep each"
          + " bullet short. Answer with the bullet points only, without a preamble.";

  static final String REDUCE_ROUND =
      "You are given bullet-point summaries of consecutive parts of one video transcript. Merge"
          + " them into one shorter English bullet list that keeps the important points, even if"
          + " the input is not in English. Start every line with \"- \". Answer with the bullet"
          + " points only, without a preamble.";

  private Prompts() {}
}
