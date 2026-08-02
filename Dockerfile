# syntax=docker/dockerfile:1
#
# All-in-one ShiftSmith image. The React SPA is built and bundled into the
# Quarkus application, which serves both the static UI and the /api backend on
# a single port (8080). Bring your own PostgreSQL — see docker-compose.yml.
#
#   docker build -t shiftsmith .

# ---- Stage 1: build the React frontend into static assets -------------------
# Both build stages are pinned to the *build* platform: their outputs (a JS bundle
# and JVM bytecode) are architecture-independent, so a multi-arch build runs npm and
# Maven natively once instead of emulating them per target architecture. Only the
# runtime stage below is built per architecture.
FROM --platform=$BUILDPLATFORM node:25-alpine AS frontend
WORKDIR /frontend
# Install dependencies first so the layer is cached unless the lockfile changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build          # → /frontend/dist (index.html + /assets/*)

# ---- Stage 2: build the Quarkus backend, bundling the SPA -------------------
FROM --platform=$BUILDPLATFORM maven:3-eclipse-temurin-26 AS backend
WORKDIR /build
# Resolve dependencies first so they are cached unless the POM changes.
COPY backend/pom.xml ./
RUN mvn -B --no-transfer-progress dependency:go-offline
COPY backend/src ./src
# Quarkus serves anything under META-INF/resources/ as static content, so the
# built SPA lands at the application root ("/" → index.html, /assets/* → bundles)
# on the same origin as the API. No nginx / reverse proxy needed.
COPY --from=frontend /frontend/dist/ ./src/main/resources/META-INF/resources/
RUN mvn -B --no-transfer-progress package -DskipTests

# ---- Stage 3: slim runtime (built per target architecture) ------------------
FROM eclipse-temurin:26-jre
# Typst renders the calendar PDF exports (see PdfExportService). Pinned, and taken
# from the upstream release tarball — there is no Debian package.
ARG TYPST_VERSION=0.14.2
# curl backs the container HEALTHCHECK (and fetches Typst); fonts-dejavu-core is the
# sans-serif the PDF template asks for — Typst only embeds serif/mono faces.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates xz-utils fonts-dejavu-core \
    && arch="$(dpkg --print-architecture)" \
    && case "$arch" in \
         amd64) typst_arch=x86_64-unknown-linux-musl ;; \
         arm64) typst_arch=aarch64-unknown-linux-musl ;; \
         *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
       esac \
    && curl -fsSL "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${typst_arch}.tar.xz" \
       | tar -xJ -C /tmp \
    && install -m 0755 "/tmp/typst-${typst_arch}/typst" /usr/local/bin/typst \
    && rm -rf "/tmp/typst-${typst_arch}" \
    && apt-get purge -y --auto-remove xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && typst --version \
    && groupadd -r app && useradd -r -g app -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY --from=backend --chown=app:app /build/target/quarkus-app/ ./
USER app
EXPOSE 8080
# The app only finishes booting once it has connected to PostgreSQL, so a 200
# from the UI root doubles as a readiness signal.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/ || exit 1
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
