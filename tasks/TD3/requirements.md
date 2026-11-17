# TD3 requirements — PIT mutation testing

Status: proposed for team design review. Analysis date: 2026-11-17.
Sources: [brief](brief.md), [TD3](../todo.md), [SPEC.md](../../SPEC.md),
[research](research.md). This is a tooling change; the existing application contract remains in force.

## Objective and agreed scope

Give maintainers a separate Maven mutation check that detects weak unit assertions across existing
production code and enforces a minimum aggregate mutation score of 85%. The owner has confirmed
`mutation` as an opt-in profile and `src/test` as its only source of tests. Strengthening those tests
until the gate passes is part of TD3. This is mutation coverage, not an 85% replacement for JaCoCo.

## Requirements and acceptance criteria

### R1 — Explicit profile and lifecycle command

- `pom.xml` declares `org.pitest:pitest-maven` in the `mutation` profile, with a pinned version.
- `./mvnw verify -Pmutation` actually runs mutation analysis and enforces its gate; a separate
  invocation of a PIT goal must not be necessary to satisfy the documented command.
- A normal successful run exits 0, produces a readable PIT report on disk, and reports a nonzero
  number of generated mutants. An empty target selection must fail rather than imply 100% success.

### R2 — Production targets and unit-test-only evidence

- Mutation targets are the project's compiled `src/main` code, including the merged T1–T9 work
  and TD2, across `com.video2podcast`, subject only to R6. Test helpers and dependencies are not targets.
- Only tests originating in `src/test/java` contribute to PIT. `src/it` tests, Cucumber suites and
  steps do not execute inside PIT and cannot improve its score, even when present in the shared
  `target/test-classes` directory from the normal integration profile or a previous build.
- This restriction applies to PIT, not Failsafe: `verify -Pmutation` retains normal integration-test
  execution outside PIT. Existing `-Dno-it` behaviour still omits integration sources and Failsafe.
- Verify selection using the effective configuration and PIT execution evidence. In an isolated
  fixture, an assertion present only in `src/it` must not kill the mutant; moving that assertion
  to `src/test` must make it eligible. Include a test-only helper to prove it is not mutated.

### R3 — Minimum mutation score and honest failure

- Enforce PIT's aggregate mutation score at a minimum of 85, using `mutationThreshold` (not
  `coverageThreshold` or test strength). There is no new per-class minimum in TD3.
- A controlled result of 84% fails; exactly 85% and 86% pass when all other checks pass.
  Uncovered mutants remain part of mutation-score accounting; do not substitute the score for
  covered mutants only. Do not rely solely on a rounded HTML display to prove the boundary.
- Failed original tests, failed PIT discovery or an incompatible runtime must fail the command;
  they must not be relabelled as a passing mutation run. Keep failure-on-no-mutations enabled.
- One-off proof on the real project raises the threshold above the measured final score and
  demonstrates a nonzero exit caused by the mutation gate, then restores 85 and passes again.
  Record the exact override or temporary edit. If the score is 100%, use a controlled isolated
  fixture with survivors to demonstrate this negative case, documenting why the real score cannot
  be exceeded within PIT's valid 0–100 range. Do not leave the temporary threshold in `pom.xml`.

### R4 — Measured baseline and stronger unit assertions

- Record the initial mutation run before TD3 assertion improvements: revision/state, exact command,
  Java/PIT/JUnit-plugin versions, target/exclusion settings, generated/detected/surviving/uncovered
  counts and PIT score, report location and exit result. Record other statuses when present.
- If below 85%, the Developer adds or strengthens meaningful tests in `src/test/java` until the
  final full-scope run passes. Do not lower the threshold, remove assertions/tests, narrow targets,
  or bypass analysis to obtain a green result.
- Record final results with the same scope and operators as the baseline, or explain any approved
  scope change and remeasure the baseline on that scope. A dry run or selected-package run does
  not establish final acceptance.
- Production changes are allowed only when a surviving mutant exposes an actual defect against
  existing requirements; identify the violated behaviour and add a regression test. Equivalent
  mutants alone are not justification to alter intended behaviour.

### R5 — Default builds keep their existing work

- With `mutation` absent, neither `./mvnw verify` nor `./mvnw verify -Dno-it` invokes PIT,
  performs mutation discovery, starts mutation workers or generates a fresh mutation report.
- Preserve existing unit/IT selection, format/static checks and JaCoCo enforcement. PIT adds no
  work to either default lifecycle. This is not a new numeric wall-clock performance budget;
  timings can vary, and stronger unit tests still need to execute normally.
