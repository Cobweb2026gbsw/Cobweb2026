FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx1024m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar", "--spring.profiles.active=prod,neon", "--server.forward-headers-strategy=native", "--server.tomcat.remoteip.remote-ip-header=x-cobweb-client-ip", "--server.tomcat.remoteip.protocol-header=x-cobweb-scheme"]
