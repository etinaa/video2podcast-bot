package com.video2podcast.functional;

import io.cucumber.java.Before;
import io.cucumber.spring.CucumberContextConfiguration;

/**
 * Gives Cucumber scenarios the same application context and WireMock servers as the JUnit
 * functional tests. JUnit's {@code @BeforeEach} does not run under Cucumber, so the mocks are reset
 * in a scenario hook instead.
 */
@CucumberContextConfiguration
public class CucumberSpringConfiguration extends FunctionalTest {

  @Before
  public void resetBetweenScenarios() {
    resetMocks();
  }
}
