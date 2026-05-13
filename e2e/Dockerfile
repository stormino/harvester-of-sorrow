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

WORKDIR /app

# Bring in the cached Maven local repo from the deps stage
COPY --from=deps /root/.m2 /root/.m2

# Copy project files
COPY pom.xml ./
COPY src ./src
COPY frontend ./frontend
COPY e2e ./e2e

# Install Node deps and Playwright's Chromium with all system dependencies
WORKDIR /app/e2e
RUN npm ci --quiet
RUN npx playwright install chromium --with-deps

# Signal to playwright.config.ts that we're running in a container
ENV DOCKER_E2E=1

# Volume mount points: callers bind-mount these to retrieve results
VOLUME ["/app/target/e2e", "/app/e2e/test-results"]

WORKDIR /app
ENTRYPOINT ["bash", "e2e/run-tests.sh"]
