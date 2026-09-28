# build
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package

# run
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar

# APP_ENV=production means /shutdown is not registered (use -e APP_ENV=development to test locally)
# SHUTDOWN_TIMEOUT_SECONDS stays below docker stop's default 10 s grace period
ENV APP_ENV=production \
    GREETING_PREFIX=Hello \
    PORT=8080 \
    WORKER_THREADS=16 \
    SHUTDOWN_TIMEOUT_SECONDS=8
EXPOSE 8080

RUN adduser -S -u 1001 appuser
USER appuser

# exec form: java is PID 1 and receives SIGTERM from docker stop directly
ENTRYPOINT ["java", "-jar", "app.jar"]
