package com.video2podcast.access;

import java.time.Instant;

/** Records when a user was denied, so a new request can be refused during the cooldown. */
public record Denial(long userId, Instant deniedAt) {}
