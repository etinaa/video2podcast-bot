package com.video2podcast.functional;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/** Runs the Gherkin features in {@code src/features} as part of the IT phase. */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.video2podcast.functional")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
// T8 scenarios are deferred to the pipeline wiring in T11.
// TD2 scenarios are covered by the Developer-owned JobQueueTest.
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @T8 and not @TD2")
public class CucumberIT {}
