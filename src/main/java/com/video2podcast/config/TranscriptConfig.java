package com.video2podcast.config;

import com.video2podcast.transcript.TranscriptionClient;
import com.video2podcast.transcript.WhisperClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TranscriptConfig {

  @Bean
  WhisperClient whisperClient(AppProperties props) {
    return new WhisperClient(props.whisper());
  }

  @Bean
  TranscriptionClient transcriptionClient(WhisperClient client) {
    return new TranscriptionClient(client);
  }
}
