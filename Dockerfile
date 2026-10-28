# The Ljós web server, for Railway. Builds only core/ and server/ (no Android SDK needed).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ENV LJOS_SKIP_APP=1
RUN chmod +x gradlew && ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/ljos-server /app
# A small heap keeps memory (Railway's main cost here) at roughly 150-250 MB.
ENV JAVA_OPTS="-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["/app/bin/ljos-server"]
