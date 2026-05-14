FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/temporal-mini-0.0.2.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
