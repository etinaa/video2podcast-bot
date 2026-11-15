package com.video2podcast.bot;

/** Who sent a private message: the user's id and name, and the chat to answer in. */
record Sender(long userId, long chatId, String username, String displayName) {}
