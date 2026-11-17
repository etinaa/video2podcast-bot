# Mutation testing (PIT)

JaCoCo (90% line and branch, in `./mvnw verify`) shows which lines the unit tests run. PIT changes the
production code in small ways (a mutant: `>` becomes `>=`, a return value is replaced, a call is
removed) and checks that some unit test fails. A mutant that no test notices *survives*.

## Command

```bash
./mvnw verify -Pmutation
```

- Runs the default `verify` first, then PIT. It fails when the mutation score is below 85%.
- Ordinary `./mvnw verify` and `./mvnw verify -Dno-it` do not run PIT.
- Use `./mvnw clean verify -Pmutation` for an acceptance run, so an old report cannot be mistaken for a new one.
- Threshold: property `pit.mutation.threshold` in `pom.xml` (85). `-Dpit.mutation.threshold=100` is the one-off check that the gate goes red.

## Scope

- Mutated: every class under `com.video2podcast` in `src/main`, except the classes listed with a reason in `pom.xml` (`excludedClasses`, owner-approved).
- Tests that count: unit tests in `src/test/java` only. `src/it` (Failsafe, Cucumber) is left out by `excludedTestClasses`, so it cannot raise the score, and it does not count toward JaCoCo either.
- Mutators: PIT `DEFAULTS`. PIT's default skipping of logging calls applies; there is no project-specific filter.

## Reading the result

Report: `target/pit-reports/index.html` (and `mutations.xml`). Open a class and look for lines marked
`SURVIVED` (a test ran the line but did not notice the change) or `NO_COVERAGE` (no unit test ran it).
Both count against the score.

To improve it: add or tighten an assertion in `src/test/java` that fails for that mutant, then rerun the
command. Change production code only if the mutant shows a real defect. Do not lower the threshold or narrow the scope.
