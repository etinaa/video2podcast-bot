# TD8 tester report

STATUS: DONE

## Scope

Read `.claude/roles/tester.md`, `.claude/roles/common.md`, `SPEC.md`, `tasks/plan.md`, TD8 in `tasks/todo.md`, and `tasks/TD8/brief.md`. This is a technical task with no Analyst or Architect; the explicit assignment authorizes the feature updates and leaves full gates to the orchestrator.

Updated every occurrence of the old refusal text in the requested features and integration sources to `Files already downloaded or expired, please resend the link.`:

- `src/features/T12-transcript-button.feature`: 5 replacements.
- `src/features/T13-audio-button.feature`: 5 replacements.
- `src/features/T14-retention.feature`: 2 replacements.
- `src/it/java/com/video2podcast/functional/AccessGateIT.java`: 1 replacement.
- `src/it/java/com/video2podcast/functional/TranscriptButtonIT.java`: 1 replacement.
- `src/it/java/com/video2podcast/functional/AudioButtonIT.java`: 1 replacement.
- `src/it/java/com/video2podcast/functional/ExpiryIT.java`: 1 replacement.
- `src/it/java/com/video2podcast/functional/AudioAssertionSteps.java`: 1 replacement.

All eight diffs were checked against HEAD: each is exactly the requested string substitution. No scenarios, assertions, fixtures or checks were removed. Existing coverage exercises transcript/audio expiry boundaries, missing or unusable jobs/content, invalid callback ids, retained-file expiry, and callbacks after retention deletes the job directory. Other refusal expectations remain unchanged.

Did not edit `src/main`, `src/test`, the index or branch. Existing developer/orchestrator changes were left in place. No `clean` or `verify` command/goal was run.

## Validation

- Scoped `spotless:apply` on the five changed Java files: PASS. Log: `target/td8-tester-spotless.log`.
- `git diff --check -- src/features src/it`: PASS.
- Exact old-text search across `src/features` and `src/it`: no matches.
- Targeted compilation and Failsafe run: PASS against the shared working-tree implementation.

Command (repository root):

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/current" ./mvnw test-compile failsafe:integration-test -Dit.test=AccessGateIT,TranscriptButtonIT,AudioButtonIT,ExpiryIT,CucumberIT '-Dcucumber.filter.tags=@T12 or @T13 or @T14' > target/td8-tester-targeted-unsandboxed.log 2>&1
```

The initial sandboxed attempt could not bind WireMock ports (`java.net.SocketException: Operation not permitted`); its evidence is in `target/td8-tester-targeted.log`. After that attempt finished, the same targeted run was approved and executed outside the sandbox. No test runs overlapped.

Successful results: AccessGateIT 7/7, TranscriptButtonIT 3/3, AudioButtonIT 3/3, ExpiryIT 2/2, and T12/T13/T14 Cucumber 159/159 scenarios. Failsafe reports 372 entries, zero failures, zero errors and 198 skipped entries (unrelated scenarios excluded by the tag filter): 174 tests/scenarios executed successfully. Checked the actual Failsafe summary and log rather than relying on the integration-test goal exit code alone.

Full gates and mutation checks remain with the orchestrator; the Telegram checkpoint remains with the owner.
