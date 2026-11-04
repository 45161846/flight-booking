FROM docker.io/library/eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY flight-contract ./flight-contract
COPY flight-service ./flight-service
COPY booking-service ./booking-service
RUN --mount=type=cache,target=/root/.gradle,sharing=locked chmod +x gradlew && ./gradlew :flight-service:bootJar :booking-service:bootJar --no-daemon --max-workers=2

FROM docker.io/library/eclipse-temurin:17-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
USER app

FROM runtime AS flight
COPY --from=build /workspace/flight-service/build/libs/app.jar /app/app.jar
EXPOSE 8081 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]

FROM runtime AS booking
COPY --from=build /workspace/booking-service/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
