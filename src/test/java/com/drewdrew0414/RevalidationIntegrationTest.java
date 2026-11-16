package com.drewdrew0414;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 시험의 마이크로초 접수 경계와 제출·판정 저장 컬럼의 정밀도를 맞춥니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RevalidationIntegrationTest {
    @Autowired JdbcTemplate db;

    @Test void submissionAndJudgementColumnsPreserveMicroseconds() {
        for(String column:new String[]{"submitted_at","judged_at"}) {
            Integer precision=db.queryForObject("""
                SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=current_schema() AND TABLE_NAME='submissions' AND COLUMN_NAME=?
                """,Integer.class,column);
            assertEquals(6,precision,column+" must match contest UTC DATETIME(6) precision");
        }
    }
}
