# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and frontend theme files
COPY src ./src
COPY frontend ./frontend

# Build with production profile
RUN mvn clean package -DskipTests -Pproduction

# Stage 2: Runtime image
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

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Create downloads and data directories and set permissions
RUN mkdir -p /downloads/movies /downloads/tvshows /downloads/temp /app/data && \
    chown -R hos:hos /app /downloads

# Set environment variables
ENV DOWNLOAD_MOVIES_PATH=/downloads/movies
ENV DOWNLOAD_TV_SHOWS_PATH=/downloads/tvshows
ENV DOWNLOAD_TEMP_PATH=/downloads/temp
ENV SERVER_PORT=8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Switch to non-root user
USER hos

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
