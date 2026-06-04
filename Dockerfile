# syntax=docker/dockerfile:1
#
# All-in-one ShiftSmith image. The React SPA is built and bundled into the
# Quarkus application, which serves both the static UI and the /api backend on
# a single port (8080). Bring your own PostgreSQL — see docker-compose.yml.
#
#   docker build -t shiftsmith .

# ---- Stage 1: build the React frontend into static assets -------------------
FROM node:20-alpine AS frontend
WORKDIR /frontend
# Install dependencies first so the layer is cached unless the lockfile changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build          # → /frontend/dist (index.html + /assets/*)

# ---- Stage 2: build the Quarkus backend, bundling the SPA -------------------
FROM maven:3.9-eclipse-temurin-21 AS backend
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

# ---- Stage 3: slim runtime --------------------------------------------------
FROM eclipse-temurin:21-jre
# curl backs the container HEALTHCHECK; run as an unprivileged user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
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
