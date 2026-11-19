package com.video2podcast.job;

import java.time.Instant;

/** What the pipeline needs to know about one accepted link. */
public record JobRequest(
    String jobId, long ownerUserId, long chatId, String url, Instant createdAt) {}
