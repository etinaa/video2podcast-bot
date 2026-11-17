# TD3 research

Researched: 2026-11-17. Scope: PIT Maven gate, unit/IT separation and verification evidence.
This is Analyst research, not an implementation report or a measured mutation baseline.

## Official sources

All links accessed on 2026-11-17. Version-specific behaviour must be confirmed against the pinned
implementation version; the public quickstart and repository default branch can change.

| Source | Fact taken from the source | Application to TD3 |
|---|---|---|
| [PIT Maven quickstart](https://pitest.org/quickstart/maven/) | `mutationCoverage` runs analysis; `targetClasses` and `targetTests` select classes and tests. | Bind the analysis to the opt-in lifecycle. Select production targets and unit tests explicitly. |
| [PIT Maven parameters](https://pitest.org/quickstart/maven/) | `mutationThreshold` is an aggregate killed/all percentage, 0–100. `coverageThreshold` and test strength are different metrics. | Use mutation threshold 85; uncovered mutants cannot be omitted by substituting test strength. |
| [PIT Maven parameters](https://pitest.org/quickstart/maven/) | Threshold precision defaults to integer percentages. Configurable precision is documented. | Test the comparison boundary; a rounded display alone is insufficient evidence. |
| [PIT Maven parameters](https://pitest.org/quickstart/maven/) | No-mutation failure defaults to true. HTML/XML/CSV reports are available. Exact class globs omit nested classes unless expanded. Excluded tests can leak through suites. | Preserve nonempty analysis, review filters/suites and retain a readable report. |
| [PIT JUnit 5 plugin](https://github.com/pitest/pitest-junit5-plugin) | Supports JUnit Platform engines, including Jupiter and Cucumber. Plugin 1.2.3 requires PIT 1.19.4 or newer. It belongs on PIT's tool classpath, rather than the application classpath. | Add a compatible pinned tool dependency; do not assume Jupiter support implies Cucumber is excluded. Version minima alone do not prove compatibility with this project. |
| [PIT FAQ](https://pitest.org/faq/) | PIT mutates bytecode. Its discovery can find tests omitted by normal build configuration. Failures seen only under PIT can arise from environment or order dependence. Mutation testing is computationally expensive. | Check actual test selection and run results; do not infer scope from Surefire alone. Keep the expensive work opt-in. |
| [PIT release history](https://github.com/hcoles/pitest/releases) and [upstream changelog](https://raw.githubusercontent.com/hcoles/pitest/master/README.md) | The fetched changelog lists PIT 1.30.1 upgrading ASM to 9.11, 1.25.7 disabling JaCoCo transformations at bytecode level, and 1.25.4 adding configurable threshold precision. | These are relevant compatibility leads, not proof of Java 27 or this Boot/JUnit stack. Architect/Developer must choose and pin a version and demonstrate a real run; do not copy an old quickstart example version blindly. |
| [Maven build profiles](https://maven.apache.org/guides/introduction/introduction-to-profiles.html) | `-P` activates named profiles in addition to profiles activated by other conditions. `activeByDefault` has different deactivation semantics from property activation. | The repository's `it` profile uses absence of `no-it`, so adding `-Pmutation` must not accidentally remove ordinary Failsafe execution. |

## Local evidence

Paths are relative to the repository root and were inspected on 2026-11-17 in the shared working tree.
They describe the state at analysis time and may change as other roles work.

| Source | Finding |
|---|---|
| `tasks/TD3/brief.md`, TD3 in `tasks/todo.md` | Owner chose a separate `mutation` Maven profile, a minimum mutation score of 85%, unit tests only, and test improvements within TD3. Class exclusions need individual reasons in `pom.xml` and owner approval. |
| `.claude/roles/analyst.md`, `.claude/roles/common.md`, `CLAUDE.md` | Analyst owns requirements, research and English requirement-tagged scenarios. Developer owns unit tests/build configuration; Tester owns `src/it`; orchestrator owns commits. Questions are append-only in task communications. |
| `SPEC.md`, `tasks/plan.md` | Java 27 / Boot 4.1.2, a unit-derived 90% line and branch coverage gate, no secrets or external service dependencies for tests. TD3 changes build tooling, not Telegram/media/transcription requirements. |
| `pom.xml` | No PIT configuration at inspection. Surefire excludes `*IT`; JaCoCo uses a unit-only agent argument and bundle minimums of `0.90` for LINE and BRANCH. Format, SpotBugs/FindSecBugs and PMD checks already run at verify. |
| `pom.xml`, profile `it` | The `!no-it` property activation adds `src/it/java` as a test source and feature resources to the test classpath. Failsafe runs `*IT` without the unit JaCoCo agent. Thus unit and integration classes may coexist in `target/test-classes`. |
| `src/test/java` | Unit tests follow `*Test` naming, including `TranscriptionAssembledTest`. Non-test helpers include `FakeProcessRunner`, `RecordingGateway` and `MutableClock`. Name filtering needs to retain all actual unit tests without mutating helpers. |
| `src/main/java` | Production packages include `access`, `bot`, `config`, `job`, `media`, `storage`, `transcript`, and the application entry point. `storage` must not disappear from a hand-selected package list. Interfaces/records without mutable bytecode need not produce mutants to be in scope. |
| `src/features/whoami.feature`, `src/features/TD2-job-queue.feature` | Features use observable behaviour in English; TD2 establishes a task-tagged unit-contract feature with separate verification and an agreed application-runner exclusion. |
| `src/it/java/com/video2podcast/functional/CucumberIT.java` | Selects the whole `features` classpath resource, filtered by `not @T8 and not @TD2 and not @T9`. A new `@TD3` feature would be picked up until the Tester changes the filter or supplies safe executable steps. |

## Conclusions and verification handoff

1. Keep analysis, configuration and tests separate: the profile must schedule real PIT work, while
   default commands do no mutation work. A default report can be stale; inspect fresh outputs/logs.
2. Unit/IT separation needs explicit proof, including mixed compiled output. Failsafe may still
   execute normally during `verify -Pmutation`; its results cannot become PIT evidence.
3. First capture a full baseline, then improve assertions and record a full final run with comparable
   targets/operators. A selected-package run is useful during development but cannot establish 85%.
4. Boundary checks should use isolated fixtures with known counts. Prove the real-project gate with
   an above-score threshold and restore 85. At 100%, document an isolated below-threshold fixture
   instead of pretending a valid PIT threshold can exceed the score.
5. Existing JaCoCo agent wiring and Java 27 bytecode are compatibility checks to run, not grounds
   for lowering gates or changing the platform. Upstream release notes do not prove this stack works.
6. No class is excluded by this analysis. Propose a precise list only if needed and obtain the owner's
   approval through the orchestrator before accepting an excluded-scope score.
7. `TD3-mutation.feature` describes build outputs and reviews. Do not run recursive full-project Maven
   builds from the application's own Cucumber suite. Q-analyst-1 asks Tester to confirm an isolated
   verification approach and a Tester-owned `not @TD3` runner filter, preserving earlier exclusions.
   The Tester subsequently confirmed both in A: Q-analyst-1; the filter is a Step 2 change.

## Open coordination and evidence limits

[Q-analyst-1](comms/questions.md) is resolved. The Tester confirmed isolated fixtures must execute
actual PIT with the project configuration and exact boundary counts, plus independently inspected
project evidence. Scores in Gherkin examples are illustrative; real acceptance uses measured values.
The Tester owns the Step 2 runner exclusion; no runner/test change occurs during Step 1.
The initial Tester prompt failed before that agent was registered, and the Architect prompt was
rejected while blocked; the orchestrator relayed the question. No approval dialog was answered.

The Analyst has not run PIT, measured a score, added tests, changed `pom.xml` or approved exclusions.
The requirements and feature need team design review. Syntax and requirement-tag consistency are
checked separately; they do not establish implementation acceptance.

## Analyst artifact checks

- Parsed `src/features/TD3-mutation.feature` with the locally installed Cucumber Gherkin 42.0.1
  parser: 22 concrete scenarios after expanding outlines, no parse errors, every scenario tagged
  `@TD3` and at least one requirement, and all `@R1`–`@R8` represented.
- `git diff --check` passed. These checks establish artifact syntax/traceability only.
- Read the Architect's concurrently created `components.md`, `api.md`, `flows.md` and `data-flow.md`.
  Proposed report paths and threshold property fit these requirements. The entry-point exclusion
  remains a proposal requiring owner approval; it is not approved by either design document.
