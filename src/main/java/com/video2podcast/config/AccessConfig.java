package com.video2podcast.config;

import com.video2podcast.access.AccessService;
import com.video2podcast.access.AccessStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccessConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  AccessStore accessStore(AppProperties props) {
    return new AccessStore(props.access().file());
  }

  @Bean
  AccessService accessService(AccessStore store, AppProperties props, Clock clock) {
    return new AccessService(
        store, props.telegram().adminUserId(), props.access().requestCooldown(), clock);
  }
}
