# TD3 contracts

No Java interface, HTTP endpoint, file format or runtime configuration property changes.

## Command contract

| Command | Result |
|---|---|
| `./mvnw verify` | as before TD3, no PIT |
| `./mvnw verify -Dno-it` | as before TD3, no PIT |
| `./mvnw verify -Pmutation` | the default build, then PIT; fails when the score is below `pit.mutation.threshold` (85) |
| `./mvnw verify -Pmutation -Dno-it` | the same without Failsafe; PIT result is the same, because PIT never runs `src/it` |
| `./mvnw verify -Pmutation -Dpit.mutation.threshold=100` | one-off red check. Valid proof only when the real score is below 100: then it exits non-zero from the mutation gate. If the real score is 100, PIT cannot be made red this way (0–100 range); use an isolated fixture with surviving mutants instead (R3) |

Report: `target/pit-reports/index.html` and `target/pit-reports/mutations.xml`.

## `pom.xml` contract (Developer builds exactly this; versions may change only after the spike)

Property, in `<properties>`:

```xml
<pit.mutation.threshold>85</pit.mutation.threshold>
```

Profile, added after `it`:

```xml
<profile>
  <id>mutation</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.pitest</groupId>
        <artifactId>pitest-maven</artifactId>
        <version>1.30.2</version>
        <dependencies>
          <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.3</version>
          </dependency>
        </dependencies>
        <configuration>
          <targetClasses><param>com.video2podcast.*</param></targetClasses>
          <targetTests><param>com.video2podcast.*</param></targetTests>
          <!-- No excludedClasses at the start (R6). If one is needed after the first measurement,
               add <excludedClasses> with one <param> per class or pattern and a comment with its
               reason, and get the owner's approval of that exact list first. -->
          <excludedTestClasses><param>com.video2podcast.functional.*</param></excludedTestClasses>
          <!-- No avoidCallsTo: PIT's own default (logging packages) stays. -->
          <mutationThreshold>${pit.mutation.threshold}</mutationThreshold>
          <failWhenNoMutations>true</failWhenNoMutations>
          <timestampedReports>false</timestampedReports>
          <outputFormats><param>HTML</param><param>XML</param></outputFormats>
          <threads>4</threads>
        </configuration>
        <executions>
          <execution>
            <id>mutation-score</id>
            <phase>verify</phase>
            <goals><goal>mutationCoverage</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</profile>
```

Rules the Developer must keep:

- Nothing PIT-related outside the `mutation` profile (except the property).
- PIT does not use Surefire's `argLine` (`@{jacoco.unit.argLine}`), so JaCoCo's agent is not in the minions. If a unit test needs a JVM option, add `<jvmArgs>` to the PIT configuration.
- If the spike shows that a test needs a setting PIT does not pass (for example an environment variable), fix it in the PIT configuration, not in the test.
- Mutator set stays `DEFAULTS`. The threshold stays 85 in the property default; raising it is a task for the owner.

## Documentation contract

- `CLAUDE.md`, Tests section: one line, ``./mvnw verify -Pmutation`` runs PIT on `src/main` with the unit tests only and fails below 85%.
- `docs/decisions.md`: D7 (written by the Architect).
- `docs/architecture/mutation-testing.md` (written by the Architect): command, threshold, scope, report location, how to read survivors and rerun, why `src/it` does not count, that ordinary `verify` does not run PIT. The Developer adds the measured baseline and final numbers to `tasks/TD3/` (not to this file).

Stale reports (R5): the report directory is `target/pit-reports`; a default build never writes it, so a stale one is detected by its file time. For acceptance runs use `./mvnw clean verify -Pmutation`.
