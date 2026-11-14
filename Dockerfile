# Stage 1: build the React UI
FROM node:22-alpine AS ui
WORKDIR /ui
COPY ui/package.json ui/package-lock.json ui/.npmrc ./
RUN npm ci --no-audit --no-fund
COPY ui/ ./
RUN npm run build

# Stage 2: build the Spring Boot jar (with the UI under static/)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
COPY --from=ui /ui/dist ./ui/dist
RUN mvn clean package -DskipTests -B

# Stage 3: runtime image
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.source="https://github.com/stormino/harvester-of-sorrow"
LABEL org.opencontainers.image.description="Harvester of Sorrow (HOS) - Multi-source video downloader"
LABEL org.opencontainers.image.licenses="MIT"

# Install ffmpeg and wget (for healthcheck)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg \
    rsync \
    wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create non-root user for security
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} hos && useradd -u ${UID} -g hos hos

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /downloads/movies /downloads/tvshows /downloads/temp /app/data && \
    chown -R hos:hos /app /downloads

ENV DOWNLOAD_MOVIES_PATH=/downloads/movies
ENV DOWNLOAD_TV_SHOWS_PATH=/downloads/tvshows
ENV DOWNLOAD_TEMP_PATH=/downloads/temp
ENV SERVER_PORT=8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"

USER hos

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
