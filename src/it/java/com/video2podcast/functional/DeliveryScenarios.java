package com.video2podcast.functional;

import static io.cucumber.junit.platform.engine.Constants.*;

import org.junit.platform.suite.api.*;

/**
 * Focused T12/T13 regression suite: run explicitly with -Dit.test=DeliveryScenarios. The default
 * *IT run uses CucumberIT, which already selects these features.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.video2podcast.functional")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@T12 or @T13")
@ConfigurationParameter(key = JUNIT_PLATFORM_NAMING_STRATEGY_PROPERTY_NAME, value = "long")
public class DeliveryScenarios {}
