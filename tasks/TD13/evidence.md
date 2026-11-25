# TD13 evidence

Collected by the Developer on 2026-11-25; written here by the orchestrator.

## Dependency tree

`./mvnw dependency:tree -Dincludes=org.springframework.ai,tools.jackson.core,org.springframework.boot:spring-boot-starter-jackson`

```
com.video2podcast:video2podcast-bot:jar:0.1.0-SNAPSHOT
\- org.springframework.ai:spring-ai-ollama:jar:2.1.0:compile
   +- org.springframework.ai:spring-ai-model:jar:2.1.0:compile
   |  +- org.springframework.ai:spring-ai-commons:jar:2.1.0:compile
   |  \- org.springframework.ai:spring-ai-template-st:jar:2.1.0:compile
   +- org.springframework.ai:spring-ai-retry:jar:2.1.0:compile
   +- org.springframework.ai:spring-ai-systemone:jar:2.1.0:compile
   \- tools.jackson.core:jackson-databind:jar:3.1.7:compile
      \- tools.jackson.core:jackson-core:jar:3.1.7:compile
```

No `spring-ai-autoconfigure-*`, no `spring-ai-starter-*`, no `spring-ai-client-chat`. Jackson 3 still resolves through `spring-ai-ollama`, so `spring-boot-starter-jackson` was not added.

## No auto-configuration on the classpath

The classpath came from `dependency:build-classpath`. `unzip -p <jar> META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` was run on every Spring AI jar, and the file is missing from all six (`filename not matched`): `spring-ai-ollama`, `spring-ai-model`, `spring-ai-commons`, `spring-ai-template-st`, `spring-ai-retry`, `spring-ai-systemone`, all 2.1.0.

## Verify

1. `./mvnw verify -Dno-it`: BUILD SUCCESS, 914 unit tests, 0 failures, JaCoCo checks met, PMD and SpotBugs clean. The orchestrator repeated it with `spotless:check` before review round 1 and got the same result.
2. `./mvnw clean verify`: BUILD SUCCESS, 488 integration tests, 0 failures, 5 skipped. JaCoCo checks met.
3. `./mvnw verify -Pmutation -Dno-it`: BUILD SUCCESS on the second run: 1130 mutations, 1060 killed (94%), gate 85%. The first run on the same code stopped with PIT's "1 tests did not pass without mutation" and did not name the test. The unit suite was green on its own. A flaky unit test is suspected and was not identified; `JobQueueTest` printed a "boom" stack trace in the PIT log, but that is not proven to be the cause.
