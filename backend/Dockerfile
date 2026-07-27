FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:26-jre
# Typst renders the calendar PDF exports; DejaVu is the sans-serif the template
# asks for. Keep the version in step with the root Dockerfile.
ARG TYPST_VERSION=0.14.2
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
    && apt-get purge -y --auto-remove xz-utils curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/quarkus-app/ .
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
