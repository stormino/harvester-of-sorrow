FROM maven:3.9-eclipse-temurin-21 AS deps

WORKDIR /app

# Download Maven dependencies in a separate layer for caching
COPY pom.xml ./
RUN mvn dependency:go-offline -B -q || true

# ── Final image ──────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21

# Install system tools needed by tests and start-app.sh
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ffmpeg \
    rsync \
    lsof \
    && rm -rf /var/lib/apt/lists/*

# Install Node.js 20
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user whose UID/GID matches the host caller so that
# files written to bind-mounted volumes are owned by the host user.
ARG UID=1000
ARG GID=1000
RUN set -e; \
    if getent group ${GID} >/dev/null; then \
      groupmod -n e2e "$(getent group ${GID} | cut -d: -f1)"; \
    else \
      groupadd -g ${GID} e2e; \
    fi; \
    if getent passwd ${UID} >/dev/null; then \
      usermod -l e2e -g ${GID} -d /home/e2e -m "$(getent passwd ${UID} | cut -d: -f1)"; \
    else \
      useradd -u ${UID} -g e2e -m e2e; \
    fi

WORKDIR /app

# Maven local repo — copy to the new user's home so Maven can read/write it
COPY --from=deps --chown=${UID}:${GID} /root/.m2 /home/e2e/.m2

# Copy project files
COPY --chown=${UID}:${GID} pom.xml ./
COPY --chown=${UID}:${GID} src ./src
COPY --chown=${UID}:${GID} frontend ./frontend
COPY --chown=${UID}:${GID} e2e ./e2e

# Install Playwright browser into a fixed, non-root path
ENV PLAYWRIGHT_BROWSERS_PATH=/app/.playwright-browsers

# Install Node deps and Playwright's Chromium (system deps require root)
WORKDIR /app/e2e
RUN npm ci --quiet
RUN npx playwright install chromium --with-deps

# Fix ownership of everything under /app now that installs are done
RUN chown -R ${UID}:${GID} /app

# Signal to playwright.config.ts that we're running in a container
ENV DOCKER_E2E=1

# Volume mount points: callers bind-mount these to retrieve results
VOLUME ["/app/target/e2e", "/app/e2e/test-results"]

USER e2e

WORKDIR /app
ENTRYPOINT ["bash", "e2e/run-tests.sh"]
