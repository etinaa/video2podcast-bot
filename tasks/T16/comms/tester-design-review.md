# T16 tester design review — step 1

Reviewed the brief, task, SPEC, plan, requirements/research, all four architecture artifacts, existing communications, T16 feature, and relevant existing functional harness/access tests. Owner A: Q-analyst-2 governs worker placement and supersedes the earlier queue wording.

The API supports the scenario groups:

- Startup: fresh Spring context against local HTTP endpoints; capture the HealthChecks logger, await both result lines, and assert normal Telegram replies. Short production health configuration and held responses expose real deadline behavior.
- Per-job checks: distinguish GET / and GET /api/version from inference/chat POSTs; observe process invocation order, terminal Telegram failure, and filesystem state. Any HTTP status is reachable. Both-down uses one exact combined message.
- Recovery/FIFO: retain per-submission boundary events, change probe responses independently of inference responses, and assert the next job is checked only at its worker turn. A probe-specific connection-close fixture can preserve a successful first job's Ollama chat during the waiting-job outage scenario.
- Responsiveness: held HTTP responses plus ordered Telegram/probe completion events can demonstrate that commands and transcript delivery work during preflight. Q-tester-1 requests stronger scenario assertions so timeout followed by a late reply cannot pass.
- Access callbacks/restarts: drive actual Telegram updates, snapshot durable access state, close and recreate the application context using the same access file, and assert original/stale callback behavior and startup notification retries. These use existing public application boundaries; no new production test hook is needed.

Reuse existing private/unsupported/too-long and delivery refusal coverage as audited by Analyst. Preserve T9/T10 mid-job failure tests by ensuring their GET preflights succeed independently of the failing POST; new startup GET traffic must not consume inference response sequences or inflate POST counts. Await terminal events before negative assertions. Startup checks must settle before resetting journals or configuring the next job's probe behavior.

Open finding: Q-tester-1, addressed to Analyst. Approval is pending its resolution. No tests or production code were written; no build was run in this design-only step.

## Resolution and approval

Verified A: Q-tester-1 and the updated feature/R4: all three responsiveness cases now require the response/upload while the relevant check remains pending before its configured deadline, with no terminal failure or startup result logs, and before mock release. Boundary timestamps and the actual configured deadline prevent the timeout-then-reply false positive. Q-tester-1 is resolved; no open tester design questions remain. All scenario groups are testable through api.md and existing application/HTTP/filesystem contracts. Design approved. Step 2 remains deferred until the orchestrator requests it.
