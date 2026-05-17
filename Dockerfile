# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml and npm manifests; download dependencies (cached layer)
COPY pom.xml .
COPY package*.json ./
RUN mvn dependency:go-offline -B

# Copy source (includes src/main/frontend/ for Vaadin 25)
COPY src ./src

# Build with production profile
RUN mvn clean package -DskipTests -Pproduction

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.source="https://github.com/stormino/vixsrc-downloader-java"
LABEL org.opencontainers.image.description="VixSrc Video Downloader - Spring Boot + Vaadin"
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
RUN groupadd -g ${GID} vixsrc && useradd -u ${UID} -g vixsrc vixsrc

WORKDIR /app

# Copy jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Create downloads and data directories and set permissions
RUN mkdir -p /downloads/movies /downloads/tvshows /downloads/temp /app/data && \
    chown -R vixsrc:vixsrc /app /downloads

# Set environment variables
ENV DOWNLOAD_MOVIES_PATH=/downloads/movies
ENV DOWNLOAD_TV_SHOWS_PATH=/downloads/tvshows
ENV DOWNLOAD_TEMP_PATH=/downloads/temp
ENV SERVER_PORT=8080
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Switch to non-root user
USER vixsrc

EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
