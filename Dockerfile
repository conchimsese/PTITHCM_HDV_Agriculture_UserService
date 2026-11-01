FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
RUN mkdir -p /var/lib/user-media && chown app:app /var/lib/user-media
ENV MEDIA_STORAGE_DIR=/var/lib/user-media
WORKDIR /app
COPY --from=build --chown=app:app /app/target/user-service-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
