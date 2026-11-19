package com.video2podcast.summary;

/** A chat backend that answers one prompt at a time. */
public interface LlmClient {

  /**
   * One chat completion. Returns the non-blank answer text and the number of prompt tokens the
   * backend says it evaluated.
   *
   * @param maxOutputTokens upper bound for the answer
   * @throws LlmException connection, timeout or any invalid answer
   */
  LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException;
}
