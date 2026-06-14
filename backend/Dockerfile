FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/quarkus-app/ .
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
