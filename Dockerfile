# build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -B clean package

# run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar

# APP_ENV=production means /shutdown is not registered (use -e APP_ENV=development to test locally)
ENV APP_ENV=production \
    GREETING_PREFIX=Hello \
    PORT=8080
EXPOSE 8080

RUN useradd --system --uid 1001 appuser
USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
