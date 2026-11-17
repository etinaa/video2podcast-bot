@TD3 @build_contract
Feature: Opt-in mutation gate for production code
  Maintainers run PIT against production code using only unit tests.
  The minimum aggregate mutation score is 85 percent.
  These build contracts require isolated build fixtures or recorded project runs.
  Application Cucumber must not recursively launch its own Maven verification.
  Tester confirmed real-PIT fixtures and project evidence in TD3 Q-analyst-1.
  Tester adds the application runner exclusion in Step 2.
  Example scores are illustrative; project acceptance uses measured scores.
  Fixture scores and exit codes must come from actual PIT execution.

  @R1 @R3 @R7
  Scenario: The mutation profile completes a real analysis
    Given the project uses Java 27 and Spring Boot 4.1.2
    And the original unit tests pass without real external services or secrets
    And the full production mutation score is 86 percent
    When the maintainer runs "./mvnw verify -Pmutation"
    Then the build exits with code 0
    And PIT writes a readable report containing more than 0 generated mutants
    And the report identifies the production mutation targets and unit-test results

  @R3
  Scenario Outline: The aggregate mutation score controls the gate
    Given an isolated mutation build fixture with an aggregate score of <score> percent
    And the mutation threshold is 85 percent
    And all other build checks pass
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then the mutation gate <outcome>

    Examples:
      | score | outcome         |
      | 84    | fails the build |
      | 85    | passes          |
      | 86    | passes          |

  @R2 @R3
  Scenario: An integration-only assertion cannot improve the mutation score
    Given an isolated mutation build fixture with a production method returning 7
    And unit tests execute that method without checking the return value
    And an integration test checks that the method returns 7
    And integration tests and a Cucumber suite are present in the compiled test output
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then the mutant returning 0 survives or is uncovered by unit tests
    And PIT does not execute the integration test or the Cucumber suite
    And the mutant counts against the mutation score

  @R2
  Scenario: A unit assertion can detect the same mutant
    Given an isolated mutation build fixture with a production method returning 7
    And a unit test checks that the method returns 7
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then PIT reports the mutant returning 0 as detected by the unit test

  @R2 @R6
  Scenario: Targets cover main code without mutating test helpers
    Given all current production packages are included in the mutation target selection
    And a test-only helper named "MutationFixtureHelper" is compiled from "src/test/java"
    And no project class exclusions have been approved
    When the maintainer inspects the effective mutation configuration and PIT report
    Then no production package is omitted by project target filters
    And "MutationFixtureHelper" is not a mutation target
    And dependencies are not mutation targets

  @R3
  Scenario: Empty mutation selection is a failure
    Given an isolated mutation build fixture whose target filter matches no production classes
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then the build exits with a nonzero code
    And the build reports that no mutations were found

  @R3 @R7
  Scenario Outline: A broken analysis cannot pass the mutation gate
    Given an isolated mutation build fixture with <failure>
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then the build exits with a nonzero code
    And the build reports the failure instead of a passing mutation result

    Examples:
      | failure                              |
      | a failing original unit test         |
      | unusable PIT test discovery           |
      | an incompatible PIT runtime setup    |

  @R3 @R8
  Scenario: Raising the threshold proves enforcement
    Given a completed full production mutation run with a score of 86 percent
    And all other build checks pass
    When the maintainer temporarily raises the mutation threshold to 87 percent and reruns the analysis
    Then the build exits with a nonzero code because the mutation score is below the threshold
    And the verification record contains the command and the mutation-gate failure

  @R3 @R8
  Scenario: The normal threshold is restored after the negative proof
    Given a mutation-gate failure was demonstrated with a temporary threshold of 87 percent
    When the maintainer restores the mutation threshold to 85 percent and runs "./mvnw verify -Pmutation"
    Then the build exits with code 0
    And the committed mutation configuration retains the threshold of 85 percent

  @R3 @R8
  Scenario: A perfect score uses a controlled fixture for negative proof
    Given a completed full production mutation run with a score of 100 percent
    When the maintainer runs an isolated mutation build fixture scoring 84 percent against a threshold of 85 percent
    Then the build exits with a nonzero code because the mutation score is below the threshold
    And the verification record explains that no valid PIT threshold exceeds 100 percent

  @R4
  Scenario: A weak baseline is improved through unit assertions
    Given the first full production mutation run scores 80 percent
    And its command, versions, scope, counts, report location and exit result are recorded
    When the Developer strengthens unit assertions and repeats the full analysis with the same scope and operators
    Then the final recorded mutation score is at least 85 percent
    And the mutation build exits with code 0
    And existing assertions and tests have not been removed to obtain that result

  @R4
  Scenario: A survivor reveals a real production defect
    Given a surviving mutant exposes behaviour that violates an existing requirement
    When the Developer changes production code to fix the defect
    Then a regression unit test checks the violated behaviour
    And the task evidence identifies the defect and the requirement it violated

  @R5
  Scenario Outline: Ordinary verification does not perform mutation work
    Given fresh build outputs and passing existing checks
    When the maintainer runs "<command>"
    Then the build exits with code 0
    And PIT performs no discovery or mutation analysis
    And no fresh mutation report is created
    And the existing unit and integration test selection for that command is preserved

    Examples:
      | command                    |
      | ./mvnw verify              |
      | ./mvnw verify -Dno-it      |

  @R6
  Scenario: An explicit class exclusion needs a documented approval
    Given the implementation proposes excluding "com.video2podcast.Video2PodcastApplication" from PIT
    When the team reviews the mutation configuration for acceptance
    Then "pom.xml" lists the precise exclusion and its reason
    And task communications contain owner approval of that exact exclusion before it is accepted

  @R7
  Scenario Outline: JaCoCo still rejects an insufficient unit coverage ratio
    Given a mutation build fixture whose mutation score is 86 percent
    And its unit line coverage is <line> percent
    And its unit branch coverage is <branch> percent
    And all other build checks pass
    When the maintainer runs "./mvnw verify -Pmutation" in the fixture
    Then the build exits with a nonzero code because the JaCoCo gate fails
    And integration coverage does not contribute to the unit coverage ratios

    Examples:
      | line | branch |
      | 89   | 90     |
      | 90   | 89     |

  @R8
  Scenario: Maintainers can find the command and reports
    When the maintainer reads "CLAUDE.md" and the mutation instructions under "docs/"
    Then both explain "./mvnw verify -Pmutation" and the minimum mutation score of 85 percent
    And the instructions identify the report location and how to inspect surviving mutants
    And the instructions explain the main-code and unit-test-only scope
    And the instructions explain that ordinary verification does not run PIT
