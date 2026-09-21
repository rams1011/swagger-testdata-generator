FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY testdata-generator-core/pom.xml testdata-generator-core/pom.xml
COPY testdata-generator-api/pom.xml testdata-generator-api/pom.xml
COPY testdata-generator-core testdata-generator-core
COPY testdata-generator-api testdata-generator-api
RUN mvn -q -B -DskipTests -pl testdata-generator-api -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/testdata-generator-api/target/testdata-generator-api-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
