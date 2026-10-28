# The Ljós web server, for Railway. Builds only core/ and server/ (no Android SDK needed).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
ENV LJOS_SKIP_APP=1
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1g"

# Gradle itself and the dependencies first, in their own layer: code-only changes reuse it
# instead of downloading everything again.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core/build.gradle.kts core/
COPY server/build.gradle.kts server/
RUN chmod +x gradlew && ./gradlew --no-daemon -q :server:dependencies > /dev/null

COPY core core
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/ljos-server /app
# A small heap keeps memory (Railway's main cost here) at roughly 150-250 MB.
ENV JAVA_OPTS="-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["/app/bin/ljos-server"]
