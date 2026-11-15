package com.video2podcast;

import com.video2podcast.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class Video2PodcastApplication {

  public static void main(String[] args) {
    SpringApplication.run(Video2PodcastApplication.class, args);
  }
}
