# syntax=docker/dockerfile:1
# Build with the Gradle wrapper, extract Spring Boot's layers (dependencies change less often than
# code, so image layers cache well), and run on a distroless JRE as a non-root user.
FROM eclipse-temurin:21-jdk@sha256:8ad118b146aa92da815b576441acd6b42b38236797ad0b6edc8c200b85bb0734 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# The lockfile, when there is one (gradle/ carries verification-metadata.xml): every resolved
# version is the locked one, and every artifact matches its recorded checksum.
COPY gradle.lockfil[e] ./
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination /extracted

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:128bd878463537eef7771b50fecf68faace9089f654a2d233f1c4f344e6817d9
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
EXPOSE 8080
# Distroless's nonroot user, by number: Kubernetes can only verify runAsNonRoot for a numeric user.
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
