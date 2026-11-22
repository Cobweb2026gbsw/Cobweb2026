package com.drewdrew0414;

import com.drewdrew0414.domain.user.controller.TermsController;
import com.drewdrew0414.domain.user.service.ServiceTerms;
import com.drewdrew0414.domain.user.service.TermsPublicationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TermsPublicationIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired TermsPublicationService terms;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;

    @Test void metadataIsPublicAndMatchesTheRecordedPublication() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/terms/current")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var data = json.readTree(response.body()).path("data");
        assertEquals(ServiceTerms.VERSION, data.path("version").asString());
        assertEquals("cobweb 개발팀", data.path("operator").asString());
        assertEquals("cobweb2026.gbsw@gmail.com", data.path("contactEmail").asString());
        assertEquals(json.valueToTree(terms.current()), data);
    }

    @Test @Transactional void onlyFirstProductionWebStartupSetsTheSeoulDate() {
        // 현재 트랜잭션에서만 보이는 테이블로 운영 날짜를 재현합니다.
        db.execute("CREATE TEMP TABLE terms_publications (LIKE public.terms_publications INCLUDING ALL) ON COMMIT DROP");
        terms.publishWhenReady();
        assertNull(terms.current().effectiveDate());
        var production = new MockEnvironment();
        production.setActiveProfiles("prod", "neon");
        var published = new TermsPublicationService(db, production);
        published.publishWhenReady();
        var today = db.queryForObject("SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date", LocalDate.class);
        assertEquals(today, published.current().effectiveDate());

        var previous = today.minusDays(4);
        db.update("UPDATE terms_publications SET effective_date = ? WHERE terms_version = ?", previous, ServiceTerms.VERSION);
        published.publishWhenReady();
        assertEquals(previous, published.current().effectiveDate());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM terms_publications", Integer.class));
    }

    @Test void productionWorkerAndMigrationContextsHaveNoPublisher() {
        try (var nonWeb = new AnnotationConfigApplicationContext()) {
            nonWeb.getEnvironment().setActiveProfiles("prod");
            nonWeb.register(TermsPublicationService.class, TermsController.class);
            nonWeb.refresh();
            assertTrue(nonWeb.getBeansOfType(TermsPublicationService.class).isEmpty());
            assertTrue(nonWeb.getBeansOfType(TermsController.class).isEmpty());
        }
    }
}
