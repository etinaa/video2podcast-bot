TD10 tester implementation: added the media-page duration step using JSON-LD metadata with the existing short recording; added exact duration-line assertion. No src/it assertions name the old duration format. Scoped spotless:apply and git diff --check pass.

Developer reported ready in A: Q-tester-1. Plain ./mvnw verify is running (no clean); please keep other Maven runs idle until it finishes.

Verification finished 2026-11-23 15:54:58 +02:00: plain ./mvnw verify, BUILD SUCCESS (07:09). No clean was run.
- 398 Cucumber scenarios / 5806 steps passed, including all seven TD10 examples.
- Failsafe: 443 tests, 0 failures, 0 errors, 5 skipped (398 executable Cucumber scenarios all passed).
- Spotless, JaCoCo coverage gates, SpotBugs and PMD passed.
- Scoped spotless:apply was run on the three changed src/it files.

STATUS: DONE
