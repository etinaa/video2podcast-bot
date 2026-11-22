VERDICT: APPROVED

## Findings
- [Suggestion] Dockerfile:96-107 - yt-dlp is fetched from `releases/latest` with no integrity check. Over HTTPS from GitHub this is accepted by the task ("latest at build time"), but a corrupted or swapped asset would run unchecked inside the image. Downloading `SHA2-256SUMS` from the same release and running `sha256sum -c` costs a few lines. It guards against corruption and a CDN-level swap, not against a compromised release. A comment saying the risk is accepted would also be enough.
- [Suggestion] Dockerfile:82,92 - base images are tagged (`27-jdk`, `27-jre`), not pinned by digest. Builds are not reproducible and a base-image change is picked up silently. Fine for a personal bot; worth one line in the docs or a TODO.
- [Suggestion] docs/host-setup.md:197 / Dockerfile:95-107 - "to refresh only yt-dlp" is not exact. `YT_DLP_REFRESH` sits in the same `RUN` as `apt-get install ffmpeg`, so the apt layer is re-run too (the Maven stage is not). Put the yt-dlp download in its own `RUN`, or reword the sentence. Also, `--build-arg` on `podman compose build` is not shown in evidence.md. I could not confirm that podman-compose passes it when compose.yaml has no `build.args`.
- [Suggestion] .env.example:1-3 - the header says the defaults are the ones in `application.yml`. Under `./run.sh` (dev profile) `ACCESS_FILE`, `OLLAMA_BASE_URL`, `WHISPER_BASE_URL` and `WORK_DIR` default to different values (`application-dev.yml`). The text says "(in the container)" and the docs warn about it, so this is not wrong. A pointer to `application-dev.yml` would remove the doubt.
- [Suggestion] compose.yaml:8-9 - `env_file: .env` reads the whole file, and quote handling differs between podman-compose and docker compose. The example has no quoted values, so there is no problem today. If a value ever needs quotes, test it.

Checked, no problem:
- Secrets: the Dockerfile copies only `.mvn`, `mvnw`, `pom.xml`, `config` and `src/main`, so `.env` can never get into the image. `.dockerignore` also excludes `.env`, `.env.*` and `.git`. `.env` is injected at runtime only. `git ls-files` shows only `.env.example` tracked, and `.gitignore` still excludes `.env`.
- `.dockerignore`: the patterns are root-anchored, so `data` and `work` do not hit source packages. Nothing needed by the build is excluded: `src/main`, `config`, `.mvn` and `pom.xml` are present, and the build passed per evidence. No gaps found (`*.pem` and `*.key` are not listed, but they are not copied either).
- Non-root and volumes: uid/gid 10001, `/work` and `/data` are created and chowned before `USER bot`. Named volumes take that ownership on first use, and evidence shows `touch` works in both. The jar stays root-owned and read-only. The standalone yt-dlp unpacks to `/tmp`, which is writable, and `yt-dlp --version` ran in the container.
- Compose: no Docker-only keys (no `version`, `depends_on` conditions, `deploy`, `extra_hosts` or `network_mode`). There are no `ports`. The `host.containers.internal` defaults come from `application.yml`, as evidence shows.
- Variables: all 19 variables in `application.yml` are in `.env.example`, and the names match. `application-dev.yml` and `src/main/java` (no `getenv`, no `@Value`) add none.
- Docs: the build, start, update, logs and one-token-at-a-time sections match the files. The reachability paragraph matches evidence.md. The "not verified" text is replaced and the `0.0.0.0` fallback is kept.
- Scope: no Java changes, and the patch touches only the files listed in the task.

## Requirements
- Multi-stage Dockerfile, JDK 27, yt-dlp latest, ffmpeg, non-root, default profile: met
- compose.yaml with `bot`, `/work` and `/data` named volumes, host access, `.env`, no ports, no Docker-only extensions: met
- `.env.example` lists every variable with its default and no secrets; `.gitignore` keeps `.env` out: met
- docs/host-setup.md covers build, start, update yt-dlp and logs: met
- Reachability checked and documented, with evidence in evidence.md: met
