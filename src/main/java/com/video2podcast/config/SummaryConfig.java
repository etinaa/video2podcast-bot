package com.video2podcast.config;

import com.video2podcast.summary.LlmClient;
import com.video2podcast.summary.OllamaClient;
import com.video2podcast.summary.SummaryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SummaryConfig {

  @Bean
  LlmClient llmClient(AppProperties props) {
    return new OllamaClient(props.ollama());
  }

  @Bean
  SummaryService summaryService(LlmClient llm, AppProperties props) {
    return new SummaryService(llm, props.ollama());
  }
}
