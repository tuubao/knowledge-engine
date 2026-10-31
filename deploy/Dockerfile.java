FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY java-service/pom.xml ./pom.xml
COPY java-service/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17.0.17_10-jre-jammy
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
