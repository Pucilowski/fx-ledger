# One Dockerfile for both services; compose passes MODULE=ledger-service|fx-service.
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN ./gradlew :${MODULE}:installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
ARG MODULE
ENV MODULE=${MODULE}
COPY --from=build /src/${MODULE}/build/install/${MODULE} /app
CMD ["/bin/sh", "-c", "/app/bin/${MODULE}"]
