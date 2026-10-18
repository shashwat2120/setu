# syntax=docker/dockerfile:1
#
# One image for every Setu service. The application is a single jar that holds all the modules; which service a container is decided by
# SETU_MAIN, the entry point class it starts (see infra/docker/docker-compose.services.yml): com.setu.gateway.GatewayApplication,
# com.setu.identity.IdentityServiceApplication, and so on, or com.setu.BackendApplication for the core process (the modules not yet extracted,
# and the owner of the database migrations).
#
# Built from the REPOSITORY ROOT, because the database migrations live outside backend/ (infra/db/migrations) and ship in the image:
#
#   docker build -f backend/Dockerfile -t setu/backend:dev .
#
# Built from source inside the image, so the result does not depend on what is installed on the machine that builds it (the development
# machine is Windows; the services run on Linux). The Maven cache is a BuildKit cache mount: a rebuild after a source change recompiles, it does
# not download the world again.

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src/backend
COPY backend/pom.xml ./
COPY backend/.mvn .mvn
# dependencies first, so that a change to the sources does not invalidate them
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -DskipTests || true
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
 && cp target/backend-*.jar /setu.jar

FROM eclipse-temurin:25-jre
# not root
RUN useradd --system --uid 10001 --home-dir /app --create-home setu
WORKDIR /app
COPY --from=build /setu.jar /app/setu.jar
# application.yml points Flyway at ../infra/db/migrations, which from /app is /infra/db/migrations
COPY infra/db/migrations /infra/db/migrations
USER setu
# JAVA_OPTS: heap and the like, set per service by the compose file. SETU_MAIN: which service this container is.
ENV SETU_MAIN=com.setu.BackendApplication \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
# PropertiesLauncher starts any class of the jar by name; exec so that the JVM is PID 1 and receives the stop signal (graceful shutdown).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dloader.main=$SETU_MAIN -cp /app/setu.jar org.springframework.boot.loader.launch.PropertiesLauncher"]
