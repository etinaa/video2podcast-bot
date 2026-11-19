package com.video2podcast.summary;

/** A valid answer: non-blank {@code content} and the prompt tokens the backend evaluated. */
public record LlmReply(String content, long promptTokens) {}
