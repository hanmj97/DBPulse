# Multi-stage image: build JAR with Maven; run only with a JRE.
FROM maven:3.9.11-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system dbpulse && useradd --system --gid dbpulse dbpulse
WORKDIR /app
COPY --from=builder /workspace/target/dbpulse-api.jar app.jar
USER dbpulse
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]

