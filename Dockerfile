# syntax=docker/dockerfile:1
# Build with the Gradle wrapper, extract Spring Boot's layers (dependencies change less often than
# code, so image layers cache well), and run on a distroless JRE as a non-root user.
FROM eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS build
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

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:cddca4cc6b1dfc76142e5c1a1d00ce7a79bcc1292ad65d34227794f9d371a496
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
EXPOSE 8080
# Distroless's nonroot user, by number: Kubernetes can only verify runAsNonRoot for a numeric user.
USER 65532:65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