- Check fresh outputs or timestamps so a stale `target/pit-reports` cannot be mistaken for a new run.

### R6 — Exclusions require a concrete reason and owner approval

- Start with all production classes in scope. Config classes, generated code and the application
  entry point are candidates mentioned in the task, not pre-approved exclusions.
- If any class exclusions are necessary, list each class or precise pattern and its reason in
  `pom.xml`; retain the owner's approval of that exact list in task communications before
  accepting a run with those exclusions. Broad positive target filters must not hide omissions.
- If no classes are excluded, state that in the results; no exclusion approval is needed.
- Keep PIT's standard built-in filters distinct from explicit project exclusions and record the
  selected operator set. Do not suppress surviving mutants through new method/operator filters
  as an alternative to the agreed coverage scope.

### R7 — Existing platform and quality limits remain satisfied

- The selected PIT and JUnit Platform integration must successfully run this repository's unit
  tests on the specified Java 27 / Spring Boot 4.1.2 stack. Pin the integration plugin where needed.
  Documentation alone is not runtime compatibility proof.
- JaCoCo continues to require at least 90% line and 90% branch coverage across production code,
  measured from unit tests only. PIT does not replace, dilute or absorb that gate.
- Preserve application behaviours and limits in `SPEC.md`: private-chat access control, queue
  ordering, media validation/duration/timeouts and transcription ordering/retry/output semantics.
  Existing regression tests remain applicable; TD3 introduces no new Telegram flow.
- Checks require no secrets, real Telegram calls, paid APIs or running Whisper/Ollama instances.
  Local WireMock and subprocess fixtures remain valid.

### R8 — Maintainer instructions and reviewable evidence

- `CLAUDE.md` and a document under `docs/` explain `./mvnw verify -Pmutation`, the 85% minimum,
  the main-code/unit-test scope, report location, and that ordinary verification does not run PIT.
- Document how to inspect survivors and rerun the gate after unit-test improvements. Explain
  that `src/it` results do not contribute to PIT or unit JaCoCo coverage.
- Keep initial/final measurements, gate-failure proof, restored passing result and any exclusion
  approvals available in TD3 working files or linked docs for reviewers.

## Verification ownership and scenarios

[TD3-mutation.feature](../../src/features/TD3-mutation.feature) describes observable build commands,
exit outcomes and report contents. It uses build vocabulary because existing Telegram and queue
steps cannot represent these observations. Each scenario carries its requirement tag.

Developer owns PIT wiring, unit improvements, baseline/final runs and real-project threshold proof.
Tester owns independent verification and any `src/it` changes. Do not launch a recursive full-project
`verify` from the application's own Cucumber run. Isolated Maven fixtures and effective-POM checks
can exercise build boundaries without repeating expensive real-project mutation analysis.
Tester confirmed this approach in [A: Q-analyst-1](comms/questions.md): isolated fixtures must execute
real PIT using the actual project configuration, retain exact counts for the 84/85/86 boundary,
and be complemented by effective-configuration checks and independent real-project evidence review.
Fixture scores and exit codes must not be stubbed. The Tester will append `and not @TD3` to the
existing application Cucumber filter in Step 2, preserving all current exclusions; no runner/test
changes are required during Step 1 design review. Documentation and approvals are review checks.
Concrete scores in the feature are illustrative examples, not claims about this project: baseline
and final acceptance use measured values, and the negative proof uses a threshold above the actual
score (or R3's perfect-score fallback).

## Out of scope

- Implementing T10–T17 or adding product behaviours, services, storage or deployment machinery.
- Running PIT by default, on startup, in every unit-test run, or adding a CI service/paid tool.
- Counting integration tests, mutating test code/dependencies, or setting a per-class 85% target.
- Reducing the 90% line/branch gates, changing Java/Boot versions or pre-approving exclusions.
- Refactoring production code to improve mutation scores without an evidenced real bug.
- Analyst changes to `pom.xml`, unit tests, `src/it`, `CLAUDE.md`, task status in `todo.md`, or commits.

## Open items

No new product decision is required to write this specification. Runtime compatibility, actual scores,
necessary exclusions (if any) and gate-failure proof remain implementation evidence to obtain.
Q-analyst-1 is resolved by the Tester. Any remaining architecture questions are tracked separately
in task communications; they do not reopen the agreed verification strategy.
