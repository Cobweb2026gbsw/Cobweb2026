package com.drewdrew0414.global.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 로컬 HTTP 포트는 그대로 두고, 인증서가 명시된 경우에만 별도 HTTPS 포트를 추가합니다.
 * 운영 TLS는 Caddy가 담당하며 non-web 채점 워커에는 커넥터를 생성하지 않습니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "local.https", name = "enabled", havingValue = "true")
public class LocalHttpsConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> localHttpsConnector(
            @Value("${local.https.port:8443}") int port,
            @Value("${server.port:8080}") int httpPort,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${local.https.key-store:}") String keyStore,
            @Value("${COBWEB_HTTPS_KEYSTORE_PASSWORD:}") String password) {
        Connector connector = createConnector(port, httpPort, address, keyStore, password);
        return factory -> factory.addAdditionalConnectors(connector);
    }

    static Connector createConnector(int port, int httpPort, String address, String keyStore, String password) {
        if (port < 1 || port > 65535 || port == httpPort) {
            throw new IllegalArgumentException("로컬 HTTPS 포트는 HTTP와 다른 1~65535 포트여야 합니다.");
        }
        if (keyStore == null || keyStore.isBlank() || !Files.isRegularFile(Path.of(keyStore))) {
            throw new IllegalArgumentException("로컬 HTTPS PKCS12 키 저장소 파일이 필요합니다.");
        }
        if (password == null || password.length() < 16) {
            throw new IllegalArgumentException("COBWEB_HTTPS_KEYSTORE_PASSWORD 설정을 확인하세요.");
        }

        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");
        connector.setProperty("address", address);

        SSLHostConfig host = new SSLHostConfig();
        host.setProtocols("TLSv1.2,TLSv1.3");
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeystoreFile(Path.of(keyStore).toAbsolutePath().toString());
        certificate.setCertificateKeystoreType("PKCS12");
        certificate.setCertificateKeystorePassword(password);
        certificate.setCertificateKeyAlias("cobweb-local");
        host.addCertificate(certificate);
        connector.addSslHostConfig(host);
        return connector;
    }
}
