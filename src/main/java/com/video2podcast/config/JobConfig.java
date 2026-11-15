package com.video2podcast.config;

import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JobConfig {

  @Bean
  JobStore jobStore(AppProperties props, Clock clock) {
    return new JobStore(props.jobs().workDir(), clock);
  }

  @Bean
  JobQueue jobQueue() {
    return new JobQueue();
  }
}
