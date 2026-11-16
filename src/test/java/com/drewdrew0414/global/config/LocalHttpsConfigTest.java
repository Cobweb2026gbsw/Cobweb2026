package com.drewdrew0414.global.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LocalHttpsConfigTest {
    @TempDir Path temporary;
    private final String password = "test-only-keystore-password-2026";

    @Test void additionalConnectorPreservesPrimaryHttpFactoryAndUsesTlsOnly() throws Exception {
        Path keyStore = Files.createFile(temporary.resolve("localhost.p12"));
        var factory = new TomcatServletWebServerFactory(18080);
        new LocalHttpsConfig().localHttpsConnector(18443, 18080, "127.0.0.1", keyStore.toString(), password).customize(factory);
        assertEquals(18080, factory.getPort(), "기존 HTTP 포트를 HTTPS로 바꾸면 안 됩니다.");
        assertEquals(1, factory.getAdditionalConnectors().size());
        var connector = factory.getAdditionalConnectors().getFirst();
        assertEquals(18443, connector.getPort());
        assertEquals("https", connector.getScheme());
        assertTrue(connector.getSecure());
        assertEquals("true", connector.getProperty("SSLEnabled").toString());
        var host = connector.findSslHostConfigs()[0];
        assertEquals(Set.of("TLSv1.2", "TLSv1.3"), host.getProtocols());
        var certificate = host.getCertificates().iterator().next();
        assertEquals(SSLHostConfigCertificate.Type.RSA, certificate.getType());
        assertEquals("PKCS12", certificate.getCertificateKeystoreType());
        assertEquals(keyStore.toAbsolutePath().toString(), certificate.getCertificateKeystoreFile());
        assertEquals("cobweb-local", certificate.getCertificateKeyAlias());
        assertEquals(password, certificate.getCertificateKeystorePassword());
    }

    @Test void rejectsInvalidAndCollidingPorts() {
        for (int port : new int[]{0, 65536, 8080}) {
            assertThrows(IllegalArgumentException.class,
                () -> LocalHttpsConfig.createConnector(port, 8080, "127.0.0.1", "unused", password));
        }
    }

    @Test void rejectsMissingKeystoreAndNeverIncludesPasswordInDiagnostics() {
        var error = assertThrows(IllegalArgumentException.class,
            () -> LocalHttpsConfig.createConnector(8443, 8080, "127.0.0.1", temporary.resolve("missing.p12").toString(), password));
        assertFalse(error.getMessage().contains(password));
    }

    @Test void rejectsMissingPasswordWithoutPrintingIt() throws Exception {
        Path keyStore = Files.createFile(temporary.resolve("localhost.p12"));
        var error = assertThrows(IllegalArgumentException.class,
            () -> LocalHttpsConfig.createConnector(8443, 8080, "127.0.0.1", keyStore.toString(), "tiny-secret"));
        assertFalse(error.getMessage().contains("tiny-secret"));
    }

    @Test void doesNotEnableLocalTlsOutsideLocalProfileOrWithoutExplicitFlag() {
        var runner = new WebApplicationContextRunner().withUserConfiguration(LocalHttpsConfig.class);
        runner.withPropertyValues("spring.profiles.active=prod", "local.https.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(LocalHttpsConfig.class));
        runner.withPropertyValues("spring.profiles.active=prod,local", "local.https.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(LocalHttpsConfig.class));
        runner.withPropertyValues("spring.profiles.active=local")
            .run(context -> assertThat(context).doesNotHaveBean(LocalHttpsConfig.class));
    }

    @Test void localWebProfileCreatesCustomizerOnlyWhenExplicitlyEnabled() throws Exception {
        Path keyStore = Files.createFile(temporary.resolve("localhost.p12"));
        new WebApplicationContextRunner().withUserConfiguration(LocalHttpsConfig.class)
            .withPropertyValues("spring.profiles.active=local", "local.https.enabled=true",
                "local.https.key-store=" + keyStore, "COBWEB_HTTPS_KEYSTORE_PASSWORD=" + password)
            .run(context -> assertThat(context).hasBean("localHttpsConnector"));
    }

    @Test void realHttpAndPinnedHttpsRequestsWorkWithoutDisablingCertificateVerification() throws Exception {
        Path keyStore = temporary.resolve("real-localhost.p12");
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-noprompt", "-alias", "cobweb-local", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-dname", "CN=localhost,O=Cobweb Test",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "EKU=serverAuth",
            "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass:env", "COBWEB_HTTPS_TEST_PASSWORD")
            .redirectErrorStream(true);
        command.environment().put("COBWEB_HTTPS_TEST_PASSWORD", password);
        var keytool = command.start();
        try {
            assertTrue(keytool.waitFor(30, TimeUnit.SECONDS), "테스트 인증서 생성 시간 초과");
            assertEquals(0, keytool.exitValue(), "테스트 인증서 생성 실패");
        } finally { keytool.destroyForcibly(); }

        int tlsPort;
        try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            tlsPort = reservation.getLocalPort();
        }
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.addAdditionalConnectors(LocalHttpsConfig.createConnector(tlsPort, 0, "127.0.0.1", keyStore.toString(), password));
        var server = factory.getWebServer(context -> context.addServlet("local-tls-test", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
                response.setContentType("text/plain");
                response.getWriter().write("local-tls-ok");
            }
        }).addMapping("/tls-check"));
        try {
            server.start();
            var plain = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var http = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/tls-check"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, http.statusCode());
            assertEquals("local-tls-ok", http.body());

            // 해당 인증서만 명시적으로 신뢰합니다. 전역 CA/호스트명 검증을 끄지 않습니다.
            var source = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStore)) { source.load(input, password.toCharArray()); }
            var trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            trusted.setCertificateEntry("cobweb-local", source.getCertificate("cobweb-local"));
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            var pinned = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + tlsPort + "/tls-check"))
                .timeout(Duration.ofSeconds(5)).build();
            var https = pinned.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, https.statusCode());
            assertEquals("local-tls-ok", https.body());
            assertTrue(Set.of("TLSv1.2", "TLSv1.3").contains(https.sslSession().orElseThrow().getProtocol()));
            assertThrows(javax.net.ssl.SSLHandshakeException.class,
                () -> plain.send(request, HttpResponse.BodyHandlers.ofString()), "기본 CA로 자체서명 인증서를 신뢰하면 안 됩니다.");
        } finally { server.stop(); server.destroy(); }
    }
}
