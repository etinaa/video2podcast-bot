package com.video2podcast.access;

import java.time.Instant;

/** A user on the allowlist. {@code username} may be null (not every Telegram user has one). */
public record AllowedUser(long id, String username, Instant addedAt, long addedBy) {}
