# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Copy build files first for better caching
COPY project/build.properties project/plugins.sbt project/Dependencies.scala project/
COPY build.sbt .

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY auth auth
COPY implementations implementations

# Build the application
RUN sbt "project postgres-impl" stage

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the staged application from builder
COPY --from=builder /app/implementations/postgres/target/universal/stage /app

# Copy migrations to the path expected by Flyway
COPY --from=builder /app/implementations/postgres/migrations /app/implementations/postgres/migrations

# Expose ports
EXPOSE 8080 9345

# Set environment variables
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
ENV CONFIG_PATH="/app/config/env.conf"

# Run the application with config path
ENTRYPOINT ["/bin/sh", "-c", "/app/bin/postgres-impl -Denv.path=$CONFIG_PATH $JAVA_OPTS"]

