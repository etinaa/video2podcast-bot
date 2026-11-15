package com.video2podcast.bot;

/** A single inline keyboard button. {@code callbackData} must be at most 64 bytes. */
public record InlineButton(String text, String callbackData) {}
