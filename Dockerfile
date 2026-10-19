FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B package -DskipTests
RUN cp target/*.jar /app/app.jar

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
RUN useradd --system --create-home --uid 1000 spring
COPY --from=build /app/app.jar app.jar
USER spring
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
