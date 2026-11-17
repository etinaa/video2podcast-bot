# TD3 components

TD3 changes the build only. No runtime component, HTTP endpoint or `job.json` changes.

## What changes

| Component | Change | Why there |
|---|---|---|
| `pom.xml`, new profile `mutation` | adds `org.pitest:pitest-maven` (goal `mutationCoverage`, phase `verify`) with the `pitest-junit5-plugin` dependency, and the property `pit.mutation.threshold` (default `85`) | the owner wants PIT off the default build; a profile keeps `./mvnw verify` and `-Dno-it` byte-for-byte as they are. Nothing is added to the top-level `<build>`. |
| `src/test/java/**` | new or stronger unit tests, only where the first measurement is below 85% | the Developer owns them; they are the only tests PIT runs |
| `src/main/java/**` | no change, unless a surviving mutant shows a real bug | agreed in the brief |
| `CLAUDE.md` (Tests section), `docs/decisions.md` (D7), new `docs/architecture/mutation-testing.md` | the command and the threshold | acceptance criterion |
| `tasks/TD3/comms/questions.md` Q-analyst-1 | build-contract scenarios stay out of `CucumberIT` (Tester decides the filter in `src/it`) | not an architecture matter; noted so the Tester sees the design does not need an IT |

Not touched: the `it` profile, Surefire, JaCoCo (its 90% gate keeps its own rule), SpotBugs, PMD, Spotless, `src/it`, `src/features`.

## Diagram

```mermaid
flowchart LR
  subgraph default["default build (unchanged)"]
    SF[Surefire: src/test *Test] --> JC[JaCoCo check 90%]
    JC --> FS[Failsafe: src/it *IT, Cucumber]
    FS --> SB[SpotBugs / PMD / Spotless]
  end
  subgraph mut["profile mutation (new)"]
    PIT[pitest-maven mutationCoverage]
    PIT -->|mutates| MAIN[src/main classes]
    PIT -->|runs| UT[src/test unit tests]
    PIT -->|score under 85 percent| FAIL[[build fails]]
    PIT --> REP[target/pit-reports]
  end
  verify[./mvnw verify -Pmutation] --> default
  verify --> mut
  SF -. same test classes .-> UT
```

`PIT` runs in the same `verify` phase, after the default plugins (POM order), so a red unit test or a failed JaCoCo/SpotBugs/PMD check stops the build before the slow mutation run.

## Decisions

1. **Plugin versions:** `pitest-maven` 1.30.2 and `pitest-junit5-plugin` 1.2.3 (latest on Maven Central, checked 2026-11-17). The Developer's first step is a spike: run the plugin on JDK 27. If PIT cannot read Java 27 class files, report `BLOCKED` with the error; do not lower the Java level.
2. **Test selection:** PIT sees the test classpath, and the `it` profile (on by default, even with `-Pmutation`) adds `src/it/java` to it. PIT must not run those classes: `excludedTestClasses` is `com.video2podcast.functional.*` (every IT, Cucumber runner and step class lives there). `targetTests` is `com.video2podcast.*`, so `*Test` classes in every package are run. This works with and without `-Dno-it`.
3. **Mutated code:** `targetClasses` is `com.video2podcast.*`. pitest-maven takes the code to mutate only from `target/classes`, so test helpers in `src/test` (for example `support.MutableClock`) and dependencies are not mutated even though they share the package prefix. The Developer confirms this in the report.
4. **Exclusions:** none at the start (R6). Candidates (`Video2PodcastApplication`, `config.*`) are excluded only if mutants survive after tests are strengthened, with a reason in `pom.xml` and the owner's approval of the exact list.
5. **Logging calls:** no explicit `avoidCallsTo`. PIT's default (which skips calls to the logging packages it ships with) stays, so the design adds no filter beyond the pinned defaults (R6). The Developer records the effective default list in the baseline.
6. **Mutators:** `DEFAULTS` set. No `STRONGER`/`ALL`, so 85% stays the agreed, reproducible bar.
7. **Threshold:** `mutationThreshold` = `${pit.mutation.threshold}`, property default `85`, so the one-off red check is `-Dpit.mutation.threshold=100` with no pom edit.
