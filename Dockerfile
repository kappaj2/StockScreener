FROM azul/zulu-openjdk-alpine:25.0.1

WORKDIR /app
COPY target/stocklistene*.jar app.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]