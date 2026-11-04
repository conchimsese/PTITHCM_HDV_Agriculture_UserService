FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
# Full verify runs separately with Docker/Testcontainers. Cache actual build dependencies;
# go-offline also resolves unused plugin transitives and can fail on unrelated artifacts.
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
RUN mkdir -p /var/lib/user-media && chown app:app /var/lib/user-media
ENV MEDIA_STORAGE_DIR=/var/lib/user-media
WORKDIR /app
COPY --from=build --chown=app:app /app/target/user-service-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
