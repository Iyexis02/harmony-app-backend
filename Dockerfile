# syntax=docker/dockerfile:1

# ---- Build stage ----
# Use a Maven base image (the Maven wrapper jar is gitignored, so ./mvnw is unavailable here).
# Tests require a live database (they are not self-contained), so the image build skips them.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies separately from source for faster rebuilds.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---- Run stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Railway injects $PORT; application.yml binds server.port to it (default 8080).
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
