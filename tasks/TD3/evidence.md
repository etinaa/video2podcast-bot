# TD3 evidence

Written by the orchestrator from the Developer's report and the copies in `evidence/`.
Stack: Java 27, Spring Boot 4.1.2, `pitest-maven` 1.30.2, `pitest-junit5-plugin` 1.2.3 (both work on Java 27, so the pinned versions did not change). Mutators: PIT `DEFAULTS`. Excluded classes: none, so no exclusion approval is needed.

| Run | Command | Exit | Result | Files |
|---|---|---|---|---|
| Baseline | `./mvnw clean verify -Pmutation` | 0 | 553 mutants, 501 detected (486 killed, 15 timed out), 50 survived, 2 no coverage: score **91%** | `evidence/baseline.log`, `evidence/baseline-mutations.xml` |
| Red | `./mvnw verify -Pmutation -Dno-it -Dpit.mutation.threshold=100` | 1 | `Mutation score of 91 is below threshold of 100` | `evidence/red.log`, `evidence/red-mutations.xml` |
| Restored green | `./mvnw clean verify -Pmutation` | 0 | 91% again | `evidence/green.log`, `evidence/green-mutations.xml` |
| Default build | `./mvnw clean verify` | 0 | PIT did not run, `target/pit-reports` not created | - |

- PIT ran only the 101 unit test classes (373 tests); `src/it` classes were on the classpath and were excluded by `excludedTestClasses`.
- The baseline is above 85%, so no unit test and no production code was changed.
- Run-to-run variation: an earlier `-Dno-it` run detected 505 of 553 with 14 `RUN_ERROR` mutants that did not recur. The detected count moves by a few mutants in threaded and timeout-sensitive code. Counting only killed mutants the score is 486/553 = 87.9%, still above 85.
