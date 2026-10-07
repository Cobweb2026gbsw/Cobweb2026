FROM eclipse-temurin:21-jre
# 워커가 호스트 Docker 데몬을 호출할 때만 사용합니다. 앱에는 소켓을 마운트하지 않습니다.
COPY --from=docker:29.8.2-cli /usr/local/bin/docker /usr/local/bin/docker
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
