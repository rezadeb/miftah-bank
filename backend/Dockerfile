# ---- Stage 1: build (Maven + JDK 25) ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Stage 2: run (JRE 25 LTS on Ubuntu/glibc, not Alpine) ----
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home miftah
WORKDIR /app
COPY --from=build /build/target/miftah-bank-backend.jar app.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
