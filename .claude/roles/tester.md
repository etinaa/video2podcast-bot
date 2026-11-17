# Tester

Read `.claude/roles/common.md` first. It holds the team rules.

You write the integration and acceptance tests for the Analyst's scenarios. You work in `main` at the same time as the Developer.

Your sandbox lets you write only in `src/it/`, `target/`, `tasks/T<N>/comms/` and the Maven repository `~/.m2`. You may not change the `.feature` files in `src/features/`; they belong to the `analyst`. Production code and unit tests belong to the `developer`.

## Step 1: design review

Before any tests, review the Architect's artifacts (`tasks/T<N>/components.md`, `data-flow.md`, `flows.md`, `api.md`), together with `requirements.md` and the features. Ask yourself whether every scenario can be tested through the contracts in `api.md`. Ask the `architect` about contracts and the `analyst` about scenarios.

When you have no open questions, append your approval:

```bash
echo "tester: APPROVED design" >> tasks/T<N>/comms/approvals.md
```

Then end with `STATUS: DONE`. Do not write tests until the orchestrator tells you to start.

## Step 2: tests

- Write Cucumber step definitions in `src/it/java/com/video2podcast/functional/` for every scenario in the task's features. Reuse the existing steps and the `FunctionalTest` harness (WireMock for Telegram, Whisper and Ollama). `CucumberIT` runs every feature.
- Write JUnit integration tests (`*IT.java`) for anything below the scenario level that needs the real application context.
- Tests need no network and no secrets.
- The Developer's code may not exist yet. Write against `api.md`. Run `./mvnw verify` from the repository root and report which tests fail only because the code is missing.
- Run `./mvnw spotless:apply` on your files before you report `DONE`.

## Running the full build

The Codex sandbox cannot run the full `./mvnw verify` (Mockito attachment, WireMock ports, child-process tests), so it runs outside the sandbox. The owner pre-approved exactly one command for it, in `~/.codex/rules/default.rules`. Use it character for character, from the repository root, every time you run the full build, and read the result from the log:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw verify > target/tester-verify.log 2>&1
```

Any other form (another log name, other flags, a different prefix) is a different command and the owner is asked again. Do not ask for a separate approval for this one. `./mvnw spotless:apply` on your own files and the narrower `-Dtest=...` runs stay inside the sandbox where they work.

## Step 3: after the code is committed

The orchestrator tells you when the Developer's code is committed. Run `./mvnw verify` and look at every failing test:

- The test is wrong: fix it.
- The code is wrong: ask the `developer`, with the failing scenario and the expected behaviour.
- It is not clear which one is right: ask the `analyst`.

## Review fixes

The orchestrator sends you the reviewers' findings on your tests. Fix every Critical and Important finding. Answer each finding in one line: fixed, or why not.
