package com.drewdrew0414.domain.user.service;

import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TermsPublicationService {
    public record Publication(String version, LocalDate effectiveDate, String operator, String contactEmail) {}

    private final JdbcTemplate db;
    private final Environment environment;

    public TermsPublicationService(JdbcTemplate db, Environment environment) {
        this.db = db;
        this.environment = environment;
    }

    /** 웹 초기화와 모든 ApplicationRunner가 끝난 뒤, 운영 웹에서만 최초 시행일을 확정합니다. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void publishWhenReady() {
        if (environment.matchesProfiles("prod")) {
            db.update("""
                INSERT INTO terms_publications(terms_version, effective_date)
                VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date)
                ON CONFLICT (terms_version) DO NOTHING
                """, ServiceTerms.VERSION);
        }
    }

    public Publication current() {
        LocalDate date = db.query("SELECT effective_date FROM terms_publications WHERE terms_version = ?",
            rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, ServiceTerms.VERSION);
        return new Publication(ServiceTerms.VERSION, date, "cobweb 개발팀", "cobweb2026.gbsw@gmail.com");
    }
}
