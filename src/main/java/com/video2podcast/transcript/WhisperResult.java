package com.video2podcast.transcript;

/**
 * The answer of whisper-server to one chunk.
 *
 * @param text the raw "text" field, not normalized
 * @param language the ISO code in detect mode when the text is not empty; otherwise null
 */
public record WhisperResult(String text, String language) {}
