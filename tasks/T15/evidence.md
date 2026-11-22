# T15 evidence

Date: 2026-11-22. Machine: Apple M3 Max, Podman 6.2.0, `podman machine` (applehv, 8 CPUs, 6 GiB), `podman-compose` provider. Written by the orchestrator from the commands below.

## Build

`podman compose build` → exit 0, image `localhost/video2podcast-bot:latest` (log: `comms/build-1.log`). Two stages: `eclipse-temurin:27-jdk` builds the jar with `sh mvnw -B -ntp package -DskipTests -Dno-it`; `eclipse-temurin:27-jre` holds `ffmpeg` (apt), the latest `yt-dlp` standalone binary (checked with `yt-dlp --version` at build time) and the jar.

## Reachability of host services from the container

Host: `whisper-server` bound to `127.0.0.1:8081`, Ollama bound to `127.0.0.1:11434` (`lsof` shows only loopback listeners). From the host: both answer 200.

```
podman run --rm --entrypoint curl localhost/video2podcast-bot:latest -s -m 8 -o /dev/null -w "%{http_code}" http://host.containers.internal:8081/
200
podman run --rm --entrypoint curl localhost/video2podcast-bot:latest -s -m 8 -o /dev/null -w "%{http_code}" http://host.containers.internal:11434/api/tags
200
```

Result: services bound to `127.0.0.1` are visible from the container; no `0.0.0.0` binding is needed on this machine.

## Start

`podman compose up -d` → exit 0 (log: `comms/up-1.log`). `podman compose logs bot`: `No active profile set, falling back to 1 default profile: "default"`, `Result when calling deleteWebhook: true`, `Started Video2PodcastApplication in 1.069 seconds`. 0 WARN or ERROR lines. The Spring Boot health check that the first task text mentioned is T16's.

Inside the container: `id` → `uid=10001(bot) gid=10001(bot)`; `touch` in `/work` and `/data` works (both owned by `bot`); `yt-dlp --version` → `2026.10.26`; `ffmpeg -version` → 8.0.1. `podman compose ps` shows no published ports.

## Owner's check

The owner sent `https://www.youtube.com/watch?v=P4U7-I4dELU` to the containerized bot and reported that everything worked as in the Checkpoint 4 check (summary, both buttons).

## Review round 1 fixes and the rebuild

Round 1 (reviewer-codex, Important): the yt-dlp binary was executed without an integrity check, and the docs did not install Podman. Fixed by the Developer: the build resolves the latest release once, downloads the binary and `SHA2-256SUMS` from that tag and runs `sha256sum -c` before installing or running the binary; the docs now start with `brew install podman podman-compose` and `podman machine init --cpus 8 --memory 6144`.

Rebuild: `podman compose build` → exit 0 (`comms/build-2.log`), `yt-dlp_linux_aarch64: OK`. Round 2: both reviewers approved.

Decision (orchestrator): no signature check (`SHA2-256SUMS.sig`), because no independently checkable key fingerprint is available. Residual risk: an attacker who replaces both the binary and the checksum file in the release. Accepted for a locally run personal bot; adding a pinned key later is a small change if the owner supplies the fingerprint.

No Java files changed, so no `src/it` zone and no new full `clean verify`; the last full gate is TD8's (803 unit tests, 392 IT entries, mutation 92%).
