# TD3 flows

## F1: main flow, score at or above the threshold

```mermaid
sequenceDiagram
  actor Dev as Developer / orchestrator
  participant M as ./mvnw verify -Pmutation
  participant D as default verify plugins
  participant P as pitest-maven
  Dev->>M: run
  M->>D: compile, Surefire, JaCoCo 90%, Failsafe, SpotBugs, PMD, Spotless
  D-->>M: green
  M->>P: mutationCoverage
  P->>P: coverage run of src/test, then mutate targetClasses
  P-->>M: score >= 85, writes target/pit-reports
  M-->>Dev: BUILD SUCCESS
```

## F2: failure, score below the threshold

```mermaid
sequenceDiagram
  actor Dev as Developer
  participant M as ./mvnw verify -Pmutation
  participant P as pitest-maven
  Dev->>M: run (also the one-off check with -Dpit.mutation.threshold=100)
  M->>P: mutationCoverage
  P-->>M: score X < threshold
  M-->>Dev: BUILD FAILURE, "Mutation score of X is below threshold of N"
  Dev->>Dev: open target/pit-reports/index.html, find SURVIVED mutants
  Dev->>Dev: strengthen src/test unit tests (production code only for a real bug)
  Dev->>M: run again
```

## F3: failure, a unit test is red before mutation

```mermaid
sequenceDiagram
  participant M as ./mvnw verify -Pmutation
  participant S as Surefire / earlier plugin
  participant P as pitest-maven
  M->>S: test
  S-->>M: failure
  M-->>M: stops, PIT never starts
  Note over P: if PIT is called alone (pitest:mutationCoverage), it also stops with "did not pass without mutation"
```

## F4: default builds are unchanged

```mermaid
sequenceDiagram
  actor Dev
  participant M as ./mvnw verify [-Dno-it]
  Dev->>M: run without -Pmutation
  M-->>Dev: the same plugins as before TD3, no PIT, same time
```

## F5: IT classes on the test classpath

```mermaid
sequenceDiagram
  participant M as -Pmutation with the it profile active
  participant P as pitest-maven
  M->>P: test classpath has com.video2podcast.functional.*
  P->>P: excludedTestClasses drops them
  P-->>M: runs only *Test
```
