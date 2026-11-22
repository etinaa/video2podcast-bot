# T15 developer evidence

Files written: `Dockerfile`, `compose.yaml`, `.dockerignore`, `.env.example`, `docs/host-setup.md` (new section "Container: build, start, update, logs"). `.gitignore` unchanged (already excludes `.env`, keeps `.env.example`). Nothing in `src/` touched.

## Checked
- `podman compose config`: valid. One service `bot`, `env_file: .env`, volumes `work:/work` and `data:/data`, no `ports`, no Docker-only keys.
- Not run: `podman build`, `compose up`, reachability (orchestrator, with the owner's approval).

## Not verified (needs a build)
- Image tags `docker.io/library/eclipse-temurin:27-jdk` and `:27-jre` exist for arm64.
- Maven build in the image: `sh mvnw -B -ntp package -DskipTests -Dno-it`, jar `target/video2podcast-bot.jar` (pom `finalName`).
- yt-dlp standalone binary: `yt-dlp_linux_aarch64` on arm64, `yt-dlp_linux` otherwise, from `releases/latest`. The build runs `yt-dlp --version` as a check.
- Named volumes inherit `bot` ownership from `/work` and `/data` created in the image (podman copies it on first use).

## Decisions
- Non-root user `bot` uid/gid 10001. No `SPRING_PROFILES_ACTIVE`, so the default profile applies.
- `.env.example` keeps the two required variables active; all others are commented out with their defaults, because an empty value in `.env` would override the default in Spring.
- yt-dlp refresh: `ARG YT_DLP_REFRESH` or `build --no-cache`.
- docs reachability paragraph left as a placeholder for the orchestrator's result.

## Review round 1 fixes
1. **yt-dlp integrity (Dockerfile).** The build resolves the latest release tag once (redirect of `/releases/latest`), downloads the binary and `SHA2-256SUMS` from that same tag, and checks the binary with `grep -E '^<64 hex>  <asset>$' SHA2-256SUMS | sha256sum -c -` before `install` and before the first run of `yt-dlp --version`. A missing line or a mismatch fails the build (`&&` chain). No skip or suppress.
   - **Signature not done.** `SHA2-256SUMS.sig` is published, and the key is `public.key` in the yt-dlp repo. I could not pin a fingerprint from a source I can check independently: the README only links the key and gives no fingerprint, and `gpg` is not installed here. Fetching the key from the same GitHub repo would not add trust over the checksum file. The check therefore covers a replaced or corrupted binary, but not an attacker who also replaces `SHA2-256SUMS` in the release. Your decision whether to add pinned-key verification (you would supply the fingerprint).
   - Checked locally (macOS, not the image): tag resolves, the grep pattern matches exactly one line for `yt-dlp_linux_aarch64`, `shasum -c` passes on the real file and fails on a modified copy.
2. **docs/host-setup.md.** Added the one-time Podman setup before the build/start commands: `brew install podman podman-compose`, `podman machine init --cpus 8 --memory 6144`, `podman machine start`.

Not run: `podman build`, Maven, spotless (no Java touched).
