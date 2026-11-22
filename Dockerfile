# Build stage: compiles the bot with the Maven wrapper (JDK 27).
FROM docker.io/library/eclipse-temurin:27-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 \
    sh mvnw -B -ntp package -DskipTests -Dno-it

# Runtime stage: JRE 27, ffmpeg and the latest yt-dlp (its SHA-256 is checked against the checksum
# file of the same release before the binary runs; a mismatch fails the build), running as a non-root user.
FROM docker.io/library/eclipse-temurin:27-jre

# Changing this value forces the yt-dlp download to run again (see docs/host-setup.md).
ARG YT_DLP_REFRESH=0
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && case "$(uname -m)" in \
         aarch64|arm64) asset=yt-dlp_linux_aarch64 ;; \
         *) asset=yt-dlp_linux ;; \
       esac \
    && echo "yt-dlp refresh: ${YT_DLP_REFRESH}" \
    && tag="$(curl -fsSL -o /dev/null -w '%{url_effective}' https://github.com/yt-dlp/yt-dlp/releases/latest | sed 's|.*/||')" \
    && test -n "${tag}" \
    && echo "yt-dlp release: ${tag}" \
    && base="https://github.com/yt-dlp/yt-dlp/releases/download/${tag}" \
    && mkdir /tmp/yt-dlp \
    && curl -fsSL -o "/tmp/yt-dlp/${asset}" "${base}/${asset}" \
    && curl -fsSL -o /tmp/yt-dlp/SHA2-256SUMS "${base}/SHA2-256SUMS" \
    && (cd /tmp/yt-dlp && grep -E "^[0-9a-f]{64}  ${asset}\$" SHA2-256SUMS | sha256sum -c -) \
    && install -m 0755 "/tmp/yt-dlp/${asset}" /usr/local/bin/yt-dlp \
    && rm -rf /tmp/yt-dlp \
    && yt-dlp --version

RUN groupadd --system --gid 10001 bot \
    && useradd --system --uid 10001 --gid bot --no-create-home --shell /usr/sbin/nologin bot \
    && mkdir -p /app /work /data \
    && chown bot:bot /work /data

COPY --from=build /build/target/video2podcast-bot.jar /app/video2podcast-bot.jar

USER bot
WORKDIR /app
# No Spring profile is set: the default (non-dev) profile applies.
ENTRYPOINT ["java", "-jar", "/app/video2podcast-bot.jar"]
