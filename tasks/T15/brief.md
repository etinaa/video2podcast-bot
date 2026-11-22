# T15 brief

Owner's original words: "Начать T15".

Questions and answers:
- Task type: technical (owner's choice): Developer, Tester, two code reviewers; no Analyst, no Architect.
- How Whisper and Ollama are reached from the container (docs say it is not verified for services on 127.0.0.1): owner chose "check and choose by fact", i.e. test from the host first; if the containers cannot see 127.0.0.1 services, document 0.0.0.0 bindings (Whisper `--host 0.0.0.0`, Ollama `OLLAMA_HOST=0.0.0.0`) and firewall limits.

Changes the orchestrator made to the planned text, shown to the owner and approved: the "health check passing" check is dropped from Verify (the startup health check is T16); explicit reachability evidence added; `.dockerignore` and `evidence.md` added to Files; the owner sends one link to the containerized bot.

Constraints: the local `./run.sh` bot and the container share one Telegram token, so only one may run at a time. Podman commands run outside the sandbox and each is shown to the owner for approval.

Agreed task: see T15 in `tasks/todo.md`.
